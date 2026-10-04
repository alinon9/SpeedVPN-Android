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
}
