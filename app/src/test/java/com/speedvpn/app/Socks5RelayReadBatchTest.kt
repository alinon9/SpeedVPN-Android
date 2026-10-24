package com.speedvpn.app

import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5RelayReadBatchTest {
    @Test
    fun healthProbeIdentityAuthenticatesWithoutQuotaDatabaseLookup() {
        val probeName = Socks5Server.healthProbeUsername("session-user")
        assertEquals("session-user#health-probe", probeName)
        assertTrue(
            Socks5Server.usernameShapeAllowed(
                suppliedUsername = probeName,
                baseUsername = "session-user",
                sessionUid = null,
                requireUidAttribution = true,
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
            ),
        )
        assertTrue(
            !Socks5Server.usernameShapeAllowed(
                suppliedUsername = "session-user#10243",
                baseUsername = "session-user",
                sessionUid = 10242,
                requireUidAttribution = true,
            ),
        )
    }

    @Test
    fun baseUsernameCannotBypassUidlessQuotaAfterProbeReservationCollision() {
        // A source-port reservation may choose the probe worker, but the base username
        // alone must not authorize an ownerless app relay while per-app quotas exist.
        assertTrue(
            !Socks5Server.usernameShapeAllowed(
                suppliedUsername = "session-user",
                baseUsername = "session-user",
                sessionUid = null,
                requireUidAttribution = true,
            ),
        )
    }

    @Test
    fun probeReservationExpiresInsteadOfMatchingLateSockets() {
        assertTrue(Socks5Server.healthProbeReservationIsValid(20_000L, 19_999L))
        assertTrue(!Socks5Server.healthProbeReservationIsValid(20_000L, 20_000L))
        assertTrue(!Socks5Server.healthProbeReservationIsValid(null, 19_999L))
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

    @Test
    fun tcpRelayChunkRecordsOnlyAdmittedBytesAfterSuccessfulWrite() {
        val payload = ByteArray(1024) { ((it * 17 + 3) and 0xff).toByte() }
        val decision = QuotaTrafficDecision(
            allowedBytes = 350,
            exhaustedPackages = setOf("com.example.app"),
        )
        val bucket = TokenBucket().apply { beginSession(7L) }
        var settledOutcome: QuotaSettlementOutcome? = null
        var settledBytes = -1
        var bytesRecordedDuringWrite = -1L

        val destination = object : java.io.ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                // Settlement and bucket accounting must happen only AFTER write completes.
                bytesRecordedDuringWrite = bucket.sessionBytes(7L)
                super.write(b, off, len)
            }
        }

        val written = writeAdmittedTcpRelayChunk(
            destination = destination,
            buffer = payload,
            requestedBytes = payload.size,
            decision = decision,
            bucket = bucket,
            generation = 7L,
            packages = setOf("com.example.app"),
            uid = 10201,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
            settleQuotaBytes = { _, _, _, dec, outcome, bytes ->
                settledOutcome = outcome
                settledBytes = bytes
                dec.copy(allowedBytes = bytes)
            },
        )

        assertEquals(0L, bytesRecordedDuringWrite)
        assertEquals(350, written)
        assertArrayEquals(payload.copyOf(350), destination.toByteArray())
        assertEquals(350L, bucket.sessionBytes(7L))
        assertEquals(350L, bucket.total.get())
        assertEquals(QuotaSettlementOutcome.TCP_WRITE_SUCCEEDED, settledOutcome)
        assertEquals(350, settledBytes)
    }

    @Test
    fun tcpRelayChunkWriteFailureDoesNotRecordLiveTrafficOrBucketBytes() {
        val payload = ByteArray(512) { 0x42 }
        val decision = QuotaTrafficDecision(allowedBytes = 512)
        val bucket = TokenBucket().apply { beginSession(9L) }
        var liveForwardedCalls = 0
        var settledOutcome: QuotaSettlementOutcome? = null
        var settledBytes = -1

        val failingDestination = object : java.io.OutputStream() {
            override fun write(b: Int) {
                throw java.net.SocketException("Broken pipe")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw java.net.SocketException("Broken pipe")
            }
        }

        assertThrows(java.net.SocketException::class.java) {
            writeAdmittedTcpRelayChunk(
                destination = failingDestination,
                buffer = payload,
                requestedBytes = payload.size,
                decision = decision,
                bucket = bucket,
                generation = 9L,
                packages = setOf("com.example.fail"),
                uid = 10202,
                direction = QuotaTrafficDirection.UPLOAD,
                hasOwner = true,
                recordForwardedBytes = { _, _, _, _ -> liveForwardedCalls++ },
                settleQuotaBytes = { _, _, _, dec, outcome, bytes ->
                    settledOutcome = outcome
                    settledBytes = bytes
                    dec
                },
            )
        }

        assertEquals(0, liveForwardedCalls)
        assertEquals(0L, bucket.sessionBytes(9L))
        assertEquals(0L, bucket.total.get())
        assertEquals(QuotaSettlementOutcome.TCP_WRITE_FAILED_CONSERVATIVE, settledOutcome)
        assertEquals(0, settledBytes)
    }

    @Test
    fun udpRelayDatagramBelowRemainingQuotaOrFailedSendRecordsZeroLiveTraffic() {
        var sendAttempts = 0
        var liveRecordedBytes = 0
        val outcomes = mutableListOf< Pair<QuotaSettlementOutcome, Int> >()

        // 1. Admitted bytes (300) < UDP payload (500): dropped before send, 0 live bytes.
        val overQuotaResult = deliverAdmittedUdpDatagramDetailed(
            payloadBytes = 500,
            decision = QuotaTrafficDecision(allowedBytes = 300, exhaustedPackages = setOf("com.example.udp")),
            packages = setOf("com.example.udp"),
            uid = 10203,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
            sendDatagram = {
                sendAttempts++
                true
            },
            recordForwardedBytes = { _, _, _, bytes -> liveRecordedBytes += bytes },
            settleQuotaBytes = { _, _, _, dec, outcome, bytes ->
                outcomes += outcome to bytes
                dec.copy(allowedBytes = 0, exhaustedPackages = emptySet())
            },
        )
        assertTrue(!overQuotaResult.delivered)
        assertEquals(0, sendAttempts)
        assertEquals(0, liveRecordedBytes)
        assertEquals(0, overQuotaResult.settledDecision.allowedBytes)

        // 2. Admitted bytes match payload (300), but channel send returns false: 0 live bytes.
        val failedSendResult = deliverAdmittedUdpDatagramDetailed(
            payloadBytes = 300,
            decision = QuotaTrafficDecision(allowedBytes = 300),
            packages = setOf("com.example.udp"),
            uid = 10203,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
            sendDatagram = {
                sendAttempts++
                false
            },
            recordForwardedBytes = { _, _, _, bytes -> liveRecordedBytes += bytes },
            settleQuotaBytes = { _, _, _, dec, outcome, bytes ->
                outcomes += outcome to bytes
                dec.copy(allowedBytes = 0)
            },
        )
        assertTrue(!failedSendResult.delivered)
        assertEquals(1, sendAttempts)
        assertEquals(0, liveRecordedBytes)

        // 3. Admitted bytes match payload (300) and channel send succeeds: 300 bytes settled.
        val deliveredResult = deliverAdmittedUdpDatagramDetailed(
            payloadBytes = 300,
            decision = QuotaTrafficDecision(allowedBytes = 300),
            packages = setOf("com.example.udp"),
            uid = 10203,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
            sendDatagram = {
                sendAttempts++
                true
            },
            recordForwardedBytes = { _, _, _, bytes -> liveRecordedBytes += bytes },
            settleQuotaBytes = { _, _, _, dec, outcome, bytes ->
                outcomes += outcome to bytes
                liveRecordedBytes += bytes
                dec
            },
        )
        assertTrue(deliveredResult.delivered)
        assertEquals(2, sendAttempts)
        assertEquals(300, liveRecordedBytes)
        assertEquals(
            listOf(
                QuotaSettlementOutcome.UDP_DROPPED_OR_SEND_FAILED to 0,
                QuotaSettlementOutcome.UDP_DROPPED_OR_SEND_FAILED to 0,
                QuotaSettlementOutcome.UDP_DELIVERED to 300,
            ),
            outcomes,
        )
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
