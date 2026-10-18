package com.speedvpn.app

import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5RelayReadBatchTest {
    @Test
    fun healthProbeCanAuthenticateWithoutUidWhenQuotaRequiresAttribution() {
        assertTrue(
            Socks5Server.usernameShapeAllowed(
                suppliedUsername = "session-user",
                baseUsername = "session-user",
                sessionUid = null,
                requireUidAttribution = true,
                isHealthProbe = true,
            ),
        )
    }

    @Test
    fun ordinaryUidlessSessionIsRejectedWhenQuotaRequiresAttribution() {
        assertTrue(
            !Socks5Server.usernameShapeAllowed(
                suppliedUsername = "session-user",
                baseUsername = "session-user",
                sessionUid = null,
                requireUidAttribution = true,
                isHealthProbe = false,
            ),
        )
    }

    @Test
    fun uidTaggedSessionMustMatchTheExactUidSuffix() {
        assertTrue(
            Socks5Server.usernameShapeAllowed(
                suppliedUsername = "session-user#10242",
                baseUsername = "session-user",
                sessionUid = 10242,
                requireUidAttribution = true,
                isHealthProbe = false,
            ),
        )
        assertTrue(
            !Socks5Server.usernameShapeAllowed(
                suppliedUsername = "session-user#10243",
                baseUsername = "session-user",
                sessionUid = 10242,
                requireUidAttribution = true,
                isHealthProbe = false,
            ),
        )
    }

    @Test
    fun lowRatePlanBoundsTcpRelayReadToOneKiB() {
        val bytesPerSecond = SpeedLimiter.kbpsToBytesPerSec(
            SpeedLimiter.kilobytesPerSecondToKbps(10L),
        )
        val bucket = TokenBucket().apply { setRate(bytesPerSecond) }
        val payload = ByteArray(8 * 1024) { (it % 251).toByte() }
        val source = SingleReadInputStream(payload, maxReadBytes = payload.size)
        val buffer = ByteArray(256 * 1024)

        val batch = readRelayBatch(source, buffer, bucket.recommendedReadBytes())

        assertEquals(10_000L, bytesPerSecond)
        assertEquals(1024, batch.bytesRead)
        assertEquals(7 * 1024, source.remainingBytes())
    }

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
