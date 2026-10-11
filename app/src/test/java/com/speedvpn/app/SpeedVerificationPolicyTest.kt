package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedVerificationPolicyTest {
    @Test
    fun matchedWithinTwentyPercent() {
        val result = SpeedVerificationPolicy.evaluate(
            planKbps = 512L,
            baselineBps = 8_400_000L,
            vpnBps = 505_000L,
        )

        assertEquals(SpeedVerificationStatus.MATCH, result.status)
        assertEquals(98.6, result.accuracyPercent!!, 0.2)
    }

    @Test
    fun lowerOriginalNetworkIsNotVerifiable() {
        val result = SpeedVerificationPolicy.evaluate(
            planKbps = 5_000L,
            baselineBps = 2_100_000L,
            vpnBps = 2_000_000L,
        )

        assertEquals(SpeedVerificationStatus.NOT_VERIFIABLE, result.status)
    }

    @Test
    fun baselineJustBelowHeadroomFloorIsNotVerifiable() {
        val result = SpeedVerificationPolicy.evaluate(
            planKbps = 1_000L,
            baselineBps = 1_199_999L,
            vpnBps = 1_000_000L,
        )

        assertEquals(SpeedVerificationStatus.NOT_VERIFIABLE, result.status)
    }

    @Test
    fun baselineAtHeadroomFloorIsVerifiable() {
        val result = SpeedVerificationPolicy.evaluate(
            planKbps = 1_000L,
            baselineBps = 1_200_000L,
            vpnBps = 1_000_000L,
        )

        assertEquals(SpeedVerificationStatus.MATCH, result.status)
    }

    @Test
    fun moreThanTwentyPercentErrorIsMismatch() {
        val result = SpeedVerificationPolicy.evaluate(
            planKbps = 5_000L,
            baselineBps = 20_000_000L,
            vpnBps = 2_000_000L,
        )

        assertEquals(SpeedVerificationStatus.MISMATCH, result.status)
        assertTrue(result.accuracyPercent!! < 50.0)
    }

    @Test
    fun unlimitedUsesBaselineComparison() {
        val result = SpeedVerificationPolicy.evaluate(
            planKbps = null,
            baselineBps = 10_000_000L,
            vpnBps = 9_200_000L,
        )

        assertEquals(SpeedVerificationStatus.UNLIMITED_OK, result.status)
        assertEquals(92.0, result.accuracyPercent!!, 0.1)
    }

    @Test
    fun zeroSentinelUsesUnlimitedBaselineComparison() {
        val nullPlan = SpeedVerificationPolicy.evaluate(
            planKbps = null,
            baselineBps = 10_000_000L,
            vpnBps = 9_200_000L,
        )
        val zeroPlan = SpeedVerificationPolicy.evaluate(
            planKbps = 0L,
            baselineBps = 10_000_000L,
            vpnBps = 9_200_000L,
        )

        assertEquals(SpeedVerificationStatus.UNLIMITED_OK, zeroPlan.status)
        assertEquals(nullPlan.status, zeroPlan.status)
        assertEquals(nullPlan.accuracyPercent!!, zeroPlan.accuracyPercent!!, 0.001)

        val belowRetentionFloor = SpeedVerificationPolicy.evaluate(
            planKbps = 0L,
            baselineBps = 10_000_000L,
            vpnBps = 7_900_000L,
        )
        assertEquals(SpeedVerificationStatus.MISMATCH, belowRetentionFloor.status)
    }

    @Test
    fun failedVpnMeasurementsPreserveSuccessfulPhysicalBaselineByDirection() {
        val baseline = SpeedTestResult(
            downloadBps = 101_860_000L,
            uploadBps = 63_210_000L,
            durationMs = 4_000L,
            error = null,
        )

        val result = SpeedVerificationEngine.unavailable(
            started = 1L,
            note = "VPN measurement failed",
            planDownloadKbps = 32_000L,
            planUploadKbps = 32_000L,
            baseline = baseline,
        )

        assertEquals(101_860_000L, result.download.baselineBps)
        assertEquals(63_210_000L, result.upload.baselineBps)
        assertEquals(null, result.download.vpnBps)
        assertEquals(null, result.upload.vpnBps)
        assertEquals(SpeedVerificationStatus.NOT_VERIFIABLE, result.download.status)
        assertEquals(SpeedVerificationStatus.NOT_VERIFIABLE, result.upload.status)
        assertEquals(32_000L, result.download.planKbps)
        assertEquals(32_000L, result.upload.planKbps)
    }

    @Test
    fun failedVpnRestorationInvalidatesBothMetricVerdicts() {
        val matched = SpeedMetricVerification(
            planKbps = 1_000L,
            baselineBps = 2_000_000L,
            vpnBps = 1_000_000L,
            status = SpeedVerificationStatus.MATCH,
            accuracyPercent = 100.0,
        )
        val result = SpeedVerificationResult(
            startedAtMillis = 1L,
            durationMs = 100L,
            download = matched,
            upload = matched,
            overallStatus = SpeedVerificationStatus.MATCH,
        )

        val invalidated = SpeedVerificationEngine.withRestorationFailure(result, "stop timeout")

        assertEquals(SpeedVerificationStatus.NOT_VERIFIABLE, invalidated.overallStatus)
        assertEquals(SpeedVerificationStatus.NOT_VERIFIABLE, invalidated.download.status)
        assertEquals(SpeedVerificationStatus.NOT_VERIFIABLE, invalidated.upload.status)
    }
}
