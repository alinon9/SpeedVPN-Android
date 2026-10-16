package com.speedvpn.app

import android.net.Network
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun shouldQueryIpv6Dns(ipv6Enabled: Boolean, dnsIpv4Only: Boolean): Boolean =
    ipv6Enabled && !dnsIpv4Only

class EncryptedDnsResolver(
    private val network: Network,
    private val protect: (Socket) -> Boolean,
    private val ipv6Enabled: Boolean,
    private val dnsIpv4Only: Boolean = false,
) : Closeable {
    companion object {
        private const val DOH_HOST = "cloudflare-dns.com"
        private const val DOH_IP_V4 = "1.1.1.1"
        private const val TIMEOUT_MS = 3_000L
    }

    private val closed = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName(DOH_IP_V4))
        })
        .socketFactory(ProtectedNetworkSocketFactory(network, protect) { closed.get() })
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Mark closed before cancellation so a racing call cannot create another
        // untracked socket after this resolver has been detached from the service.
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    fun resolve(host: String): List<InetAddress> {
        if (closed.get()) throw IOException("Encrypted DNS resolver is closed")
        require(host.isNotBlank()) { "empty host" }
        if (isNumericAddress(host)) {
            return listOf(InetAddress.getByName(host)).filter { ipv6Enabled || it.address.size == 4 }
        }
        val base = ("https://" + DOH_HOST).toHttpUrl()
            .newBuilder()
            .addPathSegment("dns-query")
            .addQueryParameter("name", host)
            .addQueryParameter("type", "A")
            .build()
        val addresses = mutableListOf<InetAddress>()
        query(base, addresses, 4)
        if (shouldQueryIpv6Dns(ipv6Enabled, dnsIpv4Only)) {
            query(base.newBuilder().setQueryParameter("type", "AAAA").build(), addresses, 6)
        }
        if (addresses.isEmpty()) throw IOException("Encrypted DNS returned no usable address for $host")
        return addresses.distinctBy { it.hostAddress }.sortedBy { if (it.address.size == 4) 0 else 1 }
    }

    private fun query(url: okhttp3.HttpUrl, out: MutableList<InetAddress>, family: Int) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DoH HTTP " + response.code)
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.optInt("Status", -1) != 0) {
                throw IOException("DoH DNS status " + json.optInt("Status"))
            }
            val answers = json.optJSONArray("Answer") ?: return
            for (i in 0 until answers.length()) {
                val answer = answers.optJSONObject(i) ?: continue
                if (answer.optInt("type", -1) != family) continue
                val value = answer.optString("data", "")
                if (value.isBlank()) continue
                runCatching { InetAddress.getByName(value) }
                    .getOrNull()
                    ?.takeIf { ipv6Enabled || it.address.size == 4 }
                    ?.let(out::add)
            }
        }
    }

    private fun isNumericAddress(host: String): Boolean =
        host.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * Every socket returned here has already been protected and explicitly bound
     * to the physical Network. A failed protect/connect/bind closes the socket
     * before an exception escapes to OkHttp.
     */
    private class ProtectedNetworkSocketFactory(
        private val network: Network,
        private val protect: (Socket) -> Boolean,
        private val isClosed: () -> Boolean,
    ) : SocketFactory() {
        override fun createSocket(): Socket {
            if (isClosed()) throw java.net.SocketException("Encrypted DNS resolver is closed")
            val socket = Socket()
            try {
                protectSocketOrClose(socket, protect, "encrypted DNS upstream")
                network.bindSocket(socket)
                if (isClosed()) {
                    throw java.net.SocketException("Encrypted DNS resolver closed during socket setup")
                }
                return socket
            } catch (failure: Throwable) {
                try {
                    socket.close()
                } catch (closeError: Throwable) {
                    failure.addSuppressed(closeError)
                }
                throw failure
            }
        }

        override fun createSocket(host: String, port: Int): Socket {
            val remote = InetSocketAddress(host, port)
            return connectOrClose(createSocket(), remote)
        }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket {
            val remote = InetSocketAddress(host, port)
            val local = InetSocketAddress(localHost, localPort)
            return connectOrClose(createSocket(), remote, local)
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val remote = InetSocketAddress(host, port)
            return connectOrClose(createSocket(), remote)
        }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket {
            val remote = InetSocketAddress(address, port)
            val local = InetSocketAddress(localAddress, localPort)
            return connectOrClose(createSocket(), remote, local)
        }

        private fun connectOrClose(
            socket: Socket,
            remote: InetSocketAddress,
            local: InetSocketAddress? = null,
        ): Socket {
            try {
                if (local != null) socket.bind(local)
                socket.connect(remote, TIMEOUT_MS.toInt())
                return socket
            } catch (failure: Throwable) {
                try {
                    socket.close()
                } catch (closeError: Throwable) {
                    failure.addSuppressed(closeError)
                }
                throw failure
            }
        }
    }
}
