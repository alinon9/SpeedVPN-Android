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

    @Test
    fun remoteLimitUpdateIsNotPersistedWhileVerificationOwnsLimiter() {
        assertTrue(SpeedLimiter.beginVerification(6_000L, 4_000L))
        var persisted = 0
        try {
            assertFalse(
                SpeedLimiter.setDownloadKbps(9_000L) { persisted++ },
            )
            assertFalse(
                SpeedLimiter.setUploadKbps(7_000L) { persisted++ },
            )
            assertFalse(
                SpeedLimiter.setLimitsKbps(10_000L, 8_000L) { persisted++ },
            )

            assertEquals(0, persisted)
            assertEquals(6_000L * 125L, SpeedLimiter.download.bytesPerSec)
            assertEquals(4_000L * 125L, SpeedLimiter.upload.bytesPerSec)
        } finally {
            SpeedLimiter.endVerification(6_000L, 4_000L)
        }

        assertTrue(SpeedLimiter.setDownloadKbps(9_000L) { persisted++ })
        assertEquals(1, persisted)
        assertEquals(9_000L * 125L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun verificationTrafficRouteIsClearedWhenVerificationEnds() {
        assertTrue(SpeedLimiter.beginVerification(6_000L, 4_000L))
        SpeedLimiter.setVerificationTrafficRoutingEnabled(true)
        assertTrue(SpeedLimiter.routeVerificationTrafficThroughVpn)

        SpeedLimiter.endVerification(6_000L, 4_000L)

        assertFalse(SpeedLimiter.routeVerificationTrafficThroughVpn)
    }

    @Test
    fun externalVpnCommandsAndVerificationCannotOverlap() {
        assertTrue(SpeedLimiter.beginExternalVpnCommand())
        assertFalse(SpeedLimiter.beginVerification(6_000L, 4_000L))
        SpeedLimiter.endExternalVpnCommand()

        assertTrue(SpeedLimiter.beginVerification(6_000L, 4_000L))
        assertFalse(SpeedLimiter.beginExternalVpnCommand())
        SpeedLimiter.endVerification(6_000L, 4_000L)
    }

    @Test
    fun stagedLimitStoreReflectsAcceptedLiveLimitsAndRejectsUpdatesDuringVerification() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        SpeedLimitStore.resetStagedForTests()
        SpeedLimitStore.save(context, 2_000L, 1_000L)

        // Stage a new download limit inside SpeedLimiter.setDownloadKbps callback before disk write.
        assertTrue(
            SpeedLimiter.setDownloadKbps(8_000L) {
                SpeedLimitStore.stageDownload(context, 8_000L)
            },
        )
        assertEquals(8_000L to 1_000L, SpeedLimitStore.load(context))

        // While verification owns the limiter, UI/overlay stage callbacks must not run.
        assertTrue(SpeedLimiter.beginVerification(null, null))
        try {
            assertFalse(
                SpeedLimiter.setDownloadKbps(16_000L) {
                    SpeedLimitStore.stageDownload(context, 16_000L)
                },
            )
            assertFalse(
                SpeedLimiter.setUploadKbps(16_000L) {
                    SpeedLimitStore.stageUpload(context, 16_000L)
                },
            )
            assertEquals(8_000L to 1_000L, SpeedLimitStore.load(context))
        } finally {
            SpeedLimiter.endVerification(8_000L, 1_000L)
        }
    }
}
