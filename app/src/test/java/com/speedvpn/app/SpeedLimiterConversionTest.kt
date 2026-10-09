package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertTrue(bucket.acquire(16 * 1024))
    }

    @Test
    fun limiterPacesByConfiguredBytesPerSecond() {
        val bucket = TokenBucket()
        bucket.setRate(2_000L)
        val generation = 1L
        bucket.beginSession(generation)

        // Drain the initial bounded burst, then request 100 bytes at 2,000 B/s.
        assertTrue(bucket.acquire(16 * 1024, { true }))
        val start = System.nanoTime()
        assertTrue(bucket.acquire(100, { true }))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        // 100 bytes at 2,000 B/s is 50 ms. Allow scheduler jitter, but reject
        // a limiter that grants the second chunk immediately.
        assertTrue("Limiter granted too early: ${elapsedMs}ms", elapsedMs >= 35L)
        bucket.endSession(generation)
    }

    @Test
    fun limiterAccruesCreditDuringOutsideIoWork() {
        val bucket = TokenBucket()
        bucket.setRate(20_000L)

        // Consume half the initial burst. While the caller performs socket I/O,
        // a true token bucket should earn credit during the 120 ms outside the lock.
        assertTrue(bucket.acquire(8 * 1024, { true }))
        Thread.sleep(120L)

        val start = System.nanoTime()
        assertTrue(bucket.acquire(8 * 1024, { true }))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        // The current deadline-per-chunk scheduler waits about another 280 ms here.
        // A refillable bucket has accumulated enough credit to grant this chunk now.
        assertTrue("Credit was not accrued during outside I/O: ${elapsedMs}ms", elapsedMs < 150L)
    }
}
