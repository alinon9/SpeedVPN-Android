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
 *
 * Eviction notifications are delivered outside the table lock. The owner can then
 * close the flow's DatagramChannel without introducing a table-lock/mux-lock cycle.
 */
internal class UdpFlowTable(
    private val maxFlows: Int = 256,
    private val ttlMs: Long = 30_000L,
    private val onFlowEvicted: (String) -> Unit = {},
) {
    init {
        require(maxFlows > 0) { "maxFlows must be positive" }
        require(ttlMs > 0L) { "ttlMs must be positive" }
    }

    private val lock = Any()
    private val flows = LinkedHashMap<String, UdpFlow>(16, 0.75f, true)
    // Defensive lookup/index for tests and diagnostics. Production replies use one
    // connected upstream DatagramChannel per logical flow.
    private val replyIndex = HashMap<String, LinkedHashSet<String>>()
    private var endpointCollisionCount = 0L

    fun getOrCreate(
        logicalKey: String,
        destinationPort: Int,
        clientEndpoint: InetSocketAddress,
        candidates: List<InetAddress>,
        nowMs: Long,
    ): UdpFlow? {
        val candidateSnapshot = candidates.toList()
        val evicted = ArrayList<String>()
        val result = synchronized(lock) {
            if (destinationPort !in 0..65_535 || candidateSnapshot.isEmpty()) {
                return@synchronized null
            }
            purgeLocked(nowMs, evicted)
            var flow = flows[logicalKey]
            if (flow == null) {
                while (flows.size >= maxFlows) evictOldestLocked(evicted)
                flow = UdpFlow(
                    logicalKey = logicalKey,
                    destinationPort = destinationPort,
                    clientEndpoint = clientEndpoint,
                    candidates = candidateSnapshot,
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
                flow.candidates = candidateSnapshot
                flow.selectedIndex = previousSelected?.let {
                    candidateSnapshot.indexOf(it).takeIf { index -> index >= 0 }
                } ?: flow.selectedIndex.coerceIn(0, candidateSnapshot.lastIndex)
                flow.lastSeenMs = nowMs
            }

            candidateSnapshot.forEach { candidate ->
                registerReplyMappingLocked(endpointKey(candidate, flow.destinationPort), logicalKey)
            }
            flow
        }
        notifyEvicted(evicted)
        return result
    }

    fun findByDestination(address: InetAddress, port: Int, nowMs: Long): UdpFlow? {
        val evicted = ArrayList<String>()
        val result = synchronized(lock) {
            purgeLocked(nowMs, evicted)
            val endpoint = endpointKey(address, port)
            val keys = replyIndex[endpoint] ?: return@synchronized null
            var matched: UdpFlow? = null
            var matchCount = 0
            for (key in keys) {
                val candidate = flows[key] ?: continue
                matchCount++
                if (matchCount > 1) return@synchronized null
                matched = candidate
            }
            val flow = matched ?: return@synchronized null
            flow.lastSeenMs = nowMs
            flow.lastReplyMs = nowMs
            flow
        }
        notifyEvicted(evicted)
        return result
    }

    fun markReply(flow: UdpFlow, nowMs: Long) = synchronized(lock) {
        val current = flows[flow.logicalKey] ?: return@synchronized
        current.lastSeenMs = nowMs
        current.lastReplyMs = nowMs
    }

    fun contains(logicalKey: String, nowMs: Long): Boolean {
        val evicted = ArrayList<String>()
        val result = synchronized(lock) {
            purgeLocked(nowMs, evicted)
            flows.containsKey(logicalKey)
        }
        notifyEvicted(evicted)
        return result
    }

    fun touchSent(flow: UdpFlow, nowMs: Long) = synchronized(lock) {
        val current = flows[flow.logicalKey] ?: return@synchronized
        current.lastSeenMs = nowMs
        current.lastSentMs = nowMs
    }

    fun advanceCandidateIfUnanswered(flow: UdpFlow, nowMs: Long, waitMs: Long): Boolean =
        synchronized(lock) {
            val current = flows[flow.logicalKey] ?: return@synchronized false
            if (current.selectedIndex >= current.candidates.lastIndex) return@synchronized false
            if (current.lastSentMs <= current.lastReplyMs) return@synchronized false
            if (nowMs - current.lastSentMs < waitMs) return@synchronized false
            current.selectedIndex += 1
            current.lastSeenMs = nowMs
            current.lastSentMs = 0L
            current.lastReplyMs = 0L
            true
        }

    fun clear() {
        val removed = synchronized(lock) {
            val keys = flows.keys.toList()
            flows.clear()
            replyIndex.clear()
            keys
        }
        notifyEvicted(removed)
    }

    fun size(nowMs: Long): Int {
        val evicted = ArrayList<String>()
        val result = synchronized(lock) {
            purgeLocked(nowMs, evicted)
            flows.size
        }
        notifyEvicted(evicted)
        return result
    }

    fun endpointCollisionCount(): Long = synchronized(lock) { endpointCollisionCount }

    private fun purgeLocked(nowMs: Long, evicted: MutableList<String>) {
        val iterator = flows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenMs > ttlMs) {
                val flow = entry.value
                iterator.remove()
                removeReplyMappingsLocked(flow)
                evicted.add(flow.logicalKey)
            }
        }
    }

    private fun evictOldestLocked(evicted: MutableList<String>) {
        val iterator = flows.entries.iterator()
        if (!iterator.hasNext()) return
        val flow = iterator.next().value
        iterator.remove()
        removeReplyMappingsLocked(flow)
        evicted.add(flow.logicalKey)
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

    private fun notifyEvicted(logicalKeys: List<String>) {
        logicalKeys.distinct().forEach { key ->
            try {
                onFlowEvicted(key)
            } catch (e: Exception) {
                logE("UDP flow eviction cleanup failed for logicalKey=$key", e)
            }
        }
    }

    private fun endpointKey(address: InetAddress, port: Int): String =
        address.hostAddress + ":" + port
}
