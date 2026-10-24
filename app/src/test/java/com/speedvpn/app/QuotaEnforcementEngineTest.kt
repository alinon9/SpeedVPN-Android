package com.speedvpn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaEnforcementEngineTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase("speedvpn_usage.db")
        context.getSharedPreferences(VpnAppControl.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("smart_settings", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("stats_enabled", true).commit()
        AppTrafficManager.clearLiveRelayTrafficForTests()
        QuotaEnforcementEngine.invalidateQuotaBaselineCache()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("speedvpn_usage.db")
        context.getSharedPreferences(VpnAppControl.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        AppTrafficManager.clearLiveRelayTrafficForTests()
        QuotaEnforcementEngine.invalidateQuotaBaselineCache()
    }

    @Test
    fun exceededDailyQuotaIsBlocked() {
        val packageName = "com.example.over"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Over", 2001, QuotaType.DAILY, 1_000L,
        )
        val now = System.currentTimeMillis()
        val date = date(now)
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(date, packageName, "Over", 2001, 700L, 400L),
        )
        UsageDatabaseTestSupport.setQuotaUsedBytes(context, packageName, QuotaType.DAILY, 1_100L)

        assertEquals(setOf(packageName), QuotaEnforcementEngine.evaluateBlockedPackages(context, now))
    }

    @Test
    fun usageBelowQuotaIsNotBlocked() {
        val packageName = "com.example.under"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Under", 2002, QuotaType.DAILY, 1_000L,
        )
        val now = System.currentTimeMillis()
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(date(now), packageName, "Under", 2002, 600L, 300L),
        )
        UsageDatabaseTestSupport.setQuotaUsedBytes(context, packageName, QuotaType.DAILY, 900L)

        assertEquals(emptySet<String>(), QuotaEnforcementEngine.evaluateBlockedPackages(context, now))
    }

    @Test
    fun differentPackageUsageDoesNotCount() {
        val packageName = "com.example.target"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Target", 2003, QuotaType.DAILY, 1_000L,
        )
        val now = System.currentTimeMillis()
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(date(now), "com.example.other", "Other", 2004, 5_000L, 5_000L),
        )

        assertEquals(emptySet<String>(), QuotaEnforcementEngine.evaluateBlockedPackages(context, now))
    }

    @Test
    fun weeklyQuotaAggregatesMultipleDays() {
        val packageName = "com.example.weekly"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Weekly", 2005, QuotaType.WEEKLY, 2_000L,
        )
        val now = System.currentTimeMillis()
        val bounds = QuotaPeriod.current(QuotaType.WEEKLY, now)
        val start = bounds.startMillis
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(date(start), packageName, "Weekly", 2005, 1_100L, 0L),
        )
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(date(start + 86_400_000L), packageName, "Weekly", 2005, 700L, 300L),
        )
        UsageDatabaseTestSupport.setQuotaUsedBytes(context, packageName, QuotaType.WEEKLY, 2_100L)

        assertEquals(setOf(packageName), QuotaEnforcementEngine.evaluateBlockedPackages(context, now))
    }


    @Test
    fun restartThrottleAllowsFirstBlocksSecondThenAllowsAfterFiveSeconds() {
        QuotaEnforcementEngine.resetRestartThrottleForTests()
        val base = System.currentTimeMillis()

        assertEquals(
            true,
            QuotaEnforcementEngine.tryAcquireRestart(base),
        )
        assertEquals(
            false,
            QuotaEnforcementEngine.tryAcquireRestart(base + 4_999L),
        )
        assertEquals(
            true,
            QuotaEnforcementEngine.tryAcquireRestart(base + 5_000L),
        )
    }

    @Test
    fun deferredRestartRunsOnceAfterThrottleWindow() {
        QuotaEnforcementEngine.resetRestartThrottleForTests()
        val base = 1_000_000L
        var restarts = 0
        var scheduledDelay = -1L
        var scheduledAction: (() -> Unit)? = null

        QuotaEnforcementEngine.requestRestartForTests(
            context = context,
            nowMillis = base,
            restart = { restarts++ },
            postDelayed = { delay, action ->
                scheduledDelay = delay
                scheduledAction = action
            },
            clock = { base + 5_000L },
        )
        assertEquals(1, restarts)

        QuotaEnforcementEngine.requestRestartForTests(
            context = context,
            nowMillis = base + 1_000L,
            restart = { restarts++ },
            postDelayed = { delay, action ->
                scheduledDelay = delay
                scheduledAction = action
            },
            clock = { base + 5_000L },
        )

        assertEquals(4_000L, scheduledDelay)
        scheduledAction!!.invoke()
        assertEquals(2, restarts)
    }

    @Test
    fun newerImmediateRestartCancelsStaleDeferredRestart() {
        QuotaEnforcementEngine.resetRestartThrottleForTests()
        val base = 2_000_000L
        var restarts = 0
        var cancellations = 0
        var staleAction: (() -> Unit)? = null

        QuotaEnforcementEngine.requestRestartForTests(
            context = context,
            nowMillis = base,
            restart = { restarts++ },
            postDelayed = { _, action -> staleAction = action },
            clock = { base + 5_000L },
        )

        QuotaEnforcementEngine.requestRestartForTests(
            context = context,
            nowMillis = base + 1_000L,
            restart = { restarts++ },
            postDelayed = { _, action -> staleAction = action },
            clock = { base + 5_000L },
        )
        assertEquals(1, restarts)

        QuotaEnforcementEngine.requestRestartForTests(
            context = context,
            nowMillis = base + 5_000L,
            restart = { restarts++ },
            postDelayed = { _, action -> staleAction = action },
            cancelPending = { cancellations++ },
            clock = { base + 5_000L },
        )

        assertEquals(2, restarts)
        assertEquals(1, cancellations)

        // Simulate a stale callback that was already dequeued before cancellation.
        staleAction!!.invoke()
        assertEquals(2, restarts)
    }

    @Test
    fun lazyResetClearsExpiredPolicyOnFirstEnforcement() {
        val packageName = "com.example.lazy"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Lazy", 2011, QuotaType.DAILY, 1_000L,
            resetBehavior = ResetBehavior.BLOCK_UNTIL_RESET,
        )
        val now = System.currentTimeMillis()
        val current = QuotaPeriod.current(QuotaType.DAILY, now)
        val oldStart = current.startMillis - 86_400_000L
        val oldEnd = current.endMillis - 86_400_000L

        UsageRepository.resetQuotaPeriod(
            context, packageName, QuotaType.DAILY, oldStart, oldEnd,
        )
        UsageDatabaseTestSupport.setQuotaUsedBytes(context, packageName, QuotaType.DAILY, 999L)

        QuotaEnforcementEngine.evaluateBlockedPackages(context, now)

        val policy = UsageRepository.readQuotaPolicies(context, packageName).single()
        assertEquals(0L, policy.usedBytes)
        assertEquals(current.startMillis, policy.periodStartMillis)
        assertEquals(current.endMillis, policy.periodEndMillis)
        assertEquals(ResetBehavior.BLOCK_UNTIL_RESET, policy.resetBehavior)
    }

    @Test
    fun policyIsNotResetInsideCurrentPeriod() {
        val packageName = "com.example.stable"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Stable", 2012, QuotaType.DAILY, 1_000L,
        )
        val now = System.currentTimeMillis()
        val current = QuotaPeriod.current(QuotaType.DAILY, now)
        UsageDatabaseTestSupport.setQuotaUsedBytes(context, packageName, QuotaType.DAILY, 999L)

        val resets = QuotaResetEngine.resetExpiredPolicies(context, now)

        val policy = UsageRepository.readQuotaPolicies(context, packageName).single()
        assertEquals(0, resets)
        assertEquals(999L, policy.usedBytes)
        assertEquals(current.startMillis, policy.periodStartMillis)
        assertEquals(current.endMillis, policy.periodEndMillis)
    }

    @Test
    fun unchangedQuotaBlockSetDoesNotTriggerEnforcementChange() {
        val packageName = "com.example.unchanged"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Unchanged", 2013, QuotaType.DAILY, 1_000L,
        )

        var restarts = 0
        assertEquals(
            false,
            QuotaEnforcementEngine.enforce(
                context,
                System.currentTimeMillis(),
                restart = { restarts++ },
            ),
        )
        assertEquals(0, restarts)
    }

    @Test
    fun duplicateEnforcementIsIdempotent() {
        val packageName = "com.example.idempotent"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Idempotent", 2014, QuotaType.DAILY, 1_000L,
        )
        val now = System.currentTimeMillis()
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(date(now), packageName, "Idempotent", 2014, 700L, 400L),
        )
        UsageDatabaseTestSupport.setQuotaUsedBytes(context, packageName, QuotaType.DAILY, 1_100L)

        assertEquals(
            true,
            QuotaEnforcementEngine.enforce(context, now, restart = {}),
        )
        assertEquals(
            false,
            QuotaEnforcementEngine.enforce(context, now, restart = {}),
        )
        assertEquals(
            setOf(packageName),
            VpnAppControl.quotaBlockedPackages(context),
        )
    }

    @Test
    fun workerIsIdempotentForUnchangedQuotaState() = runBlocking {
        val packageName = "com.example.worker"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Worker", 2015, QuotaType.DAILY, 1_000L,
        )
        val now = System.currentTimeMillis()
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(date(now), packageName, "Worker", 2015, 700L, 400L),
        )
        UsageDatabaseTestSupport.setQuotaUsedBytes(context, packageName, QuotaType.DAILY, 1_100L)
        context.getSharedPreferences("smart_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("stats_enabled", true).commit()

        val first = TestListenableWorkerBuilder<QuotaEnforcementWorker>(context).build()
        val second = TestListenableWorkerBuilder<QuotaEnforcementWorker>(context).build()

        assertEquals(ListenableWorker.Result.success().toString(), first.doWork().toString())
        assertEquals(setOf(packageName), VpnAppControl.quotaBlockedPackages(context))

        assertEquals(ListenableWorker.Result.success().toString(), second.doWork().toString())
        assertEquals(setOf(packageName), VpnAppControl.quotaBlockedPackages(context))
    }

    @Test
    fun quotaBlockedPackagesPersistAcrossReads() {
        val packageName = "com.example.persist"
        VpnAppControl.replaceQuotaBlockedPackages(context, setOf(packageName))

        assertEquals(
            setOf(packageName),
            VpnAppControl.quotaBlockedPackages(context),
        )
        assertEquals(
            setOf(packageName),
            VpnAppControl.quotaBlockedPackages(context),
        )
    }

    @Test
    fun relayAccountingCountsBothDirectionsAndStopsAtTheExactQuotaBoundary() {
        val packageName = "com.example.relayquota"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Relay quota", 2020, QuotaType.DAILY, 1_000L,
        )

        val upload = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(packageName), 2020, QuotaTrafficDirection.UPLOAD, 700,
        )
        assertEquals(700, upload.allowedBytes)
        assertEquals(emptySet<String>(), upload.exhaustedPackages)

        val download = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(packageName), 2020, QuotaTrafficDirection.DOWNLOAD, 500,
        )
        assertEquals("Only the remaining quota bytes may pass", 300, download.allowedBytes)
        assertEquals(setOf(packageName), download.exhaustedPackages)

        val policy = UsageRepository.readQuotaPolicies(context, packageName).single()
        assertEquals(1_000L, policy.usedBytes)
        assertEquals(300L, policy.downloadBytes)
        assertEquals(700L, policy.uploadBytes)
        assertEquals(setOf(packageName), VpnAppControl.quotaBlockedPackages(context))
    }

    @Test
    fun editingQuotaLimitDoesNotResetBytesAlreadyCounted() {
        val packageName = "com.example.editquota"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Edit quota", 2022, QuotaType.DAILY, 1_000L,
        )
        val first = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(packageName), 2022, QuotaTrafficDirection.UPLOAD, 300,
        )
        assertEquals(300, first.allowedBytes)

        UsageRepository.setQuotaPolicy(
            context, packageName, "Edit quota", 2022, QuotaType.DAILY, 1_500L,
        )
        val afterEdit = UsageRepository.readQuotaPolicies(context, packageName).single()
        assertEquals(300L, afterEdit.usedBytes)
        assertEquals(300L, afterEdit.uploadBytes)

        val finalChunk = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(packageName), 2022, QuotaTrafficDirection.DOWNLOAD, 1_300,
        )
        assertEquals(1_200, finalChunk.allowedBytes)
        assertEquals(setOf(packageName), finalChunk.exhaustedPackages)
    }

    @Test
    fun quotaEnforcementDoesNotDependOnStatsToggleOrUsageAccess() = runBlocking {
        val packageName = "com.example.statsdisabled"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Stats disabled", 2021, QuotaType.DAILY, 100L,
        )
        UsageDatabaseTestSupport.setQuotaUsedBytes(context, packageName, QuotaType.DAILY, 100L)
        context.getSharedPreferences("smart_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("stats_enabled", false).commit()

        val worker = TestListenableWorkerBuilder<QuotaEnforcementWorker>(context).build()
        assertEquals(ListenableWorker.Result.success().toString(), worker.doWork().toString())
        assertEquals(setOf(packageName), VpnAppControl.quotaBlockedPackages(context))
    }

    @Test
    fun partialTcpWriteRecordsOnlyWrittenPrefixAndDroppedUdpRecordsZeroLiveStats() {
        val tcpPackage = "com.example.partialtcp"
        val tcpUid = 2030
        val now = System.currentTimeMillis()
        val today = date(now)
        UsageRepository.setQuotaPolicy(
            context, tcpPackage, "Partial TCP", tcpUid, QuotaType.DAILY, 1_000L,
        )

        // 1. Admission alone increments inFlightReservedBytes, NOT live-forwarded counters.
        val upDecision = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(tcpPackage), tcpUid, QuotaTrafficDirection.UPLOAD, 700, now,
        )
        assertEquals(700, upDecision.allowedBytes)
        assertEquals(700L, QuotaEnforcementEngine.inFlightReservedBytes(tcpPackage))
        assertEquals(0L, QuotaEnforcementEngine.committedForwardedBytes(tcpPackage))
        assertEquals(0L, AppTrafficManager.liveRelayUsage(tcpUid, tcpPackage, today).uploadBytes)

        val upBuffer = ByteArray(700) { 1 }
        val upStream = java.io.ByteArrayOutputStream()
        val upWritten = writeAdmittedTcpRelayChunk(
            destination = upStream,
            buffer = upBuffer,
            requestedBytes = 700,
            decision = upDecision,
            bucket = TokenBucket(),
            generation = 1L,
            packages = setOf(tcpPackage),
            uid = tcpUid,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
            settleQuotaBytes = { pkgs, uid, dir, dec, outcome, bytes ->
                QuotaEnforcementEngine.settleRelayBytes(context, pkgs, uid, dir, dec, outcome, bytes, now)
            },
        )
        assertEquals(700, upWritten)
        assertEquals(700, upStream.size())
        assertEquals(0L, QuotaEnforcementEngine.inFlightReservedBytes(tcpPackage))
        assertEquals(700L, QuotaEnforcementEngine.committedForwardedBytes(tcpPackage))
        assertEquals(700L, AppTrafficManager.liveRelayUsage(tcpUid, tcpPackage, today).uploadBytes)

        // 2. Request 500 download bytes when only 300 remain in quota:
        // TCP writes only the admitted 300-byte prefix, and live stats must record 300 (never 500).
        val downDecision = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(tcpPackage), tcpUid, QuotaTrafficDirection.DOWNLOAD, 500, now,
        )
        assertEquals(300, downDecision.allowedBytes)
        assertEquals(setOf(tcpPackage), downDecision.exhaustedPackages)
        assertEquals(300L, QuotaEnforcementEngine.inFlightReservedBytes(tcpPackage))
        assertEquals(0L, AppTrafficManager.liveRelayUsage(tcpUid, tcpPackage, today).downloadBytes)

        val downBuffer = ByteArray(500) { 2 }
        val downStream = java.io.ByteArrayOutputStream()
        val downWritten = writeAdmittedTcpRelayChunk(
            destination = downStream,
            buffer = downBuffer,
            requestedBytes = 500,
            decision = downDecision,
            bucket = TokenBucket(),
            generation = 1L,
            packages = setOf(tcpPackage),
            uid = tcpUid,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
            settleQuotaBytes = { pkgs, uid, dir, dec, outcome, bytes ->
                QuotaEnforcementEngine.settleRelayBytes(context, pkgs, uid, dir, dec, outcome, bytes, now)
            },
        )
        assertEquals(300, downWritten)
        assertEquals(300, downStream.size())
        assertEquals(0L, QuotaEnforcementEngine.inFlightReservedBytes(tcpPackage))
        assertEquals(1_000L, QuotaEnforcementEngine.committedForwardedBytes(tcpPackage))
        val tcpLive = AppTrafficManager.liveRelayUsage(tcpUid, tcpPackage, today)
        assertEquals(300L, tcpLive.downloadBytes)
        assertEquals(700L, tcpLive.uploadBytes)
        assertEquals(setOf(tcpPackage), VpnAppControl.quotaBlockedPackages(context))

        // 3. Confirmed UDP drop (allowedBytes < payloadBytes) or send failure releases durable
        // reservation and records 0 live bytes so the remaining 300 bytes stay usable.
        val udpPackage = "com.example.droppedudp"
        val udpUid = 2031
        UsageRepository.setQuotaPolicy(
            context, udpPackage, "Dropped UDP", udpUid, QuotaType.DAILY, 1_000L,
        )
        val firstUdpDecision = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(udpPackage), udpUid, QuotaTrafficDirection.UPLOAD, 700, now,
        )
        val firstDelivered = deliverAdmittedUdpDatagram(
            payloadBytes = 700,
            decision = firstUdpDecision,
            packages = setOf(udpPackage),
            uid = udpUid,
            direction = QuotaTrafficDirection.UPLOAD,
            hasOwner = true,
            sendDatagram = { true },
            settleQuotaBytes = { pkgs, uid, dir, dec, outcome, bytes ->
                QuotaEnforcementEngine.settleRelayBytes(context, pkgs, uid, dir, dec, outcome, bytes, now)
            },
        )
        assertEquals(true, firstDelivered)
        assertEquals(700L, AppTrafficManager.liveRelayUsage(udpUid, udpPackage, today).uploadBytes)

        // Second UDP datagram requests 500 bytes when only 300 remain -> dropped in full,
        // reservation of 300 bytes is released back to durable SQLite quota (usedBytes == 700).
        val secondUdpDecision = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(udpPackage), udpUid, QuotaTrafficDirection.DOWNLOAD, 500, now,
        )
        assertEquals(300, secondUdpDecision.allowedBytes)
        var datagramSent = false
        val secondDelivery = deliverAdmittedUdpDatagramDetailed(
            payloadBytes = 500,
            decision = secondUdpDecision,
            packages = setOf(udpPackage),
            uid = udpUid,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
            sendDatagram = {
                datagramSent = true
                true
            },
            settleQuotaBytes = { pkgs, uid, dir, dec, outcome, bytes ->
                QuotaEnforcementEngine.settleRelayBytes(context, pkgs, uid, dir, dec, outcome, bytes, now)
            },
        )
        assertEquals(false, secondDelivery.delivered)
        assertEquals(false, datagramSent)
        assertEquals(emptySet<String>(), secondDelivery.settledDecision.exhaustedPackages)
        val policyAfterDrop = UsageRepository.readQuotaPolicies(context, udpPackage).single()
        assertEquals("Dropped UDP datagram must release its 300-byte reservation", 700L, policyAfterDrop.usedBytes)
        assertEquals(0L, policyAfterDrop.downloadBytes)
        assertEquals(700L, policyAfterDrop.uploadBytes)
        assertEquals(false, udpPackage in VpnAppControl.quotaBlockedPackages(context))
        val udpLive = AppTrafficManager.liveRelayUsage(udpUid, udpPackage, today)
        assertEquals("Dropped UDP datagram must not increase live download stats", 0L, udpLive.downloadBytes)
        assertEquals(700L, udpLive.uploadBytes)

        // Subsequent 300-byte UDP datagram that fits within the remaining 300-byte budget succeeds and exhausts quota.
        val thirdUdpDecision = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(udpPackage), udpUid, QuotaTrafficDirection.DOWNLOAD, 300, now,
        )
        assertEquals(300, thirdUdpDecision.allowedBytes)
        val thirdDelivery = deliverAdmittedUdpDatagramDetailed(
            payloadBytes = 300,
            decision = thirdUdpDecision,
            packages = setOf(udpPackage),
            uid = udpUid,
            direction = QuotaTrafficDirection.DOWNLOAD,
            hasOwner = true,
            sendDatagram = { true },
            settleQuotaBytes = { pkgs, uid, dir, dec, outcome, bytes ->
                QuotaEnforcementEngine.settleRelayBytes(context, pkgs, uid, dir, dec, outcome, bytes, now)
            },
        )
        assertEquals(true, thirdDelivery.delivered)
        assertEquals(setOf(udpPackage), thirdDelivery.settledDecision.exhaustedPackages)
        val policyAfterThird = UsageRepository.readQuotaPolicies(context, udpPackage).single()
        assertEquals(1_000L, policyAfterThird.usedBytes)
        assertEquals(300L, policyAfterThird.downloadBytes)
        assertEquals(700L, policyAfterThird.uploadBytes)
        assertTrue(udpPackage in VpnAppControl.quotaBlockedPackages(context))
    }

    @Test
    fun tcpWriteExceptionFollowsConservativeDurableQuotaRetentionWithoutIncrementingLiveStats() {
        val pkg = "com.example.tcpfail"
        val uid = 2032
        val now = System.currentTimeMillis()
        val today = date(now)
        UsageRepository.setQuotaPolicy(context, pkg, "TCP Fail", uid, QuotaType.DAILY, 1_000L)

        val decision = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(pkg), uid, QuotaTrafficDirection.UPLOAD, 1_000, now,
        )
        assertEquals(1_000, decision.allowedBytes)
        assertEquals(setOf(pkg), decision.exhaustedPackages)
        assertEquals(1_000L, QuotaEnforcementEngine.inFlightReservedBytes(pkg))

        val failingOutput = object : java.io.OutputStream() {
            override fun write(b: Int) {
                throw java.net.SocketException("Broken pipe")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw java.net.SocketException("Broken pipe")
            }
        }
        val bucket = TokenBucket().apply { beginSession(11L) }

        var threw = false
        try {
            writeAdmittedTcpRelayChunk(
                destination = failingOutput,
                buffer = ByteArray(1_000) { 1 },
                requestedBytes = 1_000,
                decision = decision,
                bucket = bucket,
                generation = 11L,
                packages = setOf(pkg),
                uid = uid,
                direction = QuotaTrafficDirection.UPLOAD,
                hasOwner = true,
                settleQuotaBytes = { pkgs, ownerUid, dir, dec, outcome, bytes ->
                    QuotaEnforcementEngine.settleRelayBytes(context, pkgs, ownerUid, dir, dec, outcome, bytes, now)
                },
            )
        } catch (_: java.net.SocketException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(0L, QuotaEnforcementEngine.inFlightReservedBytes(pkg))
        assertEquals(0L, QuotaEnforcementEngine.committedForwardedBytes(pkg))
        assertEquals(0L, bucket.sessionBytes(11L))
        assertEquals(0L, AppTrafficManager.liveRelayUsage(uid, pkg, today).uploadBytes)

        // Durable quota conservatively retains the 1,000-byte reservation so an app cannot
        // exploit socket write exceptions to undercount partially flushed kernel buffers.
        val policy = UsageRepository.readQuotaPolicies(context, pkg).single()
        assertEquals(1_000L, policy.usedBytes)
        assertEquals(1_000L, policy.uploadBytes)
        assertTrue(pkg in VpnAppControl.quotaBlockedPackages(context))
    }

    @Test
    fun concurrentFlowsCannotExceedConfiguredQuotaAndMaintainMultiPolicyConsistency() {
        val pkgA = "com.example.concurrent.a"
        val pkgB = "com.example.concurrent.b"
        val sharedUid = 2033
        val now = System.currentTimeMillis()
        val today = date(now)

        UsageRepository.setQuotaPolicy(context, pkgA, "Concurrent A", sharedUid, QuotaType.DAILY, 1_000L)
        UsageRepository.setQuotaPolicy(context, pkgA, "Concurrent A", sharedUid, QuotaType.WEEKLY, 2_000L)
        UsageRepository.setQuotaPolicy(context, pkgA, "Concurrent A", sharedUid, QuotaType.MONTHLY, 5_000L)
        UsageRepository.setQuotaPolicy(context, pkgB, "Concurrent B", sharedUid, QuotaType.DAILY, 1_000L)

        val packages = setOf(pkgA, pkgB)
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val doneLatch = java.util.concurrent.CountDownLatch(8)
        val totalCommitted = java.util.concurrent.atomic.AtomicInteger(0)

        repeat(8) {
            Thread {
                try {
                    startLatch.await()
                    val decision = QuotaEnforcementEngine.accountRelayBytes(
                        context, packages, sharedUid, QuotaTrafficDirection.DOWNLOAD, 250, now,
                    )
                    if (decision.allowedBytes > 0) {
                        val out = java.io.ByteArrayOutputStream()
                        val written = writeAdmittedTcpRelayChunk(
                            destination = out,
                            buffer = ByteArray(250) { 7 },
                            requestedBytes = 250,
                            decision = decision,
                            bucket = TokenBucket(),
                            generation = 12L,
                            packages = packages,
                            uid = sharedUid,
                            direction = QuotaTrafficDirection.DOWNLOAD,
                            hasOwner = true,
                            settleQuotaBytes = { pkgs, ownerUid, dir, dec, outcome, bytes ->
                                QuotaEnforcementEngine.settleRelayBytes(context, pkgs, ownerUid, dir, dec, outcome, bytes, now)
                            },
                        )
                        totalCommitted.addAndGet(written)
                    }
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1_000, totalCommitted.get())
        assertEquals(0L, QuotaEnforcementEngine.inFlightReservedBytes(pkgA))
        assertEquals(0L, QuotaEnforcementEngine.inFlightReservedBytes(pkgB))
        assertEquals(1_000L, QuotaEnforcementEngine.committedForwardedBytes(pkgA))
        assertEquals(1_000L, QuotaEnforcementEngine.committedForwardedBytes(pkgB))
        assertEquals(1_000L, AppTrafficManager.liveRelayUsage(sharedUid, pkgA, today).downloadBytes)

        val policiesA = UsageRepository.readQuotaPolicies(context, pkgA).associateBy { it.quotaType }
        assertEquals(1_000L, policiesA.getValue(QuotaType.DAILY).usedBytes)
        assertEquals(1_000L, policiesA.getValue(QuotaType.WEEKLY).usedBytes)
        assertEquals(1_000L, policiesA.getValue(QuotaType.MONTHLY).usedBytes)
        val policyB = UsageRepository.readQuotaPolicies(context, pkgB).single()
        assertEquals(1_000L, policyB.usedBytes)
    }

    @Test
    fun udpSendSucceedsBeforeProcessDeathAndBeforeSettlementRetainsQuotaAndPreventsBypass() {
        val udpPkg = "com.example.crash.udp"
        val tcpPkg = "com.example.crash.tcp"
        val udpUid = 2040
        val tcpUid = 2041
        val now = System.currentTimeMillis()

        UsageRepository.setQuotaPolicy(context, udpPkg, "Crash UDP", udpUid, QuotaType.DAILY, 1_000L)
        UsageRepository.setQuotaPolicy(context, udpPkg, "Crash UDP", udpUid, QuotaType.WEEKLY, 2_000L)
        UsageRepository.setQuotaPolicy(context, tcpPkg, "Crash TCP", tcpUid, QuotaType.DAILY, 1_000L)

        // 1. Commit 700 bytes of UDP traffic normally.
        val committedUdp = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(udpPkg), udpUid, QuotaTrafficDirection.UPLOAD, 700, now, QuotaTransport.UDP,
        )
        assertEquals(700, committedUdp.allowedBytes)
        QuotaEnforcementEngine.settleRelayBytes(
            context, setOf(udpPkg), udpUid, QuotaTrafficDirection.UPLOAD,
            committedUdp, QuotaSettlementOutcome.UDP_DELIVERED, 700, now,
        )

        // 2. Oversized UDP datagram (500 > 300 remaining) is rejected at admission without charging used_bytes.
        val oversizedUdp = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(udpPkg), udpUid, QuotaTrafficDirection.DOWNLOAD, 500, now, QuotaTransport.UDP,
        )
        assertEquals(0, oversizedUdp.allowedBytes)
        assertEquals(700L, UsageRepository.readQuotaPolicies(context, udpPkg).first { it.quotaType == QuotaType.DAILY }.usedBytes)

        // 3. Reserve the remaining 300 bytes for a fitting UDP datagram, simulate the UDP send
        // succeeding on the wire, and then simulate process death BEFORE settleRelayBytes() runs.
        val inFlightUdp = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(udpPkg), udpUid, QuotaTrafficDirection.DOWNLOAD, 300, now, QuotaTransport.UDP,
        )
        assertEquals(300, inFlightUdp.allowedBytes)
        var wireDatagramSent = false
        run {
            // The kernel UDP send completes, then the process is killed before settleRelayBytes().
            wireDatagramSent = true
        }
        assertTrue(wireDatagramSent)
        assertEquals(setOf(udpPkg), inFlightUdp.exhaustedPackages)
        assertTrue(udpPkg in VpnAppControl.quotaBlockedPackages(context))

        val inFlightTcp = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(tcpPkg), tcpUid, QuotaTrafficDirection.UPLOAD, 1_000, now, QuotaTransport.TCP,
        )
        assertEquals(1_000, inFlightTcp.allowedBytes)
        assertTrue(tcpPkg in VpnAppControl.quotaBlockedPackages(context))

        // Simulate abrupt process termination and restart before settleRelayBytes() executes.
        QuotaEnforcementEngine.simulateProcessRestartForTests(context, now + 100L)

        // 4. Because the process died after UDP send and before settlement, the outcome is uncertain
        // to the restarted process: it MUST conservatively retain the 300-byte reservation (total 1,000)
        // and keep udpPkg blocked so post-send crashes cannot undercount traffic or bypass quota.
        val udpPolicies = UsageRepository.readQuotaPolicies(context, udpPkg).associateBy { it.quotaType }
        assertEquals(1_000L, udpPolicies.getValue(QuotaType.DAILY).usedBytes)
        assertEquals(300L, udpPolicies.getValue(QuotaType.DAILY).downloadBytes)
        assertEquals(700L, udpPolicies.getValue(QuotaType.DAILY).uploadBytes)
        assertEquals(1_000L, udpPolicies.getValue(QuotaType.WEEKLY).usedBytes)
        assertTrue(udpPkg in VpnAppControl.quotaBlockedPackages(context))

        // A subsequent UDP datagram after restart must be rejected (0 allowedBytes).
        val afterRestartAttempt = QuotaEnforcementEngine.accountRelayBytes(
            context, setOf(udpPkg), udpUid, QuotaTrafficDirection.DOWNLOAD, 100, now + 200L, QuotaTransport.UDP,
        )
        assertEquals(0, afterRestartAttempt.allowedBytes)
        assertEquals(setOf(udpPkg), afterRestartAttempt.exhaustedPackages)

        val tcpPolicy = UsageRepository.readQuotaPolicies(context, tcpPkg).single()
        assertEquals(1_000L, tcpPolicy.usedBytes)
        assertEquals(1_000L, tcpPolicy.uploadBytes)
        assertTrue(tcpPkg in VpnAppControl.quotaBlockedPackages(context))
    }

    private object UsageDatabaseTestSupport {
        fun setQuotaUsedBytes(
            context: Context,
            packageName: String,
            quotaType: QuotaType,
            usedBytes: Long,
        ) {
            UsageDbHelper(context).use { helper ->
                helper.writableDatabase.update(
                    "app_quota_policy",
                    android.content.ContentValues().apply {
                        put("used_bytes", usedBytes)
                    },
                    "package_name = ? AND quota_type = ?",
                    arrayOf(packageName, quotaType.name),
                )
            }
        }
    }

    private fun date(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
