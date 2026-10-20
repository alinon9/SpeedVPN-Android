package com.speedvpn.app

import android.net.Network
import android.util.Base64
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Performs one blocking socket read for each relay iteration.
 *
 * Do not poll InputStream.available() to build a larger batch: the throughput
 * sweep regressed after that optimization, while synthetic streams could not
 * model real socket readiness. The bounded socket read already requests the
 * maximum supported buffer and preserves natural TCP backpressure.
 */
internal data class RelayReadBatch(val bytesRead: Int, val readCalls: Int)

/**
 * An upstream peer may finish its HTTP request and close its receive half while
 * the reverse response is still in flight. Preserve the reverse pipe only for
 * that upload-write/half-close case, never for a dead VPN session or a download
 * write failure.
 */
internal fun shouldPreserveReverseFlowAfterUploadHalfClose(
    isUploadDirection: Boolean,
    sessionActive: Boolean,
    destinationWriteBrokenPipe: Boolean,
    halfCloseFailed: Boolean,
): Boolean =
    isUploadDirection && sessionActive && (destinationWriteBrokenPipe || halfCloseFailed)

internal fun readRelayBatch(
    source: InputStream,
    buffer: ByteArray,
    maxBytes: Int,
): RelayReadBatch {
    require(maxBytes in 1..buffer.size) { "maxBytes must fit inside the relay buffer" }
    val bytesRead = source.read(buffer, 0, maxBytes)
    return RelayReadBatch(bytesRead, if (bytesRead < 0) 0 else 1)
}

/**
 * Local SOCKS5 relay used by the native tun2socks engine.
 *
 * Security: a fresh random username/password is generated per VPN session and
 * passed to hev through tunnel.yml. This prevents unrelated localhost clients
 * from using the relay as an unrestricted egress proxy.
 */
class Socks5Server internal constructor(
    private val generation: Long,
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val currentNetwork: () -> Network?,
    private val ipv6Enabled: Boolean,
    private val dnsIpv4Only: Boolean = false,
    private val accountQuotaBytes: (
        packages: Set<String>,
        uid: Int?,
        direction: QuotaTrafficDirection,
        requestedBytes: Int,
    ) -> QuotaTrafficDecision = { _, _, _, requestedBytes -> QuotaTrafficDecision(requestedBytes) },
    private val onQuotaExceeded: (Set<String>) -> Unit = {},
    /** Refuse ownerless data sessions while at least one per-app quota is configured. */
    private val requireUidAttribution: () -> Boolean = { false },
) {
    companion object {
        private const val MAX_SESSIONS = 80
        // TCP relays use the handler thread for one direction and one pool worker for
        // the reverse direction, so the normal TCP cost is ~2 workers/session rather
        // than 3. UDP still uses two workers plus its control handler.
        private const val MAX_WORKERS = 192
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val UDP_BUFFER_SIZE = 65_535
        private const val MAX_UDP_ASSOCIATIONS = 24
        private const val UDP_DNS_WORKERS = 4
        private const val UDP_FLOW_TTL_MS = 30_000L
        private const val UDP_CANDIDATE_FALLBACK_MS = 750L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val UDP_DNS_TIMEOUT_MS = 3_000L
        private val HEALTH_PROBE_RESERVATION_TTL_NANOS = TimeUnit.SECONDS.toNanos(5L)

        private const val IPPROTO_TCP = 6
        private const val IPPROTO_UDP = 17

        internal fun acceptsUdpAddressType(atyp: Int, ipv6Enabled: Boolean): Boolean = when (atyp) {
            1 -> true
            4 -> ipv6Enabled
            else -> false
        }

        /** Defense-in-depth check for a SOCKS association whose owner became blocked. */
        internal fun isSessionUidBlocked(sessionUid: Int?, blockedUids: Set<Int>): Boolean =
            sessionUid != null && sessionUid > 0 && sessionUid in blockedUids

        /** A health probe has a distinct authenticated identity, never a UID-less app identity. */
        internal fun healthProbeUsername(baseUsername: String): String = "$baseUsername#health-probe"

        internal fun healthProbeReservationIsValid(expiresAtNanos: Long?, nowNanos: Long): Boolean =
            expiresAtNanos != null && nowNanos < expiresAtNanos

        /**
         * Permit the special control identity without a UID, but never let a port reservation
         * accidentally turn an ordinary UID-less app session into an unmetered session.
         */
        internal fun usernameShapeAllowed(
            suppliedUsername: String,
            baseUsername: String,
            sessionUid: Int?,
            requireUidAttribution: Boolean,
        ): Boolean = when {
            suppliedUsername == healthProbeUsername(baseUsername) -> true
            sessionUid != null -> suppliedUsername == "$baseUsername#$sessionUid"
            else -> suppliedUsername == baseUsername && !requireUidAttribution
        }

        internal fun udpFlowKey(
            clientEndpoint: InetSocketAddress,
            candidates: List<InetAddress>,
            destinationPort: Int,
        ): String {
            val candidateKey = candidates
                .map { it.hostAddress }
                .sorted()
                .joinToString(",")
            return "src:${clientEndpoint.address.hostAddress}:${clientEndpoint.port}" +
                "|dst:$candidateKey:$destinationPort"
        }
    }

    private data class FlowOwnerMetadata(
        val endpointKey: String,
        @Volatile var uid: Int? = null,
        @Volatile var packages: Set<String> = emptySet(),
        @Volatile var tuple: String = "",
    )

    private val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    val username: String = randomToken()
    val password: String = randomToken(32)

    @Volatile private var running = true

    private val openTcp = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val openUdp = Collections.newSetFromMap(ConcurrentHashMap<DatagramSocket, Boolean>())
    // User sessions are capped at MAX_SESSIONS. Health probes bypass this
    // admission limit through a source-port reservation, so a full user
    // session table cannot make the health monitor report a false failure.
    private val sessions = Semaphore(MAX_SESSIONS, true)
    private val udpAssociations = Semaphore(MAX_UDP_ASSOCIATIONS, true)
    // Keep a short-lived reservation after the probe socket times out. The accept thread may
    // be descheduled under load and must still recognize a just-accepted health socket.
    private val probeSourcePorts = ConcurrentHashMap<Int, Long>()
    private val upstreamTcp = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val upstreamUdp = Collections.newSetFromMap(ConcurrentHashMap<DatagramSocket, Boolean>())
    private val blockedDestinations = ConcurrentHashMap.newKeySet<String>()
    private val permanentlyBlockedDestinations = ConcurrentHashMap.newKeySet<String>()
    private val policyBlockedPackagesByDestination = ConcurrentHashMap<String, Set<String>>()
    private val blockedFlowTuples = ConcurrentHashMap.newKeySet<String>()
    private val flowOwnersByEndpoint = ConcurrentHashMap<String, FlowOwnerMetadata>()
    private val upstreamTcpMetadata = ConcurrentHashMap<Socket, FlowOwnerMetadata>()
    private val activeUdpMuxes = ConcurrentHashMap.newKeySet<UdpUpstreamMux>()
    @Volatile private var blockedUids: Set<Int> = emptySet()
    // Policy changes invalidate accepted SOCKS sessions and asynchronous DNS work.
    @Volatile private var policyEpoch: Long = 0L
    // User-domain DNS resolution is encrypted DoH. The bounded pool prevents
    // stalled encrypted lookups from building an unbounded backlog.
    private val dnsPool: ExecutorService = ThreadPoolExecutor(
        0,
        UDP_DNS_WORKERS,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { r -> Thread(r, "socks-dns").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile private var encryptedDnsNetwork: Network? = null
    @Volatile private var encryptedDnsResolver: EncryptedDnsResolver? = null

    private fun encryptedResolver(network: Network): EncryptedDnsResolver {
        val cached = encryptedDnsResolver
        if (cached != null && encryptedDnsNetwork == network) return cached
        return synchronized(this) {
            val current = encryptedDnsResolver
            if (current != null && encryptedDnsNetwork == network) {
                current
            } else {
                EncryptedDnsResolver(network, protectTcp, ipv6Enabled, dnsIpv4Only).also {
                    encryptedDnsNetwork = network
                    encryptedDnsResolver = it
                }
            }
        }
    }

    private val pool: ExecutorService = ThreadPoolExecutor(
        0,
        MAX_WORKERS,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { r ->
            Thread(r, "socks-worker").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    // Health probes use a dedicated worker so a saturated data-plane pool cannot
    // make a healthy local SOCKS endpoint look dead.
    private val probePool: ExecutorService = ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { r ->
            Thread(r, "socks-probe").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun start() {
        thread(name = "socks-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                if (!running) {
                    runCatching { client.close() }
                    break
                }
                val sourcePort = (client.remoteSocketAddress as? InetSocketAddress)?.port
                val isHealthProbe = sourcePort?.let { port ->
                    healthProbeReservationIsValid(probeSourcePorts.remove(port), System.nanoTime())
                } == true
                val acceptedPolicyEpoch = policyEpoch

                if (!isHealthProbe && !sessions.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                track(client)
                val executor = if (isHealthProbe) probePool else pool
                try {
                    executor.execute {
                        try {
                            handle(client, acceptedPolicyEpoch, isHealthProbe)
                        } finally {
                            if (!isHealthProbe) sessions.release()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    if (!isHealthProbe) sessions.release()
                    untrack(client)
                    runCatching { client.close() }
                }
            }
        }
        log("SOCKS relay listening on 127.0.0.1:$port (authenticated)")
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        openTcp.toList().forEach { runCatching { it.close() } }
        openUdp.toList().forEach { runCatching { it.close() } }
        openTcp.clear()
        openUdp.clear()
        upstreamTcp.clear()
        upstreamUdp.clear()
        upstreamTcpMetadata.clear()
        activeUdpMuxes.toList().forEach { runCatching { it.close() } }
        activeUdpMuxes.clear()
        blockedDestinations.clear()
        permanentlyBlockedDestinations.clear()
        policyBlockedPackagesByDestination.clear()
        blockedFlowTuples.clear()
        flowOwnersByEndpoint.clear()
        pool.shutdownNow()
        probePool.shutdownNow()
        dnsPool.shutdownNow()
        runCatching { pool.awaitTermination(3, TimeUnit.SECONDS) }
        runCatching { probePool.awaitTermination(3, TimeUnit.SECONDS) }
        // Global pacing lifecycle is owned by SpeedVpnService's session generation.
        // A relay must not reset process-wide schedulers because an older relay can
        // outlive a newer VPN Service instance during asynchronous teardown.
    }

    /** Local control-plane probe: verifies the authenticated SOCKS endpoint is alive. */
    fun probe(timeoutMs: Int = 1_000): Boolean {
        if (!running) return false
        val socket = Socket()
        var sourcePort: Int? = null
        return try {
            // Register a source-port reservation before connect(). The accept loop can
            // then recognize this in-process health check and bypass the user-session
            // semaphore even when MAX_SESSIONS is fully occupied.
            pruneExpiredProbeReservations()
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            sourcePort = (socket.localSocketAddress as? InetSocketAddress)?.port
            if (sourcePort == null) return false
            probeSourcePorts[sourcePort] = System.nanoTime() + HEALTH_PROBE_RESERVATION_TTL_NANOS

            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            out.write(byteArrayOf(5, 1, 2))
            out.flush()
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) return false

            val ub = healthProbeUsername(username).toByteArray(Charsets.UTF_8)
            val pb = password.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(1, ub.size.toByte()))
            out.write(ub)
            out.write(byteArrayOf(pb.size.toByte()))
            out.write(pb)
            out.flush()
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) return false

            // Send an intentionally unsupported command. The expected 0x07 response
            // proves the authenticated SOCKS control path is alive without opening
            // an upstream socket or creating a long-lived UDP association.
            out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            out.flush()
            val repVersion = input.readUnsignedByte()
            val repCode = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            input.readUnsignedByte() // ATYP
            repeat(6) { input.readUnsignedByte() }
            repVersion == 5 && repCode == 7
        } catch (_: Exception) {
            false
        } finally {
            // If connect failed, release the unused reservation. For a connected socket,
            // let the accept loop consume it; removing it here races a delayed accept.
            if (!socket.isConnected) sourcePort?.let(probeSourcePorts::remove)
            runCatching { socket.close() }
        }
    }

    private fun pruneExpiredProbeReservations(nowNanos: Long = System.nanoTime()) {
        probeSourcePorts.entries.forEach { entry ->
            if (entry.value <= nowNanos) probeSourcePorts.remove(entry.key, entry.value)
        }
    }

    private fun track(socket: Socket): Socket = socket.also { openTcp.add(it) }
    private fun untrack(socket: Socket) { openTcp.remove(socket) }
    private fun trackUpstream(
        socket: Socket,
        endpoint: String? = null,
        sessionUid: Int? = null,
    ): Socket {
        upstreamTcp.add(socket)
        openTcp.add(socket)
        if (endpoint != null) {
            // Ownership is attached by the per-session SOCKS username, never inferred from
            // a destination shared by multiple apps.
            upstreamTcpMetadata[socket] = FlowOwnerMetadata(
                endpointKey = endpoint,
                uid = sessionUid,
                packages = emptySet(),
                tuple = sessionUid?.let { "SOCKS5_UID_AUTH:$it" } ?: "UID_UNRESOLVED",
            )
        }
        return socket
    }
    private fun untrackUpstream(socket: Socket) {
        upstreamTcp.remove(socket)
        openTcp.remove(socket)
        upstreamTcpMetadata.remove(socket)
    }
    private fun track(socket: DatagramSocket): DatagramSocket = socket.also { openUdp.add(it) }
    private fun untrack(socket: DatagramSocket) { openUdp.remove(socket) }
    private fun trackUpstream(socket: DatagramSocket): DatagramSocket = socket.also { upstreamUdp.add(it); openUdp.add(it) }
    private fun untrackUpstream(socket: DatagramSocket) { upstreamUdp.remove(socket); openUdp.remove(socket) }

    internal fun updateBlockedUids(uids: Set<Int>) {
        val normalized = uids.filter { it > 0 }.toSet()
        blockedUids = normalized
        killConnectionsForBlockedUids(normalized)
        log("BLOCKED_UID_POLICY_UPDATED count=${normalized.size} uids=${normalized.sorted().joinToString(",")}")
    }

    /**
     * Close relay sockets whose original flow has a resolved owner in the blocked UID set.
     * The native per-flow DROP remains authoritative when a SOCKS destination is ambiguous.
     */
    internal fun killConnectionsForBlockedUids(blocked: Set<Int>) {
        val normalized = blocked.filter { it > 0 }.toSet()
        if (normalized.isEmpty()) return
        var closedTcp = 0
        upstreamTcpMetadata.entries.toList().forEach { entry ->
            val ownerUid = entry.value.uid
            if (ownerUid != null && ownerUid in normalized) {
                runCatching { entry.key.close() }
                untrackUpstream(entry.key)
                closedTcp++
            }
        }
        activeUdpMuxes.toList().forEach { it.killConnectionsForBlockedUids(normalized) }
        log("BLOCKED_UID_CONNECTIONS_KILLED uidCount=${normalized.size} tcpClosed=$closedTcp")
    }


    /**
     * SOCKS destination metadata may be ambiguous when two apps contact one endpoint.
     * On any policy change, invalidate every prior relay and cancel pending DoH requests.
     */
    internal fun closeActiveSessionsForPolicyChange() {
        val dnsResolver = synchronized(this) {
            policyEpoch += 1L
            flowOwnersByEndpoint.clear()
            encryptedDnsNetwork = null
            encryptedDnsResolver.also { encryptedDnsResolver = null }
        }
        val dnsCancelled = dnsResolver != null
        runCatching { dnsResolver?.cancelPendingRequests() }

        val tcpSockets = openTcp.toList()
        val udpSockets = openUdp.toList()
        val muxes = activeUdpMuxes.toList()
        muxes.forEach { runCatching { it.close() } }
        tcpSockets.forEach { socket ->
            runCatching { socket.close() }
            untrackUpstream(socket)
            untrack(socket)
        }
        udpSockets.forEach { socket ->
            runCatching { socket.close() }
            untrackUpstream(socket)
            untrack(socket)
        }
        activeUdpMuxes.removeAll(muxes.toSet())
        blockedFlowTuples.clear()
        val tcpCount = tcpSockets.size
        val udpCount = udpSockets.size
        val muxCount = muxes.size
        log("APP_POLICY_SESSIONS_CLOSED epoch=$policyEpoch tcp=$tcpCount udp=$udpCount udpMuxes=$muxCount dnsCancelled=$dnsCancelled")
    }

    private fun endpointKey(protocol: Int, host: String, port: Int): String =
        protocol.toString() + "|" + host.substringBefore("%").trim('[', ']').lowercase() + "|" + port

    private fun endpointKey(protocol: Int, address: InetAddress, port: Int): String =
        endpointKey(protocol, address.hostAddress ?: address.hostName, port)

    private fun isDestinationBlocked(protocol: Int, address: InetAddress, port: Int): Boolean =
        endpointKey(protocol, address, port) in blockedDestinations

    internal fun recordDetectedFlow(
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        uid: Int,
        packages: Collection<String>,
    ) {
        val endpoint = endpointKey(protocol, dstIp, dstPort)
        val flowTuple = protocol.toString() + "|" + srcIp + ":" + srcPort + "->" +
            dstIp + ":" + dstPort + "|uid=" + uid
        val owner = flowOwnersByEndpoint.compute(endpoint) { _, existing ->
            when {
                existing == null -> FlowOwnerMetadata(endpoint).also {
                    it.uid = uid
                    it.packages = packages.toSet()
                    it.tuple = flowTuple
                }
                existing.uid == uid -> existing.also {
                    it.packages = packages.toSet()
                    it.tuple = flowTuple
                }
                existing.uid == null && existing.packages.isEmpty() && existing.tuple.isBlank() ->
                    existing.also {
                        it.uid = uid
                        it.packages = packages.toSet()
                        it.tuple = flowTuple
                    }
                else -> FlowOwnerMetadata(endpoint, uid = null, packages = emptySet(), tuple = "AMBIGUOUS_OWNER")
            }
        } ?: return

        // This endpoint table is diagnostic/policy state only. Never copy its owner to
        // live relays: two apps can contact the same destination concurrently.
        if (owner.uid == null) {
            log("APP_FLOW_ENDPOINT_AMBIGUOUS endpoint=$endpoint; relay ownership stays per-session")
        }
    }

    internal fun enforceBlockedFlow(
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        uid: Int,
        packages: Collection<String>,
        policyBlockedPackages: Collection<String>,
        permanent: Boolean,
    ) {
        recordDetectedFlow(protocol, srcIp, srcPort, dstIp, dstPort, uid, packages)
        if (!permanent) {
            // An app blacklist is UID-scoped. Do not convert one blocked app's destination
            // into a global endpoint block that also affects unblocked apps.
            if (uid > 0 && uid in blockedUids) killConnectionsForBlockedUids(setOf(uid))
            return
        }
        val endpoint = endpointKey(protocol, dstIp, dstPort)
        val flowTuple = protocol.toString() + "|" + srcIp + ":" + srcPort + "->" +
            dstIp + ":" + dstPort + "|uid=" + uid
        blockEndpoint(
            protocol = protocol,
            endpoint = endpoint,
            uid = uid,
            flowTuple = flowTuple,
            policyPackages = policyBlockedPackages,
            permanent = true,
        )
    }

    internal fun enforceBlockedPackages(blockedPackages: Set<String>) {
        // Remove endpoint blocks whose dynamic policy reason no longer exists.
        policyBlockedPackagesByDestination.entries.toList().forEach { entry ->
            val stillBlocked = entry.value.intersect(blockedPackages)
            if (stillBlocked.isEmpty()) {
                policyBlockedPackagesByDestination.remove(entry.key, entry.value)
            } else {
                policyBlockedPackagesByDestination[entry.key] = stillBlocked
            }
            refreshBlockedDestination(entry.key)
        }

        upstreamTcpMetadata.values.toList().forEach { metadata ->
            val matches = metadata.packages.intersect(blockedPackages)
            val uidIsBlocked = metadata.uid?.let { it in blockedUids } == true
            if (uidIsBlocked || matches.isNotEmpty()) {
                blockEndpoint(
                    protocol = IPPROTO_TCP,
                    endpoint = metadata.endpointKey,
                    uid = metadata.uid,
                    flowTuple = metadata.tuple,
                    policyPackages = matches,
                )
            }
        }
        activeUdpMuxes.toList().forEach { it.enforceBlockedPackages(blockedPackages) }
    }

    private fun refreshBlockedDestination(endpoint: String) {
        if (endpoint in permanentlyBlockedDestinations ||
            policyBlockedPackagesByDestination[endpoint].orEmpty().isNotEmpty()
        ) {
            blockedDestinations.add(endpoint)
        } else {
            blockedDestinations.remove(endpoint)
        }
    }

    private fun blockEndpoint(
        protocol: Int,
        endpoint: String,
        uid: Int?,
        flowTuple: String,
        policyPackages: Collection<String> = emptySet(),
        permanent: Boolean = false,
    ) {
        // Never persist a destination-wide deny: multiple apps can contact the same server.
        // This SOCKS-layer action only closes active relays with a matching known owner.
        if (flowTuple.isNotBlank()) blockedFlowTuples.add(flowTuple)
        var closedTcp = 0
        when (protocol) {
            IPPROTO_TCP -> upstreamTcpMetadata.entries.toList()
                .filter { entry ->
                    entry.value.endpointKey == endpoint &&
                        ((uid != null && entry.value.uid == uid) ||
                            (uid == null && policyPackages.isNotEmpty() &&
                                entry.value.packages.any { it in policyPackages }))
                }
                .forEach { entry ->
                    if (uid != null) entry.value.uid = uid
                    if (flowTuple.isNotBlank()) entry.value.tuple = flowTuple
                    runCatching { entry.key.close() }
                    untrackUpstream(entry.key)
                    closedTcp++
                }
            IPPROTO_UDP -> activeUdpMuxes.toList().forEach {
                it.blockDestination(endpoint, uid, flowTuple, policyPackages)
            }
        }
        log("APP_FLOW_BLOCKED protocol=$protocol endpoint=$endpoint uid=" +
            (uid ?: -1) + " flow=" + flowTuple +
            " activeTcpClosed=$closedTcp globalDestinationBlock=false")
    }
    fun onUnderlyingNetworkChanged() {
        upstreamTcp.toList().forEach { socket ->
            runCatching { socket.close() }
            untrackUpstream(socket)
        }
        upstreamUdp.toList().forEach { socket ->
            runCatching { socket.close() }
            untrackUpstream(socket)
        }
    }

    private fun handle(client: Socket, expectedPolicyEpoch: Long, isHealthProbe: Boolean) {
        try {
            if (policyEpoch != expectedPolicyEpoch) return
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            if (input.readUnsignedByte() != 5) return
            val nMethods = input.readUnsignedByte()
            if (nMethods <= 0) return
            val methods = ByteArray(nMethods)
            input.readFully(methods)

            // Require RFC1929 username/password authentication.
            if (!methods.any { (it.toInt() and 0xff) == 0x02 }) {
                out.write(byteArrayOf(5, 0xFF.toByte()))
                out.flush()
                return
            }
            out.write(byteArrayOf(5, 0x02))
            out.flush()

            if (input.readUnsignedByte() != 1) return
            val uLen = input.readUnsignedByte()
            val userBytes = ByteArray(uLen)
            input.readFully(userBytes)
            val pLen = input.readUnsignedByte()
            val passBytes = ByteArray(pLen)
            input.readFully(passBytes)

            val suppliedUsername = userBytes.toString(Charsets.UTF_8)
            val isHealthProbeIdentity = suppliedUsername == healthProbeUsername(username)
            val uidPrefix = "$username#"
            val sessionUid = if (isHealthProbeIdentity) null else if (suppliedUsername.startsWith(uidPrefix)) {
                suppliedUsername.removePrefix(uidPrefix).toIntOrNull()?.takeIf { it >= 0 }
            } else {
                null
            }
            // The authenticated control identity needs no database quota lookup. Avoid competing
            // with per-chunk SQLite quota transactions during active traffic.
            val uidRequired = if (isHealthProbeIdentity) false else
                runCatching { requireUidAttribution() }.getOrDefault(true)
            val expectedUsername = when {
                isHealthProbeIdentity -> healthProbeUsername(username)
                sessionUid != null -> "$username#$sessionUid"
                else -> username
            }
            val usernameShapeValid = usernameShapeAllowed(
                suppliedUsername = suppliedUsername,
                baseUsername = username,
                sessionUid = sessionUid,
                requireUidAttribution = uidRequired,
            )
            val authOk = usernameShapeValid &&
                MessageDigest.isEqual(userBytes, expectedUsername.toByteArray(Charsets.UTF_8)) &&
                MessageDigest.isEqual(passBytes, password.toByteArray(Charsets.UTF_8))
            if ((isHealthProbe || isHealthProbeIdentity) && !authOk) {
                log("SOCKS_HEALTH_PROBE_AUTH_FAILED uidRequired=$uidRequired reservationRecognized=$isHealthProbe")
            }
            if (!authOk) {
                logE("SOCKS_AUTH_REJECTED uidRequired=$uidRequired parsedUid=${sessionUid ?: "UNRESOLVED"} healthProbe=$isHealthProbeIdentity")
            } else if (!isHealthProbeIdentity) {
                log("SOCKS_AUTH_ACCEPTED uid=${sessionUid ?: "UNRESOLVED"} uidRequired=$uidRequired")
            }
            out.write(byteArrayOf(1, if (authOk) 0 else 1))
            out.flush()
            if (!authOk || policyEpoch != expectedPolicyEpoch) return

            // Long-lived relays should not inherit the handshake timeout.
            client.soTimeout = 0

            if (input.readUnsignedByte() != 5) return
            val cmd = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) return // RSV
            val host = readAddress(input)
            val port = input.readUnsignedShort()

            if (policyEpoch != expectedPolicyEpoch) return
            when (cmd) {
                1 -> connect(client, input, out, host, port, expectedPolicyEpoch, sessionUid)
                3 -> udpAssociate(client, input, out, expectedPolicyEpoch, sessionUid)
                else -> {
                    out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                    out.flush()
                }
            }
        } catch (_: SocketTimeoutException) {
            // Slow/malformed client during handshake.
        } catch (e: Exception) {
            when (classifySocketError(e)) {
                SockErrKind.EXPECTED_CLOSE -> Unit
                SockErrKind.NETWORK_EVENT -> log("SOCKS network event: ${e.message}")
                SockErrKind.UNKNOWN -> logE("Unexpected SOCKS error", e)
            }
        } finally {
            untrack(client)
            runCatching { client.close() }
        }
    }

    private fun readAddress(input: DataInputStream): Any = when (input.readUnsignedByte()) {
        1 -> ByteArray(4).also { input.readFully(it) }.let(InetAddress::getByAddress)
        4 -> ByteArray(16).also { input.readFully(it) }.let(InetAddress::getByAddress)
        3 -> {
            val len = input.readUnsignedByte()
            require(len > 0) { "empty domain" }
            ByteArray(len).also { input.readFully(it) }.toString(Charsets.UTF_8)
        }
        else -> throw IllegalArgumentException("bad atyp")
    }

    private fun resolveAll(
        host: String,
        timeoutMs: Long,
        expectedPolicyEpoch: Long? = null,
    ): List<InetAddress> {
        fun policyStillAllowsDns(): Boolean = expectedPolicyEpoch == null || policyEpoch == expectedPolicyEpoch
        if (!policyStillAllowsDns()) throw UnknownHostException("DNS cancelled by app policy change")
        val network = currentNetwork()
            ?: throw UnknownHostException("No physical network available for encrypted DNS resolution: $host")
        if (timeoutMs <= 0L) throw SocketTimeoutException("DNS deadline exceeded for $host")

        val future = dnsPool.submit<List<InetAddress>> {
            if (!policyStillAllowsDns()) throw UnknownHostException("DNS cancelled by app policy change")
            val resolver = encryptedResolver(network)
            if (!policyStillAllowsDns()) {
                resolver.cancelPendingRequests()
                throw UnknownHostException("DNS cancelled by app policy change")
            }
            resolver.resolve(host, ::policyStillAllowsDns)
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
                .filter { ipv6Enabled || it is java.net.Inet4Address }
                .also {
                    if (!policyStillAllowsDns()) throw UnknownHostException("DNS cancelled by app policy change")
                    if (it.isEmpty()) throw UnknownHostException("No usable DNS address for $host")
                }
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("Encrypted DNS timeout for $host")
        } catch (e: Exception) {
            future.cancel(true)
            if (e.cause is UnknownHostException) throw e.cause as UnknownHostException
            throw UnknownHostException("Encrypted DNS failed for $host: ${e.cause?.message ?: e.message}")
        }
    }
    private fun connect(
        client: Socket,
        cin: InputStream,
        cout: OutputStream,
        target: Any,
        port: Int,
        expectedPolicyEpoch: Long,
        sessionUid: Int?,
    ) {
        if (policyEpoch != expectedPolicyEpoch) {
            runCatching { client.close() }
            return
        }
        if (sessionUid != null && sessionUid in blockedUids) {
            replyFailure(cout, 2)
            runCatching { client.close() }
            return
        }
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS)
        val candidates = when (target) {
            is InetAddress -> listOf(target)
            is String -> {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                resolveAll(target, remainingMs, expectedPolicyEpoch)
            }
            else -> emptyList()
        }
            .filter { ipv6Enabled || it is java.net.Inet4Address }
            .sortedBy { if (it.address.size == 4) 0 else 1 }

        if (policyEpoch != expectedPolicyEpoch) {
            runCatching { client.close() }
            return
        }
        if (candidates.isEmpty()) {
            replyFailure(cout, 4)
            client.close()
            return
        }
        if (candidates.any { isDestinationBlocked(IPPROTO_TCP, it, port) }) {
            replyFailure(cout, 2)
            client.close()
            return
        }

        var upstream: Socket? = null
        for (addr in candidates) {
            if (policyEpoch != expectedPolicyEpoch) break
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                .coerceAtMost(5_000L)
                .toInt()
            if (remainingMs <= 0) break

            val endpoint = endpointKey(IPPROTO_TCP, addr, port)
            if (endpoint in blockedDestinations) continue
            val socket = Socket()
            trackUpstream(socket, endpoint, sessionUid)
            try {
                val network = currentNetwork() ?: throw UnknownHostException("No physical network available")
                network.bindSocket(socket)
                if (!protectTcp(socket)) throw SocketException("VPN socket protection failed")
                socket.connect(InetSocketAddress(addr, port), remainingMs)
                if (policyEpoch != expectedPolicyEpoch) throw SocketException("Socket closed by app policy change")
                if (endpoint in blockedDestinations) {
                    throw SocketException("connection blocked by app policy")
                }
                socket.tcpNoDelay = true
                upstream = socket
                break
            } catch (e: Exception) {
                when (classifySocketError(e)) {
                    SockErrKind.EXPECTED_CLOSE -> Unit
                    SockErrKind.NETWORK_EVENT -> log("SOCKS upstream network event: ${e.message}")
                    SockErrKind.UNKNOWN -> logE("SOCKS upstream connect failed", e)
                }
                untrackUpstream(socket)
                runCatching { socket.close() }
            }
        }

        val remote = upstream ?: run {
            replyFailure(cout, if (candidates.any { isDestinationBlocked(IPPROTO_TCP, it, port) }) 2 else 5)
            client.close()
            return
        }

        if (policyEpoch != expectedPolicyEpoch) {
            runCatching { remote.close() }
            untrackUpstream(remote)
            runCatching { client.close() }
            return
        }
        cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        cout.flush()

        val closeOnce = AtomicBoolean(false)
        val remaining = AtomicInteger(2)
        val done = CountDownLatch(2)
        val closeBoth = {
            if (closeOnce.compareAndSet(false, true)) {
                runCatching { client.close() }
                runCatching { remote.close() }
                untrack(client)
                untrackUpstream(remote)
            }
        }

        var submitted = 0
        try {
            // The SOCKS handler is already a worker. Reuse it for upload and allocate
            // only one additional worker for download, reducing per-TCP-session
            // concurrency from 3 threads to 2 without changing half-close semantics.
            pool.execute {
                pipe(
                    src = remote.getInputStream(),
                    dst = cout,
                    destination = client,
                    bucket = SpeedLimiter.download,
                    generation = generation,
                    expectedPolicyEpoch = expectedPolicyEpoch,
                    other = remote,
                    remaining = remaining,
                    done = done,
                    closeBoth = closeBoth,
                )
            }
            submitted++
            pipe(
                src = cin,
                dst = remote.getOutputStream(),
                destination = remote,
                bucket = SpeedLimiter.upload,
                generation = generation,
                expectedPolicyEpoch = expectedPolicyEpoch,
                other = client,
                remaining = remaining,
                done = done,
                closeBoth = closeBoth,
            )
            // If upload hit EOF while download is still draining, keep the client and
            // remote pair alive until the reverse direction also completes.
            done.await()
        } catch (_: Exception) {
            closeBoth()
            repeat(2 - submitted) { done.countDown() }
        }
    }

    private fun pipe(
        src: InputStream,
        dst: OutputStream,
        destination: Socket,
        bucket: TokenBucket,
        generation: Long,
        expectedPolicyEpoch: Long,
        other: Socket,
        remaining: AtomicInteger,
        done: CountDownLatch,
        closeBoth: () -> Unit,
    ) {
        var eof = false
        val flowSocket = if (bucket === SpeedLimiter.download) other else destination
        val flowRemoteHost = flowSocket.inetAddress?.hostAddress ?: "unknown"
        val flowId = "${flowSocket.localPort}->${flowRemoteHost}:${flowSocket.port}"
        var pipeError: Throwable? = null
        var exitReason = "eof"
        val pipeStartedNs = System.nanoTime()
        var firstForwardedNs = 0L
        var lastForwardedNs = 0L
        var forwardedBytes = 0L
        var readCalls = 0L
        var writeCalls = 0L
        var maxReadBytes = 0
        var sourceReadNs = 0L
        var limiterCallNs = 0L
        var destinationWriteNs = 0L
        var destinationWriteInProgress = false
        try {
            // Match relay read granularity to the current pacing bucket. A 16 KiB
            // read requires hundreds of scheduler wakeups per second at high rates;
            // larger reads reduce that overhead while staying inside the bucket's
            // bounded burst window. The bucket can change while the pipe is active.
            val buf = ByteArray(256 * 1024)
            while (running && policyEpoch == expectedPolicyEpoch && SpeedLimiter.isGenerationActive(generation)) {
                destinationWriteInProgress = false
                val readStartedNs = System.nanoTime()
                val readSize = bucket.recommendedReadBytes().coerceAtMost(buf.size)
                val batch = readRelayBatch(
                    source = src,
                    buffer = buf,
                    maxBytes = readSize,
                )
                sourceReadNs += System.nanoTime() - readStartedNs
                val n = batch.bytesRead
                if (n < 0) {
                    eof = true
                    exitReason = "eof"
                    break
                }
                readCalls += batch.readCalls.toLong()
                maxReadBytes = maxOf(maxReadBytes, n)
                val limiterStartedNs = System.nanoTime()
                val mayProceed = SpeedLimiter.acquire(bucket, n, generation)
                limiterCallNs += System.nanoTime() - limiterStartedNs
                if (!mayProceed) {
                    exitReason = "limiter_or_session_inactive"
                    break
                }
                if (!running || policyEpoch != expectedPolicyEpoch || !SpeedLimiter.isGenerationActive(generation)) {
                    exitReason = "session_or_policy_stopped"
                    break
                }
                // Account at the relay boundary and never write beyond the remaining quota.
                // flowSocket is the physical upstream socket for both directions.
                val owner = upstreamTcpMetadata[flowSocket]
                val direction = if (bucket === SpeedLimiter.download) {
                    QuotaTrafficDirection.DOWNLOAD
                } else {
                    QuotaTrafficDirection.UPLOAD
                }
                val quotaDecision = if (owner == null) {
                    QuotaTrafficDecision(n)
                } else {
                    try {
                        accountQuotaBytes(owner.packages, owner.uid, direction, n)
                    } catch (error: Exception) {
                        logE("Quota accounting failed; dropping this relay chunk flowId=$flowId", error)
                        exitReason = "quota_accounting_failed"
                        QuotaTrafficDecision(0)
                    }
                }
                val allowedBytes = quotaDecision.allowedBytes.coerceIn(0, n)
                if (allowedBytes > 0) {
                    val writeStartedNs = System.nanoTime()
                    destinationWriteInProgress = true
                    dst.write(buf, 0, allowedBytes)
                    destinationWriteInProgress = false
                    writeCalls++
                    val writeFinishedNs = System.nanoTime()
                    destinationWriteNs += writeFinishedNs - writeStartedNs
                    if (firstForwardedNs == 0L) firstForwardedNs = writeStartedNs
                    lastForwardedNs = writeFinishedNs
                    bucket.recordForwarded(allowedBytes, generation)
                    forwardedBytes += allowedBytes.toLong()
                }

                if (quotaDecision.exhaustedPackages.isNotEmpty()) {
                    exitReason = "quota_exhausted"
                    runCatching { onQuotaExceeded(quotaDecision.exhaustedPackages) }
                        .onFailure { logE("Immediate quota block callback failed", it) }
                    closeBoth()
                    break
                }
                if (allowedBytes < n) {
                    if (exitReason != "quota_accounting_failed") exitReason = "quota_accounting_failed"
                    closeBoth()
                    break
                }
            }
            runCatching { dst.flush() }
        } catch (error: SocketException) {
            pipeError = error
            exitReason = "socket_error"
            logPipeException(error, bucket, generation, flowId)
        } catch (error: Exception) {
            pipeError = error
            exitReason = "relay_error"
            logPipeException(error, bucket, generation, flowId)
        } finally {
            val wallDurationNs = (System.nanoTime() - pipeStartedNs).coerceAtLeast(1L)
            val transferDurationNs = if (firstForwardedNs > 0L) {
                (lastForwardedNs - firstForwardedNs).coerceAtLeast(1L)
            } else {
                0L
            }
            if (!eof && pipeError == null && exitReason == "eof") exitReason = "session_stopped"
            val direction = if (bucket === SpeedLimiter.download) "download" else "upload"
            val throughputBps = if (transferDurationNs > 0L) {
                (forwardedBytes.toDouble() * 8_000_000_000.0 / transferDurationNs.toDouble()).toLong()
            } else {
                0L
            }
            val errorKind = pipeError?.let { classifySocketError(it).name } ?: "NONE"
            log(
                "Relay pipe completion flowId=$flowId direction=$direction bytesForwarded=$forwardedBytes " +
                    "rateBps=$throughputBps firstToLastMs=${TimeUnit.NANOSECONDS.toMillis(transferDurationNs)} " +
                    "pipeWallMs=${TimeUnit.NANOSECONDS.toMillis(wallDurationNs)} readCalls=$readCalls writeCalls=$writeCalls " +
                    "maxReadBytes=$maxReadBytes sourceReadMs=${TimeUnit.NANOSECONDS.toMillis(sourceReadNs)} " +
                    "limiterCallMs=${TimeUnit.NANOSECONDS.toMillis(limiterCallNs)} " +
                    "destinationWriteMs=${TimeUnit.NANOSECONDS.toMillis(destinationWriteNs)} " +
                    "eof=$eof exitReason=$exitReason errorKind=$errorKind " +
                    "error=${pipeError?.let(::classifyPipeErrorForLog) ?: "none"}"
            )
            val uploadDirection = bucket === SpeedLimiter.upload
            val sessionActive = running && SpeedLimiter.isGenerationActive(generation)
            val destinationWriteBrokenPipe =
                uploadDirection &&
                    destinationWriteInProgress &&
                    pipeError is SocketException &&
                    pipeError.message?.contains("broken pipe", ignoreCase = true) == true

            if (eof && running) {
                val halfCloseError = runCatching { destination.shutdownOutput() }.exceptionOrNull()
                if (halfCloseError != null) {
                    val preserveReverse = shouldPreserveReverseFlowAfterUploadHalfClose(
                        isUploadDirection = uploadDirection,
                        sessionActive = sessionActive,
                        destinationWriteBrokenPipe = false,
                        halfCloseFailed = true,
                    )
                    if (preserveReverse) {
                        log(
                            "SOCKS upload half-close failed; preserving reverse response flowId=$flowId " +
                                "error=${halfCloseError.javaClass.simpleName}:${halfCloseError.message ?: "no message"}",
                        )
                    } else {
                        closeBoth()
                    }
                }
            } else if (
                shouldPreserveReverseFlowAfterUploadHalfClose(
                    isUploadDirection = uploadDirection,
                    sessionActive = sessionActive,
                    destinationWriteBrokenPipe = destinationWriteBrokenPipe,
                    halfCloseFailed = false,
                )
            ) {
                // A Broken pipe while forwarding the request does not prove the
                // reverse HTTP response is unusable. Do not close the client socket
                // here: drain the server's response on the sibling download pipe.
                runCatching { destination.shutdownOutput() }
                log(
                    "SOCKS upload destination Broken pipe; preserving reverse response flowId=$flowId " +
                        "bytesForwarded=$forwardedBytes",
                )
            } else {
                closeBoth()
            }
            if (remaining.decrementAndGet() == 0) closeBoth()
            done.countDown()
        }
    }

    /**
     * Ignore an error only when the pipe is already shutting down or when the
     * exception type/message classifies it as an expected close. All active-session
     * network events and unknown exceptions retain their stack traces in Logcat.
     */
    private fun classifyPipeErrorForLog(error: Throwable): String =
        "${classifySocketError(error).name}:${error.javaClass.simpleName}:${error.message ?: "no message"}"

    private fun logPipeException(error: Throwable, bucket: TokenBucket, generation: Long, flowId: String) {
        if (!running || !SpeedLimiter.isGenerationActive(generation)) return
        val direction = if (bucket === SpeedLimiter.download) "download" else "upload"
        val detail = error.message ?: "no message"
        when (classifySocketError(error)) {
            SockErrKind.EXPECTED_CLOSE -> Unit
            SockErrKind.NETWORK_EVENT ->
                logE("SOCKS relay pipe network event flowId=$flowId direction=$direction error=${error.javaClass.simpleName}: $detail", error)
            SockErrKind.UNKNOWN ->
                logE("Unexpected SOCKS relay pipe failure flowId=$flowId direction=$direction error=${error.javaClass.simpleName}: $detail", error)
        }
    }

    private fun udpAssociate(
        client: Socket,
        cin: InputStream,
        cout: OutputStream,
        expectedPolicyEpoch: Long,
        sessionUid: Int?,
    ) {
        if (policyEpoch != expectedPolicyEpoch) {
            runCatching { client.close() }
            return
        }
        log("SOCKS_UDP_ASSOC_START uid=${sessionUid ?: "UNRESOLVED"} blocked=${sessionUid != null && sessionUid in blockedUids} policyEpoch=$expectedPolicyEpoch")
        if (sessionUid != null && sessionUid in blockedUids) {
            replyFailure(cout, 2)
            runCatching { client.close() }
            return
        }
        if (!udpAssociations.tryAcquire()) {
            replyFailure(cout, 1)
            client.close()
            return
        }

        var relay: DatagramSocket? = null

        try {
            relay = track(DatagramSocket(0, InetAddress.getByName("127.0.0.1")))
            val relaySocket = requireNotNull(relay)

            // Fail closed at association creation time when there is no physical
            // network. Individual flow sockets also re-check the network before
            // they are created, because the network can disappear later.
            if (currentNetwork() == null) {
                throw UnknownHostException("No physical network available")
            }

            val relayPort = relaySocket.localPort
            cout.write(
                byteArrayOf(
                    5, 0, 0, 1, 127, 0, 0, 1,
                    (relayPort shr 8).toByte(), relayPort.toByte()
                )
            )
            cout.flush()
            log("SOCKS_UDP_ASSOC_READY uid=${sessionUid ?: "UNRESOLVED"} relayPort=$relayPort")

            val clientAddr = AtomicReference<InetAddress?>(null)
            val udpFlows = UdpFlowTable(maxFlows = 256, ttlMs = UDP_FLOW_TTL_MS)
            val mux = UdpUpstreamMux(
                relaySocket = relaySocket,
                udpFlows = udpFlows,
                clientAddr = clientAddr,
                expectedPolicyEpoch = expectedPolicyEpoch,
                sessionUid = sessionUid,
            ).also { activeUdpMuxes.add(it) }
            val closeOnce = AtomicBoolean(false)
            val closeAll = {
                if (closeOnce.compareAndSet(false, true)) {
                    runCatching { mux.close() }
                    activeUdpMuxes.remove(mux)
                    runCatching { relaySocket.close() }
                    runCatching { client.close() }
                    untrack(relaySocket)
                }
            }

            try {
                pool.execute {
                    val buf = ByteArray(UDP_BUFFER_SIZE)
                    var loggedClientDatagram = false
                    var loggedForwardResult = false
                    try {
                        while (running && policyEpoch == expectedPolicyEpoch &&
                            SpeedLimiter.isGenerationActive(generation) && !relaySocket.isClosed &&
                            !Socks5Server.isSessionUidBlocked(sessionUid, blockedUids)
                        ) {
                            val pkt = DatagramPacket(buf, buf.size)
                            relaySocket.receive(pkt)
                            if (!loggedClientDatagram) {
                                log("SOCKS_UDP_CLIENT_DATAGRAM uid=${sessionUid ?: "UNRESOLVED"} bytes=${pkt.length} sender=${pkt.address.hostAddress}:${pkt.port}")
                                loggedClientDatagram = true
                            }
                            // A block may be installed while receive() is waiting. Re-check
                            // immediately after wake-up before parsing or forwarding the packet.
                            if (Socks5Server.isSessionUidBlocked(sessionUid, blockedUids)) break
                            val sender = pkt.address
                            val first = clientAddr.compareAndSet(null, sender)
                            // RFC 1928 requires source-IP validation for UDP ASSOCIATE.
                            // Source ports may rotate during one association; the port is
                            // part of the logical flow identity and therefore gets its own
                            // upstream socket when it changes.
                            val expectedClient = clientAddr.get()
                            if (!first && (expectedClient == null || !expectedClient.equals(sender))) continue
                            val senderEndpoint = InetSocketAddress(sender, pkt.port)

                            val bb = ByteBuffer.wrap(pkt.data, 0, pkt.length)
                            if (bb.remaining() < 4) continue
                            if (bb.short.toInt() != 0) continue // RSV
                            if (bb.get().toInt() != 0) continue // FRAG

                            val candidates: List<InetAddress> = when (bb.get().toInt() and 0xff) {
                                1 -> {
                                    if (bb.remaining() < 4) continue
                                    val addr = ByteArray(4).also { bb.get(it) }.let(InetAddress::getByAddress)
                                    if (!acceptsUdpAddressType(1, ipv6Enabled)) continue
                                    listOf(addr)
                                }
                                4 -> {
                                    if (!acceptsUdpAddressType(4, ipv6Enabled) || bb.remaining() < 16) continue
                                    val addr = ByteArray(16).also { bb.get(it) }.let(InetAddress::getByAddress)
                                    listOf(addr)
                                }
                                3 -> {
                                    if (!bb.hasRemaining()) continue
                                    val len = bb.get().toInt() and 0xff
                                    if (bb.remaining() < len) continue
                                    val host = ByteArray(len).also { bb.get(it) }.toString(Charsets.UTF_8)
                                    runCatching { resolveAll(host, UDP_DNS_TIMEOUT_MS, expectedPolicyEpoch) }
                                        .getOrElse { emptyList() }
                                }
                                else -> continue
                            }
                            if (policyEpoch != expectedPolicyEpoch) break
                            if (candidates.isEmpty() || bb.remaining() < 2) continue

                            val port = bb.short.toInt() and 0xffff
                            val payloadLen = bb.remaining()
                            if (payloadLen <= 0) continue

                            val now = System.currentTimeMillis()
                            // Each logical flow gets a distinct connected DatagramChannel.
                            // The upstream socket's local source port is therefore unique
                            // per flow, making replies deterministic even when two clients
                            // target the same remote IP:port.
                            val flowKey = udpFlowKey(senderEndpoint, candidates, port)
                            val flow = udpFlows.getOrCreate(
                                flowKey,
                                port,
                                senderEndpoint,
                                candidates,
                                now,
                            ) ?: continue
                            udpFlows.advanceCandidateIfUnanswered(flow, now, UDP_CANDIDATE_FALLBACK_MS)
                            val addr = flow.candidates.getOrNull(flow.selectedIndex) ?: continue

                            if (!SpeedLimiter.acquire(SpeedLimiter.upload, payloadLen, generation)) break
                            if (!running || !SpeedLimiter.isGenerationActive(generation)) break

                            val forwarded = mux.send(
                                flow = flow,
                                address = addr,
                                port = port,
                                data = pkt.data,
                                offset = bb.position(),
                                length = payloadLen,
                            )
                            if (!forwarded) {
                                if (!loggedForwardResult) {
                                    logE("SOCKS_UDP_FORWARD_REJECTED uid=${sessionUid ?: "UNRESOLVED"} dst=${addr.hostAddress}:$port bytes=$payloadLen")
                                    loggedForwardResult = true
                                }
                                continue
                            }
                            if (!loggedForwardResult) {
                                log("SOCKS_UDP_FORWARD_SENT uid=${sessionUid ?: "UNRESOLVED"} dst=${addr.hostAddress}:$port bytes=$payloadLen")
                                loggedForwardResult = true
                            }
                            SpeedLimiter.upload.recordForwarded(payloadLen, generation)
                            udpFlows.touchSent(flow, System.currentTimeMillis())
                            mux.trimClosedFlows(System.currentTimeMillis())
                        }
                    } catch (_: Exception) {
                        // teardown
                    } finally {
                        closeAll()
                    }
                }

                while (cin.read() >= 0 && running && policyEpoch == expectedPolicyEpoch && SpeedLimiter.isGenerationActive(generation)) { }
            } catch (_: Exception) {
                // teardown
            } finally {
                udpFlows.clear()
                closeAll()
            }
        } catch (error: Exception) {
            if (running && policyEpoch == expectedPolicyEpoch) {
                logE("SOCKS_UDP_ASSOC_FAILED uid=${sessionUid ?: "UNRESOLVED"}", error)
            }
            runCatching { client.close() }
        } finally {
            relay?.let {
                runCatching { it.close() }
                untrack(it)
            }
            udpAssociations.release()
        }
    }

    private data class UdpBinding(
        val flow: UdpFlow,
        val channel: DatagramChannel,
        // Immutable for the lifetime of the channel. Candidate fallback creates a
        // fresh binding/channel instead of retargeting a channel that may already
        // have a packet queued for the previous remote endpoint.
        val remote: InetSocketAddress,
        val endpointKey: String,
        @Volatile var uid: Int? = null,
        @Volatile var packages: Set<String> = emptySet(),
        @Volatile var tuple: String = "",
    )

    /**
     * Deterministic UDP upstream multiplexer.
     *
     * Every logical SOCKS UDP flow owns one connected DatagramChannel. A Selector
     * services all channels from one worker thread, so we do not trade correctness
     * for one thread per flow. Because each channel has its own local source port
     * and is connected to exactly one remote candidate, an incoming datagram can
     * only belong to that flow; no remote-IP:port heuristic is required.
     */
    private inner class UdpUpstreamMux(
        private val relaySocket: DatagramSocket,
        private val udpFlows: UdpFlowTable,
        private val clientAddr: AtomicReference<InetAddress?>,
        private val expectedPolicyEpoch: Long,
        private val sessionUid: Int?,
    ) : AutoCloseable {
        private val selector = Selector.open()
        private val lock = Any()
        private val bindings = HashMap<String, UdpBinding>()
        private val runningMux = AtomicBoolean(true)
        private val selectorRegistrationGate = Any()
        private val pendingSelectorRegistrations = AtomicInteger(0)

        private val worker = thread(name = "socks-udp-mux", isDaemon = true) {
            runLoop()
        }

        fun enforceBlockedPackages(blockedPackages: Set<String>) {
            val matches = synchronized(lock) {
                bindings.values.mapNotNull { binding ->
                    val packages = binding.packages.intersect(blockedPackages)
                    if (packages.isEmpty()) null
                    else binding to packages
                }
            }
            matches.forEach { (binding, packages) ->
                blockEndpoint(
                    protocol = IPPROTO_UDP,
                    endpoint = binding.endpointKey,
                    uid = binding.uid,
                    flowTuple = binding.tuple,
                    policyPackages = packages,
                )
            }
        }

        fun killConnectionsForBlockedUids(blockedUids: Set<Int>) {
            // Closing only current channels is insufficient for UDP: the SOCKS UDP
            // association remains alive and could create a fresh channel on its next
            // datagram. Close the association socket too, forcing its receive loop out.
            if (Socks5Server.isSessionUidBlocked(sessionUid, blockedUids)) {
                runCatching { relaySocket.close() }
                close()
                return
            }
            synchronized(lock) {
                val entries = bindings.entries.toList()
                entries.forEach { entry ->
                    val binding = entry.value
                    val uid = binding.uid
                    if (uid != null && uid in blockedUids) {
                        bindings.remove(entry.key)
                        closeBindingLocked(binding)
                    }
                }
            }
        }

        fun blockDestination(
            endpoint: String,
            uid: Int?,
            tuple: String,
            policyPackages: Collection<String> = emptySet(),
        ) {
            synchronized(lock) {
                val entries = bindings.entries.toList()
                entries.forEach { entry ->
                    val binding = entry.value
                    val sameOwner = (uid != null && binding.uid == uid) ||
                        (uid == null && policyPackages.isNotEmpty() &&
                            binding.packages.any { it in policyPackages })
                    if (binding.endpointKey == endpoint && sameOwner) {
                        binding.uid = uid ?: binding.uid
                        if (tuple.isNotBlank()) binding.tuple = tuple
                        bindings.remove(entry.key)
                        closeBindingLocked(binding)
                    }
                }
            }
        }

        fun send(
            flow: UdpFlow,
            address: InetAddress,
            port: Int,
            data: ByteArray,
            offset: Int,
            length: Int,
        ): Boolean {
            if (!runningMux.get() || !running || policyEpoch != expectedPolicyEpoch ||
                !SpeedLimiter.isGenerationActive(generation) ||
                Socks5Server.isSessionUidBlocked(sessionUid, blockedUids)
            ) return false
            if (isDestinationBlocked(IPPROTO_UDP, address, port)) return false

            val remote = InetSocketAddress(address, port)
            var exceededPackages: Set<String> = emptySet()
            val sent = synchronized(lock) {
                // Re-check inside the same critical section as channel creation/send.
                if (Socks5Server.isSessionUidBlocked(sessionUid, blockedUids) ||
                    !runningMux.get() || policyEpoch != expectedPolicyEpoch
                ) return@synchronized false
                var binding = bindings[flow.logicalKey]
                    ?.takeUnless { !it.channel.isOpen }

                if (binding != null && binding.remote != remote) {
                    if (bindings[flow.logicalKey] === binding) bindings.remove(flow.logicalKey)
                    closeBindingLocked(binding)
                    binding = null
                }

                if (binding == null) {
                    binding = try {
                        createBindingLocked(flow, remote)
                    } catch (error: Exception) {
                        logE("SOCKS_UDP_BINDING_CREATE_FAILED uid=${sessionUid ?: "UNRESOLVED"} remote=${address.hostAddress}:$port", error)
                        return@synchronized false
                    }
                }

                val activeBinding = binding
                val decision = try {
                    if (activeBinding.uid != null || activeBinding.packages.isNotEmpty()) {
                        accountQuotaBytes(
                            activeBinding.packages,
                            activeBinding.uid,
                            QuotaTrafficDirection.UPLOAD,
                            length,
                        )
                    } else {
                        QuotaTrafficDecision(length)
                    }
                } catch (error: Exception) {
                    logE("Quota accounting failed; dropping UDP upload datagram", error)
                    return@synchronized false
                }
                exceededPackages = decision.exhaustedPackages
                if (decision.allowedBytes < length) {
                    // UDP payloads are atomic: do not emit a malformed partial datagram.
                    log("SOCKS_UDP_QUOTA_DROP uid=${sessionUid ?: "UNRESOLVED"} requested=$length allowed=${decision.allowedBytes}")
                    return@synchronized false
                }

                runCatching {
                    val written = activeBinding.channel.write(ByteBuffer.wrap(data, offset, length))
                    written == length
                }.getOrElse {
                    if (bindings[flow.logicalKey] === activeBinding) bindings.remove(flow.logicalKey)
                    closeBindingLocked(activeBinding)
                    false
                }
            }
            if (exceededPackages.isNotEmpty()) {
                runCatching { onQuotaExceeded(exceededPackages) }
                    .onFailure { logE("Immediate quota block callback failed for UDP upload", it) }
            }
            return sent
        }

        fun trimClosedFlows(nowMs: Long) {
            synchronized(lock) {
                udpFlows.size(nowMs) // Purges expired flows.
                val iterator = bindings.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val binding = entry.value
                    if (!binding.channel.isOpen || !udpFlows.contains(binding.flow.logicalKey, nowMs)) {
                        closeBindingLocked(binding)
                        iterator.remove()
                    }
                }
            }
        }

        override fun close() {
            if (!runningMux.compareAndSet(true, false)) return
            synchronized(lock) {
                bindings.values.toList().forEach(::closeBindingLocked)
                bindings.clear()
            }
            selector.wakeup()
            // Quota exhaustion may close this mux from its selector worker callback.
            // Never join the current thread: the loop exits after this callback returns.
            if (Thread.currentThread() !== worker) {
                runCatching { worker.join(1_000) }
            }
            runCatching { selector.close() }
        }

        internal fun registerChannelWithSelector(
            channel: DatagramChannel,
            attachment: Any,
        ) {
            pendingSelectorRegistrations.incrementAndGet()
            try {
                selector.wakeup()
                synchronized(selectorRegistrationGate) {
                    selector.wakeup()
                    channel.register(selector, SelectionKey.OP_READ, attachment)
                }
            } finally {
                pendingSelectorRegistrations.decrementAndGet()
                selector.wakeup()
            }
        }

        internal fun yieldToPendingSelectorRegistrations() {
            if (pendingSelectorRegistrations.get() <= 0) return
            synchronized(selectorRegistrationGate) {
                // Wait for channel registration to finish before selecting again.
            }
        }

        private fun createBindingLocked(flow: UdpFlow, remote: InetSocketAddress): UdpBinding {
            if (policyEpoch != expectedPolicyEpoch) throw SocketException("Socket closed by app policy change")
            val network = currentNetwork()
                ?: throw UnknownHostException("No physical network available")
            val channel = DatagramChannel.open()
            val socket = channel.socket()
            try {
                socket.receiveBufferSize = 64 * 1024
                socket.sendBufferSize = 64 * 1024
                network.bindSocket(socket)
                socket.bind(InetSocketAddress(0))
                if (!protectUdp(socket)) {
                    throw SocketException("VPN UDP socket protection failed")
                }
                if (policyEpoch != expectedPolicyEpoch) throw SocketException("Socket closed by app policy change")
                channel.configureBlocking(false)
                channel.connect(remote)
                val endpoint = endpointKey(IPPROTO_UDP, remote.address, remote.port)
                val binding = UdpBinding(
                    flow = flow,
                    channel = channel,
                    remote = remote,
                    endpointKey = endpoint,
                    uid = sessionUid,
                    packages = emptySet(),
                    tuple = sessionUid?.let { "SOCKS5_UID_AUTH:$it" } ?: "UID_UNRESOLVED",
                )
                // Selector registration is thread-safe. Do not synchronize on the
                // Selector object: select() may hold that monitor while blocking, which
                // prevents this thread from calling wakeup() and delays UDP setup.
                registerChannelWithSelector(channel, binding)
                trackUpstream(socket)
                bindings[flow.logicalKey] = binding
                return binding
            } catch (e: Exception) {
                runCatching { channel.close() }
                throw e
            }
        }

        private fun closeBindingLocked(binding: UdpBinding) {
            runCatching { binding.channel.keyFor(selector)?.cancel() }
            val socket = runCatching { binding.channel.socket() }.getOrNull()
            runCatching { binding.channel.close() }
            socket?.let(::untrackUpstream)
        }

        private fun runLoop() {
            val buffer = ByteBuffer.allocate(UDP_BUFFER_SIZE)
            var loggedUdpReply = false
            try {
                while (runningMux.get() && running && policyEpoch == expectedPolicyEpoch &&
                    SpeedLimiter.isGenerationActive(generation) &&
                    !Socks5Server.isSessionUidBlocked(sessionUid, blockedUids)
                ) {
                    yieldToPendingSelectorRegistrations()
                    selector.select(500)
                    val selected = selector.selectedKeys()
                    val iterator = selected.iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        if (!key.isValid) continue

                        val binding = key.attachment() as? UdpBinding ?: continue
                        if (Socks5Server.isSessionUidBlocked(sessionUid, blockedUids)) break
                        try {
                            buffer.clear()
                            val count = binding.channel.read(buffer)
                            if (count <= 0) continue
                            if (!loggedUdpReply) {
                                log("SOCKS_UDP_UPSTREAM_REPLY uid=${binding.uid ?: "UNRESOLVED"} remote=${binding.remote.address.hostAddress}:${binding.remote.port} bytes=$count")
                                loggedUdpReply = true
                            }
                            buffer.flip()
                            val payload = ByteArray(count)
                            buffer.get(payload)

                            if (!SpeedLimiter.acquire(SpeedLimiter.download, count, generation)) {
                                continue
                            }
                            if (!running || !SpeedLimiter.isGenerationActive(generation)) continue

                            val quotaDecision = try {
                                if (binding.uid != null || binding.packages.isNotEmpty()) {
                                    accountQuotaBytes(
                                        binding.packages,
                                        binding.uid,
                                        QuotaTrafficDirection.DOWNLOAD,
                                        count,
                                    )
                                } else {
                                    QuotaTrafficDecision(count)
                                }
                            } catch (error: Exception) {
                                logE("Quota accounting failed; dropping UDP download datagram", error)
                                continue
                            }
                            if (quotaDecision.allowedBytes < count) {
                                if (quotaDecision.exhaustedPackages.isNotEmpty()) {
                                    runCatching { onQuotaExceeded(quotaDecision.exhaustedPackages) }
                                        .onFailure { logE("Immediate quota block callback failed for UDP download", it) }
                                }
                                // Drop the full datagram when only a prefix remains in quota.
                                continue
                            }

                            // Candidate fallback may replace this binding while the
                            // selector still has a queued event. Serialize the final
                            // ownership check with candidate replacement and relay send.
                            var delivered = false
                            synchronized(lock) {
                                if (bindings[binding.flow.logicalKey] === binding && binding.channel.isOpen) {
                                    val destination = binding.flow.clientEndpoint
                                    val address = binding.remote.address.address
                                    val type = if (address.size == 4) 1 else 4
                                    val header = ByteBuffer.allocate(4 + address.size + 2 + count)
                                        .put(byteArrayOf(0, 0, 0, type.toByte()))
                                        .put(address)
                                        .putShort(binding.remote.port.toShort())
                                        .put(payload)

                                    relaySocket.send(
                                        DatagramPacket(
                                            header.array(),
                                            0,
                                            header.position(),
                                            destination.address,
                                            destination.port,
                                        )
                                    )
                                    udpFlows.markReply(binding.flow, System.currentTimeMillis())
                                    delivered = true
                                }
                            }
                            if (delivered) {
                                SpeedLimiter.download.recordForwarded(count, generation)
                                if (quotaDecision.exhaustedPackages.isNotEmpty()) {
                                    runCatching { onQuotaExceeded(quotaDecision.exhaustedPackages) }
                                        .onFailure { logE("Immediate quota block callback failed for UDP download", it) }
                                }
                            }
                        } catch (error: Exception) {
                            if (runningMux.get() && running && policyEpoch == expectedPolicyEpoch) {
                                logE("SOCKS_UDP_RESPONSE_RELAY_FAILED uid=${binding.uid ?: "UNRESOLVED"} remote=${binding.remote.address.hostAddress}:${binding.remote.port}", error)
                            }
                            synchronized(lock) {
                                bindings.remove(binding.flow.logicalKey)
                                closeBindingLocked(binding)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // association teardown
            } finally {
                synchronized(lock) {
                    bindings.values.toList().forEach(::closeBindingLocked)
                    bindings.clear()
                }
            }
        }
    }

    private enum class SockErrKind { EXPECTED_CLOSE, NETWORK_EVENT, UNKNOWN }

    private fun classifySocketError(t: Throwable): SockErrKind = when {
        t is java.util.concurrent.CancellationException -> SockErrKind.EXPECTED_CLOSE
        t is java.nio.channels.ClosedChannelException -> SockErrKind.EXPECTED_CLOSE
        t is SocketException -> {
            val message = t.message?.lowercase() ?: ""
            when {
                message.contains("socket closed") -> SockErrKind.EXPECTED_CLOSE
                message.contains("connection refused") ||
                    message.contains("connection reset") ||
                    message.contains("broken pipe") ||
                    message.contains("network is unreachable") ||
                    message.contains("no route to host") -> SockErrKind.NETWORK_EVENT
                else -> SockErrKind.UNKNOWN
            }
        }
        t is SocketTimeoutException -> SockErrKind.NETWORK_EVENT
        else -> SockErrKind.UNKNOWN
    }

    private fun replyFailure(out: OutputStream, code: Int) {
        runCatching {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        }
    }

    private fun randomToken(bytes: Int = 18): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.encodeToString(
            data,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}
