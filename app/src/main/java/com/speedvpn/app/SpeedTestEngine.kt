package com.speedvpn.app

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.util.Locale
import kotlin.math.max

data class SpeedTestResult(
    val mode: String,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val elapsedDownloadMs: Long?,
    val elapsedUploadMs: Long?,
    val error: String? = null,
) {
    val successful: Boolean
        get() = error == null && (downloadMbps != null || uploadMbps != null)
}

object SpeedTestEngine {
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=100000000"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val PHASE_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun run(useVpn: Boolean): SpeedTestResult {
        val mode = if (useVpn) "VPN" else "Direct"
        val relay = if (useVpn) SpeedVpnService.activeRelayAccess() else null
        if (useVpn && relay == null) {
            return SpeedTestResult(mode, null, null, 0, 0, null, null, "شغّل الـVPN أولًا وانتظر حالة Connected")
        }

        return try {
            val download = measureDownload(relay)
            val upload = measureUpload(relay)
            SpeedTestResult(
                mode = mode,
                downloadMbps = download.mbps,
                uploadMbps = upload.mbps,
                downloadBytes = download.bytes,
                uploadBytes = upload.bytes,
                elapsedDownloadMs = download.elapsedMs,
                elapsedUploadMs = upload.elapsedMs,
            )
        } catch (t: Throwable) {
            SpeedTestResult(mode, null, null, 0, 0, null, null, t.message ?: t.javaClass.simpleName)
        }
    }

    private data class Measurement(val mbps: Double, val bytes: Long, val elapsedMs: Long)

    private fun measureDownload(relay: SpeedVpnService.RelayAccess?): Measurement {
        val connection = open(DOWNLOAD_URL, relay)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val input = BufferedInputStream(connection.inputStream, BUFFER_SIZE)
            val buffer = ByteArray(BUFFER_SIZE)
            val started = System.nanoTime()
            val deadline = started + PHASE_MS * 1_000_000L
            var total = 0L
            while (System.nanoTime() < deadline) {
                val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                val read = try {
                    input.read(buffer, 0, buffer.size)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                if (read <= 0) break
                total += read
                if (remainingMs <= 0) break
            }
            val elapsedMs = max(1L, (System.nanoTime() - started) / 1_000_000L)
            Measurement(total * 8.0 / elapsedMs / 1000.0, total, elapsedMs)
        } finally {
            runCatching { connection.inputStream.close() }
            connection.disconnect()
        }
    }

    private fun measureUpload(relay: SpeedVpnService.RelayAccess?): Measurement {
        val connection = open(UPLOAD_URL, relay)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setChunkedStreamingMode(BUFFER_SIZE)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            val output: OutputStream = connection.outputStream
            val buffer = ByteArray(BUFFER_SIZE)
            val started = System.nanoTime()
            val deadline = started + PHASE_MS * 1_000_000L
            var total = 0L
            while (System.nanoTime() < deadline) {
                output.write(buffer)
                total += buffer.size
            }
            output.flush()
            runCatching { output.close() }
            runCatching { connection.responseCode }
            val elapsedMs = max(1L, (System.nanoTime() - started) / 1_000_000L)
            Measurement(total * 8.0 / elapsedMs / 1000.0, total, elapsedMs)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, relay: SpeedVpnService.RelayAccess?): HttpURLConnection {
        val proxy = relay?.let {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it.port))
        }
        val authenticatorBefore = Authenticator.getDefault()
        if (relay != null) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestingProtocol.equals("SOCKS5", true) ||
                        requestingProtocol.equals("SOCKS", true)
                    ) {
                        return PasswordAuthentication(
                            relay.username,
                            relay.password.toCharArray(),
                        )
                    }
                    return authenticatorBefore?.let { PasswordAuthentication("", CharArray(0)) }
                }
            })
        }
        return try {
            val connection = if (proxy == null) URL(url).openConnection() else URL(url).openConnection(proxy)
            connection as HttpURLConnection
        } catch (t: Throwable) {
            if (relay != null) Authenticator.setDefault(authenticatorBefore)
            throw t
        } finally {
            // The JVM authenticator is process-wide. Keep it only long enough to
            // construct the connection; SOCKS authentication happens during connect.
            if (relay != null) Authenticator.setDefault(authenticatorBefore)
        }
    }

    fun format(mbps: Double?): String =
        mbps?.let { String.format(Locale.US, "%.3f Mbps", it) } ?: "—"
}
