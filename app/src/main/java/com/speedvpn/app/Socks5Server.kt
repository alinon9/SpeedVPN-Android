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
 * Local SOCKS5 relay used by the native tun2socks engine.
 *
 * Security: a fresh random username/password is generated per VPN session and
 * passed to hev through tunnel.yml. This prevents unrelated localhost clients
 * from using the relay as an unrestricted egress proxy.
 */
class Socks5Server(
    private val generation: Long,
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val currentNetwork: () -> Network?,
    private val ipv6Enabled: Boolean,
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

        internal fun acceptsUdpAddressType(atyp: Int, ipv6Enabled: Boolean): Boolean = when (atyp) {
            1 -> true
            4 -> ipv6Enabled
            else -> false
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
    private val probeSourcePorts = ConcurrentHashMap.newKeySet<Int>()
    private val upstreamTcp = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val upstreamUdp = Collections.newSetFromMap(ConcurrentHashMap<DatagramSocket, Boolean>())
    // DNS resolution uses Android Network.getAllByName(), which is blocking.
    // Keep a small bounded pool with no waiting queue so stalled resolutions cannot
    // build an unbounded backlog or starve later lookups behind timed-out Futures.
    private val dnsPool: ExecutorService = ThreadPoolExecutor(
        0,
        UDP_DNS_WORKERS,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { r -> Thread(r, "socks-dns").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

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
                val isHealthProbe = sourcePort != null && probeSourcePorts.remove(sourcePort)

                if (!isHealthProbe && !sessions.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                track(client)
                val executor = if (isHealthProbe) probePool else pool
                try {
                    executor.execute {
                        try {
                            handle(client)
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
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            sourcePort = (socket.localSocketAddress as? InetSocketAddress)?.port
            if (sourcePort == null) return false
            probeSourcePorts.add(sourcePort)

            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            out.write(byteArrayOf(5, 1, 2))
            out.flush()
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) return false

            val ub = username.toByteArray(Charsets.UTF_8)
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
            sourcePort?.let(probeSourcePorts::remove)
            runCatching { socket.close() }
        }
    }

    private fun track(socket: Socket): Socket = socket.also { openTcp.add(it) }
    private fun untrack(socket: Socket) { openTcp.remove(socket) }
    private fun trackUpstream(socket: Socket): Socket = socket.also { upstreamTcp.add(it); openTcp.add(it) }
    private fun untrackUpstream(socket: Socket) { upstreamTcp.remove(socket); openTcp.remove(socket) }
    private fun track(socket: DatagramSocket): DatagramSocket = socket.also { openUdp.add(it) }
    private fun untrack(socket: DatagramSocket) { openUdp.remove(socket) }
    private fun trackUpstream(socket: DatagramSocket): DatagramSocket = socket.also { upstreamUdp.add(it); openUdp.add(it) }
    private fun untrackUpstream(socket: DatagramSocket) { upstreamUdp.remove(socket); openUdp.remove(socket) }

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

    private fun handle(client: Socket) {
        try {
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

            val authOk = MessageDigest.isEqual(userBytes, username.toByteArray(Charsets.UTF_8)) &&
                MessageDigest.isEqual(passBytes, password.toByteArray(Charsets.UTF_8))
            out.write(byteArrayOf(1, if (authOk) 0 else 1))
            out.flush()
            if (!authOk) return

            // Long-lived relays should not inherit the handshake timeout.
            client.soTimeout = 0

            if (input.readUnsignedByte() != 5) return
            val cmd = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) return // RSV
            val host = readAddress(input)
            val port = input.readUnsignedShort()

            when (cmd) {
                1 -> connect(client, input, out, host, port)
                3 -> udpAssociate(client, input, out)
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

    private fun resolveAll(host: String, timeoutMs: Long): List<InetAddress> {
        val network = currentNetwork()
            ?: throw UnknownHostException("No physical network available for DNS resolution: $host")
        if (timeoutMs <= 0L) throw SocketTimeoutException("DNS deadline exceeded for $host")

        val future: Future<List<InetAddress>> = dnsPool.submit<List<InetAddress>> {
            network.getAllByName(host).toList()
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
                .filter { ipv6Enabled || it is java.net.Inet4Address }
                .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                .also { if (it.isEmpty()) throw UnknownHostException("No usable DNS address for $host") }
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("DNS timeout for $host")
        } catch (e: Exception) {
            future.cancel(true)
            if (e.cause is UnknownHostException) throw e.cause as UnknownHostException
            throw UnknownHostException("DNS failed for $host: ${e.cause?.message ?: e.message}")
        }
    }

    private fun connect(client: Socket, cin: InputStream, cout: OutputStream, target: Any, port: Int) {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS)
        val candidates = when (target) {
            is InetAddress -> listOf(target)
            is String -> {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                resolveAll(target, remainingMs)
            }
            else -> emptyList()
        }
            .filter { ipv6Enabled || it is java.net.Inet4Address }
            .sortedBy { if (it.address.size == 4) 0 else 1 }

        if (candidates.isEmpty()) {
            replyFailure(cout, 4)
            client.close()
            return
        }

        var upstream: Socket? = null
        for (addr in candidates) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                .coerceAtMost(5_000L)
                .toInt()
            if (remainingMs <= 0) break

            val socket = Socket()
            trackUpstream(socket)
            try {
                val network = currentNetwork() ?: throw UnknownHostException("No physical network available")
                network.bindSocket(socket)
                if (!protectTcp(socket)) throw SocketException("VPN socket protection failed")
                socket.connect(InetSocketAddress(addr, port), remainingMs)
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
            replyFailure(cout, 5)
            client.close()
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
        other: Socket,
        remaining: AtomicInteger,
        done: CountDownLatch,
        closeBoth: () -> Unit,
    ) {
        var eof = false
        try {
            val buf = ByteArray(16 * 1024)
            while (running && SpeedLimiter.isGenerationActive(generation)) {
                val n = src.read(buf)
                if (n < 0) {
                    eof = true
                    break
                }
                if (!SpeedLimiter.acquire(bucket, n, generation)) break
                if (!running || !SpeedLimiter.isGenerationActive(generation)) break
                dst.write(buf, 0, n)
                bucket.recordForwarded(n, generation)
            }
            runCatching { dst.flush() }
        } catch (_: SocketException) {
            // peer closed
        } catch (_: Exception) {
            // connection teardown
        } finally {
            if (eof && running) {
                runCatching { destination.shutdownOutput() }.onFailure { closeBoth() }
            } else {
                closeBoth()
            }
            if (remaining.decrementAndGet() == 0) closeBoth()
            done.countDown()
        }
    }

    private fun udpAssociate(client: Socket, cin: InputStream, cout: OutputStream) {
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

            val clientAddr = AtomicReference<InetAddress?>(null)
            val udpFlows = UdpFlowTable(maxFlows = 256, ttlMs = UDP_FLOW_TTL_MS)
            val mux = UdpUpstreamMux(
                relaySocket = relaySocket,
                udpFlows = udpFlows,
                clientAddr = clientAddr,
            )
            val closeOnce = AtomicBoolean(false)
            val closeAll = {
                if (closeOnce.compareAndSet(false, true)) {
                    runCatching { mux.close() }
                    runCatching { relaySocket.close() }
                    runCatching { client.close() }
                    untrack(relaySocket)
                }
            }

            try {
                pool.execute {
                    val buf = ByteArray(UDP_BUFFER_SIZE)
                    try {
                        while (running && SpeedLimiter.isGenerationActive(generation) && !relaySocket.isClosed) {
                            val pkt = DatagramPacket(buf, buf.size)
                            relaySocket.receive(pkt)
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
                                    runCatching { resolveAll(host, UDP_DNS_TIMEOUT_MS) }
                                        .getOrElse { emptyList() }
                                }
                                else -> continue
                            }
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

                            if (!mux.send(
                                    flow = flow,
                                    address = addr,
                                    port = port,
                                    data = pkt.data,
                                    offset = bb.position(),
                                    length = payloadLen,
                                )
                            ) {
                                continue
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

                while (cin.read() >= 0 && running && SpeedLimiter.isGenerationActive(generation)) { }
            } catch (_: Exception) {
                // teardown
            } finally {
                udpFlows.clear()
                closeAll()
            }
        } catch (_: Exception) {
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
    ) : AutoCloseable {
        private val selector = Selector.open()
        private val lock = Any()
        private val bindings = HashMap<String, UdpBinding>()
        private val runningMux = AtomicBoolean(true)
        private val worker = thread(name = "socks-udp-mux", isDaemon = true) {
            runLoop()
        }

        fun send(
            flow: UdpFlow,
            address: InetAddress,
            port: Int,
            data: ByteArray,
            offset: Int,
            length: Int,
        ): Boolean {
            if (!runningMux.get() || !running || !SpeedLimiter.isGenerationActive(generation)) return false

            val remote = InetSocketAddress(address, port)
            synchronized(lock) {
                var binding = bindings[flow.logicalKey]
                    ?.takeUnless { !it.channel.isOpen }

                if (binding != null && binding.remote != remote) {
                    // Never retarget a live channel. Cancel/close the old binding first
                    // and publish a new immutable binding for the selected candidate.
                    if (bindings[flow.logicalKey] === binding) bindings.remove(flow.logicalKey)
                    closeBindingLocked(binding)
                    binding = null
                }

                if (binding == null) {
                    binding = runCatching { createBindingLocked(flow, remote) }.getOrNull()
                        ?: return false
                }

                val activeBinding = binding
                return runCatching {
                    val written = activeBinding.channel.write(ByteBuffer.wrap(data, offset, length))
                    written == length
                }.getOrElse {
                    if (bindings[flow.logicalKey] === activeBinding) bindings.remove(flow.logicalKey)
                    closeBindingLocked(activeBinding)
                    false
                }
            }
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
            runCatching { worker.join(1_000) }
            runCatching { selector.close() }
        }

        private fun createBindingLocked(flow: UdpFlow, remote: InetSocketAddress): UdpBinding {
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
                channel.configureBlocking(false)
                channel.connect(remote)
                val binding = UdpBinding(flow, channel, remote)
                synchronized(selector) {
                    selector.wakeup()
                    channel.register(selector, SelectionKey.OP_READ, binding)
                }
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
            try {
                while (runningMux.get() && running && SpeedLimiter.isGenerationActive(generation)) {
                    selector.select(500)
                    val selected = selector.selectedKeys()
                    val iterator = selected.iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        if (!key.isValid) continue

                        val binding = key.attachment() as? UdpBinding ?: continue
                        try {
                            buffer.clear()
                            val count = binding.channel.read(buffer)
                            if (count <= 0) continue
                            buffer.flip()
                            val payload = ByteArray(count)
                            buffer.get(payload)

                            if (!SpeedLimiter.acquire(SpeedLimiter.download, count, generation)) {
                                continue
                            }
                            if (!running || !SpeedLimiter.isGenerationActive(generation)) continue

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
                            if (delivered) SpeedLimiter.download.recordForwarded(count, generation)
                        } catch (_: Exception) {
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
