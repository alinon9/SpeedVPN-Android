package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedLimiterVerificationLockTest {
    @After
    fun resetLimiter() {
        SpeedLimiter.endVerification(null, null)
    }

    @Test
    fun externalLimiterChangesAreIgnoredUntilVerificationEnds() {
        assertTrue(SpeedLimiter.beginVerification(6_000L, 4_000L))
        try {
            assertTrue(SpeedLimiter.verificationInProgress.value)
            assertFalse(SpeedLimiter.beginVerification(null, null))

            SpeedLimiter.setDownloadKbps(9_000L)
            SpeedLimiter.setUploadKbps(7_000L)
            assertEquals(6_000L * 125L, SpeedLimiter.download.bytesPerSec)
            assertEquals(4_000L * 125L, SpeedLimiter.upload.bytesPerSec)

            SpeedLimiter.setVerificationLimits(8_000L, 5_000L)
            assertEquals(8_000L * 125L, SpeedLimiter.download.bytesPerSec)
            assertEquals(5_000L * 125L, SpeedLimiter.upload.bytesPerSec)
        } finally {
            SpeedLimiter.endVerification(6_000L, 4_000L)
        }

        assertFalse(SpeedLimiter.verificationInProgress.value)
        assertEquals(6_000L * 125L, SpeedLimiter.download.bytesPerSec)
        assertEquals(4_000L * 125L, SpeedLimiter.upload.bytesPerSec)

    }
}
