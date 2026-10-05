package com.speedvpn.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val TEST_WINDOW_MS = 5_000L
    // Cloudflare currently requires a browser-like Referer for larger download
    // probes. 200 MB is enough to sustain the 5-second measurement window on
    // typical mobile/Wi-Fi links while staying within Cloudflare Speed Test usage.
    private const val DOWNLOAD_MAX_BYTES = 95_000_000L
    private const val UPLOAD_MAX_BYTES = 50L * 1024L * 1024L
    private const val CHUNK_BYTES = 64 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun measure(onProgress: (SpeedTestPhase) -> Unit = {}): SpeedTestResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            var download: Long? = null
            var upload: Long? = null
            var firstError: String? = null

            try {
                onProgress(SpeedTestPhase.DOWNLOAD)
                download = measureDownload()
            } catch (t: Throwable) {
                firstError = t.message ?: t.javaClass.simpleName
            }

            try {
                onProgress(SpeedTestPhase.UPLOAD)
                upload = measureUpload()
            } catch (t: Throwable) {
                if (firstError == null) firstError = t.message ?: t.javaClass.simpleName
            }

            SpeedTestResult(download, upload, System.currentTimeMillis() - startedAt, firstError)
        }

    private fun measureDownload(): Long {
        val request = Request.Builder()
            .url(DOWNLOAD_URL + "?bytes=" + DOWNLOAD_MAX_BYTES + "&cacheBust=" + System.nanoTime())
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
            .header("Referer", "https://speed.cloudflare.com/")
            .header("Accept", "*/*")
            .build()

        val started = System.nanoTime()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download HTTP " + response.code)
            val body = response.body ?: throw IOException("Download body unavailable")
            body.byteStream().use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                var total = 0L
                val deadline = started + TEST_WINDOW_MS * 1_000_000L

                while (System.nanoTime() < deadline && total < DOWNLOAD_MAX_BYTES) {
                    val remaining = DOWNLOAD_MAX_BYTES - total
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    total += read
                }

                val elapsedNanos = (System.nanoTime() - started).coerceAtLeast(1L)
                if (total <= 0L) throw IOException("No download data received")
                return bitsPerSecond(total, elapsedNanos)
            }
        }
    }

    private fun measureUpload(): Long {
        val started = System.nanoTime()
        val body = TimedUploadBody(started + TEST_WINDOW_MS * 1_000_000L)
        val request = Request.Builder()
            .url(UPLOAD_URL + "?cacheBust=" + System.nanoTime())
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
            .header("Referer", "https://speed.cloudflare.com/")
            .header("Accept", "*/*")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Upload HTTP " + response.code)
            val elapsedNanos = (System.nanoTime() - started).coerceAtLeast(1L)
            if (body.bytesWritten <= 0L) throw IOException("No upload data sent")
            return bitsPerSecond(body.bytesWritten, elapsedNanos)
        }
    }

    private fun bitsPerSecond(bytes: Long, elapsedNanos: Long): Long =
        ((bytes.toDouble() * 8.0 * 1_000_000_000.0) / elapsedNanos.toDouble())
            .coerceAtLeast(0.0)
            .toLong()

    private class TimedUploadBody(
        private val deadlineNanos: Long,
    ) : RequestBody() {
        private val mediaType = "application/octet-stream".toMediaType()
        private val chunk = ByteArray(CHUNK_BYTES) { ((it * 31 + 17) and 0xFF).toByte() }
        var bytesWritten: Long = 0L
            private set

        override fun contentType() = mediaType
        override fun contentLength() = -1L

        override fun writeTo(sink: BufferedSink) {
            while (System.nanoTime() < deadlineNanos && bytesWritten < UPLOAD_MAX_BYTES) {
                val remaining = UPLOAD_MAX_BYTES - bytesWritten
                val count = min(chunk.size.toLong(), remaining).toInt()
                sink.write(chunk, 0, count)
                bytesWritten += count
            }
            sink.flush()
        }
    }
}
