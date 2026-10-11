package com.speedvpn.app

import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Temporary, structured diagnostics for the isolated 80 Mbps upload investigation.
 * It observes OkHttp callbacks only; it does not throttle, retry, or change socket options.
 */
internal object UploadStreamDiagnostics {
    private const val TAG = "SpeedUploadDiag"

    internal data class Tag(
        val sampleId: String,
        val requestedBytes: Long,
    )

    internal class Factory : EventListener.Factory {
        override fun create(call: Call): EventListener =
            call.request().tag(Tag::class.java)?.let(::Listener) ?: EventListener.NONE
    }

    private class Listener(private val tag: Tag) : EventListener() {
        @Volatile private var socket: Socket? = null
        @Volatile private var localPort: Int = -1
        @Volatile private var remotePort: Int = -1
        private var callStartedNs = 0L
        private var requestBodyStartedNs = 0L
        private var responseReadStartedNs = 0L
        @Volatile private var requestBytesWritten = -1L
        @Volatile private var responseBytesRead = 0L

        override fun callStart(call: Call) {
            callStartedNs = System.nanoTime()
            log("event=call_start", "requestedBytes=${tag.requestedBytes}")
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            val current = connection.socket()
            socket = current
            localPort = runCatching { current.localPort }.getOrDefault(-1)
            remotePort = runCatching { current.port }.getOrDefault(-1)
            log(
                "event=connection_acquired",
                "localPort=$localPort remotePort=$remotePort " +
                    "socketConnected=${runCatching { current.isConnected }.getOrDefault(false)}",
            )
        }

        override fun requestBodyStart(call: Call) {
            requestBodyStartedNs = System.nanoTime()
            log(
                "event=request_body_start",
                "requestedBytes=${tag.requestedBytes} localPort=$localPort remotePort=$remotePort",
            )
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            requestBytesWritten = byteCount
            val elapsedMs = elapsedMs(requestBodyStartedNs)
            log(
                "event=request_body_end",
                "requestedBytes=${tag.requestedBytes} bytesWritten=$byteCount bodyElapsedMs=$elapsedMs " +
                    "localPort=$localPort remotePort=$remotePort",
            )
        }

        override fun responseHeadersStart(call: Call) {
            responseReadStartedNs = System.nanoTime()
            log(
                "event=response_read_start",
                "localPort=$localPort remotePort=$remotePort requestBytesWritten=$requestBytesWritten",
            )
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            log(
                "event=response_headers_end",
                "status=${response.code} contentLength=${response.header("Content-Length") ?: "unknown"} " +
                    "responseReadElapsedMs=${elapsedMs(responseReadStartedNs)} " +
                    "localPort=$localPort remotePort=$remotePort",
            )
        }

        override fun responseBodyStart(call: Call) {
            log("event=response_body_start", "localPort=$localPort remotePort=$remotePort")
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBytesRead = byteCount
            log(
                "event=response_body_end",
                "bytesRead=$byteCount localPort=$localPort remotePort=$remotePort",
            )
        }

        override fun connectionReleased(call: Call, connection: Connection) {
            val current = connection.socket()
            log(
                "event=connection_released",
                "localPort=$localPort remotePort=$remotePort socketClosed=${runCatching { current.isClosed }.getOrDefault(true)} " +
                    "inputShutdown=${runCatching { current.isInputShutdown }.getOrDefault(true)} " +
                    "outputShutdown=${runCatching { current.isOutputShutdown }.getOrDefault(true)}",
            )
        }

        override fun callEnd(call: Call) {
            log(
                "event=call_end reason=normal",
                "requestedBytes=${tag.requestedBytes} bytesWritten=$requestBytesWritten " +
                    "responseBytesRead=$responseBytesRead totalElapsedMs=${elapsedMs(callStartedNs)} " +
                    "localPort=$localPort remotePort=$remotePort socketState=${socketState()}",
            )
        }

        override fun callFailed(call: Call, ioe: IOException) {
            log(
                "event=call_failed reason=${classify(ioe)}",
                "requestedBytes=${tag.requestedBytes} bytesWritten=$requestBytesWritten " +
                    "responseBytesRead=$responseBytesRead totalElapsedMs=${elapsedMs(callStartedNs)} " +
                    "localPort=$localPort remotePort=$remotePort exceptionType=${ioe.javaClass.simpleName} " +
                    "exception=${ioe.message ?: "no message"} socketState=${socketState()}",
            )
        }

        override fun canceled(call: Call) {
            log(
                "event=call_canceled reason=client_cancelled",
                "requestedBytes=${tag.requestedBytes} bytesWritten=$requestBytesWritten " +
                    "localPort=$localPort remotePort=$remotePort socketState=${socketState()}",
            )
        }

        private fun socketState(): String {
            val current = socket ?: return "unavailable"
            return "connected=${runCatching { current.isConnected }.getOrDefault(false)}," +
                "closed=${runCatching { current.isClosed }.getOrDefault(true)}," +
                "inputShutdown=${runCatching { current.isInputShutdown }.getOrDefault(true)}," +
                "outputShutdown=${runCatching { current.isOutputShutdown }.getOrDefault(true)}"
        }

        private fun classify(error: IOException): String {
            val message = error.message.orEmpty().lowercase()
            return when {
                error is SocketTimeoutException || "timeout" in message -> "timeout"
                "broken pipe" in message -> "broken_pipe"
                "connection reset" in message || "reset by peer" in message -> "server_reset_or_peer_reset"
                "unexpected end of stream" in message || "unexpected eof" in message -> "unexpected_eof"
                "closed" in message || "canceled" in message || "cancelled" in message -> "client_or_call_closed"
                else -> "io_error"
            }
        }

        private fun elapsedMs(startedNs: Long): Long =
            if (startedNs <= 0L) -1L
            else TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedNs).coerceAtLeast(0L))

        private fun log(event: String, detail: String) {
            Log.i(TAG, "sampleId=${tag.sampleId} streamId=${tag.sampleId} $event $detail")
        }
    }
}
