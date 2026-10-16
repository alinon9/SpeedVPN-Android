package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CancellationException

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
    fun originalPlanIsRestoredAfterVerificationFailureOrCancellation() {
        assertTrue(SpeedLimiter.setLimitsKbps(7_000L, 3_000L))
        val simulatedFailures: List<Throwable> = listOf(
            IllegalStateException("simulated verification failure"),
            CancellationException("simulated verification cancellation"),
        )

        simulatedFailures.forEach { failure ->
            assertTrue(SpeedLimiter.beginVerification(null, null))
            try {
                SpeedLimiter.setVerificationLimits(1_200L, 900L)
                throw failure
            } catch (_: Throwable) {
                // Emulate verify() failing/cancelling after changing the temporary plan.
            } finally {
                SpeedLimiter.setVerificationTrafficRoutingEnabled(false)
                SpeedLimiter.endVerificationRestoringPreviousLimits()
            }

            assertEquals(7_000L * 125L, SpeedLimiter.download.bytesPerSec)
            assertEquals(3_000L * 125L, SpeedLimiter.upload.bytesPerSec)
            assertFalse(SpeedLimiter.verificationInProgress.value)
            assertFalse(SpeedLimiter.routeVerificationTrafficThroughVpn)
        }
    }

    @Test
    fun verificationCounterWindowCountsOnlyTheAttributedUidAndResetsPerRun() {
        val generation = 918_273L
        val ownUid = 12_345
        assertTrue(SpeedLimiter.beginVerification(null, null))
        SpeedLimiter.beginSession(generation)
        SpeedLimiter.setVerificationTrafficRoutingEnabled(true)

        try {
            assertTrue(SpeedLimiter.beginVerificationTrafficWindow(generation, ownUid))
            val before = requireNotNull(SpeedLimiter.verificationTrafficSnapshot(generation))
            SpeedLimiter.recordVerificationForwarded(SpeedLimiter.download, 800, generation, ownUid + 1)
            SpeedLimiter.recordVerificationForwarded(SpeedLimiter.download, 400, generation, ownUid)
            SpeedLimiter.recordVerificationForwarded(SpeedLimiter.upload, 600, generation, ownUid)

            val after = requireNotNull(SpeedLimiter.verificationTrafficSnapshot(generation))
            assertEquals(0L, before.downloadBytes)
            assertEquals(400L, after.downloadBytes)
            assertEquals(600L, after.uploadBytes)
            assertFalse(SpeedLimiter.beginVerificationTrafficWindow(generation + 1L, ownUid))

            SpeedLimiter.endVerificationTrafficWindow(generation)
            assertTrue(SpeedLimiter.beginVerificationTrafficWindow(generation, ownUid))
            val nextRun = requireNotNull(SpeedLimiter.verificationTrafficSnapshot(generation))
            assertEquals(0L, nextRun.downloadBytes)
            assertEquals(0L, nextRun.uploadBytes)
        } finally {
            SpeedLimiter.endVerificationTrafficWindow(generation)
            SpeedLimiter.setVerificationTrafficRoutingEnabled(false)
            SpeedLimiter.endVerification(null, null)
            SpeedLimiter.endSession(generation)
        }
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
}
