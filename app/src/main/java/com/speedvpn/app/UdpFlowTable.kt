package com.speedvpn.app

import java.net.InetAddress
import java.net.InetSocketAddress

internal data class UdpFlow(
    val logicalKey: String,
    val destinationPort: Int,
    @Volatile var clientEndpoint: InetSocketAddress,
    @Volatile var candidates: List<InetAddress>,
    @Volatile var selectedIndex: Int,
    @Volatile var lastSeenMs: Long,
    @Volatile var lastReplyMs: Long,
    @Volatile var lastSentMs: Long,
)

/**
 * Thread-safe per-association UDP flow table with TTL + true access-order LRU.
 * Eviction callbacks are run outside the table monitor to avoid lock inversion
 * with the UDP mux, which owns and closes each flow's DatagramChannel.
 */
internal class UdpFlowTable(
    private val maxFlows: Int = 256,
    private val ttlMs: Long = 30_000L,
    private val onFlowEvicted: (UdpFlow) -> Unit = {},
) {
    init {
        require(maxFlows > 0) { "maxFlows must be positive" }
        require(ttlMs > 0L) { "ttlMs must be positive" }
    }

    private val lock = Any()
    private val flows = LinkedHashMap<String, UdpFlow>(16, 0.75f, true)
    // Defensive index retained for tests/diagnostics. Live UDP reply delivery is
    // routed by the connected DatagramChannel belonging to each logical flow.
    private val replyIndex = HashMap<String, LinkedHashSet<String>>()
    private var endpointCollisionCount = 0L

    private fun <T> withEvictionNotifications(block: (MutableList<UdpFlow>) -> T): T {
        val evicted = ArrayList<UdpFlow>()
        var operationFailure: Throwable? = null
        try {
            // The monitor is released before finally invokes callbacks below.
            return synchronized(lock) { block(evicted) }
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            // Never call into UdpUpstreamMux while holding the table lock: mux operations
            // can query this table while holding the mux lock. Attempt every close callback
            // even if the table operation or an earlier cleanup failed.
            var callbackFailure: Throwable? = null
            evicted.forEach { flow ->
                try {
                    onFlowEvicted(flow)
                } catch (failure: Throwable) {
                    val previous = callbackFailure
                    if (previous == null) {
                        callbackFailure = failure
                    } else if (previous !== failure) {
                        previous.addSuppressed(failure)
                    }
                }
            }
            val cleanupFailure = callbackFailure
            if (cleanupFailure != null) {
                val originalFailure = operationFailure
                if (originalFailure == null) {
                    throw cleanupFailure
                } else if (originalFailure !== cleanupFailure) {
                    originalFailure.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    fun getOrCreate(
        logicalKey: String,
        destinationPort: Int,
        clientEndpoint: InetSocketAddress,
        candidates: List<InetAddress>,
        nowMs: Long,
    ): UdpFlow? = withEvictionNotifications { evicted ->
        if (destinationPort !in 0..65_535 || candidates.isEmpty()) return@withEvictionNotifications null
        purgeLocked(nowMs, evicted)
        val stableCandidates = candidates.toList()
        var flow = flows[logicalKey]
        if (flow == null) {
            while (flows.size >= maxFlows) evictOldestLocked(evicted)
            flow = UdpFlow(
                logicalKey = logicalKey,
                destinationPort = destinationPort,
                clientEndpoint = clientEndpoint,
                candidates = stableCandidates,
                selectedIndex = 0,
                lastSeenMs = nowMs,
                lastReplyMs = 0L,
                lastSentMs = 0L,
            )
            flows[logicalKey] = flow
        } else {
            removeReplyMappingsLocked(flow)
            val previousSelected = flow.candidates.getOrNull(flow.selectedIndex)
            flow.clientEndpoint = clientEndpoint
            flow.candidates = stableCandidates
            flow.selectedIndex = previousSelected?.let {
                stableCandidates.indexOf(it).takeIf { index -> index >= 0 }
            } ?: flow.selectedIndex.coerceIn(0, stableCandidates.lastIndex)
            flow.lastSeenMs = nowMs
        }

        stableCandidates.forEach { candidate ->
            registerReplyMappingLocked(endpointKey(candidate, flow.destinationPort), logicalKey)
        }
        flow
    }

    fun findByDestination(address: InetAddress, port: Int, nowMs: Long): UdpFlow? =
        withEvictionNotifications { evicted ->
            purgeLocked(nowMs, evicted)
            val endpoint = endpointKey(address, port)
            val keys = replyIndex[endpoint] ?: return@withEvictionNotifications null
            var matched: UdpFlow? = null
            var matchCount = 0
            for (key in keys) {
                val candidate = flows[key] ?: continue
                matchCount++
                if (matchCount > 1) return@withEvictionNotifications null
                matched = candidate
            }
            val flow = matched ?: return@withEvictionNotifications null
            flow.lastSeenMs = nowMs
            flow.lastReplyMs = nowMs
            flow
        }

    fun markReply(flow: UdpFlow, nowMs: Long) {
        withEvictionNotifications {
            val current = flows[flow.logicalKey] ?: return@withEvictionNotifications
            if (current !== flow) return@withEvictionNotifications
            current.lastSeenMs = nowMs
            current.lastReplyMs = nowMs
        }
    }

    fun contains(logicalKey: String, nowMs: Long): Boolean =
        withEvictionNotifications { evicted ->
            purgeLocked(nowMs, evicted)
            flows.containsKey(logicalKey)
        }

    /** Identity-aware check; prevents a delayed caller from resurrecting an evicted flow. */
    fun isCurrent(flow: UdpFlow, nowMs: Long): Boolean =
        withEvictionNotifications { evicted ->
            purgeLocked(nowMs, evicted)
            flows[flow.logicalKey] === flow
        }

    fun touchSent(flow: UdpFlow, nowMs: Long) {
        withEvictionNotifications {
            val current = flows[flow.logicalKey] ?: return@withEvictionNotifications
            if (current !== flow) return@withEvictionNotifications
            current.lastSeenMs = nowMs
            current.lastSentMs = nowMs
        }
    }

    fun advanceCandidateIfUnanswered(flow: UdpFlow, nowMs: Long, waitMs: Long): Boolean =
        withEvictionNotifications {
            val current = flows[flow.logicalKey] ?: return@withEvictionNotifications false
            if (current !== flow) return@withEvictionNotifications false
            if (current.selectedIndex >= current.candidates.lastIndex) return@withEvictionNotifications false
            if (current.lastSentMs <= current.lastReplyMs) return@withEvictionNotifications false
            if (nowMs - current.lastSentMs < waitMs) return@withEvictionNotifications false
            current.selectedIndex += 1
            current.lastSeenMs = nowMs
            current.lastSentMs = 0L
            current.lastReplyMs = 0L
            true
        }

    fun clear() {
        withEvictionNotifications { evicted ->
            evicted.addAll(flows.values)
            flows.clear()
            replyIndex.clear()
        }
    }

    fun size(nowMs: Long): Int =
        withEvictionNotifications { evicted ->
            purgeLocked(nowMs, evicted)
            flows.size
        }

    fun endpointCollisionCount(): Long = synchronized(lock) { endpointCollisionCount }

    private fun purgeLocked(nowMs: Long, evicted: MutableList<UdpFlow>) {
        val iterator = flows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenMs > ttlMs) {
                val flow = entry.value
                iterator.remove()
                removeReplyMappingsLocked(flow)
                evicted.add(flow)
            }
        }
    }

    private fun evictOldestLocked(evicted: MutableList<UdpFlow>) {
        val iterator = flows.entries.iterator()
        if (!iterator.hasNext()) return
        val flow = iterator.next().value
        iterator.remove()
        removeReplyMappingsLocked(flow)
        evicted.add(flow)
    }

    private fun registerReplyMappingLocked(endpoint: String, logicalKey: String) {
        val set = replyIndex.getOrPut(endpoint) { LinkedHashSet() }
        if (logicalKey !in set && set.isNotEmpty()) endpointCollisionCount++
        set.add(logicalKey)
    }

    private fun removeReplyMappingsLocked(flow: UdpFlow) {
        flow.candidates.forEach { candidate ->
            val key = endpointKey(candidate, flow.destinationPort)
            val set = replyIndex[key] ?: return@forEach
            set.remove(flow.logicalKey)
            if (set.isEmpty()) replyIndex.remove(key)
        }
    }

    private fun endpointKey(address: InetAddress, port: Int): String =
        address.hostAddress + ":" + port
}
