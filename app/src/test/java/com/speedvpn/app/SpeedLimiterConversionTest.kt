package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimiterConversionTest {
    @Test
    fun relayReadSizeFollowsBoundedPacingWindow() {
        val bucket = TokenBucket()

        // A 10 KB/s plan should use a small 1 KiB chunk, not burst 16 KiB at once.
        bucket.setRate(10_000L)
        assertEquals(1024, bucket.recommendedReadBytes())

        // Larger finite plans use proportionally larger chunks to reduce wakeups.
        bucket.setRate(2_000_000L)
        assertEquals(20_000, bucket.recommendedReadBytes())
        bucket.setRate(4_000_000L)
        assertEquals(40_000, bucket.recommendedReadBytes())

        // The recommended read size follows the 10 ms pacing window, bounded to 256 KiB.
        bucket.setRate(9_000_000L) // 72 Mbps
        assertEquals(90_000, bucket.recommendedReadBytes())
        bucket.setRate(11_000_000L) // 88 Mbps
        assertEquals(110_000, bucket.recommendedReadBytes())
        bucket.setRate(100_000_000L)
        assertEquals(256 * 1024, bucket.recommendedReadBytes())
        bucket.setRate(0L)
        assertEquals(256 * 1024, bucket.recommendedReadBytes())
    }

    @Test
    fun convertsUiPresetsAndCustomMegabitsToBytesPerSecond() {
        // UI presets are expressed in decimal KB/s, stored as Kbps, then paced as B/s.
        val tenKbPerSec = SpeedLimiter.kilobytesPerSecondToKbps(10L)
        assertEquals(80L, tenKbPerSec)
        assertEquals(10_000L, SpeedLimiter.kbpsToBytesPerSec(tenKbPerSec))

        // 1 MB/s and the high presets must not lose a factor of 8 or 1,000.
        assertEquals(8_000L, SpeedLimiter.kilobytesPerSecondToKbps(1_000L))
        assertEquals(1_000_000L, SpeedLimiter.kbpsToBytesPerSec(8_000L))
        assertEquals(5_000_000L, SpeedLimiter.kbpsToBytesPerSec(40_000L))

        // Custom input in Mbps is converted to decimal Kbps before byte pacing.
        assertEquals(72_000L, SpeedLimiter.toKbps(72.0, "Mbps"))
        assertEquals(9_000_000L, SpeedLimiter.kbpsToBytesPerSec(SpeedLimiter.toKbps(72.0, "Mbps")))
        assertEquals(88_000L, SpeedLimiter.toKbps(88.0, "Mbps"))
        assertEquals(11_000_000L, SpeedLimiter.kbpsToBytesPerSec(SpeedLimiter.toKbps(88.0, "Mbps")))

        // Zero/null is the documented Unlimited representation.
        assertEquals(0L, SpeedLimiter.kbpsToBytesPerSec(null))
        assertEquals(0L, SpeedLimiter.kbpsToBytesPerSec(0L))
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

        // Drain the bucket's current bounded burst, then request 100 bytes at 2,000 B/s.
        assertTrue(bucket.acquire(bucket.recommendedReadBytes(), { true }))
        val start = System.nanoTime()
        assertTrue(bucket.acquire(100, { true }))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        // 100 bytes at 2,000 B/s is 50 ms. Allow scheduler jitter, but reject
        // a limiter that grants the second chunk immediately.
        assertTrue("Limiter granted too early: ${elapsedMs}ms", elapsedMs >= 35L)
        bucket.endSession(generation)
    }

    @Test
    fun switchingBetweenFiniteAndUnlimitedDoesNotRetainPacingState() {
        val bucket = TokenBucket()
        bucket.setRate(2_000L)

        // Empty the initial burst and prove the finite rate really waits.
        assertTrue(bucket.acquire(bucket.recommendedReadBytes()))
        var started = System.nanoTime()
        assertTrue(bucket.acquire(100))
        var finiteWaitMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Finite plan did not pace: ${finiteWaitMs}ms", finiteWaitMs >= 35L)

        // Unlimited must bypass the previous plan immediately, including large chunks.
        bucket.setRate(0L)
        started = System.nanoTime()
        repeat(32) { assertTrue(bucket.acquire(256 * 1024)) }
        val unlimitedElapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Unlimited retained pacing delay: ${unlimitedElapsedMs}ms", unlimitedElapsedMs < 100L)

        // Returning to a finite plan must restore the configured cap.
        bucket.setRate(2_000L)
        assertTrue(bucket.acquire(bucket.recommendedReadBytes()))
        started = System.nanoTime()
        assertTrue(bucket.acquire(100))
        finiteWaitMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Finite cap was not restored after Unlimited: ${finiteWaitMs}ms", finiteWaitMs >= 35L)
    }

    @Test
    fun changingToUnlimitedWakesAnExistingWaiterImmediately() {
        val bucket = TokenBucket()
        bucket.setRate(1L)
        assertTrue(bucket.acquire(bucket.recommendedReadBytes()))

        val entered = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val acquired = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            entered.countDown()
            acquired.set(bucket.acquire(1))
            finished.countDown()
        }.apply { isDaemon = true; start() }

        assertTrue("Waiter did not start", entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(50L)
        bucket.setRate(0L)
        assertTrue("Rate change did not wake the blocked waiter", finished.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertTrue(acquired.get())
    }

    @Test
    fun limiterAccruesCreditDuringOutsideIoWork() {
        val bucket = TokenBucket()
        bucket.setRate(20_000L)

        // Consume half the minimum bucket. While the caller performs socket I/O,
        // a true token bucket should earn credit during the 120 ms outside the lock.
        assertTrue(bucket.acquire(512, { true }))
        Thread.sleep(120L)

        val start = System.nanoTime()
        assertTrue(bucket.acquire(512, { true }))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        // A refillable bucket has accumulated enough credit to grant this chunk now.
        assertTrue("Credit was not accrued during outside I/O: ${elapsedMs}ms", elapsedMs < 150L)
    }

    @Test
    fun unlimitedFastPathHonorsCanProceedAndJitterHeadroomCapsBurstDebt() {
        val bucket = TokenBucket()
        bucket.setRate(0L)
        assertTrue(bucket.acquire(64 * 1024) { true })
        assertTrue(!bucket.acquire(64 * 1024) { false })
        assertEquals(256 * 1024, bucket.recommendedReadBytes())

        // At 10,000 B/s, capacityBytes is 1,024 and maxCreditBytes is 2,048.
        // Draining the initial 1,024-byte burst and sleeping 300ms refills at most
        // 2,048 bytes (not 3,000), so two 1,024-byte chunks pass immediately while
        // a third 500-byte chunk still paces at 10,000 B/s (~50ms).
        bucket.setRate(10_000L)
        assertTrue(bucket.acquire(1024))
        Thread.sleep(300L)
        val burstStart = System.nanoTime()
        assertTrue(bucket.acquire(1024))
        assertTrue(bucket.acquire(1024))
        val burstElapsedMs = (System.nanoTime() - burstStart) / 1_000_000L
        assertTrue("Expected 2x jitter headroom to grant two chunks without sleep, got ${burstElapsedMs}ms", burstElapsedMs < 30L)

        val pacedStart = System.nanoTime()
        assertTrue(bucket.acquire(500))
        val pacedElapsedMs = (System.nanoTime() - pacedStart) / 1_000_000L
        assertTrue("Expected third chunk beyond maxCreditBytes to pace, got ${pacedElapsedMs}ms", pacedElapsedMs >= 35L)
    }
}
