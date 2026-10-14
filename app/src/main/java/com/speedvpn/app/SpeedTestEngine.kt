package com.speedvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

enum class SpeedTestPhase { DOWNLOAD, UPLOAD }

/**
 * A peer may close its upload half immediately after consuming the declared
 * Content-Length. Treat only a late Broken pipe/Connection reset during the
 * final flush as recoverable when every body byte has been emitted and no
 * bytes remain buffered. The caller must still receive a successful HTTP
 * response before it accepts this request as a measurement sample.
 */
internal fun isRecoverableCompletedUploadFlushFailure(
    writtenBytes: Long,
    expectedBytes: Long,
    bufferedBytes: Long,
    error: Throwable,
): Boolean {
    if (expectedBytes <= 0L || writtenBytes != expectedBytes || bufferedBytes != 0L) return false
    val socketError = generateSequence(error) { it.cause }
        .filterIsInstance<SocketException>()
        .firstOrNull() ?: return false
    val message = socketError.message.orEmpty()
    return message.contains("broken pipe", ignoreCase = true) ||
        message.contains("connection reset", ignoreCase = true)
}

data class SpeedTestResult(
    val downloadBps: Long?,
    val uploadBps: Long?,
    val durationMs: Long,
    val error: String? = null,
    val uploadPayloadBytesWritten: Long = 0L,
)

object SpeedTestEngine {
    private const val TAG = "SpeedTestEngine"

    private data class SampleMeasurement(val throughputBps: Long, val payloadBytesWritten: Long)
    private data class PhaseMeasurement(val throughputBps: Long, val completedPayloadBytes: Long)

    /** Receives OkHttp's completed request-body byte count, excluding HTTP headers/TLS framing. */
    internal class UploadBodyByteCounter {
        val bytesWritten = AtomicLong(0L)
    }

    private val uploadBodyEventListenerFactory: EventListener.Factory = EventListener.Factory { _ ->
        object : EventListener() {
            override fun requestBodyEnd(call: Call, byteCount: Long) {
                call.request().tag(UploadBodyByteCounter::class.java)?.let { counter ->
                    counter.bytesWritten.set(byteCount)
                    Log.d(TAG, "OkHttp requestBodyEnd method=${call.request().method} payloadBytesWritten=$byteCount")
                }
            }
        }
    }

    /** Installs the same byte-accounting callback on production and regression-test clients. */
    internal fun createUploadAccountingClient(baseClient: OkHttpClient): OkHttpClient =
        baseClient.newBuilder()
            .eventListenerFactory(uploadBodyEventListenerFactory)
            .build()
    private const val DEFAULT_TEST_BASE_URL = "https://speed.cloudflare.com"
    private val testBaseUrl = resolveTestBaseUrl(BuildConfig.SPEED_TEST_BASE_URL)
    private val DOWNLOAD_URL = endpointUrl("__down", BuildConfig.SPEED_TEST_BASE_URL)
    private val UPLOAD_URL = endpointUrl("__up", BuildConfig.SPEED_TEST_BASE_URL)
    private val REFERER = "$testBaseUrl/"

    // Progressive sample sizes avoid oversampling. High-capacity verification uses
    // parallel HTTP streams so a single TCP flow does not become the physical-baseline
    // bottleneck; baseline and VPN use the same profile.
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
    private const val CHUNK_BYTES = 256 * 1024
    private const val MIN_SAMPLE_DURATION_MS = 10L
    private const val TARGET_STABLE_DURATION_MS = 250L
    private const val MIN_VALID_SAMPLES = 2
    private const val HIGH_CAPACITY_VERIFICATION_STREAMS = 3
    private const val MIN_CAPPED_RANGE_SAMPLES = 3
    private const val BASELINE_STABLE_DURATION_MS = 250L
    internal const val FINITE_SAMPLE_TARGET_DURATION_MS = 1_500L
    // Verification uses longer payloads so connection and HTTP startup overhead do
    // not dominate a strict whole-window rate check. Public speed-test behavior stays
    // on the shorter adaptive sample target above.
    internal const val FINITE_VERIFICATION_SAMPLE_TARGET_DURATION_MS = 3_000L
    private const val MIN_FINITE_SAMPLE_BYTES = 256_000L
    private const val MAX_FINITE_SAMPLE_BYTES = 20_000_000L
    // Keep below the local fixture's 25 MB request ceiling and the public endpoint's
    // practical payload size while amortizing per-request overhead at high plan rates.
    private const val MAX_FINITE_VERIFICATION_SAMPLE_BYTES = 24_000_000L
    private const val UNLIMITED_VERIFICATION_SAMPLE_BYTES = 20_000_000L
    private const val MAX_RETRIES = 2
    // A single bad endpoint must not consume the entire 300-second E2E wait.
    // A blocking request may finish its own bounded OkHttp timeout before the next budget check.
    internal const val PHASE_TIMEOUT_MS = 115_000L
    internal const val MIN_VERIFICATION_CALL_TIMEOUT_MS = 12_000L
    internal const val MAX_VERIFICATION_CALL_TIMEOUT_MS = 70_000L

    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val READ_TIMEOUT_SECONDS = 25L
    private const val WRITE_TIMEOUT_SECONDS = 25L
    private const val CALL_TIMEOUT_SECONDS = 30L
    private const val MEASUREMENT_POOL_KEEP_ALIVE_SECONDS = 30L

    private val client: OkHttpClient by lazy {
        createUploadAccountingClient(
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build(),
        )
    }

    suspend fun measure(
        context: Context,
        expectedDownloadKbps: Long? = null,
        expectedUploadKbps: Long? = null,
        verificationProfile: Boolean = false,
        onProgress: (SpeedTestPhase) -> Unit = {},
    ): SpeedTestResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            Log.i(
                TAG,
                "Measurement begin verificationProfile=$verificationProfile " +
                    "downloadTargetKbps=${expectedDownloadKbps ?: "unlimited"} " +
                    "uploadTargetKbps=${expectedUploadKbps ?: "unlimited"}",
            )
            var firstError: String? = null
            val testClient = buildTestClient(context)

            val download = try {
                onProgress(SpeedTestPhase.DOWNLOAD)
                measurePhase(
                    testClient,
                    isUpload = false,
                    expectedKbps = expectedDownloadKbps,
                    verificationProfile = verificationProfile,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                firstError = errorMessage(error)
                Log.w(TAG, "Download failed: ${errorMessage(error)}", error)
                null
            }

            val upload = try {
                onProgress(SpeedTestPhase.UPLOAD)
                measurePhase(
                    testClient,
                    isUpload = true,
                    expectedKbps = expectedUploadKbps,
                    verificationProfile = verificationProfile,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                firstError = firstError ?: errorMessage(error)
                Log.w(TAG, "Upload failed: ${errorMessage(error)}", error)
                null
            }

            SpeedTestResult(
                downloadBps = download?.throughputBps,
                uploadBps = upload?.throughputBps,
                durationMs = System.currentTimeMillis() - startedAt,
                error = firstError,
                uploadPayloadBytesWritten = upload?.completedPayloadBytes ?: 0L,
            )
        }

    private fun errorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun executeMeasuredRequest(
        client: OkHttpClient,
        request: Request,
        stage: String,
        requestedBytes: Long,
        startedNs: Long,
    ): Response {
        try {
            val response = client.newCall(request).execute()
            val contentType = response.header("Content-Type")?.substringBefore(';') ?: "none"
            val contentLength = response.header("Content-Length") ?: "unknown"
            Log.i(
                TAG,
                "HTTP $stage response method=${request.method} host=${request.url.host} " +
                    "path=${request.url.encodedPath} requestedBytes=$requestedBytes status=${response.code} " +
                    "contentType=$contentType contentLength=$contentLength " +
                    "headersElapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)}",
            )
            return response
        } catch (error: IOException) {
            Log.w(
                TAG,
                "HTTP $stage request failed method=${request.method} host=${request.url.host} " +
                    "path=${request.url.encodedPath} requestedBytes=$requestedBytes " +
                    "elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)} " +
                    "errorType=${error.javaClass.simpleName} error=${errorMessage(error)}",
                error,
            )
            throw error
        }
    }

    internal fun downloadResponseValidationError(
        contentType: String?,
        requestedBytes: Long,
        responseBytes: Long,
    ): String? {
        if (requestedBytes <= 0L) return "Invalid requested Content-Length: $requestedBytes"
        val normalizedType = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (normalizedType != "application/octet-stream") {
            return "Unexpected Download Content-Type '${normalizedType ?: "missing"}'; expected application/octet-stream"
        }
        if (responseBytes >= 0L && responseBytes != requestedBytes) {
            return "Download Content-Length $responseBytes does not match requested $requestedBytes bytes"
        }
        return null
    }

    private fun logHttpResponseError(stage: String, response: Response, requestedBytes: Long) {
        if (!response.isSuccessful) {
            Log.w(
                TAG,
                "HTTP $stage rejected host=${response.request.url.host} path=${response.request.url.encodedPath} " +
                    "requestedBytes=$requestedBytes status=${response.code} " +
                    "contentType=${response.header("Content-Type")?.substringBefore(';') ?: "none"} " +
                    "contentLength=${response.header("Content-Length") ?: "unknown"}",
            )
        }
    }

    private suspend fun measurePhase(
        client: OkHttpClient,
        isUpload: Boolean,
        expectedKbps: Long?,
        verificationProfile: Boolean,
    ): PhaseMeasurement {
        val phaseStartedNs = System.nanoTime()
        val directionName = if (isUpload) "upload" else "download"
        val requestSizes = requestSizesFor(expectedKbps, verificationProfile)
        val warmupBytes = warmupBytesFor(expectedKbps, verificationProfile)
        Log.i(
            TAG,
            "Phase start direction=$directionName targetKbps=${expectedKbps ?: "unlimited"} " +
                "verification=$verificationProfile " +
                "requestCount=${requestSizes.size} phaseBudgetMs=$PHASE_TIMEOUT_MS",
        )
        try {
            Log.d(TAG, "Warm-up start direction=$directionName bytes=$warmupBytes")
            val warmupRemainingMs = (
                PHASE_TIMEOUT_MS -
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedNs)
                ).coerceAtLeast(1L)
            val warmupClient = clientForMeasurementRequest(
                client,
                sizeBytes = warmupBytes,
                expectedKbps = expectedKbps,
                verificationProfile = verificationProfile,
                remainingPhaseMs = warmupRemainingMs,
            )
            warmup(warmupClient, isUpload, warmupBytes)
            Log.d(TAG, "Warm-up complete direction=$directionName bytes=$warmupBytes")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.i(TAG, "Warm-up failed for ${if (isUpload) "upload" else "download"}; continuing", error)
        }

        // A small HTTP body can overstate a rate-limited flow because the relay
        // lets the first 16 KiB chunk through immediately after an idle gap.
        // For the finite 200–1,000 Kbps plans, collect three larger samples so
        // the initial burst cannot dominate the result.
        val unlimitedVerification = verificationProfile && (expectedKbps == null || expectedKbps <= 0L)
        val requiredSamples = requiredSamplesFor(expectedKbps, verificationProfile)
        // Public speed tests stop when the adaptive samples are stable enough.
        // Verification uses the same plan-shaped requests for the uncapped
        // baseline and VPN measurements, with a fixed sample count on both sides.
        val targetDurationMs = if (unlimitedVerification || (expectedKbps != null && expectedKbps > 1_000L)) {
            FINITE_SAMPLE_TARGET_DURATION_MS
        } else {
            BASELINE_STABLE_DURATION_MS
        }
        val samples = ArrayList<Long>()
        var completedSampleBytes = 0L
        var lastFailure: Throwable? = null

        // Exclude warm-up from numerator and denominator, but include all completed sample
        // payloads and real wall-clock gaps between requests to expose relay stalls.
        val measurementStartedNs = System.nanoTime()
        for ((sizeIndex, sizeBytes) in requestSizes.withIndex()) {
            var measured: SampleMeasurement? = null
            var requestElapsedMs: Long? = null

            for (attempt in 1..(if (verificationProfile) 1 else MAX_RETRIES)) {
                if (verificationProfile &&
                    phaseBudgetExceeded(phaseStartedNs, System.nanoTime(), PHASE_TIMEOUT_MS)
                ) {
                    throw IOException(
                        "$directionName measurement exceeded its ${PHASE_TIMEOUT_MS}ms total phase budget; " +
                            "validSamples=${samples.size}/$requiredSamples; " +
                            "lastError=${lastFailure?.let(::errorMessage) ?: "none"}",
                        lastFailure,
                    )
                }
                try {
                    Log.d(TAG, "Request start direction=$directionName bytes=$sizeBytes attempt=$attempt")
                    val requestStartedNs = System.nanoTime()
                    val remainingPhaseMs = if (verificationProfile) {
                        (PHASE_TIMEOUT_MS -
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedNs)
                        ).coerceAtLeast(1L)
                    } else {
                        Long.MAX_VALUE
                    }
                    measured = measureSampleRequest(
                        client = client,
                        isUpload = isUpload,
                        sizeBytes = sizeBytes,
                        expectedKbps = expectedKbps,
                        verificationProfile = verificationProfile,
                        remainingPhaseMs = remainingPhaseMs,
                    )
                    requestElapsedMs = TimeUnit.NANOSECONDS.toMillis(
                        (System.nanoTime() - requestStartedNs).coerceAtLeast(1L),
                    )
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastFailure = error
                    Log.w(
                        TAG,
                        "${if (isUpload) "upload" else "download"} request ${sizeBytes}B attempt $attempt failed: ${errorMessage(error)}",
                    )
                    if (attempt < MAX_RETRIES) delay(250L * attempt)
                }
            }

            if (measured != null && measured.throughputBps > 0L) {
                samples += measured.throughputBps
                completedSampleBytes += measured.payloadBytesWritten
                Log.d(TAG, "Sample ${if (isUpload) "upload" else "download"} requestedBytes=$sizeBytes " +
                    "payloadBytesWritten=${measured.payloadBytesWritten} rateBps=${measured.throughputBps} " +
                    "actualRequestMs=${requestElapsedMs ?: -1L}")

                // Once a real request lasts long enough to amortize connection
                // overhead, do not keep issuing large transfers needlessly.
                if (samples.size >= requiredSamples &&
                    (verificationProfile || requestDurationFor(sizeBytes, measured.throughputBps) >= targetDurationMs)
                ) {
                    break
                }
            }
            if (verificationProfile &&
                phaseBudgetExceeded(phaseStartedNs, System.nanoTime(), PHASE_TIMEOUT_MS)
            ) {
                throw IOException(
                    "$directionName measurement exceeded its ${PHASE_TIMEOUT_MS}ms total phase budget " +
                        "after request ${sizeBytes}B; validSamples=${samples.size}/$requiredSamples",
                    lastFailure,
                )
            }
            val remainingSampleCount = requestSizes.size - sizeIndex - 1
            if (measured == null &&
                !enoughRemainingSamples(samples.size, remainingSampleCount, requiredSamples)
            ) {
                throw IOException(
                    "Unable to collect enough $directionName samples: " +
                        "validSamples=${samples.size}/$requiredSamples; " +
                        "remainingSamples=$remainingSampleCount; " +
                        "lastError=${lastFailure?.let(::errorMessage) ?: "unknown"}",
                    lastFailure,
                )
            }
        }

        if (samples.size < requiredSamples) {
            throw IOException(
                lastFailure?.let(::errorMessage)
                    ?: "Not enough valid throughput samples",
            )
        }
        val measurementElapsedNs = (System.nanoTime() - measurementStartedNs).coerceAtLeast(1L)
        val sampleMedianBps = percentile(samples, 0.50)
        val sustainedBps = verificationThroughputBps(completedSampleBytes, measurementElapsedNs)
        Log.i(
            TAG,
            "Phase complete direction=$directionName validSamples=${samples.size}/$requiredSamples " +
                "payloadBytes=$completedSampleBytes measurementElapsedMs=${TimeUnit.NANOSECONDS.toMillis(measurementElapsedNs)} " +
                "sustainedBps=$sustainedBps sampleMedianBps=$sampleMedianBps " +
                "totalPhaseElapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedNs)}",
        )

        // Strict verification divides completed payload bytes by the whole sample window,
        // including inter-request delays and relay backpressure. Public UI keeps percentiles.
        val rate = if (verificationProfile) sustainedBps else
            percentile(samples, if (expectedKbps != null && expectedKbps > 0L) 0.50 else 0.90)
        return PhaseMeasurement(rate, completedSampleBytes)
    }

    internal fun endpointUrl(path: String, configuredBaseUrl: String?): String {
        require(path == "__down" || path == "__up") { "Unsupported speed-test endpoint: $path" }
        return "${resolveTestBaseUrl(configuredBaseUrl)}/$path"
    }

    internal fun createIsolatedMeasurementClient(baseClient: OkHttpClient, dns: Dns): OkHttpClient =
        baseClient.newBuilder()
            .connectionPool(ConnectionPool(5, MEASUREMENT_POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            .dns(dns)
            .build()

    private fun resolveTestBaseUrl(configuredBaseUrl: String?): String =
        configuredBaseUrl
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_TEST_BASE_URL

    internal fun requestSizesFor(expectedKbps: Long?, verificationProfile: Boolean = false): LongArray =
        when {
            verificationProfile && (expectedKbps == null || expectedKbps <= 0L) ->
                longArrayOf(
                    UNLIMITED_VERIFICATION_SAMPLE_BYTES,
                    UNLIMITED_VERIFICATION_SAMPLE_BYTES,
                    UNLIMITED_VERIFICATION_SAMPLE_BYTES,
                )
            expectedKbps == null || expectedKbps <= 0L -> REQUEST_SIZES
            // At the slowest presets, keep progressively larger bounded requests
            // so the run stays practical while still covering many relay reads.
            expectedKbps <= 100L -> longArrayOf(128_000L, 192_000L, 256_000L)
            expectedKbps <= 1_000L -> longArrayOf(128_000L, 256_000L, 512_000L, 1_000_000L)
            else -> {
                // Public samples target 1.5 seconds; strict verification uses 3 seconds
                // per sample to amortize request startup. Repeating equal payloads provides
                // three comparable transfers rather than mixing short and long samples.
                // Both profiles remain below the endpoint's practical payload ceiling.
                val targetDurationMs = if (verificationProfile) {
                    FINITE_VERIFICATION_SAMPLE_TARGET_DURATION_MS
                } else {
                    FINITE_SAMPLE_TARGET_DURATION_MS
                }
                val maxSampleBytes = if (verificationProfile) {
                    MAX_FINITE_VERIFICATION_SAMPLE_BYTES
                } else {
                    MAX_FINITE_SAMPLE_BYTES
                }
                val sampleBytes = (
                    expectedKbps.toDouble() * targetDurationMs / 8.0
                ).toLong().coerceIn(MIN_FINITE_SAMPLE_BYTES, maxSampleBytes)
                longArrayOf(sampleBytes, sampleBytes, sampleBytes)
            }
        }

    private fun warmupBytesFor(expectedKbps: Long?, verificationProfile: Boolean = false): Long =
        when {
            verificationProfile && (expectedKbps == null || expectedKbps <= 0L) -> min(
                1_000_000L,
                requestSizesFor(expectedKbps, verificationProfile).firstOrNull()?.div(4L) ?: WARMUP_BYTES,
            ).coerceAtLeast(WARMUP_BYTES)
            expectedKbps == null || expectedKbps <= 0L -> WARMUP_BYTES
            expectedKbps <= 100L -> 8_000L
            expectedKbps <= 1_000L -> 16_000L
            else -> min(
                1_000_000L,
                requestSizesFor(expectedKbps).firstOrNull()?.div(4L) ?: WARMUP_BYTES,
            ).coerceAtLeast(WARMUP_BYTES)
        }

    /**
     * Use the same aggregate throughput profile for baseline and VPN measurements.
     * A rate-limited device-wide token bucket still caps the sum of all streams.
     * Unlimited verification uses parallel streams to measure available capacity,
     * not a single-flow TCP ceiling.
     */
    internal fun sampleParallelismFor(expectedKbps: Long?, verificationProfile: Boolean): Int =
        if (
            verificationProfile &&
            (expectedKbps == null || expectedKbps <= 0L || expectedKbps >= 32_000L)
        ) {
            HIGH_CAPACITY_VERIFICATION_STREAMS
        } else {
            1
        }

    internal fun splitSampleBytes(sizeBytes: Long, requestedParallelism: Int): LongArray {
        if (sizeBytes <= 0L || requestedParallelism <= 0) return longArrayOf()
        val streamCount = min(sizeBytes, requestedParallelism.toLong()).toInt()
        val baseBytes = sizeBytes / streamCount
        val remainder = (sizeBytes % streamCount).toInt()
        return LongArray(streamCount) { index ->
            baseBytes + if (index < remainder) 1L else 0L
        }
    }

    internal fun expectedKbpsPerStream(expectedKbps: Long?, parallelism: Int): Long? {
        if (expectedKbps == null || expectedKbps <= 0L || parallelism <= 1) return expectedKbps
        val quotient = expectedKbps / parallelism
        val remainder = expectedKbps % parallelism
        return (quotient + if (remainder == 0L) 0L else 1L).coerceAtLeast(1L)
    }

    private suspend fun measureSampleRequest(
        client: OkHttpClient,
        isUpload: Boolean,
        sizeBytes: Long,
        expectedKbps: Long?,
        verificationProfile: Boolean,
        remainingPhaseMs: Long,
    ): SampleMeasurement {
        val parallelism = sampleParallelismFor(expectedKbps, verificationProfile)
        if (parallelism <= 1) {
            return if (isUpload) {
                measureUploadRequest(client, sizeBytes, expectedKbps, verificationProfile, remainingPhaseMs)
            } else {
                measureDownloadRequest(client, sizeBytes, expectedKbps, verificationProfile, remainingPhaseMs)
            }
        }

        val chunks = splitSampleBytes(sizeBytes, parallelism)
        // The limiter is device-wide: all parallel streams share the selected
        // aggregate rate. Each stream reports its completed request-body byte count.
        val perStreamTargetKbps = expectedKbpsPerStream(expectedKbps, parallelism)
        val startedNs = System.nanoTime()
        val streamMeasurements = coroutineScope {
            chunks.map { chunkBytes ->
                async(Dispatchers.IO) {
                    if (isUpload) {
                        measureUploadRequest(
                            client, chunkBytes, perStreamTargetKbps, verificationProfile, remainingPhaseMs,
                        )
                    } else {
                        measureDownloadRequest(
                            client, chunkBytes, perStreamTargetKbps, verificationProfile, remainingPhaseMs,
                        )
                    }
                }
            }.awaitAll()
        }
        val elapsedNs = (System.nanoTime() - startedNs).coerceAtLeast(1L)
        val aggregateRate = bitsPerSecond(sizeBytes, elapsedNs)
        val payloadBytesWritten = streamMeasurements.sumOf { it.payloadBytesWritten }
        Log.i(
            TAG,
            "Aggregate sample direction=${if (isUpload) "upload" else "download"} " +
                "payloadBytesWritten=$payloadBytesWritten streams=${chunks.size} " +
                "elapsedMs=${TimeUnit.NANOSECONDS.toMillis(elapsedNs)} " +
                "aggregateBps=$aggregateRate perStreamBps=${streamMeasurements.joinToString(",") { it.throughputBps.toString() }}",
        )
        return SampleMeasurement(aggregateRate, payloadBytesWritten)
    }

    internal fun phaseBudgetExceeded(startedAtNanos: Long, nowNanos: Long, budgetMs: Long): Boolean =
        nowNanos - startedAtNanos >= TimeUnit.MILLISECONDS.toNanos(budgetMs)

    internal fun measurementCallTimeoutMs(
        sizeBytes: Long,
        expectedKbps: Long?,
        verificationProfile: Boolean,
    ): Long {
        if (!verificationProfile || expectedKbps == null || expectedKbps <= 0L) {
            return TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_SECONDS)
        }
        // sizeBytes * 8 / Kbps is the theoretical transfer duration in milliseconds.
        // Allow 50% scheduling/relay overhead plus 2 seconds, while bounding stalled requests.
        val targetDurationMs = sizeBytes.coerceAtLeast(1L).toDouble() * 8.0 / expectedKbps.toDouble()
        val recommendedMs = (targetDurationMs * 1.5 + 2_000.0).toLong()
        return recommendedMs.coerceIn(
            MIN_VERIFICATION_CALL_TIMEOUT_MS,
            MAX_VERIFICATION_CALL_TIMEOUT_MS,
        )
    }

    internal fun clientForMeasurementRequest(
        baseClient: OkHttpClient,
        sizeBytes: Long,
        expectedKbps: Long?,
        verificationProfile: Boolean,
        remainingPhaseMs: Long = PHASE_TIMEOUT_MS,
    ): OkHttpClient {
        if (!verificationProfile) return baseClient
        val requestTimeoutMs = measurementCallTimeoutMs(sizeBytes, expectedKbps, verificationProfile = true)
        val timeoutMs = min(requestTimeoutMs, remainingPhaseMs.coerceAtLeast(1L))
        // Read/write timeouts must scale with the same target-aware call deadline.
        // Otherwise a valid 512 KiB upload at 200 Kbps would hit the default 25-second I/O timeout.
        return baseClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    internal fun requiredSamplesFor(expectedKbps: Long?, verificationProfile: Boolean): Int {
        val unlimitedVerification = verificationProfile && (expectedKbps == null || expectedKbps <= 0L)
        return if (unlimitedVerification || (expectedKbps != null && expectedKbps > 100L)) {
            MIN_CAPPED_RANGE_SAMPLES
        } else {
            MIN_VALID_SAMPLES
        }
    }

    /**
     * Minimum payload bytes that must be forwarded by the SOCKS relay for a
     * successful result under this sampling contract. This is a lower bound:
     * if any early request fails, later samples are at least as large.
     */
    internal fun minimumRequiredSampleBytes(expectedKbps: Long?, verificationProfile: Boolean): Long {
        val sizes = requestSizesFor(expectedKbps, verificationProfile)
        val requiredSamples = requiredSamplesFor(expectedKbps, verificationProfile)
        if (sizes.size < requiredSamples) return Long.MAX_VALUE
        return sizes.take(requiredSamples).sum()
    }

    internal fun enoughRemainingSamples(
        validSamples: Int,
        remainingSampleCount: Int,
        requiredSamples: Int,
    ): Boolean = validSamples + remainingSampleCount >= requiredSamples

    internal fun requestDurationFor(sizeBytes: Long, bitsPerSecond: Long): Long {
        if (bitsPerSecond <= 0L) return 0L
        return (sizeBytes.toDouble() * 8_000.0 / bitsPerSecond.toDouble()).toLong()
    }

    private fun warmup(client: OkHttpClient, isUpload: Boolean, warmupBytes: Long) {
        if (isUpload) {
            val body = FixedUploadBody(warmupBytes)
            val request = buildUploadRequest(warmupBytes, body)
            val startedNs = System.nanoTime()
            executeMeasuredRequest(client, request, "upload-warmup", warmupBytes, startedNs).use { response ->
                logHttpResponseError("upload-warmup", response, warmupBytes)
                if (!response.isSuccessful) {
                    throw IOException("Upload warm-up HTTP ${response.code}")
                }
            }
        } else {
            val request = buildDownloadRequest(warmupBytes)
            val startedNs = System.nanoTime()
            executeMeasuredRequest(client, request, "download-warmup", warmupBytes, startedNs).use { response ->
                logHttpResponseError("download-warmup", response, warmupBytes)
                if (!response.isSuccessful) {
                    throw IOException("Download warm-up HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Download warm-up body unavailable")
                downloadResponseValidationError(body.contentType()?.toString(), warmupBytes, body.contentLength())?.let { error ->
                    throw IOException("Download warm-up invalid response: $error")
                }
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
        expectedKbps: Long?,
        verificationProfile: Boolean,
        remainingPhaseMs: Long,
    ): SampleMeasurement {
        val request = buildDownloadRequest(sizeBytes)
        val requestClient = clientForMeasurementRequest(
            client, sizeBytes, expectedKbps, verificationProfile, remainingPhaseMs,
        )
        val started = System.nanoTime()
        executeMeasuredRequest(requestClient, request, "download", sizeBytes, started).use { response ->
            logHttpResponseError("download", response, sizeBytes)
            if (!response.isSuccessful) {
                throw IOException("Download HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Download body unavailable")
            downloadResponseValidationError(body.contentType()?.toString(), sizeBytes, body.contentLength())?.let { error ->
                Log.w(TAG, "Download response rejected host=${request.url.host} path=${request.url.encodedPath}: $error")
                throw IOException(error)
            }
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
            return SampleMeasurement(bitsPerSecond(sizeBytes, elapsed.coerceAtLeast(1L)), sizeBytes)
        }
        return SampleMeasurement(bitsPerSecond(sizeBytes, elapsed), sizeBytes)
    }

    private fun measureUploadRequest(
        client: OkHttpClient,
        sizeBytes: Long,
        expectedKbps: Long?,
        verificationProfile: Boolean,
        remainingPhaseMs: Long,
    ): SampleMeasurement {
        val bodyCounter = UploadBodyByteCounter()
        val request = buildUploadRequest(sizeBytes, FixedUploadBody(sizeBytes))
            .newBuilder()
            .tag(UploadBodyByteCounter::class.java, bodyCounter)
            .build()
        val requestClient = clientForMeasurementRequest(
            client, sizeBytes, expectedKbps, verificationProfile, remainingPhaseMs,
        )
        val started = System.nanoTime()
        executeMeasuredRequest(requestClient, request, "upload", sizeBytes, started).use { response ->
            logHttpResponseError("upload", response, sizeBytes)
            if (!response.isSuccessful) {
                throw IOException("Upload HTTP ${response.code}")
            }
        }

        // OkHttp requestBodyEnd supplies the completed request-body byte count.
        // A sample is valid only when that count matches its declared payload.
        val payloadBytesWritten = bodyCounter.bytesWritten.get()
        if (payloadBytesWritten != sizeBytes) {
            throw IOException("Upload requestBodyEnd count mismatch: $payloadBytesWritten/$sizeBytes bytes")
        }

        val elapsed = System.nanoTime() - started
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsed)
        val rate = if (elapsedMs < MIN_SAMPLE_DURATION_MS) {
            bitsPerSecond(sizeBytes, elapsed.coerceAtLeast(1L))
        } else {
            bitsPerSecond(sizeBytes, elapsed)
        }
        Log.d(TAG, "Upload requestBodyEnd payloadBytesWritten=$payloadBytesWritten requestedBytes=$sizeBytes")
        return SampleMeasurement(rate, payloadBytesWritten)
    }

    internal fun buildDownloadRequest(sizeBytes: Long): Request =
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
            .header("Connection", "close")
            .build()

    internal fun buildUploadRequest(
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
            .header("Connection", "close")
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

        return createIsolatedMeasurementClient(client, dns)
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

    internal fun verificationThroughputBps(totalBytes: Long, elapsedNanos: Long): Long =
        bitsPerSecond(totalBytes.coerceAtLeast(0L), elapsedNanos)

    private fun bitsPerSecond(bytes: Long, elapsedNanos: Long): Long =
        (
            bytes.toDouble() * 8.0 * 1_000_000_000.0 /
                elapsedNanos.coerceAtLeast(1L).toDouble()
            ).coerceAtLeast(0.0).toLong()

    internal class FixedUploadBody(
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
            try {
                sink.flush()
            } catch (error: IOException) {
                if (!isRecoverableCompletedUploadFlushFailure(
                        writtenBytes = written,
                        expectedBytes = sizeBytes,
                        bufferedBytes = sink.buffer.size,
                        error = error,
                    )
                ) {
                    throw error
                }
                Log.w(
                    TAG,
                    "Upload payload fully emitted; final flush saw peer-close race; " +
                        "awaiting successful HTTP response before accepting sample",
                    error,
                )
            }
        }
    }
}
