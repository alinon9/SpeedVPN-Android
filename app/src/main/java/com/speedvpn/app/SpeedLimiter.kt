package com.speedvpn.app

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * Global device-wide pacing limiter.
 *
 * A single scheduler is shared by all flows in each direction. Waiting is done
 * with a Condition, which releases the lock while sleeping and wakes promptly
 * when the user changes the limit. This preserves a global limit without the
 * long head-of-line lock held by Thread.sleep inside synchronized().
 */
class TokenBucket {
    @Volatile
    var bytesPerSec: Long = 0
        private set

    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private var nextAvailableNs = System.nanoTime()

    // Counts bytes actually forwarded successfully, not bytes merely requested.
    val total = AtomicLong(0)

    fun setRate(bytesPerSec: Long) {
        lock.withLock {
            val newRate = max(0L, bytesPerSec)
            this.bytesPerSec = newRate
            // Apply a new limit immediately. One in-flight chunk may finish under
            // the old schedule, then all subsequent chunks use the new rate.
            nextAvailableNs = System.nanoTime()
            changed.signalAll()
        }
    }

    /**
     * Wait until this chunk's turn, then reserve only the slot that is actually
     * being granted. A waiter never pre-reserves future time before it can send,
     * so interruption/cancellation cannot leave phantom pacing debt behind.
     * rate==0 means unlimited.
     */
    fun acquire(n: Int, canProceed: () -> Boolean = { true }): Boolean {
        if (n <= 0) return canProceed()

        lock.lock()
        try {
            while (true) {
                if (!canProceed()) return false
                val rate = bytesPerSec
                if (rate <= 0L) return true

                val now = System.nanoTime()
                val startAt = nextAvailableNs.coerceAtLeast(now)
                val waitNs = startAt - now
                if (waitNs > 0L) {
                    try {
                        changed.awaitNanos(waitNs)
                    } catch (_: InterruptedException) {
                        // No reservation was made for this waiter, so returning
                        // cannot leave a future deadline behind for other flows.
                        Thread.currentThread().interrupt()
                        return false
                    }
                    // Re-check rate and deadline after wakeup (including a
                    // setRate() signal or a spurious wakeup).
                    continue
                }

                if (!canProceed()) return false
                val durationNs = ((n.toDouble() * 1_000_000_000.0) / rate.toDouble())
                    .toLong().coerceAtLeast(1L)
                nextAvailableNs = safeAdd(now, durationNs)
                return true
            }
        } finally {
            lock.unlock()
        }
    }

    fun recordForwarded(n: Int) {
        if (n > 0) {
            val delta = n.toLong()
            total.updateAndGet { current ->
                if (Long.MAX_VALUE - current < delta) Long.MAX_VALUE else current + delta
            }
        }
    }

    /**
     * Clears any pacing deadline left by flows that are being torn down. This is
     * safe at relay/session shutdown because the relay has already stopped its
     * workers, and prevents a cancelled session from delaying a later session.
     */
    fun resetScheduler() {
        lock.withLock {
            nextAvailableNs = System.nanoTime()
            changed.signalAll()
        }
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}

object SpeedLimiter {
    val download = TokenBucket()
    val upload = TokenBucket()

    private val activeSessionGeneration = AtomicLong(0L)
    // Serializes generation ownership changes and the paired scheduler reset, but is
    // never held while a traffic waiter sleeps.
    private val generationLock = Any()

    /** Claims the process-wide pacing scheduler for a new VPN generation. */
    fun beginSession(generation: Long) {
        if (generation <= 0L) return
        synchronized(generationLock) {
            activeSessionGeneration.set(generation)
            download.resetScheduler()
            upload.resetScheduler()
        }
    }

    /** Resets global pacing only if this generation still owns the scheduler. */
    fun endSession(generation: Long) {
        if (generation <= 0L) return
        synchronized(generationLock) {
            if (activeSessionGeneration.get() == generation) {
                activeSessionGeneration.set(0L)
                download.resetScheduler()
                upload.resetScheduler()
            }
        }
    }

    /**
     * Reserves a pacing slot only while this VPN generation owns the global scheduler.
     * A takeover resets the scheduler, so a reservation made immediately before the
     * takeover cannot become pacing debt for the new generation.
     */
    fun acquire(bucket: TokenBucket, n: Int, generation: Long): Boolean =
        bucket.acquire(n) { isGenerationActive(generation) }

    /** True only for the currently active VPN generation. */
    fun isGenerationActive(generation: Long): Boolean =
        generation > 0L && activeSessionGeneration.get() == generation

    fun toKbps(value: Double?, unit: String): Long? {
        if (value == null || !value.isFinite() || value <= 0.0) return null
        val normalized = when {
            unit.equals("Kbps", ignoreCase = true) -> value
            unit.equals("Mbps", ignoreCase = true) -> value * 1000.0
            else -> return null
        }
        if (!normalized.isFinite() || normalized <= 0.0) return null
        // TokenBucket stores bytes/sec as Long. 1 Kbps == 125 bytes/sec.
        return normalized.toLong().coerceAtMost(Long.MAX_VALUE / 125L).takeIf { it > 0L }
    }

    private fun kbpsToBytesPerSec(kbps: Long?): Long =
        kbps?.takeIf { it > 0L }
            ?.coerceAtMost(Long.MAX_VALUE / 125L)
            ?.times(125L)
            ?: 0L

    fun setDownloadKbps(kbps: Long?) {
        download.setRate(kbpsToBytesPerSec(kbps))
        VpnRuntime.update { it.copy(downloadLimitKbps = kbps) }
        log("Download limit applied: ${kbps?.let { "$it Kbps" } ?: "unlimited"}")
    }

    fun setUploadKbps(kbps: Long?) {
        upload.setRate(kbpsToBytesPerSec(kbps))
        VpnRuntime.update { it.copy(uploadLimitKbps = kbps) }
        log("Upload limit applied: ${kbps?.let { "$it Kbps" } ?: "unlimited"}")
    }
}
