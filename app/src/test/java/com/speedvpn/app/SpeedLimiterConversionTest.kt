package com.speedvpn.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimiterConversionTest {
    @Test
    fun relayReadSizeFollowsBoundedPacingWindow() {
        val bucket = TokenBucket()

        // A finite plan reads approximately its 10 ms token budget, without a
        // forced 16 KiB floor that would create a large low-rate burst.
        bucket.setRate(10_000L)
        assertEquals(100, bucket.recommendedReadBytes())
        bucket.setRate(1L)
        assertEquals(1, bucket.recommendedReadBytes())

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
    fun convertsKbpsAndMbpsAndNormalizesUnlimitedAndExtremeValues() {
        assertEquals(1_500L, SpeedLimiter.toKbps(1.5, "Mbps"))
        assertEquals(100L, SpeedLimiter.toKbps(100.9, "Kbps"))
        assertTrue(SpeedLimiter.toKbps(0.0, "Mbps") == null)
        assertTrue(SpeedLimiter.toKbps(-1.0, "Kbps") == null)
        assertTrue(SpeedLimiter.toKbps(1.0, "Gbps") == null)
        assertEquals(Long.MAX_VALUE / 125L, SpeedLimiter.toKbps(Double.MAX_VALUE, "Kbps"))
        assertTrue(SpeedLimiter.normalizeKbps(0L) == null)
        assertTrue(SpeedLimiter.normalizeKbps(-5L) == null)
        assertEquals(Long.MAX_VALUE / 125L, SpeedLimiter.normalizeKbps(Long.MAX_VALUE))
    }

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

        // Drain the configured 10 ms burst and request the same amount again.
        val chunkBytes = bucket.recommendedReadBytes()
        assertTrue(bucket.acquire(chunkBytes, { true }))
        val start = System.nanoTime()
        assertTrue(bucket.acquire(chunkBytes, { true }))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        // The next 10 ms worth of bytes must not be granted immediately.
        assertTrue("Limiter granted too early: ${elapsedMs}ms", elapsedMs >= 5L)
        bucket.endSession(generation)
    }

    @Test
    fun switchingBetweenFiniteAndUnlimitedDoesNotRetainPacingState() {
        val bucket = TokenBucket()
        bucket.setRate(2_000L)

        // Empty the rate-sized burst and prove a following chunk is paced.
        var chunkBytes = bucket.recommendedReadBytes()
        assertTrue(bucket.acquire(chunkBytes))
        var started = System.nanoTime()
        assertTrue(bucket.acquire(chunkBytes))
        var finiteWaitMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Finite plan did not pace: ${finiteWaitMs}ms", finiteWaitMs >= 5L)

        // Unlimited must bypass the previous plan immediately, including large chunks.
        bucket.setRate(0L)
        started = System.nanoTime()
        repeat(32) { assertTrue(bucket.acquire(256 * 1024)) }
        val unlimitedElapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Unlimited retained pacing delay: ${unlimitedElapsedMs}ms", unlimitedElapsedMs < 100L)

        // Returning to a finite plan must restore the configured cap.
        bucket.setRate(2_000L)
        chunkBytes = bucket.recommendedReadBytes()
        assertTrue(bucket.acquire(chunkBytes))
        started = System.nanoTime()
        assertTrue(bucket.acquire(chunkBytes))
        finiteWaitMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Finite cap was not restored after Unlimited: ${finiteWaitMs}ms", finiteWaitMs >= 5L)
    }

    @Test
    fun limiterAccruesCreditDuringOutsideIoWork() {
        val bucket = TokenBucket()
        bucket.setRate(20_000L)

        // Consume half the burst. Credit earned during outside socket I/O lets
        // the bucket refill to capacity without a second, redundant sleep.
        assertTrue(bucket.acquire(100, { true }))
        Thread.sleep(120L)

        val start = System.nanoTime()
        assertTrue(bucket.acquire(200, { true }))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        assertTrue("Credit was not accrued during outside I/O: ${elapsedMs}ms", elapsedMs < 150L)
    }

    @Test
    fun oversizedUdpPayloadWaitsForItsTransmissionBudget() {
        val bucket = TokenBucket()
        bucket.setRate(1_000L) // 10 bytes of burst capacity at 1,000 bytes/second.

        val started = System.nanoTime()
        assertTrue(bucket.acquire(100))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        // Only the first 10 bytes fit the burst. The remaining 90 bytes must be
        // paced before acquire returns, not sent as an unrestricted initial spike.
        assertTrue("Oversized payload was forwarded too early: ${elapsedMs}ms", elapsedMs >= 50L)
        assertTrue("Oversized payload waited too long: ${elapsedMs}ms", elapsedMs < 500L)
    }

    @Test
    fun changingFromLowRateWithDebtToHighRateWakesWaiterImmediately() {
        val bucket = TokenBucket()
        bucket.setRate(100L) // 1 byte of burst at 100 bytes/second.

        val waiting = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val acquired = AtomicBoolean(false)
        val waiter = Thread {
            waiting.countDown()
            // A 100-byte reservation creates debt and waits for its budget.
            acquired.set(bucket.acquire(100))
            completed.countDown()
        }
        waiter.start()

        try {
            assertTrue("Waiter did not start", waiting.await(1, TimeUnit.SECONDS))
            Thread.sleep(40L)
            val changedAt = System.nanoTime()
            bucket.setRate(1_000_000L)
            assertTrue(
                "Rate change did not wake the waiter promptly",
                completed.await(250, TimeUnit.MILLISECONDS),
            )
            val elapsedMs = (System.nanoTime() - changedAt) / 1_000_000L
            assertTrue("Waiter kept stale pacing debt for ${elapsedMs}ms", elapsedMs < 250L)
            assertTrue(acquired.get())
        } finally {
            waiter.interrupt()
            waiter.join(1_000L)
        }
    }

    @Test
    fun concurrentFlowsShareOneRateBudget() {
        val bucket = TokenBucket()
        bucket.setRate(1_000L) // 10 bytes per 10 ms, shared by every caller.

        val flowCount = 8
        val ready = CountDownLatch(flowCount)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(flowCount)
        val allAcquired = AtomicBoolean(true)
        val flows = (0 until flowCount).map {
            Thread {
                ready.countDown()
                try {
                    if (!start.await(1, TimeUnit.SECONDS) || !bucket.acquire(10)) {
                        allAcquired.set(false)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    allAcquired.set(false)
                } finally {
                    finished.countDown()
                }
            }.also { it.start() }
        }

        try {
            assertTrue("Flows did not become ready", ready.await(1, TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            start.countDown()
            assertTrue("Concurrent acquires stalled", finished.await(2, TimeUnit.SECONDS))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue(allAcquired.get())
            assertTrue(
                "Concurrent flows bypassed the shared rate budget (${elapsedMs}ms)",
                elapsedMs >= 30L,
            )
        } finally {
            flows.forEach { it.interrupt() }
            flows.forEach { it.join(1_000L) }
        }
    }
}
