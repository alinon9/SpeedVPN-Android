package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedLimiterConversionTest {
    @Test
    fun convertsKilobitsToBytesPerSecond() {
        val bucket = TokenBucket()

        bucket.setRate(10L * 125L)
        assertEquals(10L * 125L, bucket.bytesPerSec)

        bucket.setRate(800L * 125L)
        assertEquals(100_000L, bucket.bytesPerSec)

        bucket.setRate(8_000L * 125L)
        assertEquals(1_000_000L, bucket.bytesPerSec)

        bucket.setRate(40_000L * 125L)
        assertEquals(5_000_000L, bucket.bytesPerSec)

        bucket.setRate(800_000L * 125L)
        assertEquals(100_000_000L, bucket.bytesPerSec)
    }

    @Test
    fun zeroRateMeansUnlimited() {
        val bucket = TokenBucket()
        bucket.setRate(0L)
        assertEquals(0L, bucket.bytesPerSec)
    }

    @Test
    fun limiterPacesByConfiguredBytesPerSecond() {
        val bucket = TokenBucket()
        bucket.setRate(2_000L)
        val generation = 1L
        bucket.beginSession(generation)

        assertEquals(true, bucket.acquire(100, { true }))
        val start = System.nanoTime()
        assertEquals(true, bucket.acquire(100, { true }))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        // 100 bytes at 2,000 B/s is 50 ms. Allow scheduler jitter, but reject
        // a limiter that grants the second chunk immediately.
        assert(elapsedMs >= 35L) { "Limiter granted too early: ${elapsedMs}ms" }
        bucket.endSession(generation)
    }
}
