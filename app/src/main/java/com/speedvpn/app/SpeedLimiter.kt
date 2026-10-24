package com.speedvpn.app

import android.content.Context
import android.os.Process
import hev.htproxy.TProxyService
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
        private const val JITTER_HEADROOM_MULTIPLIER = 2.0
        // Keep low-rate plans responsive; a 16 KiB minimum creates long bursts at 10 KB/s.
        // At higher rates, permit at most 256 KiB of accumulated credit.
        private const val MIN_BURST_BYTES = 1024
        private const val MAX_BURST_BYTES = 256 * 1024
    }

    @Volatile
    var bytesPerSec: Long = 0
        private set

    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private var capacityBytes = 0.0
    private var maxCreditBytes = 0.0
    private var availableBytes = 0.0
    private var lastRefillNs = System.nanoTime()

    // Counts bytes actually forwarded successfully, not bytes merely requested.
    val total = AtomicLong(0)
    // Per-generation session accounting lives in one atomic state object so a reset
    // cannot race with a late write from an older generation.
    private val sessionCounter = AtomicReference(SessionCounter(0L, 0L))
    // Monotonic per-generation user traffic counter for speed-plan usage history.
    // Never reset by the Home screen "تصفير" button and excludes synthetic probe traffic
    // while continuing to count real traffic from other apps even during verification.
    private val planSessionCounter = AtomicReference(SessionCounter(0L, 0L))
    // Separate per-generation counter for synthetic speed-test / verification probe traffic.
    private val verificationProbeCounter = AtomicReference(SessionCounter(0L, 0L))

    fun setRate(bytesPerSec: Long) {
        lock.withLock {
            val newRate = max(0L, bytesPerSec)
            this.bytesPerSec = newRate
            capacityBytes = burstCapacity(newRate)
            maxCreditBytes = capacityBytes * JITTER_HEADROOM_MULTIPLIER
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
        if (bytesPerSec <= 0L) return canProceed()

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
     * Small plans use a 1 KiB minimum to avoid large low-rate bursts. Faster
     * plans use larger reads to reduce per-chunk scheduler
     * and lock overhead, up to the same 256 KiB maximum used by the bucket.
     */
    internal fun recommendedReadBytes(): Int {
        val currentRate = bytesPerSec
        if (currentRate <= 0L) return MAX_BURST_BYTES
        return lock.withLock {
            if (bytesPerSec <= 0L) {
                MAX_BURST_BYTES
            } else {
                ceil(capacityBytes).toInt().coerceIn(MIN_BURST_BYTES, MAX_BURST_BYTES)
            }
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
        val creditCeiling = if (maxCreditBytes > 0.0) maxCreditBytes else capacityBytes
        availableBytes = min(
            creditCeiling,
            availableBytes + elapsedNs.toDouble() * rate.toDouble() / NANOS_PER_SECOND,
        )
    }

    fun beginSession(generation: Long) {
        if (generation <= 0L) return
        sessionCounter.set(SessionCounter(generation, 0L))
        planSessionCounter.updateAndGet { current ->
            if (current.generation == generation) current else SessionCounter(generation, 0L)
        }
        verificationProbeCounter.updateAndGet { current ->
            if (current.generation == generation) current else SessionCounter(generation, 0L)
        }
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
        planSessionCounter.updateAndGet { current ->
            if (current.generation == generation) SessionCounter(0L, 0L) else current
        }
        verificationProbeCounter.updateAndGet { current ->
            if (current.generation == generation) SessionCounter(0L, 0L) else current
        }
    }

    /**
     * Records lifetime totals, UI session totals, and speed-plan usage totals for the active generation.
     *
     * @param isVerificationProbe True ONLY when the bytes belong to SpeedVPN's own synthetic
     * speed-test / verification probe flow. Real traffic from other apps passes `false` and
     * continues to be recorded in [planSessionCounter] even while a speed verification is running.
     */
    @JvmOverloads
    fun recordForwarded(
        n: Int,
        generation: Long,
        isVerificationProbe: Boolean = false,
    ) {
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
        if (isVerificationProbe) {
            verificationProbeCounter.updateAndGet { current ->
                if (current.generation != generation) {
                    current
                } else {
                    val next = if (Long.MAX_VALUE - current.bytes < delta) Long.MAX_VALUE
                    else current.bytes + delta
                    SessionCounter(generation, next)
                }
            }
        } else {
            planSessionCounter.updateAndGet { current ->
                if (current.generation != generation) {
                    current
                } else {
                    val next = if (Long.MAX_VALUE - current.bytes < delta) Long.MAX_VALUE
                    else current.bytes + delta
                    SessionCounter(generation, next)
                }
            }
        }
    }

    /**
     * Records forwarded bytes attributed to a specific app owner (`uid` / `packages`).
     * Distinguishes SpeedVPN's own synthetic probe traffic from real user app traffic so
     * other apps' traffic is never lost during speed verification.
     */
    @JvmOverloads
    fun recordForwardedForApp(
        n: Int,
        generation: Long,
        uid: Int? = null,
        packages: Set<String> = emptySet(),
        isVerificationProbeFlow: Boolean = false,
        remoteAddress: String? = null,
        remotePort: Int = 0,
        flowMarker: String? = null,
    ) {
        val isProbe = SpeedLimiter.isSyntheticProbeTraffic(
            uid = uid,
            packages = packages,
            isVerificationProbeFlow = isVerificationProbeFlow,
            remoteAddress = remoteAddress,
            remotePort = remotePort,
            flowMarker = flowMarker,
        )
        recordForwarded(n = n, generation = generation, isVerificationProbe = isProbe)
    }

    fun sessionBytes(generation: Long): Long {
        val current = sessionCounter.get()
        return if (current.generation == generation) current.bytes else 0L
    }

    fun planSessionBytes(generation: Long): Long {
        val current = planSessionCounter.get()
        return if (current.generation == generation) current.bytes else 0L
    }

    fun verificationProbeBytes(generation: Long): Long {
        val current = verificationProbeCounter.get()
        return if (current.generation == generation) current.bytes else 0L
    }

    /**
     * Clears stale pacing state at session teardown/startup and wakes waiters.
     * This is safe only at the lifecycle points guarded by SpeedLimiter below.
     */
    fun resetScheduler() {
        lock.withLock {
            capacityBytes = burstCapacity(bytesPerSec)
            maxCreditBytes = capacityBytes * JITTER_HEADROOM_MULTIPLIER
            availableBytes = capacityBytes
            lastRefillNs = System.nanoTime()
            changed.signalAll()
        }
    }
}

object SpeedLimiter {
    private const val DEFAULT_OWN_PACKAGE = "com.speedvpn.app"

    val download = TokenBucket()
    val upload = TokenBucket()

    /** True while a speed verification owns the global limiter configuration. */
    val verificationInProgress = MutableStateFlow(false)

    private val verificationLock = Any()
    // Tracks whether this process has loaded or accepted the user's normal speed plan.
    private var normalLimitsInitialized = false
    private var verificationLimits: Pair<Long?, Long?>? = null
    private var externalVpnCommandsInProgress = 0
    @Volatile
    private var verificationOwnPackageRoutingEnabled = false
    @Volatile
    private var appliedDownloadKbps: Long? = null
    @Volatile
    private var appliedUploadKbps: Long? = null
    @Volatile
    private var ownAppUid: Int = runCatching { Process.myUid() }.getOrDefault(-1)
    @Volatile
    private var ownPackageName: String = DEFAULT_OWN_PACKAGE

    /** True only while the verification tunnel should include SpeedVPN's own UID. */
    val routeVerificationTrafficThroughVpn: Boolean
        get() = verificationOwnPackageRoutingEnabled

    fun isVerificationActive(): Boolean = verificationInProgress.value

    fun currentDownloadLimitKbps(): Long? = appliedDownloadKbps

    fun currentUploadLimitKbps(): Long? = appliedUploadKbps

    fun registerOwnAppIdentity(context: Context) {
        val pkg = context.packageName
        if (pkg.isNotBlank()) {
            ownPackageName = pkg
        }
        val myUid = runCatching { Process.myUid() }.getOrDefault(-1)
        if (myUid > 0) {
            ownAppUid = myUid
        }
    }

    internal fun setOwnAppIdentityForTests(uid: Int, packageName: String = DEFAULT_OWN_PACKAGE) {
        ownAppUid = uid
        ownPackageName = packageName
    }

    private class ProbeSessionBucket {
        val endpoints = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val flowMarkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val clientSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }

    private val probeStateLock = Any()
    private val activeProbeEndpoints = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val activeProbeFlowMarkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val activeProbeClientSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val probeSessionBuckets = java.util.concurrent.ConcurrentHashMap<String, ProbeSessionBucket>()
    private val activeProbeSessionOrder = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val probeMeasurementTokenSeq = AtomicLong(0L)
    @Volatile
    private var activeProbeMeasurementToken: String = ""
    @Volatile
    private var nativeProbeSyncSupported: Boolean = true

    private fun normalizeProbeHost(hostOrIp: String): String {
        val trimmed = hostOrIp.substringBefore("%").trim('[', ']', ' ').lowercase()
        return when {
            trimmed == "localhost" -> "127.0.0.1"
            trimmed.startsWith("::ffff:") && trimmed.substringAfter("::ffff:").count { it == '.' } == 3 ->
                trimmed.substringAfter("::ffff:")
            else -> trimmed
        }
    }

    private fun isNumericIpLiteral(normHost: String): Boolean {
        if (normHost.isEmpty()) return false
        if (normHost.contains(':')) return true
        return normHost.count { it == '.' } == 3 && normHost.all { it.isDigit() || it == '.' }
    }

    private fun syncNativeProbeSocket(
        protocol: Int,
        localPort: Int,
        remoteHostOrIp: String,
        remotePort: Int,
        registered: Boolean,
    ) {
        if (!nativeProbeSyncSupported) return
        val norm = normalizeProbeHost(remoteHostOrIp)
        if (!isNumericIpLiteral(norm)) return
        try {
            val addrBytes = java.net.InetAddress.getByName(norm).address
            if (addrBytes != null && (addrBytes.size == 4 || addrBytes.size == 16)) {
                var hi = 0L
                var lo = 0L
                if (addrBytes.size == 4) {
                    for (i in 0 until 4) {
                        hi = (hi shl 8) or (addrBytes[i].toLong() and 0xffL)
                    }
                } else {
                    for (i in 0 until 8) {
                        hi = (hi shl 8) or (addrBytes[i].toLong() and 0xffL)
                        lo = (lo shl 8) or (addrBytes[i + 8].toLong() and 0xffL)
                    }
                }
                TProxyService.TProxySetUidPocProbeSocket(
                    protocol,
                    localPort,
                    hi,
                    lo,
                    addrBytes.size,
                    remotePort,
                    registered,
                )
            }
        } catch (_: LinkageError) {
            nativeProbeSyncSupported = false
        } catch (_: Throwable) {
        }
    }

    private fun syncNativeProbeSocketByKey(key: String, registered: Boolean) {
        val parts = key.split('|')
        if (parts.size != 3) return
        val proto = parts[0].toIntOrNull() ?: return
        val lPort = parts[1].toIntOrNull() ?: return
        val hostPort = parts[2]
        val colonIdx = hostPort.lastIndexOf(':')
        if (colonIdx <= 0) return
        val host = hostPort.substring(0, colonIdx)
        val rPort = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return
        syncNativeProbeSocket(proto, lPort, host, rPort, registered)
    }

    private fun syncNativeClearProbeSockets() {
        if (!nativeProbeSyncSupported) return
        try {
            TProxyService.TProxyClearUidPocProbeSockets()
        } catch (_: LinkageError) {
            nativeProbeSyncSupported = false
        } catch (_: Throwable) {
        }
    }

    fun clearAllProbeState() {
        synchronized(probeStateLock) {
            probeSessionBuckets.clear()
            activeProbeSessionOrder.clear()
            activeProbeEndpoints.clear()
            activeProbeFlowMarkers.clear()
            activeProbeClientSockets.clear()
            activeProbeMeasurementToken = ""
        }
        syncNativeClearProbeSockets()
    }

    /**
     * Starts a token-scoped probe measurement window (used by [SpeedTestEngine.measure]).
     * Each session token owns its own isolated bucket of registered endpoints, flow markers,
     * and client sockets so an earlier overlapping session ending out-of-order can never wipe
     * a newer active session's probe state.
     */
    fun beginProbeMeasurementSession(): String {
        var clearNative = false
        val token = synchronized(probeStateLock) {
            if (activeProbeSessionOrder.isEmpty()) {
                activeProbeEndpoints.clear()
                activeProbeFlowMarkers.clear()
                activeProbeClientSockets.clear()
                clearNative = true
            }
            val newToken = "probe-" + probeMeasurementTokenSeq.incrementAndGet() + "-" + System.nanoTime()
            probeSessionBuckets[newToken] = ProbeSessionBucket()
            activeProbeSessionOrder.add(newToken)
            activeProbeMeasurementToken = newToken
            newToken
        }
        if (clearNative) {
            syncNativeClearProbeSockets()
        }
        return token
    }

    /**
     * Ends a token-scoped probe measurement window and removes ONLY the state owned by [token].
     * If a newer measurement session is already active, its endpoints, client sockets, and flow
     * markers remain intact until that newer session itself ends.
     */
    fun endProbeMeasurementSession(token: String? = null) {
        if (token == null) {
            clearAllProbeState()
            return
        }
        val removedSocketKeys = mutableListOf<String>()
        var clearAllNative = false
        synchronized(probeStateLock) {
            val removedBucket = probeSessionBuckets.remove(token) ?: return
            activeProbeSessionOrder.remove(token)
            activeProbeMeasurementToken = activeProbeSessionOrder.lastOrNull() ?: ""
            if (activeProbeSessionOrder.isEmpty()) {
                activeProbeEndpoints.clear()
                activeProbeFlowMarkers.clear()
                activeProbeClientSockets.clear()
                clearAllNative = true
            } else {
                val remainingBuckets = probeSessionBuckets.values
                for (ep in removedBucket.endpoints) {
                    if (remainingBuckets.none { ep in it.endpoints }) {
                        activeProbeEndpoints.remove(ep)
                    }
                }
                for (fm in removedBucket.flowMarkers) {
                    if (remainingBuckets.none { fm in it.flowMarkers }) {
                        activeProbeFlowMarkers.remove(fm)
                    }
                }
                for (cs in removedBucket.clientSockets) {
                    if (remainingBuckets.none { cs in it.clientSockets }) {
                        activeProbeClientSockets.remove(cs)
                        removedSocketKeys.add(cs)
                    }
                }
            }
        }
        if (clearAllNative) {
            syncNativeClearProbeSockets()
        } else {
            removedSocketKeys.forEach { key -> syncNativeProbeSocketByKey(key, false) }
        }
    }

    fun currentProbeMeasurementToken(): String = activeProbeMeasurementToken

    fun isProbeMeasurementSessionActive(): Boolean =
        verificationInProgress.value || activeProbeMeasurementToken.isNotEmpty()

    fun registerProbeEndpoint(hostOrIp: String, port: Int) {
        if (!isVerificationActive() && !isProbeMeasurementSessionActive()) return
        val norm = normalizeProbeHost(hostOrIp)
        if (norm.isEmpty()) return
        val entry = if (port > 0) "$norm:$port" else "$norm:*"
        synchronized(probeStateLock) {
            if (isVerificationActive()) {
                activeProbeEndpoints.add(entry)
                val token = activeProbeMeasurementToken
                if (token.isNotEmpty()) {
                    probeSessionBuckets[token]?.endpoints?.add(entry)
                }
            }
        }
    }

    fun unregisterProbeEndpoint(hostOrIp: String, port: Int) {
        val norm = normalizeProbeHost(hostOrIp)
        if (norm.isEmpty()) return
        val entry = if (port > 0) "$norm:$port" else "$norm:*"
        synchronized(probeStateLock) {
            activeProbeEndpoints.remove(entry)
            probeSessionBuckets.values.forEach { it.endpoints.remove(entry) }
        }
    }

    fun clearProbeEndpoints() {
        synchronized(probeStateLock) {
            activeProbeEndpoints.clear()
            probeSessionBuckets.values.forEach { it.endpoints.clear() }
        }
    }

    fun isRegisteredProbeEndpoint(hostOrIp: String?, port: Int): Boolean {
        if (hostOrIp.isNullOrBlank()) return false
        val norm = normalizeProbeHost(hostOrIp)
        if (norm.isEmpty()) return false
        if (port > 0 && activeProbeEndpoints.contains("$norm:$port")) return true
        return activeProbeEndpoints.contains("$norm:*")
    }

    const val IPPROTO_TCP = 6
    const val IPPROTO_UDP = 17

    private fun probeClientSocketKey(
        protocol: Int,
        localPort: Int,
        remoteHostOrIp: String,
        remotePort: Int,
    ): String? {
        if ((protocol != IPPROTO_TCP && protocol != IPPROTO_UDP) || localPort <= 0 || remotePort <= 0) {
            return null
        }
        val norm = normalizeProbeHost(remoteHostOrIp)
        if (norm.isEmpty()) return null
        return "$protocol|$localPort|$norm:$remotePort"
    }

    @JvmOverloads
    fun markProbeClientSocket(
        localPort: Int,
        remoteHostOrIp: String,
        remotePort: Int,
        protocol: Int = IPPROTO_TCP,
    ) {
        if (!isProbeMeasurementSessionActive()) return
        val key = probeClientSocketKey(protocol, localPort, remoteHostOrIp, remotePort) ?: return
        val flowKey = "clientPort:$key"
        synchronized(probeStateLock) {
            activeProbeClientSockets.add(key)
            activeProbeFlowMarkers.add(flowKey)
            val token = activeProbeMeasurementToken
            if (token.isNotEmpty()) {
                probeSessionBuckets[token]?.let { bucket ->
                    bucket.clientSockets.add(key)
                    bucket.flowMarkers.add(flowKey)
                }
            }
        }
        syncNativeProbeSocket(protocol, localPort, remoteHostOrIp, remotePort, true)
    }

    @JvmOverloads
    fun unmarkProbeClientSocket(
        localPort: Int,
        remoteHostOrIp: String,
        remotePort: Int,
        protocol: Int = IPPROTO_TCP,
    ) {
        val key = probeClientSocketKey(protocol, localPort, remoteHostOrIp, remotePort) ?: return
        val flowKey = "clientPort:$key"
        synchronized(probeStateLock) {
            activeProbeClientSockets.remove(key)
            activeProbeFlowMarkers.remove(flowKey)
            probeSessionBuckets.values.forEach { bucket ->
                bucket.clientSockets.remove(key)
                bucket.flowMarkers.remove(flowKey)
            }
        }
        syncNativeProbeSocket(protocol, localPort, remoteHostOrIp, remotePort, false)
    }

    @JvmOverloads
    fun isMarkedProbeClientSocket(
        localPort: Int,
        remoteHostOrIp: String?,
        remotePort: Int,
        protocol: Int = IPPROTO_TCP,
    ): Boolean {
        if (remoteHostOrIp.isNullOrBlank()) return false
        val key = probeClientSocketKey(protocol, localPort, remoteHostOrIp, remotePort) ?: return false
        return activeProbeClientSockets.contains(key)
    }

    fun markProbeFlow(flowMarker: String) {
        val norm = flowMarker.trim()
        if (norm.isEmpty()) return
        synchronized(probeStateLock) {
            activeProbeFlowMarkers.add(norm)
            val token = activeProbeMeasurementToken
            if (token.isNotEmpty()) {
                probeSessionBuckets[token]?.flowMarkers?.add(norm)
            }
        }
    }

    fun unmarkProbeFlow(flowMarker: String) {
        val norm = flowMarker.trim()
        if (norm.isEmpty()) return
        synchronized(probeStateLock) {
            activeProbeFlowMarkers.remove(norm)
            probeSessionBuckets.values.forEach { it.flowMarkers.remove(norm) }
        }
    }

    fun clearProbeFlowMarkers() {
        synchronized(probeStateLock) {
            activeProbeFlowMarkers.clear()
            probeSessionBuckets.values.forEach { it.flowMarkers.clear() }
        }
    }

    fun isMarkedProbeFlow(flowMarker: String?): Boolean =
        !flowMarker.isNullOrBlank() && activeProbeFlowMarkers.contains(flowMarker.trim())

    /**
     * Determines whether a relay flow belongs to SpeedVPN's own synthetic speed-test / verification
     * probe rather than real user traffic, using ONLY verified flow-level probe markers:
     *
     * 1. Another user application (`uid != ownAppUid` or non-SpeedVPN package in `packages`):
     *    NEVER treated as a synthetic probe (`return false`).
     * 2. Explicit flow-level probe marker (`isVerificationProbeFlow == true` or `flowMarker` registered
     *    in `activeProbeFlowMarkers` via `SpeedProbeSocketFactory`, `Socks5Server.probeUsername`, or
     *    `X-SpeedVPN-Probe` stream inspection): returns `true` (for both attributed and unattributed probes).
     * 3. Matching destination IP and port (`remoteAddress` / `remotePort`) or `uid == ownAppUid` alone
     *    is NEVER sufficient to classify a flow as a synthetic probe (`return false`), ensuring that
     *    both unattributed real user traffic (`uid == null`) and real non-probe traffic from SpeedVPN's
     *    own UID (`uid == ownAppUid`) are preserved even when connecting to the same IP:port as the probe.
     */
    @JvmOverloads
    fun isSyntheticProbeTraffic(
        uid: Int? = null,
        packages: Set<String> = emptySet(),
        isVerificationProbeFlow: Boolean = false,
        remoteAddress: String? = null,
        remotePort: Int = 0,
        flowMarker: String? = null,
    ): Boolean {
        val myPkg = ownPackageName
        val myUid = ownAppUid
        val isOtherAppByUid = uid != null && uid > 0 && myUid > 0 && uid != myUid
        val isOtherAppByPkg = packages.isNotEmpty() && myPkg.isNotEmpty() && myPkg !in packages
        if (isOtherAppByUid || isOtherAppByPkg) {
            return false
        }
        if (isVerificationProbeFlow || isMarkedProbeFlow(flowMarker)) {
            return true
        }
        return false
    }

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

    /** Convert the decimal KB/s UI unit to the Kbps representation stored by the app. */
    internal fun kilobytesPerSecondToKbps(kilobytesPerSecond: Long): Long =
        kilobytesPerSecond.coerceAtLeast(1L)
            .coerceAtMost(Long.MAX_VALUE / 8L)
            .times(8L)

    /** Convert stored decimal Kbps to the bytes/second unit consumed by TokenBucket. */
    internal fun kbpsToBytesPerSec(kbps: Long?): Long =
        kbps?.takeIf { it > 0L }
            ?.coerceAtMost(Long.MAX_VALUE / 125L)
            ?.times(125L)
            ?: 0L

    /**
     * Restores persisted limits once per process without overwriting a live UI selection
     * or a temporary verification plan. The paired update is atomic with normal setters.
     */
    fun initializeLimitsIfUnset(downloadKbps: Long?, uploadKbps: Long?): Boolean =
        synchronized(verificationLock) {
            if (normalLimitsInitialized || verificationLimits != null || externalVpnCommandsInProgress > 0) {
                false
            } else {
                applyDownloadKbps(downloadKbps)
                applyUploadKbps(uploadKbps)
                normalLimitsInitialized = true
                true
            }
        }

    /** Begins a verification and freezes the limiter at the requested rates. */
    fun beginVerification(downloadKbps: Long?, uploadKbps: Long?): Boolean =
        synchronized(verificationLock) {
            if (verificationLimits != null || externalVpnCommandsInProgress > 0) {
                false
            } else {
                clearAllProbeState()
                verificationLimits = downloadKbps to uploadKbps
                verificationOwnPackageRoutingEnabled = false
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

    /**
     * Includes the app's own UID in an allow-listed VPN only while the verifier
     * is measuring through the tunnel. The verifier clears this before restoring
     * the user's prior VPN configuration.
     */
    fun setVerificationTrafficRoutingEnabled(enabled: Boolean) {
        synchronized(verificationLock) {
            check(verificationLimits != null || !enabled) { "No speed verification is active" }
            verificationOwnPackageRoutingEnabled = enabled
        }
    }

    /** Applies the selected plan and releases the verification lock. */
    fun endVerification(downloadKbps: Long?, uploadKbps: Long?) {
        val currentDown: Long?
        val currentUp: Long?
        synchronized(verificationLock) {
            applyDownloadKbps(downloadKbps)
            applyUploadKbps(uploadKbps)
            normalLimitsInitialized = true
            verificationOwnPackageRoutingEnabled = false
            verificationLimits = null
            verificationInProgress.value = false
            clearAllProbeState()
            currentDown = appliedDownloadKbps
            currentUp = appliedUploadKbps
        }
        runCatching {
            SpeedPlanUsageTracker.onAppliedLimitsChanged(
                downloadKbps = currentDown,
                uploadKbps = currentUp,
            )
        }
    }

    /** Prevents remote VPN state changes from racing a baseline or tunnel measurement. */
    fun beginExternalVpnCommand(): Boolean = synchronized(verificationLock) {
        if (verificationLimits != null) return@synchronized false
        externalVpnCommandsInProgress++
        true
    }

    fun endExternalVpnCommand() {
        synchronized(verificationLock) {
            check(externalVpnCommandsInProgress > 0) { "No external VPN command is active" }
            externalVpnCommandsInProgress--
        }
    }

    fun setDownloadKbps(kbps: Long?, onApplied: (() -> Unit)? = null): Boolean {
        val currentDown: Long?
        val currentUp: Long?
        synchronized(verificationLock) {
            if (verificationLimits != null) return false
            applyDownloadKbps(kbps)
            normalLimitsInitialized = true
            onApplied?.invoke()
            currentDown = appliedDownloadKbps
            currentUp = appliedUploadKbps
        }
        runCatching {
            SpeedPlanUsageTracker.onAppliedLimitsChanged(
                downloadKbps = currentDown,
                uploadKbps = currentUp,
            )
        }
        return true
    }

    fun setUploadKbps(kbps: Long?, onApplied: (() -> Unit)? = null): Boolean {
        val currentDown: Long?
        val currentUp: Long?
        synchronized(verificationLock) {
            if (verificationLimits != null) return false
            applyUploadKbps(kbps)
            normalLimitsInitialized = true
            onApplied?.invoke()
            currentDown = appliedDownloadKbps
            currentUp = appliedUploadKbps
        }
        runCatching {
            SpeedPlanUsageTracker.onAppliedLimitsChanged(
                downloadKbps = currentDown,
                uploadKbps = currentUp,
            )
        }
        return true
    }

    /** Atomically updates both selected limits and persists them against verification start. */
    fun setLimitsKbps(
        downloadKbps: Long?,
        uploadKbps: Long?,
        onApplied: (() -> Unit)? = null,
    ): Boolean {
        val currentDown: Long?
        val currentUp: Long?
        synchronized(verificationLock) {
            if (verificationLimits != null) return false
            applyDownloadKbps(downloadKbps)
            applyUploadKbps(uploadKbps)
            normalLimitsInitialized = true
            onApplied?.invoke()
            currentDown = appliedDownloadKbps
            currentUp = appliedUploadKbps
        }
        runCatching {
            SpeedPlanUsageTracker.onAppliedLimitsChanged(
                downloadKbps = currentDown,
                uploadKbps = currentUp,
            )
        }
        return true
    }

    private fun applyDownloadKbps(kbps: Long?) {
        val normalized = kbps?.takeIf { it > 0L }
        appliedDownloadKbps = normalized
        download.setRate(kbpsToBytesPerSec(normalized))
        VpnRuntime.update { it.copy(downloadLimitKbps = normalized) }
        log("Download limit applied: ${normalized?.let { "$it Kbps" } ?: "unlimited"}")
    }

    private fun applyUploadKbps(kbps: Long?) {
        val normalized = kbps?.takeIf { it > 0L }
        appliedUploadKbps = normalized
        upload.setRate(kbpsToBytesPerSec(normalized))
        VpnRuntime.update { it.copy(uploadLimitKbps = normalized) }
        log("Upload limit applied: ${normalized?.let { "$it Kbps" } ?: "unlimited"}")
    }
}
