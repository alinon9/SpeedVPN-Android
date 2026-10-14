package com.speedvpn.app

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadByteAccountingTest {
    @Test
    fun okHttpRequestBodyEndCounterMatchesServerReceivedBytesFor512KiBBody() {
        val expectedBytes = 512L * 1024L
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        server.soTimeout = 10_000
        val executor = Executors.newSingleThreadExecutor()
        val client = SpeedTestEngine.createUploadAccountingClient(
            OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
        )
        val counter = SpeedTestEngine.UploadBodyByteCounter()

        try {
            val receivedBytesFuture = executor.submit<Long> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    val headers = readHeaders(input)
                    val contentLength = Regex("(?im)^content-length\\s*:\\s*(\\d+)\\s*$")
                        .find(headers)
                        ?.groupValues
                        ?.get(1)
                        ?.toLong()
                        ?: throw AssertionError("Content-Length missing from request headers: $headers")

                    var received = 0L
                    val buffer = ByteArray(8 * 1024)
                    while (received < contentLength) {
                        val requested = minOf(buffer.size.toLong(), contentLength - received).toInt()
                        val count = input.read(buffer, 0, requested)
                        if (count < 0) {
                            throw EOFException("Server received only $received/$contentLength body bytes")
                        }
                        received += count.toLong()
                    }

                    val response = listOf(
                        "HTTP/1.1 200 OK",
                        "Content-Length: 0",
                        "Connection: close",
                        "",
                        "",
                    ).joinToString("\\r\\n")
                    socket.getOutputStream().apply {
                        write(response.toByteArray(StandardCharsets.ISO_8859_1))
                        flush()
                    }
                    received
                }
            }

            val request = Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/upload")
                .tag(SpeedTestEngine.UploadBodyByteCounter::class.java, counter)
                .post(SpeedTestEngine.FixedUploadBody(expectedBytes))
                .build()

            client.newCall(request).execute().use { response ->
                assertTrue("Local server returned HTTP ${response.code}", response.isSuccessful)
            }

            val serverReceivedBytes = receivedBytesFuture.get(10, TimeUnit.SECONDS)
            val callbackCount = counter.bytesWritten.get()
            assertEquals("OkHttp did not report the complete 512 KiB body", expectedBytes, callbackCount)
            assertEquals("Local server did not receive 512 KiB", expectedBytes, serverReceivedBytes)
            assertEquals("OkHttp requestBodyEnd and server receive counts differ", serverReceivedBytes, callbackCount)
        } finally {
            runCatching { server.close() }
            executor.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun readHeaders(input: java.io.InputStream): String {
        val headerBytes = ByteArrayOutputStream()
        val delimiter = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (matched < delimiter.size) {
            val value = input.read()
            if (value < 0) throw EOFException("Connection ended before the HTTP headers completed")
            headerBytes.write(value)
            matched = if (value == (delimiter[matched].toInt() and 0xff)) {
                matched + 1
            } else if (value == 13) {
                1
            } else {
                0
            }
        }
        return headerBytes.toString(StandardCharsets.ISO_8859_1.name())
    }
}
