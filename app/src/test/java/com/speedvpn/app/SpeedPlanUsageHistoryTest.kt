package com.speedvpn.app

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowNetwork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedPlanUsageHistoryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val migrationDbName = "speed_plan_migration_v4_v5_test.db"

    @Before
    fun setUp() {
        context.deleteDatabase("speedvpn_usage.db")
        context.deleteDatabase(migrationDbName)
        SpeedLimiter.endVerification(null, null)
        SpeedLimiter.clearAllProbeState()
        SpeedLimiter.endSession(1L)
        SpeedLimiter.endSession(2L)
        SpeedLimiter.setOwnAppIdentityForTests(uid = 10999, packageName = "com.speedvpn.app")
        SpeedPlanUsageTracker.resetForTests("proc-test-main")
        UsageRepository.speedPlanDbReadCount.set(0L)
        UsageRepository.speedPlanDbWriteCount.set(0L)
    }

    @After
    fun tearDown() {
        SpeedLimiter.endVerification(null, null)
        SpeedLimiter.clearAllProbeState()
        SpeedLimiter.endSession(1L)
        SpeedLimiter.endSession(2L)
        SpeedPlanUsageTracker.resetForTests("proc-test-cleanup")
        // Restore Robolectric's real package/UID so the synthetic identity cannot leak
        // into other test classes that share the SpeedLimiter singleton.
        SpeedLimiter.clearOwnAppIdentityForTests(context)
        context.deleteDatabase("speedvpn_usage.db")
        context.deleteDatabase(migrationDbName)
    }

    @Test
    fun migrationV4ToV5_preservesExistingTablesAndCreatesSpeedPlanUsageSessionTable() {
        val v4Db = context.openOrCreateDatabase(migrationDbName, 0, null)
        v4Db.execSQL(
            """
            CREATE TABLE app_policy (
                package_name TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                blocked INTEGER NOT NULL DEFAULT 0,
                daily_limit_bytes INTEGER
            )
            """.trimIndent(),
        )
        v4Db.execSQL(
            """
            CREATE TABLE app_quota_policy (
                package_name TEXT NOT NULL,
                quota_type TEXT NOT NULL CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
                limit_bytes INTEGER NOT NULL CHECK (limit_bytes > 0),
                period_start_millis INTEGER NOT NULL,
                period_end_millis INTEGER NOT NULL,
                used_bytes INTEGER NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
                download_bytes INTEGER NOT NULL DEFAULT 0 CHECK (download_bytes >= 0),
                upload_bytes INTEGER NOT NULL DEFAULT 0 CHECK (upload_bytes >= 0),
                baseline_initialized INTEGER NOT NULL DEFAULT 0 CHECK (baseline_initialized IN (0, 1)),
                reset_behavior TEXT NOT NULL DEFAULT 'AUTO_RESET'
                    CHECK (reset_behavior IN ('AUTO_RESET', 'BLOCK_UNTIL_RESET')),
                updated_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (package_name, quota_type),
                FOREIGN KEY (package_name) REFERENCES app_policy(package_name) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        v4Db.execSQL(
            """
            CREATE TABLE app_quota_pending_reservation (
                reservation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                process_token TEXT NOT NULL,
                transport TEXT NOT NULL CHECK (transport IN ('TCP', 'UDP')),
                package_name TEXT NOT NULL,
                quota_type TEXT NOT NULL CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
                period_start_millis INTEGER NOT NULL,
                direction TEXT NOT NULL CHECK (direction IN ('DOWNLOAD', 'UPLOAD')),
                reserved_bytes INTEGER NOT NULL CHECK (reserved_bytes > 0),
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        v4Db.execSQL(
            """
            CREATE TABLE daily_usage (
                date TEXT NOT NULL,
                package_name TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                download_bytes INTEGER NOT NULL DEFAULT 0,
                upload_bytes INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (date, package_name)
            )
            """.trimIndent(),
        )
        v4Db.insertOrThrow(
            "app_policy",
            null,
            ContentValues().apply {
                put("package_name", "org.telegram.messenger")
                put("label", "Telegram")
                put("uid", 10150)
                put("blocked", 1)
                put("daily_limit_bytes", 50_000_000L)
            },
        )
        v4Db.insertOrThrow(
            "app_quota_policy",
            null,
            ContentValues().apply {
                put("package_name", "org.telegram.messenger")
                put("quota_type", "DAILY")
                put("limit_bytes", 50_000_000L)
                put("period_start_millis", 1_700_000_000_000L)
                put("period_end_millis", 1_700_086_400_000L)
                put("used_bytes", 12_345_678L)
                put("download_bytes", 10_000_000L)
                put("upload_bytes", 2_345_678L)
                put("baseline_initialized", 1)
                put("reset_behavior", "AUTO_RESET")
                put("updated_at", 1_700_000_100_000L)
            },
        )
        v4Db.insertOrThrow(
            "daily_usage",
            null,
            ContentValues().apply {
                put("date", "2026-10-22")
                put("package_name", "org.telegram.messenger")
                put("label", "Telegram")
                put("uid", 10150)
                put("download_bytes", 10_000_000L)
                put("upload_bytes", 2_345_678L)
                put("updated_at", 1_700_000_100_000L)
            },
        )
        v4Db.execSQL("PRAGMA user_version = 4")
        v4Db.close()

        UsageDbHelper(context, migrationDbName, 5).use { helper ->
            val db = helper.writableDatabase
            assertEquals(5, db.version)

            db.rawQuery(
                "SELECT used_bytes, download_bytes, upload_bytes FROM app_quota_policy WHERE package_name = ?",
                arrayOf("org.telegram.messenger"),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(12_345_678L, c.getLong(0))
                assertEquals(10_000_000L, c.getLong(1))
                assertEquals(2_345_678L, c.getLong(2))
            }

            db.rawQuery(
                "SELECT download_bytes, upload_bytes FROM daily_usage WHERE date = ? AND package_name = ?",
                arrayOf("2026-10-22", "org.telegram.messenger"),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(10_000_000L, c.getLong(0))
                assertEquals(2_345_678L, c.getLong(1))
            }

            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'speed_plan_usage_session'",
                null,
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("speed_plan_usage_session", c.getString(0))
            }
        }
    }

    @Test
    fun realAppTrafficWithoutSpeedTest_recordsExactDownloadAndUploadBytes() {
        val gen = 1L
        val t0 = 1_729_600_000_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(8_000L, 4_000L)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)

        val sink = ByteArrayOutputStream()
        val payloadDl = ByteArray(180_000) { 1 }
        val payloadUl = ByteArray(70_000) { 2 }

        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = payloadDl,
            requestedBytes = payloadDl.size,
            decision = QuotaTrafficDecision(payloadDl.size),
            bucket = SpeedLimiter.download,
            generation = gen,
            packages = setOf("org.telegram.messenger"),
            uid = 10150,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
        )
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = payloadUl,
            requestedBytes = payloadUl.size,
            decision = QuotaTrafficDecision(payloadUl.size),
            bucket = SpeedLimiter.upload,
            generation = gen,
            packages = setOf("org.telegram.messenger"),
            uid = 10150,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
        )

        SpeedPlanUsageTracker.onVpnDisconnected(
            context = context,
            generation = gen,
            closeReason = "VPN_DISCONNECTED",
            abnormal = false,
            nowMillis = t0 + 10_000L,
        )
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        val records = UsageRepository.readAllSpeedPlanUsageSessions(context)
        assertEquals(1, records.size)
        val rec = records.single()
        assertEquals(180_000L, rec.downloadBytes)
        assertEquals(70_000L, rec.uploadBytes)
        assertEquals(250_000L, rec.totalBytes)
        assertEquals(10_000L, rec.durationMillis)
        assertEquals(SpeedPlanRecordStatus.COMPLETED, rec.status)
    }

    @Test
    fun speedTestProbeTrafficAlone_isExcludedFromPlanUsageHistory() {
        val gen = 1L
        val t0 = 1_729_600_100_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(8_000L, 8_000L)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)

        assertTrue(SpeedLimiter.beginVerification(8_000L, 8_000L))
        SpeedLimiter.setVerificationTrafficRoutingEnabled(true)

        val sink = ByteArrayOutputStream()
        val probeChunk = ByteArray(250_000) { 3 }
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = probeChunk,
            requestedBytes = probeChunk.size,
            decision = QuotaTrafficDecision(probeChunk.size),
            bucket = SpeedLimiter.download,
            generation = gen,
            packages = setOf("com.speedvpn.app"),
            uid = 10999,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
            isVerificationProbe = true,
        )
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = probeChunk,
            requestedBytes = probeChunk.size,
            decision = QuotaTrafficDecision(probeChunk.size),
            bucket = SpeedLimiter.upload,
            generation = gen,
            packages = setOf("com.speedvpn.app"),
            uid = 10999,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
            isVerificationProbe = true,
        )

        SpeedLimiter.endVerification(8_000L, 8_000L)
        SpeedPlanUsageTracker.onVpnDisconnected(context, gen, nowMillis = t0 + 6_000L)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        assertEquals(250_000L, SpeedLimiter.download.verificationProbeBytes(gen))
        assertEquals(250_000L, SpeedLimiter.upload.verificationProbeBytes(gen))
        assertEquals(0L, SpeedLimiter.download.planSessionBytes(gen))
        assertEquals(0L, SpeedLimiter.upload.planSessionBytes(gen))

        val rec = UsageRepository.readAllSpeedPlanUsageSessions(context).single()
        assertEquals(0L, rec.downloadBytes)
        assertEquals(0L, rec.uploadBytes)
        assertEquals(0L, rec.totalBytes)
    }

    @Test
    fun realAppTrafficConcurrentlyWithSpeedVerification_recordsRealAppBytesAndExcludesProbeBytes() {
        val gen = 1L
        val t0 = 1_729_600_200_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(16_000L, 16_000L)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)

        // Real app traffic before verification starts
        val sink = ByteArrayOutputStream()
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(100_000),
            requestedBytes = 100_000,
            decision = QuotaTrafficDecision(100_000),
            bucket = SpeedLimiter.download,
            generation = gen,
            packages = setOf("org.telegram.messenger"),
            uid = 10150,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
        )

        // Verification starts while real app continues transferring data concurrently
        assertTrue(SpeedLimiter.beginVerification(16_000L, 16_000L))
        SpeedLimiter.setVerificationTrafficRoutingEnabled(true)

        // Synthetic probe sends 500 KB DL + 300 KB UL (must be excluded from plan history)
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(500_000),
            requestedBytes = 500_000,
            decision = QuotaTrafficDecision(500_000),
            bucket = SpeedLimiter.download,
            generation = gen,
            packages = setOf("com.speedvpn.app"),
            uid = 10999,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
            isVerificationProbe = true,
        )
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(300_000),
            requestedBytes = 300_000,
            decision = QuotaTrafficDecision(300_000),
            bucket = SpeedLimiter.upload,
            generation = gen,
            packages = setOf("com.speedvpn.app"),
            uid = 10999,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
            isVerificationProbe = true,
        )

        // Concurrent real app traffic during verification: 220 KB DL + 140 KB UL (MUST be counted!)
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(220_000),
            requestedBytes = 220_000,
            decision = QuotaTrafficDecision(220_000),
            bucket = SpeedLimiter.download,
            generation = gen,
            packages = setOf("com.whatsapp"),
            uid = 10151,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
        )
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(140_000),
            requestedBytes = 140_000,
            decision = QuotaTrafficDecision(140_000),
            bucket = SpeedLimiter.upload,
            generation = gen,
            packages = setOf("com.whatsapp"),
            uid = 10151,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
        )

        SpeedPlanUsageTracker.onMeterSample(
            context = context,
            generation = gen,
            rawDownloadBps = 50_000_000L, // inflated by probe in total counter
            rawUploadBps = 30_000_000L,
            sampleElapsedMillis = 500L,
            nowMillis = t0 + 3_000L,
        )

        SpeedLimiter.endVerification(16_000L, 16_000L)
        SpeedPlanUsageTracker.onVpnDisconnected(context, gen, nowMillis = t0 + 8_000L)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        val records = UsageRepository.readAllSpeedPlanUsageSessions(context)
        assertEquals(1, records.size)
        val rec = records.single()
        assertEquals(320_000L, rec.downloadBytes)
        assertEquals(140_000L, rec.uploadBytes)
        assertEquals(460_000L, rec.totalBytes)
        assertFalse("Synthetic probe traffic must not trigger false limitExceeded on user segment", rec.limitExceeded)
    }

    @Test
    fun unattributedTcpAndUdpTrafficDuringVerifySpeed_isNeverLostEvenWhenTargetingSameEndpointAsProbe() {
        val gen = 1L
        val t0 = 1_729_600_250_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(16_000L, 16_000L)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)

        assertTrue(SpeedLimiter.beginVerification(16_000L, 16_000L))
        SpeedLimiter.setVerificationTrafficRoutingEnabled(true)
        // Register the probe endpoint (e.g. Cloudflare 1.1.1.1:443)
        SpeedLimiter.registerProbeEndpoint("1.1.1.1", 443)

        val sink = ByteArrayOutputStream()
        // 1. Unattributed TCP download (180 KB) and upload (90 KB) to the EXACT SAME IP:port (1.1.1.1:443) as the probe:
        //    uid = null, packages = emptySet(), hasOwner = false, isVerificationProbe = false -> MUST NOT be lost!
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(180_000),
            requestedBytes = 180_000,
            decision = QuotaTrafficDecision(180_000),
            bucket = SpeedLimiter.download,
            generation = gen,
            packages = emptySet(),
            uid = null,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = false,
            isVerificationProbe = false,
            remoteAddress = "1.1.1.1",
            remotePort = 443,
            flowMarker = "tcp:user-unattr:1.1.1.1:443",
        )
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(90_000),
            requestedBytes = 90_000,
            decision = QuotaTrafficDecision(90_000),
            bucket = SpeedLimiter.upload,
            generation = gen,
            packages = emptySet(),
            uid = null,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = false,
            isVerificationProbe = false,
            remoteAddress = "1.1.1.1",
            remotePort = 443,
            flowMarker = "tcp:user-unattr:1.1.1.1:443",
        )

        // 2. Unattributed UDP upload (60 KB) and download (120 KB) to the EXACT SAME IP:port (1.1.1.1:443) as the probe:
        val udpUpDelivery = deliverAdmittedUdpDatagramDetailed(
            payloadBytes = 60_000,
            decision = QuotaTrafficDecision(60_000),
            packages = emptySet(),
            uid = null,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = false,
            sendDatagram = { true },
        )
        assertTrue(udpUpDelivery.delivered)
        SpeedLimiter.upload.recordForwardedForApp(
            n = 60_000,
            generation = gen,
            uid = null,
            packages = emptySet(),
            isVerificationProbeFlow = false,
            remoteAddress = "1.1.1.1",
            remotePort = 443,
            flowMarker = "udp:user-unattr:1.1.1.1:443",
        )

        val udpDownDelivery = deliverAdmittedUdpDatagramDetailed(
            payloadBytes = 120_000,
            decision = QuotaTrafficDecision(120_000),
            packages = emptySet(),
            uid = null,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = false,
            sendDatagram = { true },
        )
        assertTrue(udpDownDelivery.delivered)
        SpeedLimiter.download.recordForwardedForApp(
            n = 120_000,
            generation = gen,
            uid = null,
            packages = emptySet(),
            isVerificationProbeFlow = false,
            remoteAddress = "1.1.1.1",
            remotePort = 443,
            flowMarker = "udp:user-unattr:1.1.1.1:443",
        )

        // 3. Real non-probe traffic from SpeedVPN's own UID (10999) to the EXACT SAME IP:port (1.1.1.1:443) as the probe:
        //    50 KB DL + 30 KB UL -> MUST NOT be lost!
        SpeedLimiter.download.recordForwardedForApp(
            n = 50_000,
            generation = gen,
            uid = 10999,
            packages = setOf("com.speedvpn.app"),
            isVerificationProbeFlow = false,
            remoteAddress = "1.1.1.1",
            remotePort = 443,
            flowMarker = "tcp:own-nonprobe:1.1.1.1:443",
        )
        SpeedLimiter.upload.recordForwardedForApp(
            n = 30_000,
            generation = gen,
            uid = 10999,
            packages = setOf("com.speedvpn.app"),
            isVerificationProbeFlow = false,
            remoteAddress = "1.1.1.1",
            remotePort = 443,
            flowMarker = "tcp:own-nonprobe:1.1.1.1:443",
        )

        // 4. Concurrent synthetic verification probe traffic (marked with flow-level probe flag / markProbeFlow)
        SpeedLimiter.markProbeFlow("tcp:probe-flow:1.1.1.1:443")
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(400_000),
            requestedBytes = 400_000,
            decision = QuotaTrafficDecision(400_000),
            bucket = SpeedLimiter.download,
            generation = gen,
            packages = setOf("com.speedvpn.app"),
            uid = 10999,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
            isVerificationProbe = false,
            remoteAddress = "1.1.1.1",
            remotePort = 443,
            flowMarker = "tcp:probe-flow:1.1.1.1:443",
        )
        writeAdmittedTcpRelayChunk(
            destination = sink,
            buffer = ByteArray(200_000),
            requestedBytes = 200_000,
            decision = QuotaTrafficDecision(200_000),
            bucket = SpeedLimiter.upload,
            generation = gen,
            packages = emptySet(),
            uid = null,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = false,
            isVerificationProbe = true,
            remoteAddress = "1.1.1.1",
            remotePort = 443,
            flowMarker = "tcp:probe-flow:1.1.1.1:443",
        )

        SpeedLimiter.endVerification(16_000L, 16_000L)
        SpeedPlanUsageTracker.onVpnDisconnected(context, gen, nowMillis = t0 + 10_000L)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        assertEquals(350_000L, SpeedLimiter.download.planSessionBytes(gen))
        assertEquals(180_000L, SpeedLimiter.upload.planSessionBytes(gen))
        assertEquals(400_000L, SpeedLimiter.download.verificationProbeBytes(gen))
        assertEquals(200_000L, SpeedLimiter.upload.verificationProbeBytes(gen))

        val rec = UsageRepository.readAllSpeedPlanUsageSessions(context).single()
        assertEquals(350_000L, rec.downloadBytes)
        assertEquals(180_000L, rec.uploadBytes)
        assertEquals(530_000L, rec.totalBytes)
    }

    @Test
    fun probeEndpointAndSessionLifecycle_normalSpeedTestAndVerificationStartDoNotLeaveStaleProbeState() {
        // 1. Outside Verify Speed (normal speed test / Stage 0), registerProbeEndpoint must not leave persistent endpoints
        assertFalse(SpeedLimiter.isVerificationActive())
        SpeedLimiter.registerProbeEndpoint("1.1.1.1", 443)
        assertFalse(SpeedLimiter.isRegisteredProbeEndpoint("1.1.1.1", 443))

        // 2. Scoped probe measurement session cleans up client socket markers and active token in finally,
        //    AND isolates overlapping sessions (starting A then B, ending A first preserves B until B ends)
        val tokenA = SpeedLimiter.beginProbeMeasurementSession()
        assertTrue(SpeedLimiter.isProbeMeasurementSessionActive())
        SpeedLimiter.markProbeClientSocket(54321, "1.1.1.1", 443, SpeedLimiter.IPPROTO_TCP)
        assertTrue(SpeedLimiter.isMarkedProbeClientSocket(54321, "1.1.1.1", 443, SpeedLimiter.IPPROTO_TCP))
        assertFalse(
            "TCP probe mark must never match a UDP socket on the same local port and destination",
            SpeedLimiter.isMarkedProbeClientSocket(54321, "1.1.1.1", 443, SpeedLimiter.IPPROTO_UDP),
        )
        SpeedLimiter.markProbeFlow("tcp:54321->1.1.1.1:443")
        assertTrue(SpeedLimiter.isMarkedProbeFlow("tcp:54321->1.1.1.1:443"))

        val tokenB = SpeedLimiter.beginProbeMeasurementSession()
        SpeedLimiter.markProbeClientSocket(54322, "1.1.1.1", 443, SpeedLimiter.IPPROTO_TCP)
        SpeedLimiter.markProbeFlow("tcp:54322->1.1.1.1:443")
        assertTrue(SpeedLimiter.isMarkedProbeClientSocket(54322, "1.1.1.1", 443, SpeedLimiter.IPPROTO_TCP))
        assertTrue(SpeedLimiter.isMarkedProbeFlow("tcp:54322->1.1.1.1:443"))

        // End session A first while session B is still active: A's markers must be removed, B's must remain!
        SpeedLimiter.endProbeMeasurementSession(tokenA)
        assertTrue("Session B is still active after Session A ends", SpeedLimiter.isProbeMeasurementSessionActive())
        assertEquals(tokenB, SpeedLimiter.currentProbeMeasurementToken())
        assertFalse(SpeedLimiter.isMarkedProbeClientSocket(54321, "1.1.1.1", 443, SpeedLimiter.IPPROTO_TCP))
        assertFalse(SpeedLimiter.isMarkedProbeFlow("tcp:54321->1.1.1.1:443"))
        assertTrue(
            "Session B client socket marker must remain intact when earlier Session A ends",
            SpeedLimiter.isMarkedProbeClientSocket(54322, "1.1.1.1", 443, SpeedLimiter.IPPROTO_TCP),
        )
        assertTrue(
            "Session B flow marker must remain intact when earlier Session A ends",
            SpeedLimiter.isMarkedProbeFlow("tcp:54322->1.1.1.1:443"),
        )

        // Now end session B: all markers and active token must be cleared
        SpeedLimiter.endProbeMeasurementSession(tokenB)
        assertFalse(SpeedLimiter.isProbeMeasurementSessionActive())
        assertFalse(SpeedLimiter.isMarkedProbeClientSocket(54322, "1.1.1.1", 443, SpeedLimiter.IPPROTO_TCP))
        assertFalse(SpeedLimiter.isMarkedProbeFlow("tcp:54322->1.1.1.1:443"))

        // 3. Starting a new Verify Speed session clears any stale probe state before registering new probes
        SpeedLimiter.markProbeFlow("stale-flow-marker")
        assertTrue(SpeedLimiter.beginVerification(8_000L, 8_000L))
        assertFalse(SpeedLimiter.isMarkedProbeFlow("stale-flow-marker"))
        SpeedLimiter.registerProbeEndpoint("1.1.1.1", 443)
        assertTrue(SpeedLimiter.isRegisteredProbeEndpoint("1.1.1.1", 443))
        SpeedLimiter.endVerification(8_000L, 8_000L)
        assertFalse(SpeedLimiter.isRegisteredProbeEndpoint("1.1.1.1", 443))
    }

    @Test
    fun switchingPlansAndClosingSegment_maintainsStrictlyIncreasingSegmentIndexAndByteConservation() {
        val gen = 1L
        // setLimitsKbps() notifies the tracker using the real wall clock. Anchor this
        // lifecycle test to the same clock so its explicit transition timestamps never
        // precede the automatically opened plan segment.
        val t0 = System.currentTimeMillis()
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(2_000L, 2_000L) // Low: 250 KB/s
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)

        SpeedLimiter.download.recordForwardedForApp(100_000, gen, uid = 10150, packages = setOf("app.one"))
        SpeedLimiter.upload.recordForwardedForApp(40_000, gen, uid = 10150, packages = setOf("app.one"))

        // Switch to Unlimited at t0 + 4s
        val t1 = t0 + 4_000L
        SpeedLimiter.setLimitsKbps(null, null)
        SpeedPlanUsageTracker.onAppliedLimitsChanged(null, null, nowMillis = t1, contextOverride = context)

        SpeedLimiter.download.recordForwardedForApp(500_000, gen, uid = 10150, packages = setOf("app.one"))
        SpeedLimiter.upload.recordForwardedForApp(200_000, gen, uid = 10150, packages = setOf("app.one"))

        // Pause/close active segment at t0 + 7s and resume via onMeterSample at t0 + 8s within same generation
        SpeedPlanUsageTracker.closeActiveSegmentForTransition("SEGMENT_PAUSED", nowMillis = t0 + 7_000L)
        SpeedLimiter.download.recordForwardedForApp(300_000, gen, uid = 10150, packages = setOf("app.one"))
        SpeedLimiter.upload.recordForwardedForApp(60_000, gen, uid = 10150, packages = setOf("app.one"))
        SpeedPlanUsageTracker.onMeterSample(context, gen, 200_000L, 50_000L, 500L, nowMillis = t0 + 8_000L)

        // Disconnect at t0 + 12s
        SpeedPlanUsageTracker.onVpnDisconnected(context, gen, nowMillis = t0 + 12_000L)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        val records = UsageRepository.readAllSpeedPlanUsageSessions(context)
        assertEquals(3, records.size)
        assertEquals(listOf(1, 2, 3), records.map { it.segmentIndex })
        assertEquals(3, records.map { it.sessionId }.distinct().size)
        assertEquals(900_000L, records.sumOf { it.downloadBytes })
        assertEquals(300_000L, records.sumOf { it.uploadBytes })
    }

    @Test
    fun homeScreenManualReset_zerosUiSessionCounterWithoutAffectingPlanUsageHistory() {
        val gen = 1L
        val t0 = 1_729_600_400_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(8_000L, 8_000L)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)

        SpeedLimiter.download.recordForwardedForApp(150_000, gen, uid = 10150, packages = setOf("app.one"))
        SpeedLimiter.resetSessionCounters(gen)
        assertEquals(0L, SpeedLimiter.download.sessionBytes(gen))

        SpeedLimiter.download.recordForwardedForApp(250_000, gen, uid = 10150, packages = setOf("app.one"))
        SpeedPlanUsageTracker.onVpnDisconnected(context, gen, nowMillis = t0 + 8_000L)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        val rec = UsageRepository.readAllSpeedPlanUsageSessions(context).single()
        assertEquals(400_000L, rec.downloadBytes)
    }

    @Test
    fun exceedanceWaveDuration_tracksFullWaveAfterTwoSecondsAndHandlesTimeGaps() {
        val gen = 1L
        val t0 = 1_729_600_500_000L
        SpeedLimiter.beginSession(gen)
        // 1,000 Kbps = 1,000,000 bps; 115% threshold = 1,150,000 bps
        SpeedLimiter.setLimitsKbps(1_000L, 1_000L)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)

        var now = t0
        // 1) 1.5-second spike (3 x 500ms = 1,500ms < 2,000ms) -> must NOT count as exceedance
        repeat(3) {
            now += 500L
            SpeedPlanUsageTracker.onMeterSample(context, gen, 1_300_000L, 500_000L, 500L, nowMillis = now)
        }
        now += 500L
        SpeedPlanUsageTracker.onMeterSample(context, gen, 900_000L, 500_000L, 500L, nowMillis = now)
        val snapAfterShortWave = SpeedPlanUsageTracker.snapshotActiveSegmentRecord(now)!!
        assertFalse(snapAfterShortWave.limitExceeded)
        assertEquals(0L, snapAfterShortWave.exceedanceDurationMillis)
        assertEquals(0.0, snapAfterShortWave.maxExceedancePct, 0.001)

        // 2) Sparse samples separated by 3,000ms unmeasured time gaps -> must NOT bridge across gaps
        repeat(4) {
            now += 3_000L
            SpeedPlanUsageTracker.onMeterSample(context, gen, 1_400_000L, 500_000L, 500L, nowMillis = now)
        }
        val snapAfterGappedSamples = SpeedPlanUsageTracker.snapshotActiveSegmentRecord(now)!!
        assertFalse(snapAfterGappedSamples.limitExceeded)
        assertEquals(0L, snapAfterGappedSamples.exceedanceDurationMillis)

        // Normal sample to re-anchor cadence
        now += 500L
        SpeedPlanUsageTracker.onMeterSample(context, gen, 900_000L, 500_000L, 500L, nowMillis = now)

        // 3) Confirmed 3.0-second continuous exceedance wave (6 x 500ms = 3,000ms at 1,250,000 bps = +25%)
        repeat(6) {
            now += 500L
            SpeedPlanUsageTracker.onMeterSample(context, gen, 1_250_000L, 500_000L, 500L, nowMillis = now)
        }
        now += 500L
        SpeedPlanUsageTracker.onMeterSample(context, gen, 950_000L, 500_000L, 500L, nowMillis = now)

        SpeedPlanUsageTracker.onVpnDisconnected(context, gen, nowMillis = now + 500L)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        val finalRecord = UsageRepository.readAllSpeedPlanUsageSessions(context).single()
        assertTrue(finalRecord.limitExceeded)
        assertEquals(3_000L, finalRecord.exceedanceDurationMillis)
        assertEquals(25.0, finalRecord.maxExceedancePct, 0.01)
    }

    @Test
    fun lateOngoingCheckpointAndOrphanRecovery_preserveTerminalStatusAndIsolateProcessTokens() {
        val baseRec = SpeedPlanUsageRecord(
            sessionId = "sess-terminal-guard",
            vpnGeneration = 1L,
            segmentIndex = 1,
            processToken = "proc-old",
            startedAtMillis = 1_729_600_000_000L,
            endedAtMillis = 1_729_600_020_000L,
            lastCheckpointMillis = 1_729_600_020_000L,
            durationMillis = 20_000L,
            planOptionCategory = SpeedPlanOptionCategory.MEDIUM,
            planOptionLabel = "متوسط (1 MB/s)",
            downloadLimitKbps = 8_000L,
            uploadLimitKbps = 8_000L,
            downloadLimitUnitLabel = "1 MB/s (8 Mbps)",
            uploadLimitUnitLabel = "1 MB/s (8 Mbps)",
            downloadBytes = 500_000L,
            uploadBytes = 200_000L,
            totalBytes = 700_000L,
            avgDownloadBps = 200_000L,
            avgUploadBps = 80_000L,
            peakDownloadBps = 300_000L,
            peakUploadBps = 120_000L,
            status = SpeedPlanRecordStatus.COMPLETED,
            closeReason = "VPN_DISCONNECTED",
            limitExceeded = false,
            maxExceedancePct = 0.0,
            exceedanceDurationMillis = 0L,
        )
        UsageRepository.upsertSpeedPlanUsageSession(context, baseRec)

        // Late out-of-order ONGOING checkpoint with smaller byte count arrives after COMPLETED
        val staleCheckpoint = baseRec.copy(
            endedAtMillis = null,
            lastCheckpointMillis = 1_729_600_015_000L,
            durationMillis = 15_000L,
            downloadBytes = 350_000L,
            uploadBytes = 150_000L,
            totalBytes = 500_000L,
            status = SpeedPlanRecordStatus.ONGOING,
            closeReason = "ACTIVE",
        )
        UsageRepository.upsertSpeedPlanUsageSession(context, staleCheckpoint)

        val stored = UsageRepository.readAllSpeedPlanUsageSessions(context).single()
        assertEquals(SpeedPlanRecordStatus.COMPLETED, stored.status)
        assertEquals("VPN_DISCONNECTED", stored.closeReason)
        assertEquals(500_000L, stored.downloadBytes)
        assertEquals(200_000L, stored.uploadBytes)

        // Insert two ONGOING sessions: one from dead process "proc-dead" and one from "proc-alive"
        val deadOrphan = baseRec.copy(
            sessionId = "sess-orphan-dead",
            processToken = "proc-dead",
            startedAtMillis = 1_729_600_100_000L,
            endedAtMillis = null,
            lastCheckpointMillis = 1_729_600_112_000L,
            durationMillis = 12_000L,
            status = SpeedPlanRecordStatus.ONGOING,
            closeReason = "ACTIVE",
        )
        val liveSession = deadOrphan.copy(
            sessionId = "sess-live-current",
            processToken = "proc-alive",
            startedAtMillis = 1_729_600_200_000L,
            lastCheckpointMillis = 1_729_600_205_000L,
        )
        UsageRepository.upsertSpeedPlanUsageSession(context, deadOrphan)
        UsageRepository.upsertSpeedPlanUsageSession(context, liveSession)

        val recoveredCount = UsageRepository.recoverOrphanedSpeedPlanSessions(
            context = context,
            activeProcessToken = "proc-alive",
            nowMillis = 1_729_600_300_000L,
        )
        assertEquals(1, recoveredCount)

        val byId = UsageRepository.readAllSpeedPlanUsageSessions(context).associateBy { it.sessionId }
        assertEquals(SpeedPlanRecordStatus.ABNORMAL_END, byId.getValue("sess-orphan-dead").status)
        assertEquals(1_729_600_112_000L, byId.getValue("sess-orphan-dead").endedAtMillis)
        assertEquals(SpeedPlanRecordStatus.ONGOING, byId.getValue("sess-live-current").status)
        assertNull(byId.getValue("sess-live-current").endedAtMillis)
    }

    @Test
    fun liveUiCounterReads_doNotTriggerSqliteWritesOrFullHistoryReadsOnEveryMeterTick() {
        val gen = 1L
        val t0 = 1_729_600_600_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(8_000L, 8_000L)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        // Initial screen load reads SQLite once
        val cachedPersisted = UsageRepository.readAllSpeedPlanUsageSessions(context)
        val writesBeforeLoop = UsageRepository.speedPlanDbWriteCount.get()
        val readsBeforeLoop = UsageRepository.speedPlanDbReadCount.get()

        // Simulate 8 meter samples at 500ms intervals (4.0s total < 5.0s checkpoint interval,
        // and 8 * 10 KB = 80 KB < 256 KB byte delta threshold) while UI merges live state on each tick.
        var now = t0
        repeat(8) {
            now += 500L
            SpeedLimiter.download.recordForwardedForApp(10_000, gen, uid = 10150, packages = setOf("app.one"))
            SpeedPlanUsageTracker.onMeterSample(context, gen, 160_000L, 0L, 500L, nowMillis = now)
            val uiMerged = SpeedPlanUsageTracker.mergePersistedWithLiveActive(cachedPersisted, nowMillis = now)
            assertEquals(1, uiMerged.size)
            assertTrue(uiMerged.single().downloadBytes >= 10_000L)
        }
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        // Zero SQLite reads and zero SQLite writes occurred during the 8 sub-checkpoint 500ms ticks!
        assertEquals(readsBeforeLoop, UsageRepository.speedPlanDbReadCount.get())
        assertEquals(writesBeforeLoop, UsageRepository.speedPlanDbWriteCount.get())

        // At 5.0s (10th tick), the 5-second checkpoint policy triggers exactly 1 background write
        now += 1_000L
        SpeedPlanUsageTracker.onMeterSample(context, gen, 160_000L, 0L, 500L, nowMillis = now)
        SpeedPlanUsageTracker.flushPendingWritesForTests()
        assertEquals(writesBeforeLoop + 1L, UsageRepository.speedPlanDbWriteCount.get())
    }

    @Test
    fun migrationV4ToV5_viaUsageDbHelperAndUsageRepository_endToEndCrudAndOrphanRecovery() {
        val dbName = "speedvpn_usage.db"
        context.deleteDatabase(dbName)
        val v4Db = context.openOrCreateDatabase(dbName, 0, null)
        v4Db.execSQL(
            """
            CREATE TABLE app_policy (
                package_name TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                blocked INTEGER NOT NULL DEFAULT 0,
                daily_limit_bytes INTEGER
            )
            """.trimIndent(),
        )
        v4Db.execSQL(
            """
            CREATE TABLE app_quota_policy (
                package_name TEXT NOT NULL,
                quota_type TEXT NOT NULL CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
                limit_bytes INTEGER NOT NULL CHECK (limit_bytes > 0),
                period_start_millis INTEGER NOT NULL,
                period_end_millis INTEGER NOT NULL,
                used_bytes INTEGER NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
                download_bytes INTEGER NOT NULL DEFAULT 0 CHECK (download_bytes >= 0),
                upload_bytes INTEGER NOT NULL DEFAULT 0 CHECK (upload_bytes >= 0),
                baseline_initialized INTEGER NOT NULL DEFAULT 0 CHECK (baseline_initialized IN (0, 1)),
                reset_behavior TEXT NOT NULL DEFAULT 'AUTO_RESET'
                    CHECK (reset_behavior IN ('AUTO_RESET', 'BLOCK_UNTIL_RESET')),
                updated_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (package_name, quota_type)
            )
            """.trimIndent(),
        )
        v4Db.execSQL(
            """
            CREATE TABLE daily_usage (
                date TEXT NOT NULL,
                package_name TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                download_bytes INTEGER NOT NULL DEFAULT 0,
                upload_bytes INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (date, package_name)
            )
            """.trimIndent(),
        )
        v4Db.execSQL("PRAGMA user_version = 4")
        v4Db.close()

        // Trigger UsageDbHelper.onUpgrade(db, 4, 5) on the canonical database and exercise UsageRepository
        val orphanFromPriorRun = SpeedPlanUsageRecord(
            sessionId = "sess-migrated-orphan",
            vpnGeneration = 1L,
            segmentIndex = 1,
            processToken = "proc-prior-crashed",
            startedAtMillis = 1_729_600_000_000L,
            endedAtMillis = null,
            lastCheckpointMillis = 1_729_600_010_000L,
            durationMillis = 10_000L,
            planOptionCategory = SpeedPlanOptionCategory.LOW,
            planOptionLabel = "منخفض (250 KB/s)",
            downloadLimitKbps = 2_000L,
            uploadLimitKbps = 2_000L,
            downloadLimitUnitLabel = "250 KB/s (2 Mbps)",
            uploadLimitUnitLabel = "250 KB/s (2 Mbps)",
            downloadBytes = 250_000L,
            uploadBytes = 50_000L,
            totalBytes = 300_000L,
            avgDownloadBps = 200_000L,
            avgUploadBps = 40_000L,
            peakDownloadBps = 240_000L,
            peakUploadBps = 48_000L,
            status = SpeedPlanRecordStatus.ONGOING,
            closeReason = "ACTIVE",
            limitExceeded = false,
            maxExceedancePct = 0.0,
            exceedanceDurationMillis = 0L,
        )
        UsageRepository.upsertSpeedPlanUsageSession(context, orphanFromPriorRun)
        val recovered = UsageRepository.recoverOrphanedSpeedPlanSessions(
            context = context,
            activeProcessToken = "proc-current-alive",
            nowMillis = 1_729_600_020_000L,
        )
        assertEquals(1, recovered)

        val rows = UsageRepository.readAllSpeedPlanUsageSessions(context)
        assertEquals(1, rows.size)
        assertEquals(SpeedPlanRecordStatus.ABNORMAL_END, rows.single().status)
        assertEquals("PROCESS_KILLED_OR_REBOOTED", rows.single().closeReason)
        assertEquals(300_000L, rows.single().totalBytes)
    }

    @Test
    fun socks5ServerLoopbackIntegration_preservesRealTcpAndUdpFromOtherAppOwnUidAndUnattributedToSameEndpointAndExcludesProbesDuringVerifySpeed() {
        val gen = 1L
        val t0 = 1_729_660_000_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(null, null) // Unlimited for fast loopback transfer
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)
        assertTrue(SpeedLimiter.beginVerification(null, null))
        SpeedLimiter.setVerificationTrafficRoutingEnabled(true)
        val probeToken = SpeedLimiter.beginProbeMeasurementSession()

        val testNetwork: Network = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
            ?: ShadowNetwork.newInstance(100)
        val ioPool = Executors.newCachedThreadPool()
        // Use a SINGLE shared TCP upstream and SINGLE shared UDP upstream for both real user traffic
        // AND synthetic probe traffic so all flows connect to the EXACT SAME destination IP:port!
        val sharedTcpUpstream = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val sharedUdpUpstream = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

        SpeedLimiter.registerProbeEndpoint("127.0.0.1", sharedTcpUpstream.localPort)
        SpeedLimiter.registerProbeEndpoint("127.0.0.1", sharedUdpUpstream.localPort)

        val socks = Socks5Server(
            generation = gen,
            protectTcp = { true },
            protectUdp = { true },
            currentNetwork = { testNetwork },
            ipv6Enabled = false,
            requireUidAttribution = { false },
        )
        socks.start()

        try {
            fun startTcpEchoServer(srv: ServerSocket) {
                ioPool.execute {
                    while (!srv.isClosed) {
                        val conn = runCatching { srv.accept() }.getOrNull() ?: break
                        ioPool.execute {
                            conn.use { sock ->
                                val input = sock.getInputStream()
                                val output = sock.getOutputStream()
                                val buf = ByteArray(16_384)
                                val n = input.read(buf)
                                if (n > 0) {
                                    output.write(ByteArray(n * 2) { 0x5A })
                                    output.flush()
                                }
                            }
                        }
                    }
                }
            }

            fun startUdpEchoServer(srv: DatagramSocket) {
                ioPool.execute {
                    val buf = ByteArray(8_192)
                    while (!srv.isClosed) {
                        val pkt = DatagramPacket(buf, buf.size)
                        if (runCatching { srv.receive(pkt) }.isFailure) break
                        val replyBytes = ByteArray(pkt.length * 2) { 0x3C }
                        val reply = DatagramPacket(replyBytes, replyBytes.size, pkt.address, pkt.port)
                        runCatching { srv.send(reply) }
                    }
                }
            }

            startTcpEchoServer(sharedTcpUpstream)
            startUdpEchoServer(sharedUdpUpstream)

            fun runSocksTcpTransfer(
                authUsername: String,
                targetPort: Int,
                uploadSize: Int,
                preBoundClientSocket: Socket? = null,
            ): Int {
                val client = preBoundClientSocket ?: Socket("127.0.0.1", socks.port)
                client.use { sock ->
                    if (!sock.isConnected) {
                        sock.connect(java.net.InetSocketAddress("127.0.0.1", socks.port), 5_000)
                    }
                    sock.soTimeout = 5_000
                    val cin = DataInputStream(sock.getInputStream())
                    val cout = sock.getOutputStream()
                    cout.write(byteArrayOf(5, 1, 2))
                    cout.flush()
                    assertEquals(5, cin.readUnsignedByte())
                    assertEquals(2, cin.readUnsignedByte())

                    val uBytes = authUsername.toByteArray(Charsets.UTF_8)
                    val pBytes = socks.password.toByteArray(Charsets.UTF_8)
                    cout.write(byteArrayOf(1, uBytes.size.toByte()) + uBytes + byteArrayOf(pBytes.size.toByte()) + pBytes)
                    cout.flush()
                    assertEquals(1, cin.readUnsignedByte())
                    assertEquals(0, cin.readUnsignedByte())

                    val portHi = ((targetPort shr 8) and 0xff).toByte()
                    val portLo = (targetPort and 0xff).toByte()
                    cout.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, portHi, portLo))
                    cout.flush()
                    val rep = ByteArray(10)
                    cin.readFully(rep)
                    assertEquals(0, rep[1].toInt())

                    cout.write(ByteArray(uploadSize) { 0x11 })
                    cout.flush()
                    sock.shutdownOutput()
                    return cin.readBytes().size
                }
            }

            fun runSocksUdpTransfer(
                authUsername: String,
                targetPort: Int,
                uploadSize: Int,
                markUdpClientSocketAsProbe: Boolean = false,
            ): Int {
                Socket("127.0.0.1", socks.port).use { control ->
                    control.soTimeout = 5_000
                    val cin = DataInputStream(control.getInputStream())
                    val cout = control.getOutputStream()
                    cout.write(byteArrayOf(5, 1, 2))
                    cout.flush()
                    assertEquals(5, cin.readUnsignedByte())
                    assertEquals(2, cin.readUnsignedByte())

                    val uBytes = authUsername.toByteArray(Charsets.UTF_8)
                    val pBytes = socks.password.toByteArray(Charsets.UTF_8)
                    cout.write(byteArrayOf(1, uBytes.size.toByte()) + uBytes + byteArrayOf(pBytes.size.toByte()) + pBytes)
                    cout.flush()
                    assertEquals(1, cin.readUnsignedByte())
                    assertEquals(0, cin.readUnsignedByte())

                    cout.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                    cout.flush()
                    val rep = ByteArray(10)
                    cin.readFully(rep)
                    assertEquals(0, rep[1].toInt())
                    val relayPort = ((rep[8].toInt() and 0xff) shl 8) or (rep[9].toInt() and 0xff)

                    DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { udpClient ->
                        udpClient.soTimeout = 5_000
                        if (markUdpClientSocketAsProbe) {
                            SpeedLimiter.markProbeClientSocket(udpClient.localPort, "127.0.0.1", targetPort, SpeedLimiter.IPPROTO_UDP)
                        }
                        try {
                            val dstPortHi = ((targetPort shr 8) and 0xff).toByte()
                            val dstPortLo = (targetPort and 0xff).toByte()
                            val header = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, dstPortHi, dstPortLo)
                            val packetBytes = header + ByteArray(uploadSize) { 0x22 }
                            udpClient.send(
                                DatagramPacket(
                                    packetBytes,
                                    packetBytes.size,
                                    InetAddress.getByName("127.0.0.1"),
                                    relayPort,
                                ),
                            )
                            val recvBuf = ByteArray(16_384)
                            val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                            udpClient.receive(recvPkt)
                            return recvPkt.length - 10
                        } finally {
                            if (markUdpClientSocketAsProbe) {
                                SpeedLimiter.unmarkProbeClientSocket(udpClient.localPort, "127.0.0.1", targetPort, SpeedLimiter.IPPROTO_UDP)
                            }
                        }
                    }
                }
            }

            // Pre-bind and register the synthetic probe TCP socket BEFORE normal connections open,
            // AND call socks.recordDetectedFlow(..., isVerificationProbe = true) to prove that
            // registering a probe flow NEVER grants a destination-wide permit that could be
            // consumed by concurrent normal connections to the exact same destination IP:port!
            val probeTcpClientSocket = Socket()
            probeTcpClientSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
            SpeedLimiter.markProbeClientSocket(
                probeTcpClientSocket.localPort,
                "127.0.0.1",
                sharedTcpUpstream.localPort,
            )
            socks.recordDetectedFlow(
                protocol = 6,
                srcIp = "127.0.0.1",
                srcPort = probeTcpClientSocket.localPort,
                dstIp = "127.0.0.1",
                dstPort = sharedTcpUpstream.localPort,
                uid = 10999,
                packages = setOf("com.speedvpn.app"),
                isVerificationProbe = true,
            )

            // Launch concurrent normal + probe TCP transfers to the EXACT SAME destination IP:port!
            val startGate = java.util.concurrent.CountDownLatch(1)
            val normalOtherAppFuture = ioPool.submit<Int> {
                startGate.await()
                runSocksTcpTransfer("${socks.username}#10150", sharedTcpUpstream.localPort, 4_000)
            }
            val normalOwnUidFuture = ioPool.submit<Int> {
                startGate.await()
                runSocksTcpTransfer("${socks.username}#10999", sharedTcpUpstream.localPort, 2_500)
            }
            val normalUnattributedFuture = ioPool.submit<Int> {
                startGate.await()
                runSocksTcpTransfer(socks.username, sharedTcpUpstream.localPort, 3_000)
            }
            val probeOwnUidSocketBoundFuture = ioPool.submit<Int> {
                startGate.await()
                try {
                    runSocksTcpTransfer(
                        authUsername = "${socks.username}#10999",
                        targetPort = sharedTcpUpstream.localPort,
                        uploadSize = 5_000,
                        preBoundClientSocket = probeTcpClientSocket,
                    )
                } finally {
                    SpeedLimiter.unmarkProbeClientSocket(
                        probeTcpClientSocket.localPort,
                        "127.0.0.1",
                        sharedTcpUpstream.localPort,
                    )
                }
            }
            val probeUnattributedDecisionUsername = UidDetectionPoc.socksUsernameForDecision(
                baseUsername = socks.username,
                encodedDecision = (-1L shl 32) or 2L,
            )
            val probeUnattributedFuture = ioPool.submit<Int> {
                startGate.await()
                runSocksTcpTransfer(
                    authUsername = probeUnattributedDecisionUsername,
                    targetPort = sharedTcpUpstream.localPort,
                    uploadSize = 3_500,
                )
            }
            startGate.countDown()

            assertEquals(8_000, normalOtherAppFuture.get(10, TimeUnit.SECONDS))
            assertEquals(5_000, normalOwnUidFuture.get(10, TimeUnit.SECONDS))
            assertEquals(6_000, normalUnattributedFuture.get(10, TimeUnit.SECONDS))
            assertEquals(10_000, probeOwnUidSocketBoundFuture.get(10, TimeUnit.SECONDS))
            assertEquals(7_000, probeUnattributedFuture.get(10, TimeUnit.SECONDS))

            // Concurrent UDP transfers (normal other-app, normal own-UID, normal unattributed,
            // unattributed probe via 5-tuple SOCKS auth, and attributed probe via socket-port binding)
            // all targeting the EXACT SAME UDP destination IP:port!
            val udpGate = java.util.concurrent.CountDownLatch(1)
            val udpOtherAppFuture = ioPool.submit<Int> {
                udpGate.await()
                runSocksUdpTransfer("${socks.username}#10150", sharedUdpUpstream.localPort, 1_200)
            }
            val udpOwnUidRealFuture = ioPool.submit<Int> {
                udpGate.await()
                runSocksUdpTransfer("${socks.username}#10999", sharedUdpUpstream.localPort, 800)
            }
            val udpUnattrRealFuture = ioPool.submit<Int> {
                udpGate.await()
                runSocksUdpTransfer(socks.username, sharedUdpUpstream.localPort, 1_000)
            }
            val udpUnattrProbeFuture = ioPool.submit<Int> {
                udpGate.await()
                runSocksUdpTransfer(
                    authUsername = probeUnattributedDecisionUsername,
                    targetPort = sharedUdpUpstream.localPort,
                    uploadSize = 900,
                )
            }
            val udpOwnUidProbeFuture = ioPool.submit<Int> {
                udpGate.await()
                runSocksUdpTransfer(
                    authUsername = "${socks.username}#10999",
                    targetPort = sharedUdpUpstream.localPort,
                    uploadSize = 1_500,
                    markUdpClientSocketAsProbe = true,
                )
            }
            udpGate.countDown()

            assertEquals(2_400, udpOtherAppFuture.get(10, TimeUnit.SECONDS))
            assertEquals(1_600, udpOwnUidRealFuture.get(10, TimeUnit.SECONDS))
            assertEquals(2_000, udpUnattrRealFuture.get(10, TimeUnit.SECONDS))
            assertEquals(1_800, udpUnattrProbeFuture.get(10, TimeUnit.SECONDS))
            assertEquals(3_000, udpOwnUidProbeFuture.get(10, TimeUnit.SECONDS))
        } finally {
            SpeedLimiter.endProbeMeasurementSession(probeToken)
            socks.stop()
            runCatching { sharedTcpUpstream.close() }
            runCatching { sharedUdpUpstream.close() }
            ioPool.shutdownNow()
            ioPool.awaitTermination(2, TimeUnit.SECONDS)
        }

        SpeedLimiter.endVerification(null, null)
        SpeedPlanUsageTracker.onVpnDisconnected(context, gen, nowMillis = t0 + 5_000L)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        // Expected real user bytes (other app + own UID non-probe + unattributed real, all hitting the SAME IP:port as the probe):
        // DL = (8,000 + 2,400) + (5,000 + 1,600) + (6,000 + 2,000) = 25,000
        // UL = (4,000 + 1,200) + (2,500 + 800) + (3,000 + 1,000) = 12,500
        // Expected synthetic probe bytes (unattributed probe + attributed probe):
        // DL = (7,000 + 1,800) + (10,000 + 3,000) = 21,800
        // UL = (3,500 + 900) + (5,000 + 1,500) = 10,900
        assertEquals(25_000L, SpeedLimiter.download.planSessionBytes(gen))
        assertEquals(12_500L, SpeedLimiter.upload.planSessionBytes(gen))
        assertEquals(21_800L, SpeedLimiter.download.verificationProbeBytes(gen))
        assertEquals(10_900L, SpeedLimiter.upload.verificationProbeBytes(gen))

        val rec = UsageRepository.readAllSpeedPlanUsageSessions(context).single()
        assertEquals(25_000L, rec.downloadBytes)
        assertEquals(12_500L, rec.uploadBytes)
        assertEquals(37_500L, rec.totalBytes)
    }

    @Test
    fun markedTcpProbeAndUnmarkedRealUdpSharingSameLocalPortAndDestinationWithUnresolvedUid_recordsAllUdpBytesInPlanHistory() {
        val gen = 1L
        val t0 = 1_729_680_000_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(null, null)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)
        assertTrue(SpeedLimiter.beginVerification(null, null))
        SpeedLimiter.setVerificationTrafficRoutingEnabled(true)
        val probeToken = SpeedLimiter.beginProbeMeasurementSession()

        // Bind a TCP socket and a UDP DatagramSocket to the EXACT SAME local port (sharedClientPort)
        // and target the EXACT SAME destination IP and port (127.0.0.1:sharedTargetPort).
        val tcpProbeClient = Socket()
        tcpProbeClient.reuseAddress = true
        tcpProbeClient.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        val sharedClientPort = tcpProbeClient.localPort
        val udpRealClient = DatagramSocket(java.net.InetSocketAddress("127.0.0.1", sharedClientPort))
        val sharedTargetPort = 24443

        try {
            SpeedLimiter.registerProbeEndpoint("127.0.0.1", sharedTargetPort)
            // Mark ONLY the TCP probe socket (protocol = IPPROTO_TCP = 6)
            SpeedLimiter.markProbeClientSocket(
                localPort = sharedClientPort,
                remoteHostOrIp = "127.0.0.1",
                remotePort = sharedTargetPort,
                protocol = SpeedLimiter.IPPROTO_TCP,
            )

            assertTrue(
                SpeedLimiter.isMarkedProbeClientSocket(
                    localPort = sharedClientPort,
                    remoteHostOrIp = "127.0.0.1",
                    remotePort = sharedTargetPort,
                    protocol = SpeedLimiter.IPPROTO_TCP,
                ),
            )
            assertFalse(
                "Real UDP flow sharing the same local port and destination must NOT match TCP probe mark",
                SpeedLimiter.isMarkedProbeClientSocket(
                    localPort = sharedClientPort,
                    remoteHostOrIp = "127.0.0.1",
                    remotePort = sharedTargetPort,
                    protocol = SpeedLimiter.IPPROTO_UDP,
                ),
            )

            // Unresolved UID (-1 / null) for both the marked TCP probe flow and the unmarked real UDP flow
            val tcpProbeIsMarked = SpeedLimiter.isMarkedProbeClientSocket(
                localPort = sharedClientPort,
                remoteHostOrIp = "127.0.0.1",
                remotePort = sharedTargetPort,
                protocol = UidDetectionPoc.IPPROTO_TCP,
            )
            val udpRealIsMarked = SpeedLimiter.isMarkedProbeClientSocket(
                localPort = sharedClientPort,
                remoteHostOrIp = "127.0.0.1",
                remotePort = sharedTargetPort,
                protocol = UidDetectionPoc.IPPROTO_UDP,
            )
            val tcpDecision = (-1L shl 32) or (if (tcpProbeIsMarked) 2L else 0L)
            val udpDecision = (-1L shl 32) or (if (udpRealIsMarked) 2L else 0L)
            assertEquals("vpnuser#probe", UidDetectionPoc.socksUsernameForDecision("vpnuser", tcpDecision))
            assertEquals("vpnuser", UidDetectionPoc.socksUsernameForDecision("vpnuser", udpDecision))

            // Forward TCP probe chunks (uid = null): 300 KB DL + 150 KB UL -> must go to verificationProbeBytes
            val sink = ByteArrayOutputStream()
            writeAdmittedTcpRelayChunk(
                destination = sink,
                buffer = ByteArray(300_000),
                requestedBytes = 300_000,
                decision = QuotaTrafficDecision(300_000),
                bucket = SpeedLimiter.download,
                generation = gen,
                packages = emptySet(),
                uid = null,
                direction = QuotaTrafficDirection.DOWNLOAD,
                hasOwner = false,
                isVerificationProbe = tcpProbeIsMarked,
                remoteAddress = "127.0.0.1",
                remotePort = sharedTargetPort,
                flowMarker = "tcp-relay:probe:${SpeedLimiter.IPPROTO_TCP}|$sharedClientPort->127.0.0.1:$sharedTargetPort",
            )
            writeAdmittedTcpRelayChunk(
                destination = sink,
                buffer = ByteArray(150_000),
                requestedBytes = 150_000,
                decision = QuotaTrafficDecision(150_000),
                bucket = SpeedLimiter.upload,
                generation = gen,
                packages = emptySet(),
                uid = null,
                direction = QuotaTrafficDirection.UPLOAD,
                hasOwner = false,
                isVerificationProbe = tcpProbeIsMarked,
                remoteAddress = "127.0.0.1",
                remotePort = sharedTargetPort,
                flowMarker = "tcp-relay:probe:${SpeedLimiter.IPPROTO_TCP}|$sharedClientPort->127.0.0.1:$sharedTargetPort",
            )

            // Forward real UDP datagrams on the exact same (sharedClientPort, 127.0.0.1, sharedTargetPort) with uid = null:
            // 110 KB UL + 220 KB DL -> 100% MUST be recorded in planSessionBytes!
            val udpFlowKey = "src:127.0.0.1:$sharedClientPort|dst:127.0.0.1:$sharedTargetPort"
            val udpUpResult = deliverAdmittedUdpDatagramDetailed(
                payloadBytes = 110_000,
                decision = QuotaTrafficDecision(110_000),
                packages = emptySet(),
                uid = null,
                direction = QuotaTrafficDirection.UPLOAD,
                hasOwner = false,
                sendDatagram = { true },
            )
            assertTrue(udpUpResult.delivered)
            SpeedLimiter.upload.recordForwardedForApp(
                n = 110_000,
                generation = gen,
                uid = null,
                packages = emptySet(),
                isVerificationProbeFlow = udpRealIsMarked,
                remoteAddress = "127.0.0.1",
                remotePort = sharedTargetPort,
                flowMarker = udpFlowKey,
            )

            val udpDownResult = deliverAdmittedUdpDatagramDetailed(
                payloadBytes = 220_000,
                decision = QuotaTrafficDecision(220_000),
                packages = emptySet(),
                uid = null,
                direction = QuotaTrafficDirection.DOWNLOAD,
                hasOwner = false,
                sendDatagram = { true },
            )
            assertTrue(udpDownResult.delivered)
            SpeedLimiter.download.recordForwardedForApp(
                n = 220_000,
                generation = gen,
                uid = null,
                packages = emptySet(),
                isVerificationProbeFlow = udpRealIsMarked,
                remoteAddress = "127.0.0.1",
                remotePort = sharedTargetPort,
                flowMarker = udpFlowKey,
            )
        } finally {
            SpeedLimiter.unmarkProbeClientSocket(
                localPort = sharedClientPort,
                remoteHostOrIp = "127.0.0.1",
                remotePort = sharedTargetPort,
                protocol = SpeedLimiter.IPPROTO_TCP,
            )
            SpeedLimiter.endProbeMeasurementSession(probeToken)
            runCatching { tcpProbeClient.close() }
            runCatching { udpRealClient.close() }
        }

        SpeedLimiter.endVerification(null, null)
        SpeedPlanUsageTracker.onVpnDisconnected(context, gen, nowMillis = t0 + 5_000L)
        SpeedPlanUsageTracker.flushPendingWritesForTests()

        assertEquals(220_000L, SpeedLimiter.download.planSessionBytes(gen))
        assertEquals(110_000L, SpeedLimiter.upload.planSessionBytes(gen))
        assertEquals(300_000L, SpeedLimiter.download.verificationProbeBytes(gen))
        assertEquals(150_000L, SpeedLimiter.upload.verificationProbeBytes(gen))

        val rec = UsageRepository.readAllSpeedPlanUsageSessions(context).single()
        assertEquals(220_000L, rec.downloadBytes)
        assertEquals(110_000L, rec.uploadBytes)
        assertEquals(330_000L, rec.totalBytes)
    }
}
