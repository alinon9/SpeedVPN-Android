package com.speedvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    /*
     * The reference APK contains Ookla's native throughput engine. Its binary
     * exposes a multi-threaded test stage, connection scaling, per-connection
     * samples, a throughput calculator, and explicit min/max stage durations.
     *
     * We keep our existing Cloudflare endpoint, but adopt the same core
     * measurement principles: parallel connections, short fixed windows,
     * sampled throughput, invalid-sample filtering, and P90 aggregation.
     */
    private const val ROUND_COUNT = 3
    private const val WORKER_COUNT = 4
    private const val PHASE_DURATION_MS = 4_000L
    private const val SAMPLE_INTERVAL_MS = 250L
    private const val MIN_SAMPLE_DURATION_MS = 150L
    private const val MIN_SAMPLE_BYTES = 32L * 1024L
    private const val MIN_VALID_SAMPLES = 3
    private const val MIN_VALID_ROUNDS = 2

    private const val WARMUP_BYTES = 100_000L
    private const val STREAM_REQUEST_BYTES = 25_000_000L
    private const val CHUNK_BYTES = 64 * 1024

    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 6L
    private const val WRITE_TIMEOUT_SECONDS = 6L
    private const val CALL_TIMEOUT_SECONDS = 8L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun measure(
        context: Context,
        onProgress: (SpeedTestPhase) -> Unit = {},
    ): SpeedTestResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            var firstError: String? = null

            val testClient = buildTestClient(context)

            val download = runCatching {
                onProgress(SpeedTestPhase.DOWNLOAD)
                measurePhaseWithRounds(testClient, isUpload = false)
            }.onFailure {
                firstError = firstError ?: (it.message ?: it.javaClass.simpleName)
            }.getOrNull()

            val upload = runCatching {
                onProgress(SpeedTestPhase.UPLOAD)
                measurePhaseWithRounds(testClient, isUpload = true)
            }.onFailure {
                firstError = firstError ?: (it.message ?: it.javaClass.simpleName)
            }.getOrNull()

            SpeedTestResult(
                downloadBps = download,
                uploadBps = upload,
                durationMs = System.currentTimeMillis() - startedAt,
                error = firstError,
            )
        }

    private suspend fun measurePhaseWithRounds(
        client: OkHttpClient,
        isUpload: Boolean,
    ): Long {
        runCatching { warmup(client, isUpload) }

        val allSamples = ArrayList<Long>()
        var validRounds = 0
        var lastFailure: Throwable? = null

        repeat(ROUND_COUNT) {
            val phaseSamples = runCatching {
                measurePhase(
                    client = client,
                    isUpload = isUpload,
                )
            }.onFailure { lastFailure = it }.getOrNull()

            if (phaseSamples != null) {
                validRounds++
                // The warm-up request already primes the connection. Keep the
                // first measured window so fast uploads cannot be left with too few
                // samples after a short-lived request completes.
                allSamples += phaseSamples
            }
        }

        if (validRounds < MIN_VALID_ROUNDS) {
            throw IOException(
                lastFailure?.message ?: "Not enough valid speed rounds",
            )
        }

        if (allSamples.size < MIN_VALID_SAMPLES) {
            throw IOException("Not enough valid throughput samples")
        }

        return percentile(allSamples, 0.90)
    }

    private fun warmup(
        client: OkHttpClient,
        isUpload: Boolean,
    ) {
        if (isUpload) {
            val body = FixedUploadBody(WARMUP_BYTES, AtomicLong())
            val request = buildUploadRequest(WARMUP_BYTES, body)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Upload warm-up HTTP " + response.code)
                }
            }
        } else {
            val request = buildDownloadRequest(WARMUP_BYTES)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download warm-up HTTP " + response.code)
                }
                val body = response.body
                    ?: throw IOException("Download warm-up body unavailable")
                body.byteStream().use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var readTotal = 0L
                    while (readTotal < WARMUP_BYTES) {
                        val count = input.read(
                            buffer,
                            0,
                            min(
                                buffer.size.toLong(),
                                WARMUP_BYTES - readTotal,
                            ).toInt(),
                        )
                        if (count <= 0) break
                        readTotal += count
                    }
                }
            }
        }
    }

    private suspend fun measurePhase(
        client: OkHttpClient,
        isUpload: Boolean,
    ): ArrayList<Long> = coroutineScope {
        val totalBytes = AtomicLong(0L)
        val stop = AtomicBoolean(false)
        val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())
        val phaseStartNanos = System.nanoTime()
        val deadlineNanos = phaseStartNanos +
            TimeUnit.MILLISECONDS.toNanos(PHASE_DURATION_MS)

        val workers = (0 until WORKER_COUNT).map {
            launch(Dispatchers.IO) {
                while (
                    isActive &&
                    !stop.get() &&
                    System.nanoTime() < deadlineNanos
                ) {
                    try {
                        if (isUpload) {
                            runUploadStream(
                                client = client,
                                sizeBytes = STREAM_REQUEST_BYTES,
                                totalBytes = totalBytes,
                                activeCalls = activeCalls,
                            )
                        } else {
                            runDownloadStream(
                                client = client,
                                sizeBytes = STREAM_REQUEST_BYTES,
                                totalBytes = totalBytes,
                                activeCalls = activeCalls,
                            )
                        }
                    } catch (_: Throwable) {
                        if (!isActive || stop.get()) break
                        delay(120L)
                    }
                }
            }
        }

        val samples = Collections.synchronizedList(ArrayList<Long>())
        val sampler = launch(Dispatchers.Default) {
            var previousBytes = 0L
            var previousAt = phaseStartNanos

            while (isActive && !stop.get()) {
                delay(SAMPLE_INTERVAL_MS)

                val now = System.nanoTime()
                val elapsedNanos = now - previousAt
                val bytes = totalBytes.get()
                val deltaBytes = bytes - previousBytes

                if (
                    elapsedNanos >= TimeUnit.MILLISECONDS.toNanos(
                        MIN_SAMPLE_DURATION_MS,
                    ) &&
                    deltaBytes >= MIN_SAMPLE_BYTES
                ) {
                    samples.add(bitsPerSecond(deltaBytes, elapsedNanos))
                }

                previousAt = now
                previousBytes = bytes

                if (now >= deadlineNanos) break
            }
        }

        val stopper = launch(Dispatchers.Default) {
            val remainingMillis = (
                deadlineNanos - System.nanoTime()
            ).coerceAtLeast(0L) / 1_000_000L

            delay(remainingMillis)

            stop.set(true)
            synchronized(activeCalls) {
                activeCalls.toList().forEach { call ->
                    call.cancel()
                }
            }
        }

        workers.joinAll()
        stopper.join()
        sampler.cancel()
        sampler.join()

        stop.set(true)
        synchronized(activeCalls) {
            activeCalls.toList().forEach { call ->
                call.cancel()
            }
            activeCalls.clear()
        }

        val elapsedNanos = (
            System.nanoTime() - phaseStartNanos
        ).coerceAtLeast(1L)

        if (
            samples.size < MIN_VALID_SAMPLES &&
            totalBytes.get() >= MIN_SAMPLE_BYTES
        ) {
            samples.add(
                bitsPerSecond(
                    totalBytes.get(),
                    elapsedNanos,
                ),
            )
        }

        val filtered = samples
            .filter { it > 0L && it <= 1_000_000_000_000L }

        if (filtered.size < MIN_VALID_SAMPLES) {
            throw IOException("Not enough valid throughput samples")
        }

        ArrayList(filtered)
    }

    private fun runDownloadStream(
        client: OkHttpClient,
        sizeBytes: Long,
        totalBytes: AtomicLong,
        activeCalls: MutableSet<Call>,
    ) {
        val request = buildDownloadRequest(sizeBytes)
        val call = client.newCall(request)
        activeCalls.add(call)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download HTTP " + response.code)
                }

                val body = response.body
                    ?: throw IOException("Download body unavailable")

                body.byteStream().use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var total = 0L

                    while (total < sizeBytes) {
                        val read = input.read(
                            buffer,
                            0,
                            min(
                                buffer.size.toLong(),
                                sizeBytes - total,
                            ).toInt(),
                        )
                        if (read <= 0) break

                        total += read
                        totalBytes.addAndGet(read.toLong())
                    }

                    if (total <= 0L) {
                        throw IOException("Download stream produced no data")
                    }
                }
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun runUploadStream(
        client: OkHttpClient,
        sizeBytes: Long,
        totalBytes: AtomicLong,
        activeCalls: MutableSet<Call>,
    ) {
        val body = FixedUploadBody(sizeBytes, totalBytes)
        val request = buildUploadRequest(sizeBytes, body)
        val call = client.newCall(request)
        activeCalls.add(call)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Upload HTTP " + response.code)
                }
            }
        } finally {
            activeCalls.remove(call)
        }
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
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 " +
                    "Chrome/131.0 Mobile Safari/537.36",
            )
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
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 " +
                    "Chrome/131.0 Mobile Safari/537.36",
            )
            .header("Referer", REFERER)
            .header("Accept", "*/*")
            .post(body)
            .build()

    private fun buildTestClient(context: Context): OkHttpClient {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val physicalNetwork = findPhysicalNetwork(connectivity)

        val dns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (physicalNetwork == null) {
                    return runCatching {
                        java.net.InetAddress.getAllByName(hostname).toList()
                    }.getOrElse {
                        throw IOException(
                            "DNS resolution failed for $hostname: " +
                                "${it.message}",
                        )
                    }
                }

                return runCatching {
                    physicalNetwork.getAllByName(hostname).toList()
                }.getOrElse {
                    throw IOException(
                        "Physical DNS resolution failed for $hostname: " +
                            "${it.message}",
                    )
                }
            }
        }

        return client.newBuilder()
            .dns(dns)
            .build()
    }

    private fun findPhysicalNetwork(connectivity: ConnectivityManager): Network? {
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

    private fun percentile(
        values: ArrayList<Long>,
        percentile: Double,
    ): Long {
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

    private fun bitsPerSecond(
        bytes: Long,
        elapsedNanos: Long,
    ): Long =
        (
            bytes.toDouble() * 8.0 * 1_000_000_000.0 /
                elapsedNanos.coerceAtLeast(1L).toDouble()
            )
            .coerceAtLeast(0.0)
            .toLong()

    private class FixedUploadBody(
        private val sizeBytes: Long,
        private val totalBytes: AtomicLong?,
    ) : RequestBody() {
        private val mediaType = "application/octet-stream".toMediaType()
        private val chunk = ByteArray(CHUNK_BYTES) {
            ((it * 31 + 17) and 0xFF).toByte()
        }

        constructor(sizeBytes: Long) : this(sizeBytes, null)

        override fun contentType() = mediaType

        override fun contentLength() = sizeBytes

        override fun writeTo(sink: BufferedSink) {
            var written = 0L

            while (written < sizeBytes) {
                val count = min(
                    chunk.size.toLong(),
                    sizeBytes - written,
                ).toInt()

                sink.write(chunk, 0, count)
                written += count
                totalBytes?.addAndGet(count.toLong())
            }

            sink.flush()
        }
    }
}
