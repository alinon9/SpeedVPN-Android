package com.speedvpn.app

import android.content.Context
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class SpeedVerificationResult(
    val startedAtMillis: Long,
    val durationMs: Long,
    val download: SpeedMetricVerification,
    val upload: SpeedMetricVerification,
    val overallStatus: SpeedVerificationStatus,
    val note: String? = null,
)

object SpeedVerificationEngine {
    private const val TAG = "SpeedVerificationEngine"
    private const val STATE_POLL_MS = 250L
    private const val STATE_TIMEOUT_MS = 30_000L

    suspend fun verify(
        context: Context,
        planDownloadKbps: Long?,
        planUploadKbps: Long?,
        onProgress: (String) -> Unit = {},
    ): SpeedVerificationResult {
        val started = System.currentTimeMillis()
        Log.i(
            TAG,
            "Verification started downloadKbps=${planDownloadKbps ?: "unlimited"} " +
                "uploadKbps=${planUploadKbps ?: "unlimited"}",
        )

        if (VpnService.prepare(context) != null) {
            return unavailable(started, "إذن VPN غير متاح. امنح الإذن أولًا ثم أعد التحقق.", planDownloadKbps, planUploadKbps)
        }

        // The limiter is process-wide. Disable it during the baseline measurement
        // so "internet speed without VPN" is never accidentally capped by the
        // user's previous VPN plan.
        var baseline: SpeedTestResult? = null
        var vpn: SpeedTestResult? = null
        var note: String? = null
        var result: SpeedVerificationResult? = null
        var restorationError: String? = null
        var verifierTunnelRouteEnabled = false
        var verificationLockActive = true

        if (!SpeedLimiter.beginVerification(null, null)) {
            return unavailable(started, "هناك أمر VPN أو تحقق سرعة آخر قيد التنفيذ.", planDownloadKbps, planUploadKbps)
        }
        // Read the prior state only after taking the same gate that excludes
        // remote CONNECT/DISCONNECT commands.
        val initiallyConnected = VpnRuntime.state.value.status == VpnStatus.CONNECTED

        try {
            Log.i(TAG, "Stage=disconnect-vpn-for-baseline")
            onProgress("فصل VPN لقياس سرعة الشبكة الأصلية…")
            val disconnectedForBaseline = if (VpnRuntime.state.value.status != VpnStatus.DISCONNECTED) {
                SpeedVpnService.stop(context)
                waitForStatus(VpnStatus.DISCONNECTED)
            } else {
                true
            }

            if (!disconnectedForBaseline) {
                result = unavailable(started, "تعذر فصل VPN قبل الاختبار.", planDownloadKbps, planUploadKbps)
            } else {
                Log.i(TAG, "Stage=measure-physical-baseline")
                onProgress("قياس Download للشبكة الأصلية…")
                // Use the selected plans only to choose the sample size/count and
                // percentile. The limiter is still disabled, so these are uncapped
                // physical-network measurements with the same request profile as
                // the VPN half of the comparison. Unlimited verification also uses
                // fixed larger samples instead of the short public speed-test ladder.
                baseline = SpeedTestEngine.measure(
                    context = context,
                    expectedDownloadKbps = planDownloadKbps,
                    expectedUploadKbps = planUploadKbps,
                    verificationProfile = true,
                ) { phase ->
                    onProgress(if (phase == SpeedTestPhase.DOWNLOAD) "قياس Download للشبكة الأصلية…" else "قياس Upload للشبكة الأصلية…")
                }

                if (baseline.downloadBps == null && baseline.uploadBps == null) {
                    result = unavailable(started, "فشل قياس الشبكة الأصلية.", planDownloadKbps, planUploadKbps, baseline)
                } else {
                    // The selected plan is explicit; null means Unlimited. Apply it before
                    // startup and again after CONNECTED because the service restores saved
                    // preferences during startup and those may lag a recent UI selection.
                    SpeedLimiter.setVerificationLimits(planDownloadKbps, planUploadKbps)
                    SpeedLimiter.setVerificationTrafficRoutingEnabled(true)
                    verifierTunnelRouteEnabled = true

                    Log.i(TAG, "Stage=start-verification-vpn")
                    onProgress("تشغيل VPN للتحقق من الخطة…")
                    SpeedVpnService.start(context)
                    if (!waitForStatus(VpnStatus.CONNECTED)) {
                        result = unavailable(started, "تعذر الوصول إلى حالة VPN متصل.", planDownloadKbps, planUploadKbps, baseline)
                    } else {
                        SpeedLimiter.setVerificationLimits(planDownloadKbps, planUploadKbps)
                        Log.i(
                            TAG,
                            "Stage=measure-through-vpn tunnelStatus=${VpnRuntime.state.value.status} " +
                                "ownUidRouted=${SpeedLimiter.routeVerificationTrafficThroughVpn}",
                        )

                        // Capture relay-forwarded bytes around the actual VPN measurement.
                        // UI status alone cannot prove that the probe traversed the limiter.
                        val relayDownloadBefore = SpeedLimiter.download.total.get()
                        val relayUploadBefore = SpeedLimiter.upload.total.get()
                        onProgress("قياس Download عبر VPN…")
                        vpn = SpeedTestEngine.measure(
                            context = context,
                            expectedDownloadKbps = planDownloadKbps,
                            expectedUploadKbps = planUploadKbps,
                            verificationProfile = true,
                        ) { phase ->
                            onProgress(if (phase == SpeedTestPhase.DOWNLOAD) "قياس Download عبر VPN…" else "قياس Upload عبر VPN…")
                        }

                        val relayDownloadBytes =
                            (SpeedLimiter.download.total.get() - relayDownloadBefore).coerceAtLeast(0L)
                        val relayUploadBytes =
                            (SpeedLimiter.upload.total.get() - relayUploadBefore).coerceAtLeast(0L)
                        val minimumDownloadBytes =
                            SpeedTestEngine.minimumRequiredSampleBytes(planDownloadKbps, verificationProfile = true)
                        val minimumUploadBytes =
                            SpeedTestEngine.minimumRequiredSampleBytes(planUploadKbps, verificationProfile = true)
                        Log.i(
                            TAG,
                            "Tunnel byte evidence download=" + relayDownloadBytes + "/" + minimumDownloadBytes +
                                " upload=" + relayUploadBytes + "/" + minimumUploadBytes +
                                " downloadEvidence=" + (relayDownloadBytes >= minimumDownloadBytes) +
                                " uploadEvidence=" + (relayUploadBytes >= minimumUploadBytes),
                        )

                        if (vpn.downloadBps == null && vpn.uploadBps == null) {
                            result = unavailable(started, "فشل القياس أثناء مرور الترافيك عبر VPN.", planDownloadKbps, planUploadKbps, baseline)
                        } else {
                            note = listOfNotNull(
                                baseline.error?.let { "الشبكة: $it" },
                                vpn.error?.let { "VPN: $it" },
                            ).joinToString(" • ").takeIf { it.isNotBlank() }

                            val downloadEvaluation = SpeedVerificationPolicy.evaluate(
                                planKbps = planDownloadKbps,
                                baselineBps = baseline.downloadBps,
                                vpnBps = vpn.downloadBps,
                            )
                            val uploadEvaluation = SpeedVerificationPolicy.evaluate(
                                planKbps = planUploadKbps,
                                baselineBps = baseline.uploadBps,
                                vpnBps = vpn.uploadBps,
                            )
                            val download = if (
                                vpn.downloadBps != null && relayDownloadBytes < minimumDownloadBytes
                            ) {
                                downloadEvaluation.copy(
                                    status = SpeedVerificationStatus.NOT_VERIFIABLE,
                                    accuracyPercent = null,
                                    reason = "لم يثبت مرور بيانات قياس Download عبر مرحّل VPN: " +
                                        relayDownloadBytes + "/" + minimumDownloadBytes + " بايت.",
                                )
                            } else {
                                downloadEvaluation
                            }
                            val upload = if (
                                vpn.uploadBps != null && relayUploadBytes < minimumUploadBytes
                            ) {
                                uploadEvaluation.copy(
                                    status = SpeedVerificationStatus.NOT_VERIFIABLE,
                                    accuracyPercent = null,
                                    reason = "لم يثبت مرور بيانات قياس Upload عبر مرحّل VPN: " +
                                        relayUploadBytes + "/" + minimumUploadBytes + " بايت.",
                                )
                            } else {
                                uploadEvaluation
                            }

                            result = SpeedVerificationResult(
                                startedAtMillis = started,
                                durationMs = System.currentTimeMillis() - started,
                                download = download,
                                upload = upload,
                                overallStatus = SpeedVerificationPolicy.overall(download, upload),
                                note = note,
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "Verification failed: ${t.message ?: t.javaClass.simpleName}", t)
            result = unavailable(started, "فشل التحقق: " + (t.message ?: t.javaClass.simpleName), planDownloadKbps, planUploadKbps, baseline)
        } finally {
            withContext(NonCancellable) {
                try {
                    runCatching { onProgress("استعادة حالة VPN السابقة…") }
                    if (initiallyConnected && verifierTunnelRouteEnabled) {
                        // The verification VPN includes SpeedVPN's own UID in the
                        // app allow-list so its probe traverses the tunnel. Rebuild
                        // the user's original VPN after clearing that temporary rule.
                        val verifierTunnelStopped = stopForVerificationRestore(context)
                        if (!verifierTunnelStopped) {
                            restorationError = "تعذر إيقاف نفق التحقق لإعادة إعداد VPN السابق."
                        }
                        SpeedLimiter.setVerificationTrafficRoutingEnabled(false)
                        if (verifierTunnelStopped &&
                            VpnRuntime.state.value.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR)
                        ) {
                            SpeedVpnService.start(context)
                            if (!waitForStatus(VpnStatus.CONNECTED)) {
                                restorationError = "تعذر إعادة اتصال VPN السابق بعد التحقق."
                            }
                        } else if (restorationError == null) {
                            restorationError = "تعذر تأكيد إيقاف نفق التحقق قبل استعادة VPN السابق."
                        }
                        // Keep external limit updates locked until the original
                        // VPN has been rebuilt with its normal application policy.
                        SpeedLimiter.endVerification(planDownloadKbps, planUploadKbps)
                        verificationLockActive = false
                    } else {
                        SpeedLimiter.setVerificationTrafficRoutingEnabled(false)
                        if (initiallyConnected) {
                            if (VpnRuntime.state.value.status != VpnStatus.CONNECTED) {
                                SpeedVpnService.start(context)
                                if (!waitForStatus(VpnStatus.CONNECTED)) {
                                    restorationError = "تعذر إعادة اتصال VPN السابق بعد التحقق."
                                }
                            }
                        } else if (VpnRuntime.state.value.status != VpnStatus.DISCONNECTED) {
                            SpeedVpnService.stop(context)
                            if (!waitForStatus(VpnStatus.DISCONNECTED)) {
                                restorationError = "تعذر فصل VPN بعد انتهاء التحقق."
                            }
                        }
                        SpeedLimiter.endVerification(planDownloadKbps, planUploadKbps)
                        verificationLockActive = false
                    }
                } catch (error: Exception) {
                    restorationError = restorationError ?: "تعذر استعادة حالة VPN: ${error.message ?: error.javaClass.simpleName}"
                } finally {
                    if (verificationLockActive) {
                        SpeedLimiter.setVerificationTrafficRoutingEnabled(false)
                        // Null is explicit Unlimited.
                        SpeedLimiter.endVerification(planDownloadKbps, planUploadKbps)
                        verificationLockActive = false
                    }
                }
                if (restorationError != null) {
                    Log.e(TAG, "VPN state restoration failed: $restorationError")
                    val measured = result ?: unavailable(started, "تعذر إكمال قياس السرعة.", planDownloadKbps, planUploadKbps, baseline)
                    result = withRestorationFailure(measured, restorationError!!)
                }
            }
        }
        return result ?: unavailable(started, "تعذر إكمال التحقق.", planDownloadKbps, planUploadKbps, baseline)
    }

    internal fun withRestorationFailure(result: SpeedVerificationResult, error: String): SpeedVerificationResult {
        val reason = "تعذرت استعادة حالة VPN: $error"
        return result.copy(
            download = result.download.copy(
                status = SpeedVerificationStatus.NOT_VERIFIABLE,
                reason = listOfNotNull(result.download.reason, reason).joinToString(" • "),
            ),
            upload = result.upload.copy(
                status = SpeedVerificationStatus.NOT_VERIFIABLE,
                reason = listOfNotNull(result.upload.reason, reason).joinToString(" • "),
            ),
            overallStatus = SpeedVerificationStatus.NOT_VERIFIABLE,
            note = listOfNotNull(result.note, reason).joinToString(" • "),
        )
    }

    private suspend fun stopForVerificationRestore(context: Context): Boolean {
        if (isTunnelStoppedForRestore()) return true
        repeat(2) { attempt ->
            SpeedVpnService.stop(context)
            val timeoutMs = if (attempt == 0) STATE_TIMEOUT_MS else 5_000L
            if (waitForTunnelStop(timeoutMs)) return true
            if (attempt == 0) delay(250L)
        }
        return false
    }

    private suspend fun waitForTunnelStop(timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited <= timeoutMs) {
            if (isTunnelStoppedForRestore()) return true
            delay(STATE_POLL_MS)
            waited += STATE_POLL_MS
        }
        return false
    }

    private fun isTunnelStoppedForRestore(): Boolean =
        VpnRuntime.state.value.status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR) &&
            SpeedVpnService.isNativeTunnelStopped()

    private suspend fun waitForStatus(target: VpnStatus, timeoutMs: Long = STATE_TIMEOUT_MS): Boolean {
        var waited = 0L
        while (waited <= timeoutMs) {
            if (VpnRuntime.state.value.status == target) return true
            delay(STATE_POLL_MS)
            waited += STATE_POLL_MS
        }
        return false
    }

    internal fun unavailable(
        started: Long,
        note: String,
        planDownloadKbps: Long? = null,
        planUploadKbps: Long? = null,
        baseline: SpeedTestResult? = null,
    ): SpeedVerificationResult {
        // Failure of the VPN phase must not erase a valid physical-baseline sample.
        // Preserve each direction independently so diagnostics cannot turn measured
        // baseline speed into a synthetic "measurement failed" value.
        val diagnosticNote = listOfNotNull(
            note,
            baseline?.error?.takeIf { it.isNotBlank() }?.let { "الشبكة الأصلية: $it" },
        ).joinToString(" • ")
        val downloadMetric = SpeedMetricVerification(
            planKbps = planDownloadKbps,
            baselineBps = baseline?.downloadBps,
            vpnBps = null,
            status = SpeedVerificationStatus.NOT_VERIFIABLE,
            accuracyPercent = null,
            reason = diagnosticNote,
        )
        val uploadMetric = downloadMetric.copy(
            planKbps = planUploadKbps,
            baselineBps = baseline?.uploadBps,
        )
        return SpeedVerificationResult(
            startedAtMillis = started,
            durationMs = System.currentTimeMillis() - started,
            download = downloadMetric,
            upload = uploadMetric,
            overallStatus = SpeedVerificationStatus.NOT_VERIFIABLE,
            note = diagnosticNote,
        )
    }
}
