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

/** Thread-safe per-association UDP flow table with TTL + true access-order LRU. */
internal class UdpFlowTable(
    private val maxFlows: Int = 256,
    private val ttlMs: Long = 30_000L,
) {
    init {
        require(maxFlows > 0) { "maxFlows must be positive" }
        require(ttlMs > 0L) { "ttlMs must be positive" }
    }

    private val flows = LinkedHashMap<String, UdpFlow>(16, 0.75f, true)
    // Kept as a defensive lookup/index for tests and diagnostics. The live UDP relay
    // now uses one connected upstream DatagramChannel per logical flow, so production
    // reply delivery does not depend on this remote-IP:port correlation index.
    private val replyIndex = HashMap<String, LinkedHashSet<String>>()
    private var endpointCollisionCount = 0L

    @Synchronized
    fun getOrCreate(
        logicalKey: String,
        destinationPort: Int,
        clientEndpoint: InetSocketAddress,
        candidates: List<InetAddress>,
        nowMs: Long,
    ): UdpFlow? {
        if (destinationPort !in 0..65_535 || candidates.isEmpty()) return null
        purgeLocked(nowMs)
        var flow = flows[logicalKey]
        if (flow == null) {
            while (flows.size >= maxFlows) evictOldestLocked()
            flow = UdpFlow(
                logicalKey = logicalKey,
                destinationPort = destinationPort,
                clientEndpoint = clientEndpoint,
                candidates = candidates,
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
            flow.candidates = candidates
            flow.selectedIndex = previousSelected?.let { candidates.indexOf(it).takeIf { it >= 0 } }
                ?: flow.selectedIndex.coerceIn(0, candidates.lastIndex)
            flow.lastSeenMs = nowMs
        }

        candidates.forEach { candidate ->
            registerReplyMappingLocked(endpointKey(candidate, flow.destinationPort), logicalKey)
        }
        return flow
    }

    @Synchronized
    fun findByDestination(address: InetAddress, port: Int, nowMs: Long): UdpFlow? {
        purgeLocked(nowMs)
        val endpoint = endpointKey(address, port)
        val keys = replyIndex[endpoint] ?: return null
        var matched: UdpFlow? = null
        var matchCount = 0
        for (key in keys) {
            val candidate = flows[key] ?: continue
            matchCount++
            if (matchCount > 1) return null
            matched = candidate
        }
        val flow = matched ?: return null
        flow.lastSeenMs = nowMs
        flow.lastReplyMs = nowMs
        return flow
    }

    @Synchronized
    fun markReply(flow: UdpFlow, nowMs: Long) {
        val current = flows[flow.logicalKey] ?: return
        current.lastSeenMs = nowMs
        current.lastReplyMs = nowMs
    }

    @Synchronized
    fun contains(logicalKey: String, nowMs: Long): Boolean {
        purgeLocked(nowMs)
        return flows.containsKey(logicalKey)
    }

    @Synchronized
    fun touchSent(flow: UdpFlow, nowMs: Long) {
        val current = flows[flow.logicalKey] ?: return
        current.lastSeenMs = nowMs
        current.lastSentMs = nowMs
    }

    @Synchronized
    fun advanceCandidateIfUnanswered(flow: UdpFlow, nowMs: Long, waitMs: Long): Boolean {
        val current = flows[flow.logicalKey] ?: return false
        if (current.selectedIndex >= current.candidates.lastIndex) return false
        if (current.lastSentMs <= current.lastReplyMs) return false
        if (nowMs - current.lastSentMs < waitMs) return false
        current.selectedIndex += 1
        current.lastSeenMs = nowMs
        current.lastSentMs = 0L
        current.lastReplyMs = 0L
        return true
    }

    @Synchronized
    fun clear() {
        flows.clear()
        replyIndex.clear()
    }

    @Synchronized
    fun size(nowMs: Long): Int {
        purgeLocked(nowMs)
        return flows.size
    }

    @Synchronized
    fun endpointCollisionCount(): Long = endpointCollisionCount

    private fun purgeLocked(nowMs: Long) {
        val iterator = flows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenMs > ttlMs) {
                val flow = entry.value
                iterator.remove()
                removeReplyMappingsLocked(flow)
            }
        }
    }

    private fun evictOldestLocked() {
        val iterator = flows.entries.iterator()
        if (!iterator.hasNext()) return
        val flow = iterator.next().value
        iterator.remove()
        removeReplyMappingsLocked(flow)
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
