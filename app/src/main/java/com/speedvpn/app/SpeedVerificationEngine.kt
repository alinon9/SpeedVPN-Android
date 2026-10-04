package com.speedvpn.app

import android.content.Context
import android.net.VpnService
import kotlinx.coroutines.delay

data class SpeedVerificationResult(
    val startedAtMillis: Long,
    val durationMs: Long,
    val download: SpeedMetricVerification,
    val upload: SpeedMetricVerification,
    val overallStatus: SpeedVerificationStatus,
    val note: String? = null,
)

object SpeedVerificationEngine {
    private const val STATE_POLL_MS = 250L
    private const val STATE_TIMEOUT_MS = 30_000L

    suspend fun verify(
        context: Context,
        planDownloadKbps: Long?,
        planUploadKbps: Long?,
        onProgress: (String) -> Unit = {},
    ): SpeedVerificationResult {
        val started = System.currentTimeMillis()

        if (VpnService.prepare(context) != null) {
            return unavailable(started, "إذن VPN غير متاح. امنح الإذن أولًا ثم أعد التحقق.", planDownloadKbps, planUploadKbps)
        }

        val initiallyConnected = VpnRuntime.state.value.status == VpnStatus.CONNECTED
        var baseline: SpeedTestResult? = null
        var vpn: SpeedTestResult? = null
        var note: String? = null

        try {
            onProgress("فصل VPN لقياس سرعة الشبكة الأصلية…")
            if (VpnRuntime.state.value.status != VpnStatus.DISCONNECTED) {
                SpeedVpnService.stop(context)
                if (!waitForStatus(VpnStatus.DISCONNECTED)) {
                    return unavailable(started, "تعذر فصل VPN قبل الاختبار.", planDownloadKbps, planUploadKbps)
                }
            }

            onProgress("قياس Download للشبكة الأصلية…")
            baseline = SpeedTestEngine.measure { phase ->
                onProgress(if (phase == SpeedTestPhase.DOWNLOAD) "قياس Download للشبكة الأصلية…" else "قياس Upload للشبكة الأصلية…")
            }

            if (baseline.downloadBps == null && baseline.uploadBps == null) {
                return unavailable(started, "فشل قياس الشبكة الأصلية.", planDownloadKbps, planUploadKbps)
            }

            onProgress("تشغيل VPN للتحقق من الخطة…")
            SpeedVpnService.start(context)
            if (!waitForStatus(VpnStatus.CONNECTED)) {
                return unavailable(started, "تعذر الوصول إلى حالة VPN متصل.", planDownloadKbps, planUploadKbps)
            }

            onProgress("قياس Download عبر VPN…")
            vpn = SpeedTestEngine.measure { phase ->
                onProgress(if (phase == SpeedTestPhase.DOWNLOAD) "قياس Download عبر VPN…" else "قياس Upload عبر VPN…")
            }

            if (vpn.downloadBps == null && vpn.uploadBps == null) {
                return unavailable(started, "فشل القياس أثناء مرور الترافيك عبر VPN.", planDownloadKbps, planUploadKbps)
            }

            note = listOfNotNull(
                baseline.error?.let { "الشبكة: $it" },
                vpn.error?.let { "VPN: $it" },
            ).joinToString(" • ").takeIf { it.isNotBlank() }

            val download = SpeedVerificationPolicy.evaluate(
                planKbps = planDownloadKbps,
                baselineBps = baseline.downloadBps,
                vpnBps = vpn.downloadBps,
            )
            val upload = SpeedVerificationPolicy.evaluate(
                planKbps = planUploadKbps,
                baselineBps = baseline.uploadBps,
                vpnBps = vpn.uploadBps,
            )

            return SpeedVerificationResult(
                startedAtMillis = started,
                durationMs = System.currentTimeMillis() - started,
                download = download,
                upload = upload,
                overallStatus = SpeedVerificationPolicy.overall(download, upload),
                note = note,
            )
        } catch (t: Throwable) {
            return unavailable(started, "فشل التحقق: " + (t.message ?: t.javaClass.simpleName), planDownloadKbps, planUploadKbps)
        } finally {
            onProgress("استعادة حالة VPN السابقة…")
            if (initiallyConnected) {
                if (VpnRuntime.state.value.status != VpnStatus.CONNECTED) {
                    SpeedVpnService.start(context)
                    waitForStatus(VpnStatus.CONNECTED)
                }
            } else {
                if (VpnRuntime.state.value.status != VpnStatus.DISCONNECTED) {
                    SpeedVpnService.stop(context)
                    waitForStatus(VpnStatus.DISCONNECTED)
                }
            }
        }
    }

    private suspend fun waitForStatus(target: VpnStatus): Boolean {
        var waited = 0L
        while (waited <= STATE_TIMEOUT_MS) {
            if (VpnRuntime.state.value.status == target) return true
            delay(STATE_POLL_MS)
            waited += STATE_POLL_MS
        }
        return false
    }

    private fun unavailable(started: Long, note: String, planDownloadKbps: Long? = null, planUploadKbps: Long? = null): SpeedVerificationResult {
        val downloadMetric = SpeedMetricVerification(
            planKbps = planDownloadKbps,
            baselineBps = null,
            vpnBps = null,
            status = SpeedVerificationStatus.NOT_VERIFIABLE,
            accuracyPercent = null,
            reason = note,
        )
        val uploadMetric = downloadMetric.copy(planKbps = planUploadKbps)
        return SpeedVerificationResult(
            startedAtMillis = started,
            durationMs = System.currentTimeMillis() - started,
            download = downloadMetric,
            upload = uploadMetric,
            overallStatus = SpeedVerificationStatus.NOT_VERIFIABLE,
            note = note,
        )
    }
}
