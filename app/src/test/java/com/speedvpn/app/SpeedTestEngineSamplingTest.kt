package com.speedvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestEngineSamplingTest {
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
}
