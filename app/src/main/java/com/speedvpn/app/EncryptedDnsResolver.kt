package com.speedvpn.app

import android.net.Network
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class EncryptedDnsResolver(
    private val network: Network,
    private val protect: (Socket) -> Boolean,
    private val ipv6Enabled: Boolean,
) {
    companion object {
        private const val DOH_HOST = "cloudflare-dns.com"
        private const val TIMEOUT_MS = 3_000L
    }

    private val client = OkHttpClient.Builder()
        // Resolve only the fixed numeric DoH endpoint; never recurse into system DNS.
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))
        })
        .socketFactory(ProtectedNetworkSocketFactory(network, protect))
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun resolve(host: String): List<InetAddress> {
        require(host.isNotBlank()) { "empty host" }

        parseNumericAddress(host)?.let { address ->
            return if (ipv6Enabled || address.address.size == 4) listOf(address) else emptyList()
        }

        // Keep A and AAAA lookups within one total DNS deadline, not 3 seconds each.
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        val base = ("https://" + DOH_HOST).toHttpUrl().newBuilder()
            .addPathSegment("dns-query")
            .addQueryParameter("name", host)
            .addQueryParameter("type", "A")
            .build()
        val addresses = mutableListOf<InetAddress>()
        query(base, addresses, family = 4, deadlineNanos = deadlineNanos)

        if (ipv6Enabled && System.nanoTime() < deadlineNanos) {
            query(
                base.newBuilder().setQueryParameter("type", "AAAA").build(),
                addresses,
                family = 6,
                deadlineNanos = deadlineNanos,
            )
        }
        if (addresses.isEmpty()) throw IOException("Encrypted DNS returned no usable address for $host")
        return addresses.distinctBy { it.hostAddress }.sortedBy { if (it.address.size == 4) 0 else 1 }
    }

    private fun query(
        url: okhttp3.HttpUrl,
        out: MutableList<InetAddress>,
        family: Int,
        deadlineNanos: Long,
    ) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) throw SocketTimeoutException("Encrypted DNS deadline exceeded")
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .get()
            .build()

        client.newBuilder()
            .callTimeout(remainingMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw IOException("DoH HTTP ${response.code}")
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.optInt("Status", -1) != 0) {
                    throw IOException("DoH DNS status ${json.optInt("Status")}")
                }
                val answers = json.optJSONArray("Answer") ?: return
                for (i in 0 until answers.length()) {
                    val answer = answers.optJSONObject(i) ?: continue
                    if (answer.optInt("type", -1) != family) continue
                    val value = answer.optString("data", "")
                    val address = parseNumericAddress(value) ?: continue
                    if (address.address.size != familyBytes(family)) continue
                    if (ipv6Enabled || address.address.size == 4) out.add(address)
                }
            }
    }

    private fun familyBytes(family: Int): Int = if (family == 4) 4 else 16

    /**
     * Parse numeric literals only. InetAddress.getByName is never invoked with an
     * arbitrary DoH answer, which could otherwise trigger an unprotected DNS lookup.
     */
    private fun parseNumericAddress(value: String): InetAddress? {
        if (value.isBlank()) return null
        if (value.contains(':')) {
            if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                return null
            }
            return runCatching { InetAddress.getByName(value) }
                .getOrNull()
                ?.takeIf { it.address.size == 16 }
        }

        val octets = value.split('.')
        if (octets.size != 4) return null
        val bytes = ByteArray(4)
        for (i in octets.indices) {
            val octet = octets[i]
            if (octet.isEmpty() || octet.length > 3 || octet.any { !it.isDigit() }) return null
            val number = octet.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            bytes[i] = number.toByte()
        }
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    private class ProtectedNetworkSocketFactory(
        private val network: Network,
        private val protect: (Socket) -> Boolean,
    ) : SocketFactory() {
        override fun createSocket(): Socket {
            val socket = Socket()
            try {
                // Both calls must happen before connect(); stale networks fail closed.
                network.bindSocket(socket)
                if (!protect(socket)) throw SocketException("DNS socket protection failed")
                socket.soTimeout = TIMEOUT_MS.toInt()
                return socket
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }

        private fun connect(socket: Socket, address: InetSocketAddress, local: InetSocketAddress? = null): Socket {
            try {
                if (local != null) socket.bind(local)
                socket.connect(address, TIMEOUT_MS.toInt())
                return socket
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }

        override fun createSocket(host: String, port: Int): Socket =
            connect(createSocket(), InetSocketAddress(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            connect(createSocket(), InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            connect(createSocket(), InetSocketAddress(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            connect(createSocket(), InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))
    }
}
