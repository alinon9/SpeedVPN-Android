package com.speedvpn.app

import java.util.concurrent.atomic.AtomicLong

/**
 * Token bucket shared by every connection in one direction.
 * rate = 0 means unlimited. Callers block (sleep) until they are allowed to pass bytes.
 */
class TokenBucket {
    @Volatile var bytesPerSec: Long = 0
        private set
    private var tokens = 0.0
    private var last = System.nanoTime()
    val total = AtomicLong(0)

    @Synchronized fun setRate(bytesPerSec: Long) {
        this.bytesPerSec = bytesPerSec
        tokens = 0.0
        last = System.nanoTime()
    }

    fun acquire(n: Int) {
        total.addAndGet(n.toLong())
        val waitMs: Long
        synchronized(this) {
            val rate = bytesPerSec
            if (rate <= 0) return
            val now = System.nanoTime()
            val burst = maxOf(rate / 4.0, 16_384.0)
            tokens = minOf(burst, tokens + (now - last) / 1e9 * rate)
            last = now
            tokens -= n
            waitMs = if (tokens < 0) (-tokens * 1000.0 / rate).toLong() else 0
        }
        if (waitMs > 0) Thread.sleep(waitMs)
    }
}

object SpeedLimiter {
    val download = TokenBucket() // internet -> phone apps
    val upload = TokenBucket()   // phone apps -> internet

    /** value null = unlimited. unit "Kbps" or "Mbps" (bits per second). Returns kbps applied. */
    fun toKbps(value: Double?, unit: String): Long? {
        if (value == null || value <= 0) return null
        return if (unit == "Kbps") value.toLong() else (value * 1000).toLong()
    }

    fun setDownloadKbps(kbps: Long?) {
        download.setRate(if (kbps == null) 0 else kbps * 1000 / 8)
        VpnRuntime.update { it.copy(downloadLimitKbps = kbps) }
        log("Download limit applied: ${kbps?.let { "$it Kbps" } ?: "unlimited"}")
    }

    fun setUploadKbps(kbps: Long?) {
        upload.setRate(if (kbps == null) 0 else kbps * 1000 / 8)
        VpnRuntime.update { it.copy(uploadLimitKbps = kbps) }
        log("Upload limit applied: ${kbps?.let { "$it Kbps" } ?: "unlimited"}")
    }
}
