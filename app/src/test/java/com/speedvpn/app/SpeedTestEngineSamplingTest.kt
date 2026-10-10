package com.speedvpn.app

import org.junit.Assert.assertArrayEquals
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestEngineSamplingTest {
    @Test
    fun configuredDebugFixtureIsUsedForBothEndpoints() {
        val baseUrl = "http://10.0.2.2:18765/"
        assertEquals(
            "http://10.0.2.2:18765/__down",
            SpeedTestEngine.endpointUrl("__down", baseUrl),
        )
        assertEquals(
            "http://10.0.2.2:18765/__up",
            SpeedTestEngine.endpointUrl("__up", baseUrl),
        )
    }

    @Test
    fun emptyFixtureConfigurationKeepsPublicCloudflareEndpoint() {
        assertEquals(
            "https://speed.cloudflare.com/__down",
            SpeedTestEngine.endpointUrl("__down", ""),
        )
        assertEquals(
            "https://speed.cloudflare.com/__up",
            SpeedTestEngine.endpointUrl("__up", null),
        )
    }

    @Test
    fun mediumAndHighFinitePlansUseThreeEqualSustainedSamples() {
        assertArrayEquals(
            longArrayOf(3_000_000L, 3_000_000L, 3_000_000L),
            SpeedTestEngine.requestSizesFor(16_000L),
        )
        assertArrayEquals(
            longArrayOf(16_500_000L, 16_500_000L, 16_500_000L),
            SpeedTestEngine.requestSizesFor(88_000L),
        )
    }

    @Test
    fun finiteSamplesAimForAtLeastFifteenHundredMillisecondsAtTargetRate() {
        val targetKbps = 16_000L
        val size = SpeedTestEngine.requestSizesFor(targetKbps).first()
        val durationMs = SpeedTestEngine.requestDurationFor(size, targetKbps * 1_000L)
        assertTrue(
            "Expected a sustained sample, got $durationMs ms",
            durationMs >= SpeedTestEngine.FINITE_SAMPLE_TARGET_DURATION_MS,
        )
    }

    @Test
    fun lowFinitePlansRetainBoundedRequestLadder() {
        assertArrayEquals(
            longArrayOf(128_000L, 192_000L, 256_000L),
            SpeedTestEngine.requestSizesFor(80L),
        )
        assertArrayEquals(
            longArrayOf(128_000L, 256_000L, 512_000L, 1_000_000L),
            SpeedTestEngine.requestSizesFor(800L),
        )
    }

    @Test
    fun baselineAndUnlimitedKeepAdaptiveLadder() {
        val baseline = SpeedTestEngine.requestSizesFor(null)
        assertEquals(7, baseline.size)
        assertTrue(baseline.asList().zipWithNext().all { (left, right) -> left < right })
        assertArrayEquals(baseline, SpeedTestEngine.requestSizesFor(0L))
    }

    @Test
    fun unlimitedVerificationUsesThreeEqualSustainedSamplesOnBothSides() {
        val expected = longArrayOf(20_000_000L, 20_000_000L, 20_000_000L)
        assertArrayEquals(expected, SpeedTestEngine.requestSizesFor(null, verificationProfile = true))
        assertArrayEquals(expected, SpeedTestEngine.requestSizesFor(0L, verificationProfile = true))
        assertArrayEquals(
            SpeedTestEngine.requestSizesFor(null, verificationProfile = true),
            SpeedTestEngine.requestSizesFor(0L, verificationProfile = true),
        )
    }

    @Test
    fun finiteVerificationUsesTheSelectedPlanSampleSizes() {
        for (targetKbps in listOf(80L, 800L, 16_000L, 88_000L)) {
            assertArrayEquals(
                SpeedTestEngine.requestSizesFor(targetKbps),
                SpeedTestEngine.requestSizesFor(targetKbps, verificationProfile = true),
            )
        }
    }

    @Test
    fun finiteVerificationCapsRequestsAtTheFixtureCeilingForVeryHighPlans() {
        assertArrayEquals(
            longArrayOf(20_000_000L, 20_000_000L, 20_000_000L),
            SpeedTestEngine.requestSizesFor(800_000L, verificationProfile = true),
        )
    }

    @Test
    fun phaseBudgetUsesMonotonicElapsedTimeAndAnInclusiveDeadline() {
        val started = 1_000_000_000L
        assertFalse(SpeedTestEngine.phaseBudgetExceeded(started, 1_074_999_999L, 75L))
        assertTrue(SpeedTestEngine.phaseBudgetExceeded(started, 1_075_000_000L, 75L))
    }

    @Test
    fun sampleLadderStopsWhenRequiredCoverageBecomesImpossible() {
        assertTrue(SpeedTestEngine.enoughRemainingSamples(0, 3, 3))
        assertTrue(SpeedTestEngine.enoughRemainingSamples(1, 2, 3))
        assertFalse(SpeedTestEngine.enoughRemainingSamples(0, 2, 3))
        assertFalse(SpeedTestEngine.enoughRemainingSamples(1, 1, 3))
    }

    @Test
    fun eachMeasurementClientUsesAnIsolatedConnectionPool() {
        val sharedClient = OkHttpClient.Builder().build()
        val baselineClient = SpeedTestEngine.createIsolatedMeasurementClient(sharedClient, Dns.SYSTEM)
        val vpnClient = SpeedTestEngine.createIsolatedMeasurementClient(sharedClient, Dns.SYSTEM)

        assertNotSame(sharedClient.connectionPool, baselineClient.connectionPool)
        assertNotSame(sharedClient.connectionPool, vpnClient.connectionPool)
        assertNotSame(baselineClient.connectionPool, vpnClient.connectionPool)

        baselineClient.connectionPool.evictAll()
        vpnClient.connectionPool.evictAll()
        sharedClient.connectionPool.evictAll()
    }

}
