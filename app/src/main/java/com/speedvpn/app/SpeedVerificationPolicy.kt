package com.speedvpn.app

import kotlin.math.abs
import kotlin.math.min

enum class SpeedVerificationStatus {
    MATCH,
    MISMATCH,
    NOT_VERIFIABLE,
    UNLIMITED_OK,
}

data class SpeedMetricVerification(
    val planKbps: Long?,
    val baselineBps: Long?,
    val vpnBps: Long?,
    val status: SpeedVerificationStatus,
    val accuracyPercent: Double?,
    val reason: String? = null,
)

object SpeedVerificationPolicy {
    const val TOLERANCE = 0.20
    const val BASELINE_HEADROOM_MULTIPLIER = 1.20

    // The 75 KB/s and 100 KB/s presets can be judged once the physical baseline
    // reaches the plan itself. All other presets retain the stricter 1.2x floor.
    private val LOW_RATE_HEADROOM_TARGETS_KBPS = setOf(600L, 800L)

    internal fun headroomMultiplierFor(planKbps: Long): Double =
        if (planKbps in LOW_RATE_HEADROOM_TARGETS_KBPS) 1.0 else BASELINE_HEADROOM_MULTIPLIER

    fun evaluate(planKbps: Long?, baselineBps: Long?, vpnBps: Long?): SpeedMetricVerification {
        if (planKbps == null || planKbps <= 0L) {
            if (baselineBps == null || vpnBps == null || baselineBps <= 0L || vpnBps < 0L) {
                return SpeedMetricVerification(
                    null, baselineBps, vpnBps, SpeedVerificationStatus.NOT_VERIFIABLE, null,
                    "تعذر قياس السرعة الأصلية أو سرعة VPN.",
                )
            }
            val retention = vpnBps.toDouble() / baselineBps.toDouble()
            return if (retention >= (1.0 - TOLERANCE)) {
                SpeedMetricVerification(
                    null, baselineBps, vpnBps, SpeedVerificationStatus.UNLIMITED_OK,
                    min(100.0, retention * 100.0),
                    "لا يظهر تدهور يتجاوز هامش الاختبار مقارنة بسرعة الشبكة الأصلية.",
                )
            } else {
                SpeedMetricVerification(
                    null, baselineBps, vpnBps, SpeedVerificationStatus.MISMATCH,
                    min(100.0, retention * 100.0),
                    "سرعة VPN أقل من الشبكة الأصلية بأكثر من هامش الاختبار.",
                )
            }
        }

        val targetBps = planKbps.coerceAtLeast(1L) * 1_000L
        if (baselineBps == null || vpnBps == null || baselineBps <= 0L || vpnBps < 0L) {
            return SpeedMetricVerification(
                planKbps, baselineBps, vpnBps, SpeedVerificationStatus.NOT_VERIFIABLE, null,
                "فشل قياس السرعة اللازمة للمقارنة.",
            )
        }

        val headroomMultiplier = headroomMultiplierFor(planKbps)
        if (baselineBps.toDouble() < targetBps.toDouble() * headroomMultiplier) {
            val requiredLabel = if (headroomMultiplier <= 1.0) "1.0" else "1.2"
            return SpeedMetricVerification(
                planKbps, baselineBps, vpnBps, SpeedVerificationStatus.NOT_VERIFIABLE, null,
                "سرعة الشبكة الأصلية يجب أن تبلغ $requiredLabel مرة من الخطة على الأقل.",
            )
        }

        val relativeError = abs(vpnBps.toDouble() - targetBps.toDouble()) / targetBps.toDouble()
        val accuracy = (100.0 - relativeError * 100.0).coerceIn(0.0, 100.0)
        val lowerBound = targetBps.toDouble() * (1.0 - TOLERANCE)
        val upperBound = targetBps.toDouble() * (1.0 + TOLERANCE)

        val status: SpeedVerificationStatus
        val reason: String
        when {
            vpnBps.toDouble() in lowerBound..upperBound -> {
                status = SpeedVerificationStatus.MATCH
                reason = "السرعة داخل VPN ضمن ±20% من السرعة المحددة."
            }
            vpnBps > upperBound -> {
                status = SpeedVerificationStatus.MISMATCH
                reason = "السرعة داخل VPN أعلى من الحد المحدد بأكثر من 20%؛ لم يثبت تطبيق السقف."
            }
            else -> {
                status = SpeedVerificationStatus.MISMATCH
                reason = "السرعة داخل VPN أقل من الحد المحدد بأكثر من 20%."
            }
        }

        return SpeedMetricVerification(
            planKbps, baselineBps, vpnBps, status, accuracy, reason,
        )
    }

    fun overall(download: SpeedMetricVerification, upload: SpeedMetricVerification): SpeedVerificationStatus {
        val metrics = listOf(download, upload)
        if (metrics.any { it.status == SpeedVerificationStatus.NOT_VERIFIABLE }) return SpeedVerificationStatus.NOT_VERIFIABLE
        if (metrics.any { it.status == SpeedVerificationStatus.MISMATCH }) return SpeedVerificationStatus.MISMATCH
        return if (metrics.any { it.status == SpeedVerificationStatus.MATCH }) {
            SpeedVerificationStatus.MATCH
        } else {
            SpeedVerificationStatus.UNLIMITED_OK
        }
    }
}
