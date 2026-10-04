package com.speedvpn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedControlTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("local_limits", Context.MODE_PRIVATE).edit().clear().commit()
        SpeedLimiter.setDownloadKbps(null)
        SpeedLimiter.setUploadKbps(null)
        SpeedLimiter.endSession(1L)
        SpeedLimiter.endSession(2L)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("local_limits", Context.MODE_PRIVATE).edit().clear().commit()
        SpeedLimiter.setDownloadKbps(null)
        SpeedLimiter.setUploadKbps(null)
    }

    @Test
    fun preset128KbpsUsesExactBitRate() {
        SpeedLimiter.setDownloadKbps(128L)
        assertEquals(16_000L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun preset256KbpsUsesExactBitRate() {
        SpeedLimiter.setDownloadKbps(256L)
        assertEquals(32_000L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun preset512KbpsUsesExactBitRate() {
        SpeedLimiter.setDownloadKbps(512L)
        assertEquals(64_000L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun preset1MbpsUsesExactBitRate() {
        SpeedLimiter.setDownloadKbps(1_000L)
        assertEquals(125_000L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun preset2MbpsUsesExactBitRate() {
        SpeedLimiter.setDownloadKbps(2_000L)
        assertEquals(250_000L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun preset5MbpsUsesExactBitRate() {
        SpeedLimiter.setDownloadKbps(5_000L)
        assertEquals(625_000L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun unlimitedRemovesRateLimit() {
        SpeedLimiter.setDownloadKbps(null)
        SpeedLimiter.setUploadKbps(null)

        assertEquals(0L, SpeedLimiter.download.bytesPerSec)
        assertEquals(0L, SpeedLimiter.upload.bytesPerSec)
    }

    @Test
    fun downloadAndUploadAreIndependent() {
        SpeedLimiter.setDownloadKbps(2_000L)
        SpeedLimiter.setUploadKbps(512L)

        assertEquals(250_000L, SpeedLimiter.download.bytesPerSec)
        assertEquals(64_000L, SpeedLimiter.upload.bytesPerSec)
    }

    @Test
    fun changingLimitTakesEffectImmediately() {
        SpeedLimiter.setDownloadKbps(5_000L)
        assertEquals(625_000L, SpeedLimiter.download.bytesPerSec)

        SpeedLimiter.setDownloadKbps(512L)

        assertEquals(64_000L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun unlimitedToLimitedAndBackIsSupported() {
        SpeedLimiter.setDownloadKbps(null)
        assertEquals(0L, SpeedLimiter.download.bytesPerSec)

        SpeedLimiter.setDownloadKbps(512L)
        assertEquals(64_000L, SpeedLimiter.download.bytesPerSec)

        SpeedLimiter.setDownloadKbps(null)
        assertEquals(0L, SpeedLimiter.download.bytesPerSec)
    }

    @Test
    fun negativeAndZeroLimitsAreUnlimited() {
        SpeedLimiter.setDownloadKbps(0L)
        assertEquals(0L, SpeedLimiter.download.bytesPerSec)

        SpeedLimiter.setUploadKbps(-1L)
        assertEquals(0L, SpeedLimiter.upload.bytesPerSec)
    }

    @Test
    fun persistenceRoundTripPreservesDownloadAndUploadLimits() {
        SpeedLimitStore.save(context, 2_000L, 512L)

        assertEquals(
            2_000L to 512L,
            SpeedLimitStore.load(context),
        )
    }

    @Test
    fun applyToLimiterRestoresPersistedLimits() {
        SpeedLimitStore.save(context, 1_000L, 256L)
        SpeedLimiter.setDownloadKbps(null)
        SpeedLimiter.setUploadKbps(null)

        SpeedLimitStore.applyToLimiter(context)

        assertEquals(125_000L, SpeedLimiter.download.bytesPerSec)
        assertEquals(32_000L, SpeedLimiter.upload.bytesPerSec)
    }

    @Test
    fun reconnectKeepsPersistedRateAndCreatesNewActiveGeneration() {
        SpeedLimitStore.save(context, 512L, 128L)

        SpeedLimiter.beginSession(1L)
        SpeedLimitStore.applyToLimiter(context)
        assertTrue(SpeedLimiter.isGenerationActive(1L))
        assertEquals(64_000L, SpeedLimiter.download.bytesPerSec)

        SpeedLimiter.endSession(1L)
        assertTrue(!SpeedLimiter.isGenerationActive(1L))

        SpeedLimiter.beginSession(2L)
        SpeedLimitStore.applyToLimiter(context)

        assertTrue(SpeedLimiter.isGenerationActive(2L))
        assertEquals(64_000L, SpeedLimiter.download.bytesPerSec)
        assertEquals(16_000L, SpeedLimiter.upload.bytesPerSec)
    }

    @Test
    fun activeGenerationControlsActualAcquirePath() {
        val generation = 77L
        SpeedLimiter.beginSession(generation)
        SpeedLimiter.setDownloadKbps(null)

        assertTrue(SpeedLimiter.acquire(SpeedLimiter.download, 16 * 1024, generation))
        assertEquals(0L, SpeedLimiter.download.sessionBytes(generation))

        SpeedLimiter.download.recordForwarded(16 * 1024, generation)
        assertEquals(16 * 1024L, SpeedLimiter.download.sessionBytes(generation))

        SpeedLimiter.endSession(generation)
    }

    @Test
    fun staleGenerationCannotAcquireAfterReconnect() {
        SpeedLimiter.beginSession(100L)
        SpeedLimiter.endSession(100L)
        SpeedLimiter.beginSession(101L)

        assertTrue(!SpeedLimiter.acquire(SpeedLimiter.download, 1024, 100L))
        assertTrue(SpeedLimiter.acquire(SpeedLimiter.download, 1024, 101L))

        SpeedLimiter.endSession(101L)
    }

    @Test
    fun sessionResetClearsOnlyCurrentGenerationCounter() {
        SpeedLimiter.beginSession(200L)
        SpeedLimiter.download.recordForwarded(1000, 200L)
        assertEquals(1000L, SpeedLimiter.download.sessionBytes(200L))

        SpeedLimiter.resetSessionCounters(200L)

        assertEquals(0L, SpeedLimiter.download.sessionBytes(200L))
        SpeedLimiter.endSession(200L)
    }

    @Test
    fun concurrentLimitUpdatesRemainValidAndNonNegative() {
        val executor = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        val rates = listOf(128L, 256L, 512L, 1_000L, 2_000L, 5_000L, null, 512L)

        rates.forEach { rate ->
            executor.execute {
                try {
                    SpeedLimiter.setDownloadKbps(rate)
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertTrue(SpeedLimiter.download.bytesPerSec >= 0L)
    }

    @Test
    fun concurrentSessionAccountingDoesNotLoseGenerationOwnership() {
        val generation = 300L
        SpeedLimiter.beginSession(generation)

        val executor = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) {
            executor.execute {
                try {
                    repeat(100) {
                        SpeedLimiter.download.recordForwarded(10, generation)
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(4_000L, SpeedLimiter.download.sessionBytes(generation))
        SpeedLimiter.endSession(generation)
    }
}
