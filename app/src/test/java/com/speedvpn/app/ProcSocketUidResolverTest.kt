package com.speedvpn.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcSocketUidResolverTest {
    @Test
    fun resolvesExactTcp6TupleFromProcNet() {
        val directory = Files.createTempDirectory("proc-net-tcp6").toFile()
        try {
            val src = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            val dst = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
            File(directory, "tcp6").writeText(
                "  sl  local_address remote_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n" +
                    "  0: ${procHex(src)}:9C40 ${procHex(dst)}:01BB 01 00000000:00000000 00:00000000 00000000 10249 0 111\n"
            )

            assertEquals(
                10_249,
                ProcSocketUidResolver.resolve(6, src, 40_000, dst, 443, directory),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesAmbiguousTuplesInsteadOfGuessingAnOwner() {
        val directory = Files.createTempDirectory("proc-net-ambiguous").toFile()
        try {
            val src = byteArrayOf(10, 0, 0, 2)
            val dst = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
            val rows = "  sl  local_address remote_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n" +
                "  0: ${procHex(src)}:9C40 ${procHex(dst)}:01BB 01 0:0 00:0 0 10249 0 111\n" +
                "  1: ${procHex(src)}:9C40 ${procHex(dst)}:01BB 01 0:0 00:0 0 10333 0 112\n"
            File(directory, "tcp").writeText(rows)

            assertNull(ProcSocketUidResolver.resolve(6, src, 40_000, dst, 443, directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resolvesUdpWildcardSocketOnlyWhenOwnerIsUnambiguous() {
        val directory = Files.createTempDirectory("proc-net-udp").toFile()
        try {
            val src = byteArrayOf(10, 0, 0, 3)
            val dst = byteArrayOf(8, 8, 8, 8)
            File(directory, "udp").writeText(
                "  sl  local_address remote_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n" +
                    "  0: 00000000:9C41 00000000:0000 07 0:0 00:0 0 10250 0 211\n"
            )

            assertEquals(
                10_250,
                ProcSocketUidResolver.resolve(17, src, 40_001, dst, 53, directory),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun procHex(address: ByteArray): String {
        val bytes = if (address.size == 4) address.reversedArray() else ByteArray(16) { index ->
            address[(index / 4) * 4 + (3 - index % 4)]
        }
        return bytes.joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }
}
