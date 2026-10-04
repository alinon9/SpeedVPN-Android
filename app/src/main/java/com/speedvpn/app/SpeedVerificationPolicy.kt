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

    fun evaluate(planKbps: Long?, baselineBps: Long?, vpnBps: Long?): SpeedMetricVerification {
        if (planKbps == null) {
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
                    "لا يظهر سقف رقمي واضح مقارنة بسرعة الشبكة الأصلية.",
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

        if (baselineBps < targetBps) {
            return SpeedMetricVerification(
                planKbps, baselineBps, vpnBps, SpeedVerificationStatus.NOT_VERIFIABLE, null,
                "سرعة الشبكة الأصلية أقل من الخطة المحددة.",
            )
        }

        val relativeError = abs(vpnBps.toDouble() - targetBps.toDouble()) / targetBps.toDouble()
        val accuracy = (100.0 - relativeError * 100.0).coerceIn(0.0, 100.0)
        val status = if (relativeError <= TOLERANCE) {
            SpeedVerificationStatus.MATCH
        } else {
            SpeedVerificationStatus.MISMATCH
        }

        return SpeedMetricVerification(
            planKbps, baselineBps, vpnBps, status, accuracy,
            if (status == SpeedVerificationStatus.MATCH) {
                "السرعة ضمن هامش السماح ±20%."
            } else {
                "السرعة عبر VPN خارج هامش السماح ±20%."
            },
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
