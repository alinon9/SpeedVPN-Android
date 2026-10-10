package com.speedvpn.app

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow

internal data class SessionCounter(
    val generation: Long,
    val bytes: Long,
)

/**
 * Global device-wide pacing limiter.
 *
 * Uses a monotonic-clock token bucket shared by all flows in each direction.
 * Tokens accrue while the relay is reading/writing sockets, so ordinary I/O
 * overhead is accounted for instead of being added on top of a full per-chunk
 * sleep. A bounded burst absorbs scheduler jitter; no waiter reserves future
 * tokens before those tokens are available.
 */
class TokenBucket {
    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val BURST_WINDOW_NS = 10_000_000L
        // Preserve the relay's normal 16 KiB TCP read size as the minimum burst.
        // At higher rates, permit at most 64 KiB of accumulated credit.
        private const val MIN_BURST_BYTES = 16 * 1024
        private const val MAX_BURST_BYTES = 64 * 1024
    }

    @Volatile
    var bytesPerSec: Long = 0
        private set

    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private var capacityBytes = 0.0
    private var availableBytes = 0.0
    private var lastRefillNs = System.nanoTime()

    // Counts bytes actually forwarded successfully, not bytes merely requested.
    val total = AtomicLong(0)
    // Per-generation session accounting lives in one atomic state object so a reset
    // cannot race with a late write from an older generation.
    private val sessionCounter = AtomicReference(SessionCounter(0L, 0L))

    fun setRate(bytesPerSec: Long) {
        lock.withLock {
            val newRate = max(0L, bytesPerSec)
            this.bytesPerSec = newRate
            capacityBytes = burstCapacity(newRate)
            // Apply a changed rate immediately. One in-flight chunk can complete
            // under the old schedule; subsequent acquires use the new token budget.
            availableBytes = capacityBytes
            lastRefillNs = System.nanoTime()
            changed.signalAll()
        }
    }

    /**
     * Wait until enough credit exists for this chunk, then debit it.
     * A rate of zero means unlimited. Chunks larger than the bucket capacity
     * are allowed once the bucket is full and create debt that must refill before
     * the next chunk, so a large UDP datagram cannot wait forever.
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
                refill(now, rate)
                if (!canProceed()) return false

                // For an oversized packet, a full bucket is the eligibility
                // threshold. Debiting the full packet then accounts for its
                // transmission time as token debt before another packet is sent.
                val requiredBytes = min(n.toDouble(), capacityBytes)
                if (availableBytes >= requiredBytes) {
                    availableBytes -= n.toDouble()
                    return true
                }

                val missingBytes = requiredBytes - availableBytes
                val waitNs = ceil(missingBytes * NANOS_PER_SECOND / rate.toDouble())
                    .toLong()
                    .coerceAtLeast(1L)
                try {
                    changed.awaitNanos(waitNs)
                } catch (_: InterruptedException) {
                    // No reservation was made for this waiter, so returning cannot
                    // leave phantom pacing debt behind for other flows.
                    Thread.currentThread().interrupt()
                    return false
                }
                // Re-check rate, generation and token balance after every wakeup.
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Chooses a relay read size close to the token bucket's bounded burst window.
     *
     * Small plans retain the 16 KiB minimum so a single read cannot create a
     * large burst. Faster plans use larger reads to reduce per-chunk scheduler
     * and lock overhead, up to the same 64 KiB maximum used by the bucket.
     */
    internal fun recommendedReadBytes(): Int = lock.withLock {
        if (bytesPerSec <= 0L) {
            MAX_BURST_BYTES
        } else {
            ceil(capacityBytes).toInt().coerceIn(MIN_BURST_BYTES, MAX_BURST_BYTES)
        }
    }

    private fun burstCapacity(rate: Long): Double {
        if (rate <= 0L) return 0.0
        val rateWindowBytes = rate.toDouble() * BURST_WINDOW_NS.toDouble() / NANOS_PER_SECOND
        return rateWindowBytes.coerceIn(MIN_BURST_BYTES.toDouble(), MAX_BURST_BYTES.toDouble())
    }

    /** Refill tokens using elapsed monotonic time, including time spent doing I/O. */
    private fun refill(nowNs: Long, rate: Long) {
        val elapsedNs = (nowNs - lastRefillNs).coerceAtLeast(0L)
        lastRefillNs = nowNs
        if (elapsedNs == 0L) return
        availableBytes = min(
            capacityBytes,
            availableBytes + elapsedNs.toDouble() * rate.toDouble() / NANOS_PER_SECOND,
        )
    }

    fun beginSession(generation: Long) {
        if (generation <= 0L) return
        sessionCounter.set(SessionCounter(generation, 0L))
    }

    fun resetSession(generation: Long) {
        if (generation <= 0L) return
        sessionCounter.updateAndGet { current ->
            if (current.generation == generation) SessionCounter(generation, 0L) else current
        }
    }

    fun endSession(generation: Long) {
        if (generation <= 0L) return
        sessionCounter.updateAndGet { current ->
            if (current.generation == generation) SessionCounter(0L, 0L) else current
        }
    }

    /** Records lifetime totals and session totals only for the active generation. */
    fun recordForwarded(n: Int, generation: Long) {
        if (n <= 0 || generation <= 0L) return
        val delta = n.toLong()
        total.updateAndGet { current ->
            if (Long.MAX_VALUE - current < delta) Long.MAX_VALUE else current + delta
        }
        sessionCounter.updateAndGet { current ->
            if (current.generation != generation) {
                current
            } else {
                val next = if (Long.MAX_VALUE - current.bytes < delta) Long.MAX_VALUE
                else current.bytes + delta
                SessionCounter(generation, next)
            }
        }
    }

    fun sessionBytes(generation: Long): Long {
        val current = sessionCounter.get()
        return if (current.generation == generation) current.bytes else 0L
    }

    /**
     * Clears stale pacing state at session teardown/startup and wakes waiters.
     * This is safe only at the lifecycle points guarded by SpeedLimiter below.
     */
    fun resetScheduler() {
        lock.withLock {
            capacityBytes = burstCapacity(bytesPerSec)
            availableBytes = capacityBytes
            lastRefillNs = System.nanoTime()
            changed.signalAll()
        }
    }
}

object SpeedLimiter {
    val download = TokenBucket()
    val upload = TokenBucket()

    /** True while a speed verification owns the global limiter configuration. */
    val verificationInProgress = MutableStateFlow(false)

    private val verificationLock = Any()
    private var verificationLimits: Pair<Long?, Long?>? = null

    private val activeSessionGeneration = AtomicLong(0L)
    // Serializes generation ownership changes and the paired scheduler reset, but is
    // never held while a traffic waiter sleeps.
    private val generationLock = Any()

    /** Claims the process-wide pacing scheduler for a new VPN generation. */
    fun beginSession(generation: Long) {
        if (generation <= 0L) return
        synchronized(generationLock) {
            // Publish bucket ownership before exposing the generation to new waiters.
            // This avoids a new generation acquiring against stale bucket state.
            download.beginSession(generation)
            upload.beginSession(generation)
            download.resetScheduler()
            upload.resetScheduler()
            activeSessionGeneration.set(generation)
        }
    }

    /** Resets only the current-session traffic counters; pacing is left untouched. */
    fun resetSessionCounters(generation: Long) {
        if (generation <= 0L) return
        synchronized(generationLock) {
            if (activeSessionGeneration.get() == generation) {
                download.resetSession(generation)
                upload.resetSession(generation)
            }
        }
    }

    /** Resets global pacing only if this generation still owns the scheduler. */
    fun endSession(generation: Long) {
        if (generation <= 0L) return
        synchronized(generationLock) {
            if (activeSessionGeneration.get() == generation) {
                activeSessionGeneration.set(0L)
                download.endSession(generation)
                upload.endSession(generation)
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

    /** Begins a verification and freezes the limiter at the requested rates. */
    fun beginVerification(downloadKbps: Long?, uploadKbps: Long?): Boolean =
        synchronized(verificationLock) {
            if (verificationLimits != null) {
                false
            } else {
                verificationLimits = downloadKbps to uploadKbps
                verificationInProgress.value = true
                applyDownloadKbps(downloadKbps)
                applyUploadKbps(uploadKbps)
                true
            }
        }

    /** Changes rates for the active verification while rejecting outside updates. */
    fun setVerificationLimits(downloadKbps: Long?, uploadKbps: Long?) {
        synchronized(verificationLock) {
            check(verificationLimits != null) { "No speed verification is active" }
            verificationLimits = downloadKbps to uploadKbps
            applyDownloadKbps(downloadKbps)
            applyUploadKbps(uploadKbps)
        }
    }

    /** Applies the selected plan and releases the verification lock. */
    fun endVerification(downloadKbps: Long?, uploadKbps: Long?) {
        synchronized(verificationLock) {
            applyDownloadKbps(downloadKbps)
            applyUploadKbps(uploadKbps)
            verificationLimits = null
            verificationInProgress.value = false
        }
    }

    fun setDownloadKbps(kbps: Long?) {
        synchronized(verificationLock) {
            if (verificationLimits == null) applyDownloadKbps(kbps)
        }
    }

    fun setUploadKbps(kbps: Long?) {
        synchronized(verificationLock) {
            if (verificationLimits == null) applyUploadKbps(kbps)
        }
    }

    private fun applyDownloadKbps(kbps: Long?) {
        download.setRate(kbpsToBytesPerSec(kbps))
        VpnRuntime.update { it.copy(downloadLimitKbps = kbps) }
        log("Download limit applied: ${kbps?.let { "$it Kbps" } ?: "unlimited"}")
    }

    private fun applyUploadKbps(kbps: Long?) {
        upload.setRate(kbpsToBytesPerSec(kbps))
        VpnRuntime.update { it.copy(uploadLimitKbps = kbps) }
        log("Upload limit applied: ${kbps?.let { "$it Kbps" } ?: "unlimited"}")
    }
}
