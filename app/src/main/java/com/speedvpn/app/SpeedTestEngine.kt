package com.speedvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Dns
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
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val REFERER = "https://speed.cloudflare.com/"
    private val RAMP_UP_SIZES = longArrayOf(100_000L, 1_000_000L, 10_000_000L, 25_000_000L)
    private const val MIN_PROBE_DURATION_MS = 200L
    private const val FINISH_PROBE_DURATION_MS = 1_500L
    private const val CHUNK_BYTES = 64 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun measure(
        context: Context,
        onProgress: (SpeedTestPhase) -> Unit = {},
    ): SpeedTestResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            var download: Long? = null
            var upload: Long? = null
            var firstError: String? = null

            val testClient = buildTestClient(context)

            try {
                onProgress(SpeedTestPhase.DOWNLOAD)
                download = measureDownload(testClient)
            } catch (t: Throwable) {
                firstError = t.message ?: t.javaClass.simpleName
            }

            try {
                onProgress(SpeedTestPhase.UPLOAD)
                upload = measureUpload(testClient)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t.message ?: t.javaClass.simpleName
            }

            SpeedTestResult(download, upload, System.currentTimeMillis() - startedAt, firstError)
        }

    private data class Measurement(val speedBps: Long, val durationMs: Long, val bytes: Long, val sizeBytes: Long)
    private sealed class MeasurementOutcome {
        data class Success(val measurement: Measurement) : MeasurementOutcome()
        data class HttpError(val code: Int) : MeasurementOutcome()
        data class IoError(val message: String) : MeasurementOutcome()
    }

    private fun measureDownload(client: OkHttpClient): Long {
        var lastSpeed: Long? = null

        for (sizeBytes in RAMP_UP_SIZES) {
            val outcome = runCatching { downloadProbe(client, sizeBytes) }.getOrNull()
                ?: continue

            lastSpeed = outcome.first

            // 197 only: stop ramp-up once a request is long enough to be useful.
            if (outcome.second >= FINISH_PROBE_DURATION_MS) break
        }

        return lastSpeed ?: throw IOException("No valid download measurement")
    }

    private fun downloadProbe(client: OkHttpClient, sizeBytes: Long): Pair<Long, Long> {
        val request = Request.Builder()
            .url(DOWNLOAD_URL + "?bytes=" + sizeBytes + "&cacheBust=" + System.nanoTime())
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("Accept-Encoding", "identity")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
            )
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .build()

        val started = System.nanoTime()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download HTTP " + response.code)
            val body = response.body ?: throw IOException("Download body unavailable")

            body.byteStream().use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                var total = 0L

                while (total < sizeBytes) {
                    val remaining = sizeBytes - total
                    val read = input.read(
                        buffer,
                        0,
                        min(buffer.size.toLong(), remaining).toInt(),
                    )
                    if (read <= 0) break
                    total += read
                }

                val elapsed = (System.nanoTime() - started).coerceAtLeast(1L)
                if (total <= 0L || total < sizeBytes) {
                    throw IOException("Download incomplete: $total/$sizeBytes bytes")
                }

                return bitsPerSecond(total, elapsed) to (elapsed / 1_000_000L)
            }
        }
    }

    private fun measureUpload(client: OkHttpClient): Long {
        var lastSpeed: Long? = null

        for (sizeBytes in RAMP_UP_SIZES) {
            val outcome = runCatching { uploadProbe(client, sizeBytes) }.getOrNull()
                ?: continue

            lastSpeed = outcome.first

            // 197 only: stop ramp-up once a request is long enough to be useful.
            if (outcome.second >= FINISH_PROBE_DURATION_MS) break
        }

        return lastSpeed ?: throw IOException("No valid upload measurement")
    }

    private fun uploadProbe(client: OkHttpClient, sizeBytes: Long): Pair<Long, Long> {
        val body = FixedUploadBody(sizeBytes)
        val request = Request.Builder()
            .url(UPLOAD_URL + "?bytes=" + sizeBytes + "&cacheBust=" + System.nanoTime())
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
            )
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Upload HTTP " + response.code)

            val elapsed = body.transferDurationNanos.coerceAtLeast(1L)
            return bitsPerSecond(sizeBytes, elapsed) to (elapsed / 1_000_000L)
        }
    }

    /**
     * Resolve the speed-test hostname on the physical network, not through the
     * VPN TUN. The HTTP connection itself is still created by OkHttp normally,
     * so after DNS resolution its packets follow the app's current VPN routing.
     * This avoids the VPN's synthetic DNS address (198.18.0.2) breaking the
     * speed test while keeping the measured data path inside the VPN.
     */
    private fun buildTestClient(context: Context): OkHttpClient {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val physicalNetwork = findPhysicalNetwork(connectivity)

        val dns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (physicalNetwork == null) {
                    return runCatching { java.net.InetAddress.getAllByName(hostname).toList() }
                        .getOrElse { throw IOException("DNS resolution failed for $hostname: ${it.message}") }
                }

                return runCatching {
                    physicalNetwork.getAllByName(hostname).toList()
                }.getOrElse {
                    throw IOException("Physical DNS resolution failed for $hostname: ${it.message}")
                }
            }
        }

        return client.newBuilder()
            .dns(dns)
            .build()
    }

    private fun findPhysicalNetwork(connectivity: ConnectivityManager): Network? {
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun bitsPerSecond(bytes: Long, elapsedNanos: Long): Long =
        ((bytes.toDouble() * 8.0 * 1_000_000_000.0) / elapsedNanos.toDouble())
            .coerceAtLeast(0.0)
            .toLong()

    private class FixedUploadBody(
        private val sizeBytes: Long,
    ) : RequestBody() {
        private val mediaType = "application/octet-stream".toMediaType()
        private val chunk = ByteArray(CHUNK_BYTES) { ((it * 31 + 17) and 0xFF).toByte() }
        var transferDurationNanos: Long = 0L
            private set

        override fun contentType() = mediaType
        override fun contentLength() = sizeBytes

        override fun writeTo(sink: BufferedSink) {
            val started = System.nanoTime()
            var written = 0L

            while (written < sizeBytes) {
                val count = min(chunk.size.toLong(), sizeBytes - written).toInt()
                sink.write(chunk, 0, count)
                written += count
            }

            sink.flush()
            transferDurationNanos = (System.nanoTime() - started).coerceAtLeast(1L)
        }
    }e com.speedvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Dns
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
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val REFERER = "https://speed.cloudflare.com/"
    private val RAMP_UP_SIZES = longArrayOf(100_000L, 1_000_000L, 10_000_000L, 25_000_000L)
    private const val MIN_PROBE_DURATION_MS = 200L
    private const val FINISH_PROBE_DURATION_MS = 1_500L
    private const val CHUNK_BYTES = 64 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun measure(
        context: Context,
        onProgress: (SpeedTestPhase) -> Unit = {},
    ): SpeedTestResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            var download: Long? = null
            var upload: Long? = null
            var firstError: String? = null

            val testClient = buildTestClient(context)

            try {
                onProgress(SpeedTestPhase.DOWNLOAD)
                download = measureDownload(testClient)
            } catch (t: Throwable) {
                firstError = t.message ?: t.javaClass.simpleName
            }

            try {
                onProgress(SpeedTestPhase.UPLOAD)
                upload = measureUpload(testClient)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t.message ?: t.javaClass.simpleName
            }

            SpeedTestResult(download, upload, System.currentTimeMillis() - startedAt, firstError)
        }

    private data class Measurement(val speedBps: Long, val durationMs: Long, val bytes: Long, val sizeBytes: Long)
    private sealed class MeasurementOutcome {
        data class Success(val measurement: Measurement) : MeasurementOutcome()
        data class HttpError(val code: Int) : MeasurementOutcome()
        data class IoError(val message: String) : MeasurementOutcome()
    }

    private fun measureDownload(client: OkHttpClient): Long {
        var lastSpeed: Long? = null

        for (sizeBytes in RAMP_UP_SIZES) {
            val outcome = runCatching { downloadProbe(client, sizeBytes) }.getOrNull()
                ?: continue

            lastSpeed = outcome.first

            // 197 only: stop ramp-up once a request is long enough to be useful.
            if (outcome.second >= FINISH_PROBE_DURATION_MS) break
        }

        return lastSpeed ?: throw IOException("No valid download measurement")
    }

    private fun downloadProbe(client: OkHttpClient, sizeBytes: Long): Pair<Long, Long> {
        val request = Request.Builder()
            .url(DOWNLOAD_URL + "?bytes=" + sizeBytes + "&cacheBust=" + System.nanoTime())
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("Accept-Encoding", "identity")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
            )
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .build()

        val started = System.nanoTime()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download HTTP " + response.code)
            val body = response.body ?: throw IOException("Download body unavailable")

            body.byteStream().use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                var total = 0L

                while (total < sizeBytes) {
                    val remaining = sizeBytes - total
                    val read = input.read(
                        buffer,
                        0,
                        min(buffer.size.toLong(), remaining).toInt(),
                    )
                    if (read <= 0) break
                    total += read
                }

                val elapsed = (System.nanoTime() - started).coerceAtLeast(1L)
                if (total <= 0L || total < sizeBytes) {
                    throw IOException("Download incomplete: $total/$sizeBytes bytes")
                }

                return bitsPerSecond(total, elapsed) to (elapsed / 1_000_000L)
            }
        }
    }

    private fun measureUpload(client: OkHttpClient): Long {
        var lastSpeed: Long? = null

        for (sizeBytes in RAMP_UP_SIZES) {
            val outcome = runCatching { uploadProbe(client, sizeBytes) }.getOrNull()
                ?: continue

            lastSpeed = outcome.first

            // 197 only: stop ramp-up once a request is long enough to be useful.
            if (outcome.second >= FINISH_PROBE_DURATION_MS) break
        }

        return lastSpeed ?: throw IOException("No valid upload measurement")
    }

    private fun uploadProbe(client: OkHttpClient, sizeBytes: Long): Pair<Long, Long> {
        val body = FixedUploadBody(sizeBytes)
        val request = Request.Builder()
            .url(UPLOAD_URL + "?bytes=" + sizeBytes + "&cacheBust=" + System.nanoTime())
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
            )
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Upload HTTP " + response.code)

            val elapsed = body.transferDurationNanos.coerceAtLeast(1L)
            return bitsPerSecond(sizeBytes, elapsed) to (elapsed / 1_000_000L)
        }
    }

    /**
     * Resolve the speed-test hostname on the physical network, not through the
     * VPN TUN. The HTTP connection itself is still created by OkHttp normally,
     * so after DNS resolution its packets follow the app's current VPN routing.
     * This avoids the VPN's synthetic DNS address (198.18.0.2) breaking the
     * speed test while keeping the measured data path inside the VPN.
     */
    private fun buildTestClient(context: Context): OkHttpClient {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val physicalNetwork = findPhysicalNetwork(connectivity)

        val dns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (physicalNetwork == null) {
                    return runCatching { java.net.InetAddress.getAllByName(hostname).toList() }
                        .getOrElse { throw IOException("DNS resolution failed for $hostname: ${it.message}") }
                }

                return runCatching {
                    physicalNetwork.getAllByName(hostname).toList()
                }.getOrElse {
                    throw IOException("Physical DNS resolution failed for $hostname: ${it.message}")
                }
            }
        }

        return client.newBuilder()
            .dns(dns)
            .build()
    }

    private fun findPhysicalNetwork(connectivity: ConnectivityManager): Network? {
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun bitsPerSecond(bytes: Long, elapsedNanos: Long): Long =
        ((bytes.toDouble() * 8.0 * 1_000_000_000.0) / elapsedNanos.toDouble())
            .coerceAtLeast(0.0)
            .toLong()

    private class TimedUploadBody(
        private val sizeBytes: Long,
        private val maxDurationMs: Long,
    ) : RequestBody() {
        private val mediaType = "application/octet-stream".toMediaType()
        private val chunk = ByteArray(CHUNK_BYTES) { ((it * 31 + 17) and 0xFF).toByte() }
        var bytesWritten: Long = 0L
            private set
        var transferStartedNanos: Long = 0L
            private set
        var transferFinishedNanos: Long = 0L
            private set

        override fun contentType() = mediaType
        override fun contentLength() = -1L

        override fun writeTo(sink: BufferedSink) {
            transferStartedNanos = System.nanoTime()
            val deadline = transferStartedNanos + maxDurationMs * 1_000_000L
            while (bytesWritten < sizeBytes && System.nanoTime() < deadline) {
                val remaining = sizeBytes - bytesWritten
                val count = min(chunk.size.toLong(), remaining).toInt()
                sink.write(chunk, 0, count)
                bytesWritten += count
            }
            sink.flush()
            transferFinishedNanos = System.nanoTime()
        }
    }}
