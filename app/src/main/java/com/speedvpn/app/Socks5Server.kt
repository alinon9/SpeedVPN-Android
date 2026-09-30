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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
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
) {
    companion object {
        private const val MAX_SESSIONS = 24
        private const val MAX_WORKERS = 96
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val UDP_BUFFER_SIZE = 65_535
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
    private val probeSourcePorts = ConcurrentHashMap.newKeySet<Int>()
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
                try {
                    pool.execute {
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
        pool.shutdownNow()
        runCatching { pool.awaitTermination(3, TimeUnit.SECONDS) }
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
            input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0
        } catch (_: Exception) {
            false
        } finally {
            sourcePort?.let(probeSourcePorts::remove)
            runCatching { socket.close() }
        }
    }

    private fun track(socket: Socket): Socket = socket.also { openTcp.add(it) }
    private fun untrack(socket: Socket) { openTcp.remove(socket) }
    private fun track(socket: DatagramSocket): DatagramSocket = socket.also { openUdp.add(it) }
    private fun untrack(socket: DatagramSocket) { openUdp.remove(socket) }

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
        } catch (_: Exception) {
            // Connection teardown.
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

    private fun resolveAll(host: String): List<InetAddress> {
        val network = currentNetwork()
        return try {
            (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList()
        } catch (e: Exception) {
            throw UnknownHostException("DNS failed for $host: ${e.message}")
        }
    }

    private fun connect(client: Socket, cin: InputStream, cout: OutputStream, target: Any, port: Int) {
        val candidates = when (target) {
            is InetAddress -> listOf(target)
            is String -> resolveAll(target)
            else -> emptyList()
        }.sortedBy { if (it.address.size == 4) 0 else 1 }

        if (candidates.isEmpty()) {
            replyFailure(cout, 4)
            client.close()
            return
        }

        var upstream: Socket? = null
        for (addr in candidates) {
            val socket = Socket()
            track(socket)
            if (!protectTcp(socket)) {
                untrack(socket)
                runCatching { socket.close() }
                client.close()
                return
            }
            try {
                socket.connect(InetSocketAddress(addr, port), 10_000)
                socket.tcpNoDelay = true
                socket.sendBufferSize = 32 * 1024
                socket.receiveBufferSize = 32 * 1024
                upstream = socket
                break
            } catch (_: Exception) {
                untrack(socket)
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
                untrack(remote)
            }
        }

        var submitted = 0
        try {
            pool.execute {
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
            }
            submitted++
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
                bucket.recordForwarded(n)
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
        val relay = track(DatagramSocket(0, InetAddress.getByName("127.0.0.1")))
        val outSock = track(DatagramSocket())
        if (!protectUdp(outSock)) {
            runCatching { relay.close() }
            runCatching { outSock.close() }
            client.close()
            untrack(relay)
            untrack(outSock)
            return
        }

        val relayPort = relay.localPort
        cout.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (relayPort shr 8).toByte(), relayPort.toByte()))
        cout.flush()

        val clientAddr = AtomicReference<InetSocketAddress?>(null)
        val closeOnce = AtomicBoolean(false)
        val closeAll = {
            if (closeOnce.compareAndSet(false, true)) {
                runCatching { relay.close() }
                runCatching { outSock.close() }
                runCatching { client.close() }
                untrack(relay)
                untrack(outSock)
            }
        }

        try {
            pool.execute {
                val buf = ByteArray(UDP_BUFFER_SIZE)
                try {
                    while (running && SpeedLimiter.isGenerationActive(generation) && !relay.isClosed) {
                        val pkt = DatagramPacket(buf, buf.size)
                        relay.receive(pkt)
                        val sender = InetSocketAddress(pkt.address, pkt.port)
                        val first = clientAddr.compareAndSet(null, sender)
                        if (!first && clientAddr.get() != sender) continue

                        val bb = ByteBuffer.wrap(pkt.data, 0, pkt.length)
                        if (bb.remaining() < 4) continue
                        if (bb.short.toInt() != 0) continue // RSV
                        if (bb.get().toInt() != 0) continue // FRAG
                        val addr = when (bb.get().toInt() and 0xff) {
                            1 -> {
                                if (bb.remaining() < 4) continue
                                ByteArray(4).also { bb.get(it) }.let(InetAddress::getByAddress)
                            }
                            4 -> {
                                if (bb.remaining() < 16) continue
                                ByteArray(16).also { bb.get(it) }.let(InetAddress::getByAddress)
                            }
                            3 -> {
                                if (!bb.hasRemaining()) continue
                                val len = bb.get().toInt() and 0xff
                                if (bb.remaining() < len) continue
                                val host = ByteArray(len).also { bb.get(it) }.toString(Charsets.UTF_8)
                                resolveAll(host).firstOrNull() ?: continue
                            }
                            else -> continue
                        }
                        if (bb.remaining() < 2) continue
                        val port = bb.short.toInt() and 0xffff
                        val payloadLen = bb.remaining()
                        if (payloadLen <= 0) continue
                        if (!SpeedLimiter.acquire(SpeedLimiter.upload, payloadLen, generation)) break
                        if (!running || !SpeedLimiter.isGenerationActive(generation)) break
                        outSock.send(DatagramPacket(pkt.data, bb.position(), payloadLen, addr, port))
                        SpeedLimiter.upload.recordForwarded(payloadLen)
                    }
                } catch (_: Exception) {
                    // teardown
                } finally {
                    closeAll()
                }
            }

            pool.execute {
                val buf = ByteArray(UDP_BUFFER_SIZE)
                try {
                    while (running && SpeedLimiter.isGenerationActive(generation) && !outSock.isClosed) {
                        val pkt = DatagramPacket(buf, buf.size)
                        outSock.receive(pkt)
                        val to = clientAddr.get() ?: continue
                        if (!SpeedLimiter.acquire(SpeedLimiter.download, pkt.length, generation)) break
                        if (!running || !SpeedLimiter.isGenerationActive(generation)) break

                        val address = pkt.address.address
                        val type = if (address.size == 4) 1 else 4
                        val header = ByteBuffer.allocate(4 + address.size + 2 + pkt.length)
                            .put(byteArrayOf(0, 0, 0, type.toByte()))
                            .put(address)
                            .putShort(pkt.port.toShort())
                            .put(pkt.data, 0, pkt.length)
                        relay.send(DatagramPacket(header.array(), header.position(), to))
                        SpeedLimiter.download.recordForwarded(pkt.length)
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
            closeAll()
        }
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
