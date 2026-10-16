package com.speedvpn.app

import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Socks5RelayReadBatchTest {
    @Test
    fun oneRelayIterationUsesOneBlockingReadWithoutPollingAvailable() {
        val payload = ByteArray(48 * 1024) { (it % 251).toByte() }
        val source = SingleReadInputStream(payload, maxReadBytes = 8 * 1024)
        val buffer = ByteArray(40 * 1024)

        val batch = readRelayBatch(source, buffer, buffer.size)

        assertEquals(8 * 1024, batch.bytesRead)
        assertEquals(1, batch.readCalls)
        assertEquals(0, source.availableCalls)
        assertArrayEquals(payload.copyOfRange(0, 8 * 1024), buffer.copyOf(batch.bytesRead))
        assertEquals(payload.size - batch.bytesRead, source.remainingBytes())
    }

    @Test
    fun repeatedRelayIterationsPreserveEveryByteInOrder() {
        val payload = ByteArray(48 * 1024) { ((it * 31) % 251).toByte() }
        val source = SingleReadInputStream(payload, maxReadBytes = 8 * 1024)
        val chunk = ByteArray(40 * 1024)
        val output = ByteArray(payload.size)
        var outputOffset = 0
        var readCallCount = 0

        while (outputOffset < output.size) {
            val batch = readRelayBatch(source, chunk, chunk.size)
            assertTrue("Unexpected EOF at offset $outputOffset", batch.bytesRead > 0)
            assertEquals(1, batch.readCalls)
            chunk.copyInto(output, outputOffset, 0, batch.bytesRead)
            outputOffset += batch.bytesRead
            readCallCount += batch.readCalls
        }

        assertArrayEquals(payload, output)
        assertEquals(payload.size / (8 * 1024), readCallCount)
        assertEquals(0, source.remainingBytes())
    }

    @Test
    fun endOfStreamIsReportedWithoutCountingAReadCall() {
        val source = SingleReadInputStream(byteArrayOf(), maxReadBytes = 8 * 1024)
        val batch = readRelayBatch(source, ByteArray(8 * 1024), 8 * 1024)
        assertEquals(-1, batch.bytesRead)
        assertEquals(0, batch.readCalls)
        assertEquals(0, source.availableCalls)
    }

    @Test
    fun uploadBrokenPipeKeepsReverseResponseFlowAliveForActiveSession() {
        assertTrue(
            shouldPreserveReverseFlowAfterUploadHalfClose(
                isUploadDirection = true,
                sessionActive = true,
                destinationWriteBrokenPipe = true,
                halfCloseFailed = false,
            ),
        )
    }

    @Test
    fun uploadHalfCloseFailureKeepsReverseResponseFlowAliveForActiveSession() {
        assertTrue(
            shouldPreserveReverseFlowAfterUploadHalfClose(
                isUploadDirection = true,
                sessionActive = true,
                destinationWriteBrokenPipe = false,
                halfCloseFailed = true,
            ),
        )
    }

    @Test
    fun downloadWriteFailureDoesNotKeepReverseFlowAlive() {
        assertTrue(
            !shouldPreserveReverseFlowAfterUploadHalfClose(
                isUploadDirection = false,
                sessionActive = true,
                destinationWriteBrokenPipe = true,
                halfCloseFailed = true,
            ),
        )
    }

    @Test
    fun inactiveVpnSessionDoesNotKeepReverseFlowAlive() {
        assertTrue(
            !shouldPreserveReverseFlowAfterUploadHalfClose(
                isUploadDirection = true,
                sessionActive = false,
                destinationWriteBrokenPipe = true,
                halfCloseFailed = true,
            ),
        )
    }

    @Test
    fun oversizedRelayReadIsRejected() {
        val source = SingleReadInputStream(byteArrayOf(1), maxReadBytes = 1)
        assertThrows(IllegalArgumentException::class.java) {
            readRelayBatch(source, ByteArray(8), 9)
        }
    }

    @Test
    fun failedTcpProtectionClosesSocketImmediately() {
        val socket = TrackingSocket()
        val failures = mutableListOf<String>()

        val protected = protectTcpSocketOrClose(
            socket = socket,
            protect = { false },
            description = "unit test TCP",
            onFailure = { message, _ -> failures += message },
        )

        assertFalse(protected)
        assertTrue(socket.isClosed)
        assertEquals(1, socket.closeCalls)
        assertTrue(failures.single().contains("SOCKET_PROTECTION_FAILED"))
    }

    @Test
    fun failedUdpProtectionClosesSocketImmediately() {
        val socket = TrackingDatagramSocket()
        val failures = mutableListOf<String>()

        val protected = protectUdpSocketOrClose(
            socket = socket,
            protect = { false },
            description = "unit test UDP",
            onFailure = { message, _ -> failures += message },
        )

        assertFalse(protected)
        assertTrue(socket.isClosed)
        assertEquals(1, socket.closeCalls)
        assertTrue(failures.single().contains("SOCKET_PROTECTION_FAILED"))
    }

    @Test
    fun thrownProtectionCallbackAlsoClosesTcpSocket() {
        val socket = TrackingSocket()

        val protected = protectTcpSocketOrClose(
            socket = socket,
            protect = { throw IOException("synthetic protect failure") },
            description = "throwing TCP test",
            onFailure = { _, _ -> },
        )

        assertFalse(protected)
        assertTrue(socket.isClosed)
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun stopClosesActiveSocksConnectionAndReleasesListeningPortAndWorkers() {
        val relay = Socks5Server(
            generation = 7L,
            protectTcp = { false },
            protectUdp = { false },
            currentNetwork = { null },
            ipv6Enabled = true,
        )
        val port = relay.port
        val client = Socket()
        try {
            relay.start()
            client.soTimeout = 1_000
            client.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            // An idle client leaves a worker blocked while waiting for the first greeting byte.
            awaitCondition(2_000L) { relay.activeTcpSocketCount() > 0 }
            val startedAt = System.nanoTime()
            relay.stop()
            val stopElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue("Relay stop took too long: ${stopElapsedMs}ms", stopElapsedMs < 4_000L)
            assertEquals(0, relay.activeTcpSocketCount())
            assertEquals("The active client must observe the accepted socket closing", -1, client.getInputStream().read())

            val replacement = ServerSocket()
            try {
                replacement.reuseAddress = true
                replacement.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            } finally {
                replacement.close()
            }

            awaitCondition(2_000L) {
                Thread.getAllStackTraces().keys.none { thread ->
                    thread.isAlive && (thread.name == "socks-accept" || thread.name == "socks-worker")
                }
            }
        } finally {
            relay.stop()
            client.close()
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Condition was not satisfied within ${timeoutMs}ms", condition())
    }

    private class TrackingSocket : Socket() {
        @Volatile var closeCalls = 0
            private set

        override fun close() {
            closeCalls++
            super.close()
        }
    }

    private class TrackingDatagramSocket : DatagramSocket(null as SocketAddress?) {
        @Volatile var closeCalls = 0
            private set

        override fun close() {
            closeCalls++
            super.close()
        }
    }

    private class SingleReadInputStream(
        private val payload: ByteArray,
        private val maxReadBytes: Int,
    ) : InputStream() {
        private var offset = 0
        var availableCalls = 0
            private set

        override fun read(): Int =
            if (offset >= payload.size) -1 else payload[offset++].toInt() and 0xff

        override fun read(target: ByteArray, start: Int, length: Int): Int {
            if (offset >= payload.size) return -1
            val count = minOf(length, maxReadBytes, payload.size - offset)
            payload.copyInto(target, start, offset, offset + count)
            offset += count
            return count
        }

        override fun available(): Int {
            availableCalls++
            throw AssertionError("Relay must not poll available() to coalesce socket reads")
        }

        fun remainingBytes(): Int = payload.size - offset
    }
}
