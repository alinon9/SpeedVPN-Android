package com.speedvpn.app

import org.junit.Assert.assertArrayEquals
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType

class SpeedTestEngineSamplingTest {
    @Test
    fun configuredDebugFixtureIsUsedForBothEndpoints() {
        val baseUrl = "http://10.0.2.2:18765/"
        assertEquals(
            "http://10.0.2.2:18765/__down",
            SpeedTestEngine.endpointUrl("__down", baseUrl),
        )
        assertEquals(
            "http://10.0.2.2:18765/__up",
            SpeedTestEngine.endpointUrl("__up", baseUrl),
        )
    }

    @Test
    fun emptyFixtureConfigurationKeepsPublicCloudflareEndpoint() {
        assertEquals(
            "https://speed.cloudflare.com/__down",
            SpeedTestEngine.endpointUrl("__down", ""),
        )
        assertEquals(
            "https://speed.cloudflare.com/__up",
            SpeedTestEngine.endpointUrl("__up", null),
        )
    }

    @Test
    fun mediumAndHighFinitePlansUseThreeEqualSustainedSamples() {
        assertArrayEquals(
            longArrayOf(3_000_000L, 3_000_000L, 3_000_000L),
            SpeedTestEngine.requestSizesFor(16_000L),
        )
        assertArrayEquals(
            longArrayOf(6_000_000L, 6_000_000L, 6_000_000L),
            SpeedTestEngine.requestSizesFor(16_000L, verificationProfile = true),
        )
        // The transport-lifecycle fix must not rewrite the sampling plans for
        // other medium/fast presets.
        assertArrayEquals(
            longArrayOf(12_000_000L, 12_000_000L, 12_000_000L),
            SpeedTestEngine.requestSizesFor(32_000L, verificationProfile = true),
        )
        assertArrayEquals(
            longArrayOf(15_000_000L, 15_000_000L, 15_000_000L),
            SpeedTestEngine.requestSizesFor(40_000L, verificationProfile = true),
        )
        assertArrayEquals(
            // Verification samples target 3 seconds and cap at 24 MB.
            longArrayOf(24_000_000L, 24_000_000L, 24_000_000L),
            SpeedTestEngine.requestSizesFor(88_000L, verificationProfile = true),
        )
    }

    @Test
    fun finiteSamplesAimForAtLeastFifteenHundredMillisecondsAtTargetRate() {
        val targetKbps = 16_000L
        val size = SpeedTestEngine.requestSizesFor(targetKbps).first()
        val durationMs = SpeedTestEngine.requestDurationFor(size, targetKbps * 1_000L)
        assertTrue(
            "Expected a sustained sample, got $durationMs ms",
            durationMs >= SpeedTestEngine.FINITE_SAMPLE_TARGET_DURATION_MS,
        )
        val verificationSize = SpeedTestEngine.requestSizesFor(targetKbps, verificationProfile = true).first()
        val verificationDurationMs = SpeedTestEngine.requestDurationFor(verificationSize, targetKbps * 1_000L)
        assertTrue(
            "Expected verification sample to cover at least " +
                "${SpeedTestEngine.FINITE_VERIFICATION_SAMPLE_TARGET_DURATION_MS}ms, got $verificationDurationMs ms",
            verificationDurationMs >= SpeedTestEngine.FINITE_VERIFICATION_SAMPLE_TARGET_DURATION_MS,
        )
    }

    @Test
    fun lowFinitePlansRetainBoundedRequestLadder() {
        assertArrayEquals(
            longArrayOf(128_000L, 192_000L, 256_000L),
            SpeedTestEngine.requestSizesFor(80L),
        )
        assertArrayEquals(
            longArrayOf(128_000L, 256_000L, 512_000L, 1_000_000L),
            SpeedTestEngine.requestSizesFor(800L),
        )
    }

    @Test
    fun baselineAndUnlimitedKeepAdaptiveLadder() {
        val baseline = SpeedTestEngine.requestSizesFor(null)
        assertEquals(7, baseline.size)
        assertTrue(baseline.asList().zipWithNext().all { (left, right) -> left < right })
        assertArrayEquals(baseline, SpeedTestEngine.requestSizesFor(0L))
    }

    @Test
    fun unlimitedVerificationUsesThreeEqualSustainedSamplesOnBothSides() {
        val expected = longArrayOf(20_000_000L, 20_000_000L, 20_000_000L)
        assertArrayEquals(expected, SpeedTestEngine.requestSizesFor(null, verificationProfile = true))
        assertArrayEquals(expected, SpeedTestEngine.requestSizesFor(0L, verificationProfile = true))
        assertArrayEquals(
            SpeedTestEngine.requestSizesFor(null, verificationProfile = true),
            SpeedTestEngine.requestSizesFor(0L, verificationProfile = true),
        )
    }

    @Test
    fun finiteVerificationKeepsLowPlanLadderAndExtendsHighRateSamples() {
        for (targetKbps in listOf(80L, 800L)) {
            assertArrayEquals(
                SpeedTestEngine.requestSizesFor(targetKbps),
                SpeedTestEngine.requestSizesFor(targetKbps, verificationProfile = true),
            )
        }
        assertArrayEquals(
            longArrayOf(6_000_000L, 6_000_000L, 6_000_000L),
            SpeedTestEngine.requestSizesFor(16_000L, verificationProfile = true),
        )
        assertArrayEquals(
            longArrayOf(24_000_000L, 24_000_000L, 24_000_000L),
            SpeedTestEngine.requestSizesFor(88_000L, verificationProfile = true),
        )
        assertArrayEquals(
            longArrayOf(3_000_000L, 3_000_000L, 3_000_000L),
            SpeedTestEngine.requestSizesFor(16_000L, verificationProfile = false),
        )
    }

    @Test
    fun finiteVerificationCapsRequestsBelowTheFixtureCeilingForVeryHighPlans() {
        assertArrayEquals(
            longArrayOf(24_000_000L, 24_000_000L, 24_000_000L),
            SpeedTestEngine.requestSizesFor(800_000L, verificationProfile = true),
        )
    }

    @Test
    fun parallelStreamTimeoutUsesItsShareOfTheDeviceWidePlanRate() {
        assertEquals(14_667L, SpeedTestEngine.expectedKbpsPerStream(88_000L, 6))
        assertEquals(88_000L, SpeedTestEngine.expectedKbpsPerStream(88_000L, 1))
        assertEquals(14L, SpeedTestEngine.expectedKbpsPerStream(80L, 6))
        assertEquals(80L, SpeedTestEngine.expectedKbpsPerStream(80L, 1))
        assertEquals(null, SpeedTestEngine.expectedKbpsPerStream(null, 6))
        assertEquals(
            SpeedTestEngine.MIN_VERIFICATION_CALL_TIMEOUT_MS,
            SpeedTestEngine.measurementCallTimeoutMs(
                4_000_000L,
                SpeedTestEngine.expectedKbpsPerStream(88_000L, 6),
                verificationProfile = true,
            ),
        )
    }

    @Test
    fun highCapacityAndUnlimitedVerificationUseThreeStreamsButLowPlansStaySingleStream() {
        assertEquals(3, SpeedTestEngine.sampleParallelismFor(32_000L, verificationProfile = true))
        assertEquals(3, SpeedTestEngine.sampleParallelismFor(88_000L, verificationProfile = true))
        assertEquals(3, SpeedTestEngine.sampleParallelismFor(null, verificationProfile = true))
        assertEquals(3, SpeedTestEngine.sampleParallelismFor(0L, verificationProfile = true))
        assertEquals(1, SpeedTestEngine.sampleParallelismFor(800L, verificationProfile = true))
        assertEquals(1, SpeedTestEngine.sampleParallelismFor(88_000L, verificationProfile = false))
    }

    @Test
    fun parallelSampleSplitPreservesTotalBytesWithoutEmptyRequests() {
        assertArrayEquals(
            longArrayOf(2_750_000L, 2_750_000L, 2_750_000L, 2_750_000L, 2_750_000L, 2_750_000L),
            SpeedTestEngine.splitSampleBytes(16_500_000L, 6),
        )
        assertArrayEquals(
            longArrayOf(17L, 17L, 17L, 17L, 16L, 16L),
            SpeedTestEngine.splitSampleBytes(100L, 6),
        )
        assertArrayEquals(
            longArrayOf(1L, 1L),
            SpeedTestEngine.splitSampleBytes(2L, 3),
        )
        assertArrayEquals(longArrayOf(), SpeedTestEngine.splitSampleBytes(0L, 3))
        assertArrayEquals(longArrayOf(), SpeedTestEngine.splitSampleBytes(100L, 0))
    }

    @Test
    fun phaseBudgetUsesMonotonicElapsedTimeAndAnInclusiveDeadline() {
        val started = 1_000_000_000L
        assertFalse(SpeedTestEngine.phaseBudgetExceeded(started, 1_074_999_999L, 75L))
        assertTrue(SpeedTestEngine.phaseBudgetExceeded(started, 1_075_000_000L, 75L))
    }

    @Test
    fun verificationRateUsesCompletedBytesOverTheWholeSampleWindow() {
        val bytes = 60_000_000L
        val baseline = SpeedTestEngine.verificationThroughputBps(bytes, TimeUnit.MILLISECONDS.toNanos(648L))
        val vpn = SpeedTestEngine.verificationThroughputBps(bytes, TimeUnit.MILLISECONDS.toNanos(1_697L))
        assertTrue("Expected baseline aggregate near 741 Mbps, got $baseline", baseline in 740_000_000L..742_000_000L)
        assertTrue("Expected VPN aggregate near 283 Mbps, got $vpn", vpn in 282_000_000L..284_000_000L)
        assertTrue("Whole-window rate must expose inter-request stalls, not median burst rate", vpn < 572_000_000L)
    }
    @Test
    fun verificationCallTimeoutScalesForSlowPlansWithoutChangingPublicTimeout() {
        assertEquals(
            40_400L,
            SpeedTestEngine.measurementCallTimeoutMs(256_000L, 80L, verificationProfile = true),
        )
        assertEquals(
            17_000L,
            SpeedTestEngine.measurementCallTimeoutMs(1_000_000L, 800L, verificationProfile = true),
        )
        assertEquals(
            30_000L,
            SpeedTestEngine.measurementCallTimeoutMs(20_000_000L, 0L, verificationProfile = true),
        )
        assertEquals(
            30_000L,
            SpeedTestEngine.measurementCallTimeoutMs(256_000L, 80L, verificationProfile = false),
        )
        assertEquals(
            SpeedTestEngine.MAX_VERIFICATION_CALL_TIMEOUT_MS,
            SpeedTestEngine.measurementCallTimeoutMs(20_000_000L, 80L, verificationProfile = true),
        )
    }

    @Test
    fun verificationClientScalesReadAndWriteTimeoutsAlongWithTheCallDeadline() {
        val base = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val slowPlan = SpeedTestEngine.clientForMeasurementRequest(
            base,
            sizeBytes = 512_000L,
            expectedKbps = 200L,
            verificationProfile = true,
        )
        assertEquals(32_720, slowPlan.callTimeoutMillis)
        assertEquals(32_720, slowPlan.readTimeoutMillis)
        assertEquals(32_720, slowPlan.writeTimeoutMillis)

        val almostOutOfTime = SpeedTestEngine.clientForMeasurementRequest(
            base,
            sizeBytes = 512_000L,
            expectedKbps = 200L,
            verificationProfile = true,
            remainingPhaseMs = 8_000L,
        )
        assertEquals(8_000, almostOutOfTime.callTimeoutMillis)
        assertEquals(8_000, almostOutOfTime.readTimeoutMillis)
        assertEquals(8_000, almostOutOfTime.writeTimeoutMillis)

        val publicTest = SpeedTestEngine.clientForMeasurementRequest(
            base,
            sizeBytes = 512_000L,
            expectedKbps = 200L,
            verificationProfile = false,
        )
        assertEquals(30_000, publicTest.callTimeoutMillis)
        assertEquals(25_000, publicTest.readTimeoutMillis)
        assertEquals(25_000, publicTest.writeTimeoutMillis)

        slowPlan.connectionPool.evictAll()
        almostOutOfTime.connectionPool.evictAll()
        base.connectionPool.evictAll()
    }

    @Test
    fun slowestFinitePlanFitsItsThreeSampleTimeoutsAndWarmupInsidePhaseBudget() {
        val targetKbps = 80L
        val sampleTimeouts = SpeedTestEngine.requestSizesFor(targetKbps, verificationProfile = true)
            .map { SpeedTestEngine.measurementCallTimeoutMs(it, targetKbps, verificationProfile = true) }
        val warmupTimeout = SpeedTestEngine.measurementCallTimeoutMs(
            8_000L,
            targetKbps,
            verificationProfile = true,
        )
        assertEquals(listOf(21_200L, 30_800L, 40_400L), sampleTimeouts)
        assertEquals(12_000L, warmupTimeout)
        assertTrue(sampleTimeouts.sum() + warmupTimeout <= SpeedTestEngine.PHASE_TIMEOUT_MS)
    }

    @Test
    fun requiredRelayBytesCoverTheMinimumSuccessfulSampleSetForEachPlanType() {
        assertEquals(320_000L, SpeedTestEngine.minimumRequiredSampleBytes(80L, verificationProfile = true))
        assertEquals(896_000L, SpeedTestEngine.minimumRequiredSampleBytes(800L, verificationProfile = true))
        assertEquals(72_000_000L, SpeedTestEngine.minimumRequiredSampleBytes(88_000L, verificationProfile = true))
        assertEquals(60_000_000L, SpeedTestEngine.minimumRequiredSampleBytes(0L, verificationProfile = true))
    }

    @Test
    fun sampleLadderStopsWhenRequiredCoverageBecomesImpossible() {
        assertTrue(SpeedTestEngine.enoughRemainingSamples(0, 3, 3))
        assertTrue(SpeedTestEngine.enoughRemainingSamples(1, 2, 3))
        assertFalse(SpeedTestEngine.enoughRemainingSamples(0, 2, 3))
        assertFalse(SpeedTestEngine.enoughRemainingSamples(1, 1, 3))
    }

    @Test
    fun eachMeasurementClientUsesAnIsolatedConnectionPool() {
        val sharedClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val baselineClient = SpeedTestEngine.createIsolatedMeasurementClient(sharedClient, Dns.SYSTEM)
        val vpnClient = SpeedTestEngine.createIsolatedMeasurementClient(sharedClient, Dns.SYSTEM)

        assertNotSame(sharedClient.connectionPool, baselineClient.connectionPool)
        assertNotSame(sharedClient.connectionPool, vpnClient.connectionPool)
        assertNotSame(baselineClient.connectionPool, vpnClient.connectionPool)

        baselineClient.connectionPool.evictAll()
        vpnClient.connectionPool.evictAll()
        sharedClient.connectionPool.evictAll()
    }

    @Test
    fun uploadRequestDoesNotAskThePeerToCloseWhileTheBodyWriteFinishes() {
        val download = SpeedTestEngine.buildDownloadRequest(1_000_000L)
        val upload = SpeedTestEngine.buildUploadRequest(
            1_000_000L,
            object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = 1_000_000L
                override fun writeTo(sink: okio.BufferedSink) {
                    sink.write(ByteArray(1_000_000))
                }
            },
        )

        // Download behavior is intentionally unchanged. Upload connection teardown
        // is owned by its zero-idle per-request pool after the HTTP response is read.
        assertEquals("close", download.header("Connection"))
        assertTrue(upload.header("Connection") == null)
    }

    @Test
    fun eachUploadRequestGetsItsOwnConnectionPoolAndRetainsVerificationTimeouts() {
        val base = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
        val first = SpeedTestEngine.clientForMeasurementRequest(
            base,
            sizeBytes = 6_000_000L,
            expectedKbps = 16_000L,
            verificationProfile = true,
            isUploadRequest = true,
        )
        val second = SpeedTestEngine.clientForMeasurementRequest(
            base,
            sizeBytes = 6_000_000L,
            expectedKbps = 16_000L,
            verificationProfile = true,
            isUploadRequest = true,
        )

        assertNotSame(base.connectionPool, first.connectionPool)
        assertNotSame(first.connectionPool, second.connectionPool)
        assertEquals(
            SpeedTestEngine.measurementCallTimeoutMs(6_000_000L, 16_000L, verificationProfile = true).toInt(),
            first.callTimeoutMillis,
        )
        assertEquals(first.callTimeoutMillis, first.readTimeoutMillis)
        assertEquals(first.callTimeoutMillis, first.writeTimeoutMillis)

        first.connectionPool.evictAll()
        second.connectionPool.evictAll()
        base.connectionPool.evictAll()
    }

}
