package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestEngineResponseValidationTest {
    @Test
    fun rejectsHtmlResponseEvenWhenItsLengthMatchesTheRequestedPayload() {
        val error = SpeedTestEngine.downloadResponseValidationError(
            contentType = "text/html",
            requestedBytes = 1_000_000L,
            responseBytes = 1_000_000L,
        )
        assertTrue(error?.contains("Content-Type") == true)
    }

    @Test
    fun rejectsTruncatedDownloadContentLength() {
        val error = SpeedTestEngine.downloadResponseValidationError(
            contentType = "application/octet-stream",
            requestedBytes = 1_000_000L,
            responseBytes = 999_999L,
        )
        assertTrue(error?.contains("Content-Length") == true)
    }

    @Test
    fun acceptsMatchingBinaryDownloadResponse() {
        assertEquals(
            null,
            SpeedTestEngine.downloadResponseValidationError(
                contentType = "application/octet-stream",
                requestedBytes = 1_000_000L,
                responseBytes = 1_000_000L,
            ),
        )
    }

    @Test
    fun rejectsMissingContentType() {
        val error = SpeedTestEngine.downloadResponseValidationError(
            contentType = null,
            requestedBytes = 1_000_000L,
            responseBytes = -1L,
        )
        assertTrue(error?.contains("Content-Type") == true)
    }
}
