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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import org.json.JSONObject

class EncryptedDnsResolver(
    private val network: Network,
    private val protect: (Socket) -> Boolean,
    private val ipv6Enabled: Boolean,
    private val dnsIpv4Only: Boolean = false,
) {
    private data class DohProvider(val host: String, val ipv4: String, val jsonPath: String)

    companion object {
        internal const val DNS_TYPE_A = 1
        internal const val DNS_TYPE_AAAA = 28

        // Keep independent providers. A failure or filtering of one endpoint must not
        // make every hostname lookup fail when another encrypted resolver is reachable.
        private val DOH_PROVIDERS = listOf(
            DohProvider("cloudflare-dns.com", "1.1.1.1", "/dns-query"),
            DohProvider("dns.google", "8.8.8.8", "/resolve"),
        )
        // One hostname lookup must stay bounded even when both address families and
        // the fallback resolver are tried. Each provider attempt gets at most 1.2 s.
        private const val RESOLUTION_TIMEOUT_MS = 3_000L
        private const val PROVIDER_TIMEOUT_MS = 1_200L

        internal fun expectedDnsRecordType(family: Int): Int = when (family) {
            4 -> DNS_TYPE_A
            6 -> DNS_TYPE_AAAA
            else -> -1
        }

        internal fun parseDnsAnswerAddress(
            answerType: Int,
            family: Int,
            data: String,
            ipv6Enabled: Boolean,
        ): InetAddress? {
            val expectedType = expectedDnsRecordType(family)
            if (expectedType < 0 || answerType != expectedType) return null
            val trimmed = data.trim()
            if (trimmed.isEmpty() || !isNumericAddress(trimmed)) return null
            val expectedBytes = if (family == 4) 4 else 16
            return runCatching { InetAddress.getByName(trimmed) }
                .getOrNull()
                ?.takeIf { it.address.size == expectedBytes && (ipv6Enabled || it.address.size == 4) }
        }

        internal fun isNumericAddress(host: String): Boolean =
            host.isNotEmpty() &&
                host.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private val requestGeneration = AtomicLong(0L)
    private val activeCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    private val clients: Map<DohProvider, OkHttpClient> =
        DOH_PROVIDERS.associateWith(::createClient)

    private fun createClient(provider: DohProvider): OkHttpClient =
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    // Use the known resolver address to avoid a recursive system DNS lookup.
                    listOf(InetAddress.getByName(provider.ipv4))
            })
            .socketFactory(ProtectedNetworkSocketFactory(network, protect))
            .connectTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

    fun resolve(host: String, isStillAllowed: () -> Boolean = { true }): List<InetAddress> {
        require(host.isNotBlank()) { "empty host" }
        if (isNumericAddress(host)) {
            return listOf(InetAddress.getByName(host)).filter { ipv6Enabled || it.address.size == 4 }
        }
        ensureRequestAllowed(requestGeneration.get(), isStillAllowed)
        val generation = requestGeneration.get()
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESOLUTION_TIMEOUT_MS)
        val addresses = mutableListOf<InetAddress>()
        val errors = mutableListOf<IOException>()
        var successfulResponses = 0

        // IPv4 and IPv6 are independent lookups. A failure or empty AAAA response must
        // never discard an already usable A result. Try the secondary resolver for each
        // family if the primary errors or returns no usable records, within one shared
        // deadline so a broken resolver cannot stall page loads for many timeout periods.
        val families = if (ipv6Enabled && !dnsIpv4Only) listOf(4, 6) else listOf(4)
        for (family in families) {
            var familyHasAddress = false
            for (provider in DOH_PROVIDERS) {
                ensureRequestAllowed(generation, isStillAllowed)
                if (remainingMillis(deadlineNanos) <= 0L) break
                val providerAddresses = mutableListOf<InetAddress>()
                try {
                    query(
                        provider = provider,
                        host = host,
                        out = providerAddresses,
                        family = family,
                        generation = generation,
                        deadlineNanos = deadlineNanos,
                        isStillAllowed = isStillAllowed,
                    )
                    successfulResponses++
                    if (providerAddresses.isNotEmpty()) {
                        addresses.addAll(providerAddresses)
                        familyHasAddress = true
                        break
                    }
                } catch (error: Exception) {
                    // Cancellation/policy changes are not resolver outages; propagate them
                    // immediately rather than trying another server after the session ended.
                    ensureRequestAllowed(generation, isStillAllowed)
                    errors += IOException(
                        "DoH provider ${provider.host} failed for ${if (family == 4) "A" else "AAAA"}: ${error.message}",
                        error,
                    )
                }
            }
            // No answer for this family is acceptable if the other family has usable
            // addresses. We deliberately do not turn optional AAAA failure into total failure.
            if (!familyHasAddress) continue
        }

        ensureRequestAllowed(generation, isStillAllowed)
        if (addresses.isEmpty()) {
            val failure = if (successfulResponses == 0 && errors.isNotEmpty()) {
                IOException("All encrypted DNS providers failed for $host", errors.last())
            } else {
                IOException("Encrypted DNS returned no usable address for $host")
            }
            errors.dropLast(1).forEach(failure::addSuppressed)
            throw failure
        }
        return addresses.distinctBy { it.hostAddress }.sortedBy { if (it.address.size == 4) 0 else 1 }
    }

    private fun ensureRequestAllowed(generation: Long, isStillAllowed: () -> Boolean) {
        if (generation != requestGeneration.get() || !isStillAllowed()) {
            throw IOException("Encrypted DNS request cancelled by app policy")
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))

    /** Cancel DNS work after any app-policy change so it cannot outlive its relay session. */
    internal fun cancelPendingRequests() {
        requestGeneration.incrementAndGet()
        activeCalls.toList().forEach { call -> runCatching { call.cancel() } }
        clients.values.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    private fun query(
        provider: DohProvider,
        host: String,
        out: MutableList<InetAddress>,
        family: Int,
        generation: Long,
        deadlineNanos: Long,
        isStillAllowed: () -> Boolean,
    ) {
        ensureRequestAllowed(generation, isStillAllowed)
        val remaining = remainingMillis(deadlineNanos)
        if (remaining <= 0L) throw IOException("Encrypted DNS resolution deadline exceeded")
        val url = ("https://" + provider.host).toHttpUrl().newBuilder()
            .encodedPath(provider.jsonPath)
            .addQueryParameter("name", host)
            .addQueryParameter("type", if (family == 4) "A" else "AAAA")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .get()
            .build()
        val client = clients.getValue(provider)
        val call = client.newCall(request)
        call.timeout().timeout(minOf(remaining, PROVIDER_TIMEOUT_MS), TimeUnit.MILLISECONDS)
        activeCalls.add(call)
        try {
            ensureRequestAllowed(generation, isStillAllowed)
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("DoH HTTP " + response.code)
                val json = JSONObject(response.body?.string().orEmpty())
                ensureRequestAllowed(generation, isStillAllowed)
                if (json.optInt("Status", -1) != 0) {
                    throw IOException("DoH DNS status " + json.optInt("Status"))
                }
                val answers = json.optJSONArray("Answer") ?: return
                for (i in 0 until answers.length()) {
                    val answer = answers.optJSONObject(i) ?: continue
                    parseDnsAnswerAddress(
                        answerType = answer.optInt("type", -1),
                        family = family,
                        data = answer.optString("data", ""),
                        ipv6Enabled = ipv6Enabled,
                    )?.let(out::add)
                }
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    private class ProtectedNetworkSocketFactory(
        private val network: Network,
        private val protect: (Socket) -> Boolean,
    ) : SocketFactory() {
        override fun createSocket(): Socket {
            val socket = Socket()
            try {
                network.bindSocket(socket)
                if (!protect(socket)) throw SocketException("DNS socket protection failed")
                return socket
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }

        override fun createSocket(host: String, port: Int): Socket =
            createSocket().also { it.connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket().also {
                it.bind(InetSocketAddress(localHost, localPort))
                it.connect(InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            createSocket().also { it.connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            createSocket().also {
                it.bind(InetSocketAddress(localAddress, localPort))
                it.connect(InetSocketAddress(address, port))
            }
    }
}
