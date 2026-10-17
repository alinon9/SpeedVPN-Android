package com.speedvpn.app

import java.io.File

/**
 * Best-effort owner lookup for a socket tuple when ConnectivityManager returns INVALID_UID.
 *
 * Linux /proc/net tables expose socket UID alongside the local/remote 5-tuple. We accept a
 * result only when all matching rows agree on one UID; ambiguous reuse-port/shared sockets are
 * deliberately not guessed. This is a fallback, not a replacement for Android's owner API.
 */
internal object ProcSocketUidResolver {
    fun resolve(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
        procNetDir: File = File("/proc/net"),
    ): Int? {
        if (protocol != 6 && protocol != 17) return null
        if (srcPort !in 0..65535 || dstPort !in 0..65535) return null
        if (srcAddress.size !in setOf(4, 16) || dstAddress.size != srcAddress.size) return null

        val names = when (protocol) {
            6 -> listOf("tcp", "tcp6")
            else -> listOf("udp", "udp6")
        }
        val candidateUids = LinkedHashSet<Int>()

        names.forEach { name ->
            val file = File(procNetDir, name)
            if (!file.isFile || !file.canRead()) return@forEach
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val fields = line.trim().split(WHITESPACE)
                        if (fields.size < 8) return@forEach
                        val local = parseEndpoint(fields[1]) ?: return@forEach
                        val remote = parseEndpoint(fields[2]) ?: return@forEach
                        val uid = fields[7].toIntOrNull()?.takeIf { it >= 0 } ?: return@forEach

                        val localAddressMatches =
                            sameAddress(local.address, srcAddress) ||
                                (protocol == 17 && local.address.all { it == 0.toByte() })
                        val remoteAddressMatches =
                            sameAddress(remote.address, dstAddress) ||
                                (protocol == 17 && remote.port == 0 &&
                                    remote.address.all { it == 0.toByte() })

                        if (local.port == srcPort && localAddressMatches &&
                            (if (protocol == 6) {
                                remote.port == dstPort && sameAddress(remote.address, dstAddress)
                            } else {
                                remoteAddressMatches && (remote.port == dstPort ||
                                    (remote.port == 0 && remote.address.all { it == 0.toByte() }))
                            })
                        ) {
                            candidateUids += uid
                        }
                    }
                }
            }
        }

        return candidateUids.singleOrNull()
    }

    private data class ProcEndpoint(val address: ByteArray, val port: Int)

    private fun parseEndpoint(value: String): ProcEndpoint? {
        val colon = value.lastIndexOf(':')
        if (colon <= 0 || colon == value.lastIndex) return null
        val addressHex = value.substring(0, colon)
        val address = decodeProcAddress(addressHex) ?: return null
        val port = value.substring(colon + 1).toIntOrNull(16)?.takeIf { it in 0..65535 }
            ?: return null
        return ProcEndpoint(address, port)
    }

    private fun decodeProcAddress(hex: String): ByteArray? {
        if (hex.length != 8 && hex.length != 32) return null
        if (hex.any { it.digitToIntOrNull(16) == null }) return null
        val raw = ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return if (raw.size == 4) {
            raw.reversedArray()
        } else {
            // procfs prints each IPv6 32-bit word in host byte order.
            ByteArray(16) { index -> raw[(index / 4) * 4 + (3 - index % 4)] }
        }
    }

    private fun sameAddress(left: ByteArray, right: ByteArray): Boolean {
        if (left.contentEquals(right)) return true
        if (left.size == 4 && isIpv4MappedIpv6(right)) {
            return right.copyOfRange(12, 16).contentEquals(left)
        }
        if (right.size == 4 && isIpv4MappedIpv6(left)) {
            return left.copyOfRange(12, 16).contentEquals(right)
        }
        return false
    }

    private fun isIpv4MappedIpv6(address: ByteArray): Boolean =
        address.size == 16 &&
            address.copyOfRange(0, 10).all { it == 0.toByte() } &&
            address[10] == 0xff.toByte() && address[11] == 0xff.toByte()

    private val WHITESPACE = Regex("\\s+")
}
