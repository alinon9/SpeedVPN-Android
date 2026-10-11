package com.speedvpn.app

import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5RelayReadBatchTest {
    @Test
    fun coalescingIsEnabledForUnlimitedAndSustainedHighRatePlansOnly() {
        assertFalse(shouldCoalesceRelayReads(100_000L)) // 800 Kbps
        assertFalse(shouldCoalesceRelayReads(2_000_000L)) // 16 Mbps
        assertTrue(shouldCoalesceRelayReads(4_000_000L)) // 32 Mbps threshold
        assertTrue(shouldCoalesceRelayReads(9_000_000L)) // 72 Mbps
        assertTrue(shouldCoalesceRelayReads(10_000_000L)) // 80 Mbps
        assertTrue(shouldCoalesceRelayReads(11_000_000L)) // 88 Mbps
        assertTrue(shouldCoalesceRelayReads(0L)) // Unlimited
    }

    @Test
    fun drainsAvailableFragmentsIntoOnePacingBatchWithoutLosingBytes() {
        val payload = ByteArray(48 * 1024) { (it % 251).toByte() }
        val source = FragmentingInputStream(payload, maxReadBytes = 8 * 1024)
        val buffer = ByteArray(40 * 1024)

        val batch = readRelayBatch(source, buffer, buffer.size, coalesceAvailable = true)

        assertEquals(buffer.size, batch.bytesRead)
        assertEquals(5, batch.readCalls)
        assertArrayEquals(payload.copyOfRange(0, buffer.size), buffer)
        assertEquals(8 * 1024, source.remainingBytes())
    }

    @Test
    fun coalescingReturnsAvailablePartialDataWithoutWaitingForFutureBytes() {
        val payload = ByteArray(12 * 1024) { (it % 239).toByte() }
        val source = FragmentingInputStream(payload, maxReadBytes = 8 * 1024)
        val buffer = ByteArray(32 * 1024)

        val batch = readRelayBatch(source, buffer, buffer.size, coalesceAvailable = true)

        assertEquals(payload.size, batch.bytesRead)
        assertEquals(2, batch.readCalls)
        assertArrayEquals(payload, buffer.copyOf(batch.bytesRead))
    }

    @Test
    fun lowRateModeKeepsSingleReadLatencyAndLeavesRemainingBytesQueued() {
        val payload = ByteArray(48 * 1024) { (it % 241).toByte() }
        val source = FragmentingInputStream(payload, maxReadBytes = 8 * 1024)
        val buffer = ByteArray(40 * 1024)

        val batch = readRelayBatch(source, buffer, buffer.size, coalesceAvailable = false)

        assertEquals(8 * 1024, batch.bytesRead)
        assertEquals(1, batch.readCalls)
        assertEquals(payload.size - batch.bytesRead, source.remainingBytes())
    }

    private class FragmentingInputStream(
        private val payload: ByteArray,
        private val maxReadBytes: Int,
    ) : InputStream() {
        private var offset = 0

        override fun read(): Int =
            if (offset >= payload.size) -1 else payload[offset++].toInt() and 0xff

        override fun read(target: ByteArray, start: Int, length: Int): Int {
            if (offset >= payload.size) return -1
            val count = minOf(length, maxReadBytes, payload.size - offset)
            payload.copyInto(target, start, offset, offset + count)
            offset += count
            return count
        }

        override fun available(): Int = payload.size - offset

        fun remainingBytes(): Int = payload.size - offset
    }
}
