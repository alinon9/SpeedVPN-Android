package com.speedvpn.app

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Local SOCKS5 server on 127.0.0.1. The native tun2socks engine hands every TCP/UDP flow
 * from the TUN interface to this server; we open the REAL outbound socket (protected, so it
 * bypasses the VPN) and relay bytes through the speed limiter.
 */
class Socks5Server(
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
) {
    private val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var running = true

    fun start() {
        thread(name = "socks-accept", isDaemon = true) {
            while (running) {
                val client = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
        log("SOCKS relay listening on 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()
            // Greeting
            if (input.readUnsignedByte() != 5) return client.close()
            val nMethods = input.readUnsignedByte()
            input.readFully(ByteArray(nMethods))
            out.write(byteArrayOf(5, 0)); out.flush()
            // Request
            input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte()
            val host = readAddress(input)
            val port = input.readUnsignedShort()
            when (cmd) {
                1 -> connect(client, input, out, host, port)
                3 -> udpAssociate(client, input, out)
                else -> { out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); client.close() }
            }
        } catch (e: Exception) {
            runCatching { client.close() }
        }
    }

    private fun readAddress(input: DataInputStream): InetAddress = when (input.readUnsignedByte()) {
        1 -> ByteArray(4).also { input.readFully(it) }.let { InetAddress.getByAddress(it) }
        4 -> ByteArray(16).also { input.readFully(it) }.let { InetAddress.getByAddress(it) }
        3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.let { InetAddress.getByName(String(it)) }
        else -> throw IllegalArgumentException("bad atyp")
    }

    private fun connect(client: Socket, cin: InputStream, cout: OutputStream, host: InetAddress, port: Int) {
        val remote = Socket()
        if (!protectTcp(remote)) { client.close(); return }
        try {
            remote.connect(InetSocketAddress(host, port), 10_000)
            remote.tcpNoDelay = true
        } catch (e: Exception) {
            cout.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); client.close(); remote.close(); return
        }
        cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); cout.flush()
        val rin = remote.getInputStream(); val rout = remote.getOutputStream()
        thread(isDaemon = true) { pipe(cin, rout, SpeedLimiter.upload, client, remote) }
        pipe(rin, cout, SpeedLimiter.download, client, remote)
    }

    private fun pipe(src: InputStream, dst: OutputStream, bucket: TokenBucket, a: Socket, b: Socket) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                bucket.acquire(n)
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { a.close() }; runCatching { b.close() }
        }
    }

    /** SOCKS5 UDP ASSOCIATE: relay socket on loopback <-> protected outbound socket. */
    private fun udpAssociate(client: Socket, cin: InputStream, cout: OutputStream) {
        val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val outSock = DatagramSocket()
        if (!protectUdp(outSock)) { relay.close(); outSock.close(); client.close(); return }
        val p = relay.localPort
        cout.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (p shr 8).toByte(), p.toByte())); cout.flush()

        val clientAddr = java.util.concurrent.atomic.AtomicReference<InetSocketAddress?>(null)
        val closeAll = { runCatching { relay.close() }; runCatching { outSock.close() }; runCatching { client.close() } }

        // phone apps -> internet
        thread(isDaemon = true) {
            val buf = ByteArray(65535)
            try {
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    relay.receive(pkt)
                    clientAddr.set(InetSocketAddress(pkt.address, pkt.port))
                    val bb = ByteBuffer.wrap(pkt.data, 0, pkt.length)
                    bb.short; if (bb.get().toInt() != 0) continue // fragmented: drop
                    val addr = when (bb.get().toInt()) {
                        1 -> ByteArray(4).also { bb.get(it) }.let { InetAddress.getByAddress(it) }
                        4 -> ByteArray(16).also { bb.get(it) }.let { InetAddress.getByAddress(it) }
                        3 -> ByteArray(bb.get().toInt() and 0xff).also { bb.get(it) }.let { InetAddress.getByName(String(it)) }
                        else -> continue
                    }
                    val port = bb.short.toInt() and 0xffff
                    val len = bb.remaining()
                    SpeedLimiter.upload.acquire(len)
                    outSock.send(DatagramPacket(pkt.data, bb.position(), len, addr, port))
                }
            } catch (_: Exception) { closeAll() }
        }
        // internet -> phone apps
        thread(isDaemon = true) {
            val buf = ByteArray(65535)
            try {
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    outSock.receive(pkt)
                    val to = clientAddr.get() ?: continue
                    SpeedLimiter.download.acquire(pkt.length)
                    val a = pkt.address.address
                    val hdr = ByteBuffer.allocate(4 + a.size + 2 + pkt.length)
                    hdr.put(byteArrayOf(0, 0, 0, (if (a.size == 4) 1 else 4).toByte())).put(a)
                        .putShort(pkt.port.toShort()).put(pkt.data, 0, pkt.length)
                    relay.send(DatagramPacket(hdr.array(), hdr.position(), to))
                }
            } catch (_: Exception) { closeAll() }
        }
        // The association lives as long as the TCP control connection.
        try { while (cin.read() >= 0) { } } catch (_: Exception) { }
        closeAll()
    }
}
