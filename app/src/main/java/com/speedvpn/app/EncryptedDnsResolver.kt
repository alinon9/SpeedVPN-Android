package com.speedvpn.app

import android.net.Network
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketFactory
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class EncryptedDnsResolver(private val network: Network, private val protect: (Socket) -> Boolean, private val ipv6Enabled: Boolean) {
    companion object { private const val DOH_HOST = "cloudflare-dns.com"; private const val DOH_IP_V4 = "1.1.1.1"; private const val TIMEOUT_MS = 3_000L }
    private val client = OkHttpClient.Builder().dns(Dns { listOf(InetAddress.getByName(DOH_IP_V4)) }).socketFactory(ProtectedNetworkSocketFactory(network, protect)).connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
    fun resolve(host: String): List<InetAddress> {
        require(host.isNotBlank()) { "empty host" }
        if (isNumericAddress(host)) return listOf(InetAddress.getByName(host)).filter { ipv6Enabled || it.address.size == 4 }
        val base = "https://" + DOH_HOST).toHttpUrl().newBuilder().addPathSegment("dns-query").addQueryParameter("name", host).addQueryParameter("type", "A").build()
        val addresses = mutableListOf<InetAddress>(); query(base, addresses, 4)
        if (ipv6Enabled) query(base.newBuilder().setQueryParameter("type", "AAAA").build(), addresses, 6)
        if (addresses.isEmpty()) throw IOException("Encrypted DNS returned no usable address for $host")
        return addresses.distinctBy { it.hostAddress }.sortedBy { if (it.address.size == 4) 0 else 1 }
    }
    private fun query(url: okhttp3.HttpUrl, out: MutableList<InetAddress>, family: Int) {
        val request = Request.Builder().url(url).header("Accept", "application/dns-json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DoH HTTP " + response.code)
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.optInt("Status", -1) != 0) throw IOException("DoH DNS status " + json.optInt("Status"))
            val answers = json.optJSONArray("Answer") ?: return
            for (i in 0 until answers.length()) {
                val answer = answers.optJSONObject(i) ?: continue; if (answer.optInt("type", -1) != family) continue
                val value = answer.optString("data", ""); if (value.isBlank()) continue
                runCatching { InetAddress.getByName(value) }.getOrNull()?.takeIf { ipv6Enabled || it.address.size == 4 }?.let(out::add)
            }
        }
    }
    private fun isNumericAddress(host: String): Boolean = host.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
    private class ProtectedNetworkSocketFactory(private val network: Network, private val protect: (Socket) -> Boolean) : SocketFactory() {
        override fun createSocket(): Socket { val socket = Socket(); try { network.bindSocket(socket); if (!protect(socket)) throw SocketException("DNS socket protection failed"); return socket } catch (t: Throwable) { runCatching { socket.close() }; throw t } }
        override fun createSocket(host: String, port: Int): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket().also { it.bind(InetSocketAddress(localHost, localPort)); it.connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = createSocket().also { it.bind(InetSocketAddress(localAddress, localPort)); it.connect(InetSocketAddress(address, port)) }
    }
}