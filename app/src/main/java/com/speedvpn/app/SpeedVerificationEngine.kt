package com.speedvpn.app

import android.content.Context
import android.net.VpnService
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

private data class VerificationRelayMeasurement(
    val result: SpeedTestResult,
    val downloadRelayBytes: Long,
    val uploadRelayBytes: Long,
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
                    networkPath = SpeedTestNetworkPath.PHYSICAL_BASELINE,
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

                        // Use UID-attributed relay evidence; global counters can include unrelated app traffic.
                        val relayMeasurement = measureThroughVerificationRelay(
                            context = context,
                            planDownloadKbps = planDownloadKbps,
                            planUploadKbps = planUploadKbps,
                            onPhaseProgress = { phase ->
                                onProgress(
                                    if (phase == SpeedTestPhase.DOWNLOAD) {
                                        "قياس Download عبر VPN…"
                                    } else {
                                        "قياس Upload عبر VPN…"
                                    },
                                )
                            },
                        )
                        vpn = relayMeasurement.result
                        val relayDownloadBytes = relayMeasurement.downloadRelayBytes
                        val relayUploadBytes = relayMeasurement.uploadRelayBytes
                        val minimumDownloadBytes =
                            SpeedTestEngine.minimumRequiredSampleBytes(planDownloadKbps, verificationProfile = true)
                        val minimumUploadBytes =
                            SpeedTestEngine.minimumRequiredSampleBytes(planUploadKbps, verificationProfile = true)
                        val uploadPayloadBytesWritten = vpn.uploadPayloadBytesWritten
                        val uploadEvidence =
                            uploadPayloadBytesWritten >= minimumUploadBytes && relayUploadBytes > 0L
                        Log.i(
                            TAG,
                            "Tunnel byte evidence download=" + relayDownloadBytes + "/" + minimumDownloadBytes +
                                " uploadRelay=" + relayUploadBytes +
                                " uploadPayloadWritten=" + uploadPayloadBytesWritten + "/" + minimumUploadBytes +
                                " downloadEvidence=" + (relayDownloadBytes >= minimumDownloadBytes) +
                                " uploadEvidence=" + uploadEvidence,
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
                            val download = withRelayEvidence(
                                evaluation = downloadEvaluation,
                                measuredBps = vpn.downloadBps,
                                relayBytes = relayDownloadBytes,
                                minimumRequiredBytes = minimumDownloadBytes,
                                direction = "Download",
                            )
                            val upload = if (
                                vpn.uploadBps != null && uploadPayloadBytesWritten < minimumUploadBytes
                            ) {
                                uploadEvaluation.copy(
                                    status = SpeedVerificationStatus.NOT_VERIFIABLE,
                                    accuracyPercent = null,
                                    reason = "لم تكتمل كتابة حمولة Upload عبر OkHttp: " +
                                        uploadPayloadBytesWritten + "/" + minimumUploadBytes + " بايت.",
                                )
                            } else {
                                withRelayEvidence(
                                    evaluation = uploadEvaluation,
                                    measuredBps = vpn.uploadBps,
                                    relayBytes = relayUploadBytes,
                                    minimumRequiredBytes = minimumUploadBytes,
                                    direction = "Upload",
                                )
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
            // Preserve an explicit NOT_VERIFIABLE record before propagating cancellation.
            // The NonCancellable finalizer restores VPN/limiter state and persists this
            // outcome, so a lifecycle cancellation does not silently erase the run.
            result = unavailable(
                started,
                "تم إلغاء التحقق قبل اكتمال القياس.",
                planDownloadKbps,
                planUploadKbps,
                baseline,
            )
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
                        SpeedLimiter.endVerificationRestoringPreviousLimits()
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
                        SpeedLimiter.endVerificationRestoringPreviousLimits()
                        verificationLockActive = false
                    }
                } catch (error: Exception) {
                    restorationError = restorationError ?: "تعذر استعادة حالة VPN: ${error.message ?: error.javaClass.simpleName}"
                } finally {
                    if (verificationLockActive) {
                        runCatching { SpeedLimiter.setVerificationTrafficRoutingEnabled(false) }
                            .onFailure { error ->
                                restorationError = restorationError
                                    ?: "تعذر تعطيل توجيه التحقق: ${error.message ?: error.javaClass.simpleName}"
                            }

                        // Recovery path: if any earlier cleanup operation threw, rebuild
                        // the tunnel to the state that existed before verification instead
                        // of releasing the verification lock with a possibly stale TUN policy.
                        runCatching {
                            if (initiallyConnected) {
                                if (!stopForVerificationRestore(context)) {
                                    throw IllegalStateException("تعذر إيقاف نفق التحقق أثناء الاستعادة الاحتياطية.")
                                }
                                SpeedVpnService.start(context)
                                if (!waitForStatus(VpnStatus.CONNECTED)) {
                                    throw IllegalStateException("تعذر إعادة اتصال VPN السابق أثناء الاستعادة الاحتياطية.")
                                }
                            } else if (
                                VpnRuntime.state.value.status != VpnStatus.DISCONNECTED ||
                                !SpeedVpnService.isNativeTunnelStopped()
                            ) {
                                SpeedVpnService.stop(context)
                                if (!waitForStatus(VpnStatus.DISCONNECTED) || !SpeedVpnService.isNativeTunnelStopped()) {
                                    throw IllegalStateException("تعذر فصل نفق التحقق أثناء الاستعادة الاحتياطية.")
                                }
                            }
                        }.onFailure { error ->
                            restorationError = restorationError
                                ?: "فشلت استعادة حالة VPN الاحتياطية: ${error.message ?: error.javaClass.simpleName}"
                        }

                        runCatching { SpeedLimiter.endVerificationRestoringPreviousLimits() }
                            .onFailure { error ->
                                restorationError = restorationError
                                    ?: "تعذر استعادة خطة محدّد السرعة الأصلية: ${error.message ?: error.javaClass.simpleName}"
                            }
                        verificationLockActive = false
                    }
                }
                if (restorationError != null) {
                    Log.e(TAG, "VPN state restoration failed: $restorationError")
                    val measured = result ?: unavailable(started, "تعذر إكمال قياس السرعة.", planDownloadKbps, planUploadKbps, baseline)
                    result = withRestorationFailure(measured, restorationError!!)
                }
                // Persist only the finalized verdict, after restoration failures have
                // downgraded any provisional PASS/MATCH result. NonCancellable protects
                // this write from caller cancellation while the final state is committed.
                result?.let { finalized ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            SpeedVerificationStore.save(context, finalized)
                        }
                    }.onFailure { Log.e(TAG, "Could not persist verification result", it) }
                }
            }
        }
        return result ?: unavailable(started, "تعذر إكمال التحقق.", planDownloadKbps, planUploadKbps, baseline)
    }

    internal fun withRelayEvidence(
        evaluation: SpeedMetricVerification,
        measuredBps: Long?,
        relayBytes: Long,
        minimumRequiredBytes: Long,
        direction: String,
    ): SpeedMetricVerification {
        if (measuredBps == null) return evaluation
        if (minimumRequiredBytes >= 0L && relayBytes >= minimumRequiredBytes) return evaluation
        return evaluation.copy(
            status = SpeedVerificationStatus.NOT_VERIFIABLE,
            accuracyPercent = null,
            reason = listOfNotNull(
                evaluation.reason,
                "لم يثبت مرور حمولة $direction الكافية عبر Socks5Server/SpeedLimiter: " +
                    "${relayBytes.coerceAtLeast(0L)}/$minimumRequiredBytes بايت.",
            ).joinToString(" • "),
        )
    }

    private suspend fun measureThroughVerificationRelay(
        context: Context,
        planDownloadKbps: Long?,
        planUploadKbps: Long?,
        onPhaseProgress: (SpeedTestPhase) -> Unit,
    ): VerificationRelayMeasurement {
        val generation = SpeedVpnService.currentGeneration()
        check(generation > 0L && VpnRuntime.state.value.status == VpnStatus.CONNECTED) {
            "VPN is not connected while starting the verification counter window"
        }
        check(SpeedLimiter.routeVerificationTrafficThroughVpn) {
            "SpeedVPN own UID is excluded from the verification TUN route"
        }
        check(SpeedLimiter.beginVerificationTrafficWindow(generation, context.applicationInfo.uid)) {
            "Could not acquire an isolated verification relay-counter window"
        }

        val before = SpeedLimiter.verificationTrafficSnapshot(generation)
        var after: VerificationTrafficSnapshot? = null
        val measured = try {
            checkNotNull(before) { "Verification relay-counter window disappeared before measurement" }
            SpeedTestEngine.measure(
                context = context,
                expectedDownloadKbps = planDownloadKbps,
                expectedUploadKbps = planUploadKbps,
                verificationProfile = true,
            ) { phase -> onPhaseProgress(phase) }
        } finally {
            after = SpeedLimiter.verificationTrafficSnapshot(generation)
            SpeedLimiter.endVerificationTrafficWindow(generation)
        }

        val startSnapshot = before ?: throw IOException("Missing initial verification relay-counter snapshot")
        val endSnapshot = after ?: throw IOException("Missing final verification relay-counter snapshot")
        if (startSnapshot.uid != context.applicationInfo.uid || endSnapshot.uid != startSnapshot.uid) {
            throw IOException("Verification relay counters were not attributed to SpeedVPN's UID")
        }
        return VerificationRelayMeasurement(
            result = measured,
            downloadRelayBytes = (endSnapshot.downloadBytes - startSnapshot.downloadBytes).coerceAtLeast(0L),
            uploadRelayBytes = (endSnapshot.uploadBytes - startSnapshot.uploadBytes).coerceAtLeast(0L),
        )
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
