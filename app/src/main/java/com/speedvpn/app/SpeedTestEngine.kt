package com.speedvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class SpeedTestPhase { DOWNLOAD, UPLOAD }

data class SpeedTestResult(
    val downloadBps: Long?,
    val uploadBps: Long?,
    val durationMs: Long,
    val error: String? = null,
)

object SpeedTestEngine {
    private const val TAG = "SpeedTestEngine"
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val REFERER = "https://speed.cloudflare.com/"

    // Sequential, adaptive requests. Cloudflare public speedtest also uses
    // progressively larger request sizes instead of a fixed concurrent window.
    private val REQUEST_SIZES = longArrayOf(
        100_000L,
        250_000L,
        500_000L,
        1_000_000L,
        5_000_000L,
        10_000_000L,
        25_000_000L,
    )

    private const val WARMUP_BYTES = 100_000L
    private const val CHUNK_BYTES = 64 * 1024
    private const val MIN_SAMPLE_DURATION_MS = 10L
    private const val TARGET_STABLE_DURATION_MS = 250L
    private const val MIN_VALID_SAMPLES = 2
    private const val MIN_CAPPED_RANGE_SAMPLES = 3
    private const val MAX_RETRIES = 2

    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val READ_TIMEOUT_SECONDS = 25L
    private const val WRITE_TIMEOUT_SECONDS = 25L
    private const val CALL_TIMEOUT_SECONDS = 30L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun measure(
        context: Context,
        expectedDownloadKbps: Long? = null,
        expectedUploadKbps: Long? = null,
        onProgress: (SpeedTestPhase) -> Unit = {},
    ): SpeedTestResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            var firstError: String? = null
            val testClient = buildTestClient(context)

            val download = runCatching {
                onProgress(SpeedTestPhase.DOWNLOAD)
                measurePhase(testClient, isUpload = false, expectedKbps = expectedDownloadKbps)
            }.onFailure { error ->
                firstError = errorMessage(error)
                Log.w(TAG, "Download failed: ${errorMessage(error)}", error)
            }.getOrNull()

            val upload = runCatching {
                onProgress(SpeedTestPhase.UPLOAD)
                measurePhase(testClient, isUpload = true, expectedKbps = expectedUploadKbps)
            }.onFailure { error ->
                firstError = firstError ?: errorMessage(error)
                Log.w(TAG, "Upload failed: ${errorMessage(error)}", error)
            }.getOrNull()

            SpeedTestResult(
                downloadBps = download,
                uploadBps = upload,
                durationMs = System.currentTimeMillis() - startedAt,
                error = firstError,
            )
        }

    private fun errorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private suspend fun measurePhase(
        client: OkHttpClient,
        isUpload: Boolean,
        expectedKbps: Long?,
    ): Long {
        val requestSizes = requestSizesFor(expectedKbps)
        val warmupBytes = warmupBytesFor(expectedKbps)
        runCatching { warmup(client, isUpload, warmupBytes) }
            .onFailure { Log.i(TAG, "Warm-up failed for ${if (isUpload) "upload" else "download"}; continuing", it) }

        // A small HTTP body can overstate a rate-limited flow because the relay
        // lets the first 16 KiB chunk through immediately after an idle gap.
        // For the finite 200–1,000 Kbps plans, collect three larger samples so
        // the initial burst cannot dominate the result.
        val requiredSamples = if (expectedKbps != null && expectedKbps in 101L..1_000L) {
            MIN_CAPPED_RANGE_SAMPLES
        } else {
            MIN_VALID_SAMPLES
        }
        val samples = ArrayList<Long>()
        var lastFailure: Throwable? = null

        for (sizeBytes in requestSizes) {
            var measured: Long? = null

            for (attempt in 1..MAX_RETRIES) {
                try {
                    measured = if (isUpload) {
                        measureUploadRequest(client, sizeBytes)
                    } else {
                        measureDownloadRequest(client, sizeBytes)
                    }
                    break
                } catch (error: Throwable) {
                    lastFailure = error
                    Log.w(
                        TAG,
                        "${if (isUpload) "upload" else "download"} request ${sizeBytes}B attempt $attempt failed: ${errorMessage(error)}",
                    )
                    if (attempt < MAX_RETRIES) delay(250L * attempt)
                }
            }

            if (measured != null && measured > 0L) {
                samples += measured
                Log.d(TAG, "Sample ${if (isUpload) "upload" else "download"} ${sizeBytes}B = $measured bps")

                // Once a real request lasts long enough to amortize connection
                // overhead, do not keep issuing large transfers needlessly.
                if (samples.size >= requiredSamples &&
                    requestDurationFor(sizeBytes, measured) >= TARGET_STABLE_DURATION_MS
                ) {
                    break
                }
            }
        }

        if (samples.size < requiredSamples) {
            throw IOException(
                lastFailure?.let(::errorMessage)
                    ?: "Not enough valid throughput samples",
            )
        }

        // The public speed-test screen reports a high percentile, while a cap
        // verification must be resistant to one optimistic sample. Use the
        // median for finite plans; an uncapped baseline continues to use p90.
        return percentile(samples, if (expectedKbps != null && expectedKbps > 0L) 0.50 else 0.90)
    }

    private fun requestSizesFor(expectedKbps: Long?): LongArray =
        when {
            expectedKbps == null || expectedKbps <= 0L -> REQUEST_SIZES
            // Tiny requests can fit in one or two 16 KiB relay reads, measuring the
            // initial burst rather than sustained throughput. Use larger samples
            // while staying under the 30s HTTP call timeout at 80 Kbps (10 KB/s).
            expectedKbps <= 100L -> longArrayOf(128_000L, 192_000L, 256_000L)
            expectedKbps <= 1_000L -> longArrayOf(128_000L, 256_000L, 512_000L, 1_000_000L)
            expectedKbps <= 5_000L -> longArrayOf(64_000L, 128_000L, 256_000L, 512_000L, 1_000_000L)
            expectedKbps <= 20_000L -> longArrayOf(128_000L, 256_000L, 512_000L, 1_000_000L, 2_000_000L)
            expectedKbps <= 100_000L -> longArrayOf(256_000L, 512_000L, 1_000_000L, 2_000_000L, 5_000_000L)
            else -> REQUEST_SIZES
        }

    private fun warmupBytesFor(expectedKbps: Long?): Long =
        when {
            expectedKbps == null || expectedKbps <= 0L -> WARMUP_BYTES
            expectedKbps <= 100L -> 8_000L
            expectedKbps <= 1_000L -> 16_000L
            expectedKbps <= 5_000L -> 32_000L
            else -> WARMUP_BYTES
        }

    private fun requestDurationFor(sizeBytes: Long, bitsPerSecond: Long): Long {
        if (bitsPerSecond <= 0L) return 0L
        return (sizeBytes.toDouble() * 8_000.0 / bitsPerSecond.toDouble()).toLong()
    }

    private fun warmup(client: OkHttpClient, isUpload: Boolean, warmupBytes: Long) {
        if (isUpload) {
            val body = FixedUploadBody(warmupBytes)
            val request = buildUploadRequest(warmupBytes, body)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Upload warm-up HTTP ${response.code}")
                }
            }
        } else {
            val request = buildDownloadRequest(warmupBytes)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download warm-up HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Download warm-up body unavailable")
                body.byteStream().use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var readTotal = 0L
                    while (readTotal < warmupBytes) {
                        val count = input.read(
                            buffer,
                            0,
                            min(buffer.size.toLong(), warmupBytes - readTotal).toInt(),
                        )
                        if (count <= 0) break
                        readTotal += count
                    }
                }
            }
        }
    }

    private fun measureDownloadRequest(
        client: OkHttpClient,
        sizeBytes: Long,
    ): Long {
        val request = buildDownloadRequest(sizeBytes)
        val started = System.nanoTime()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Download body unavailable")
            body.byteStream().use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                var readTotal = 0L
                while (readTotal < sizeBytes) {
                    val count = input.read(
                        buffer,
                        0,
                        min(buffer.size.toLong(), sizeBytes - readTotal).toInt(),
                    )
                    if (count <= 0) break
                    readTotal += count
                }
                if (readTotal != sizeBytes) {
                    throw IOException("Download truncated: $readTotal/$sizeBytes bytes")
                }
            }
        }
        val elapsed = System.nanoTime() - started
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsed)
        if (elapsedMs < MIN_SAMPLE_DURATION_MS) {
            return bitsPerSecond(sizeBytes, elapsed.coerceAtLeast(1L))
        }
        return bitsPerSecond(sizeBytes, elapsed)
    }

    private fun measureUploadRequest(
        client: OkHttpClient,
        sizeBytes: Long,
    ): Long {
        val request = buildUploadRequest(sizeBytes, FixedUploadBody(sizeBytes))
        val started = System.nanoTime()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Upload HTTP ${response.code}")
            }
        }
        val elapsed = System.nanoTime() - started
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsed)
        if (elapsedMs < MIN_SAMPLE_DURATION_MS) {
            return bitsPerSecond(sizeBytes, elapsed.coerceAtLeast(1L))
        }
        return bitsPerSecond(sizeBytes, elapsed)
    }

    private fun buildDownloadRequest(sizeBytes: Long): Request =
        Request.Builder()
            .url(
                DOWNLOAD_URL +
                    "?bytes=" + sizeBytes +
                    "&cacheBust=" + System.nanoTime(),
            )
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "SpeedVPN/1.0 Android")
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .build()

    private fun buildUploadRequest(
        sizeBytes: Long,
        body: RequestBody,
    ): Request =
        Request.Builder()
            .url(
                UPLOAD_URL +
                    "?bytes=" + sizeBytes +
                    "&cacheBust=" + System.nanoTime(),
            )
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("User-Agent", "SpeedVPN/1.0 Android")
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .post(body)
            .build()

    private data class CachedDnsEntry(
        val expiresAtNanos: Long,
        val addresses: List<java.net.InetAddress>,
    )

    // Reuse a successful Cloudflare DNS answer across the consecutive requests
    // in a speed test and across the baseline/VPN halves of Verify Speed. Besides
    // avoiding needless resolver load, this prevents a transient DNS outage
    // halfway through a three-run CI check from invalidating an otherwise good
    // measurement. Entries expire quickly to tolerate address rotation.
    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, CachedDnsEntry>()
    private val dnsCacheTtlNanos = TimeUnit.SECONDS.toNanos(60)

    private fun buildTestClient(context: Context): OkHttpClient {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val activeCapabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val activeDefaultIsVpn = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val canUseSystemDnsFallback = activeCapabilities != null &&
            !activeDefaultIsVpn &&
            activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val physicalNetwork = findPhysicalNetwork(connectivity)

        val dns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val now = System.nanoTime()
                dnsCache[hostname]?.let { cached ->
                    if (now < cached.expiresAtNanos) return cached.addresses
                    dnsCache.remove(hostname, cached)
                }

                val addresses = if (physicalNetwork == null) {
                    if (!canUseSystemDnsFallback) {
                        throw IOException(
                            "No usable physical network for DNS lookup of $hostname; refusing VPN-routed DNS",
                        )
                    }
                    lookupWithRetry(hostname, "DNS") {
                        java.net.InetAddress.getAllByName(hostname).toList()
                    }
                } else {
                    try {
                        lookupWithRetry(hostname, "Physical DNS") {
                            physicalNetwork.getAllByName(hostname).toList()
                        }
                    } catch (physicalError: IOException) {
                        // The system resolver fallback is safe only while Android's
                        // default route is explicitly non-VPN. If VPN is active,
                        // never silently send a DNS request through the VPN path or
                        // accept its synthetic MapDNS response.
                        if (!canUseSystemDnsFallback) throw physicalError
                        Log.w(
                            TAG,
                            "Physical DNS failed on a non-VPN default network; retrying via Android system DNS",
                            physicalError,
                        )
                        lookupWithRetry(hostname, "Default DNS fallback") {
                            java.net.InetAddress.getAllByName(hostname).toList()
                        }
                    }
                }

                val immutableAddresses = addresses.toList()
                dnsCache[hostname] = CachedDnsEntry(
                    expiresAtNanos = System.nanoTime() + dnsCacheTtlNanos,
                    addresses = immutableAddresses,
                )
                return immutableAddresses
            }
        }

        return client.newBuilder()
            .dns(dns)
            .build()
    }

    /**
     * Retry transient Android physical-network DNS failures. Do not silently
     * switch to VPN-routed DNS, which can return synthetic MapDNS addresses.
     */
    private fun lookupWithRetry(
        hostname: String,
        resolverName: String,
        lookup: () -> List<java.net.InetAddress>,
    ): List<java.net.InetAddress> {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                val addresses = lookup()
                if (addresses.isNotEmpty()) return addresses
                lastError = IOException("$resolverName returned no addresses for $hostname")
            } catch (error: Exception) {
                lastError = error
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(200L * attempt)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("$resolverName lookup interrupted for $hostname", interrupted)
                }
            }
        }
        val prefix = if (resolverName == "Physical DNS") "Physical DNS resolution failed" else "DNS resolution failed"
        throw IOException("$prefix for $hostname after 3 attempts: ${errorMessage(lastError ?: IOException("unknown DNS error"))}", lastError)
    }

    private fun findPhysicalNetwork(connectivity: ConnectivityManager): Network? {
        // During a normal speed test the active network is already physical.
        // Prefer it rather than an arbitrary connected network, which may have
        // stale DNS settings even though another network is the default route.
        val active = connectivity.activeNetwork
        if (active != null) {
            val caps = connectivity.getNetworkCapabilities(active)
            if (caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return active
            }
        }

        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
                ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
                ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun percentile(values: ArrayList<Long>, percentile: Double): Long {
        if (values.isEmpty()) throw IOException("No speed samples")
        values.sort()
        if (values.size == 1) return values[0]
        val rank = (values.size - 1) * percentile
        val lower = rank.toInt()
        val upper = (lower + 1).coerceAtMost(values.lastIndex)
        val fraction = rank - lower
        return (
            values[lower].toDouble() +
                (values[upper] - values[lower]) * fraction
            ).toLong().coerceAtLeast(0L)
    }

    private fun bitsPerSecond(bytes: Long, elapsedNanos: Long): Long =
        (
            bytes.toDouble() * 8.0 * 1_000_000_000.0 /
                elapsedNanos.coerceAtLeast(1L).toDouble()
            ).coerceAtLeast(0.0).toLong()

    private class FixedUploadBody(
        private val sizeBytes: Long,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = sizeBytes

        private val chunk = ByteArray(CHUNK_BYTES) {
            ((it * 31 + 17) and 0xFF).toByte()
        }

        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            while (written < sizeBytes) {
                val count = min(chunk.size.toLong(), sizeBytes - written).toInt()
                sink.write(chunk, 0, count)
                written += count
            }
            sink.flush()
        }
    }
}
