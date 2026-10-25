package com.speedvpn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsToggleAndUiOrganizationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase("speedvpn_usage.db")
        context.getSharedPreferences(VpnAppControl.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("smart_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AppTrafficManager.clearLiveRelayTrafficForTests()
        QuotaEnforcementEngine.invalidateQuotaBaselineCache()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("speedvpn_usage.db")
        context.getSharedPreferences(VpnAppControl.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("smart_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AppTrafficManager.clearLiveRelayTrafficForTests()
        QuotaEnforcementEngine.invalidateQuotaBaselineCache()
    }

    @Test
    fun freshInstallWithUnsetPreference_showsStatsDisabledAndStopsOptionalCollectionWhileEnforcingQuotas() {
        val pkg = "org.telegram.messenger"
        val uid = 10150
        val now = 1_729_600_000_000L
        val today = UsageDate.today()

        // 1. On a fresh install (preference key never written), UI state and backend state must both be false.
        assertFalse(SmartSettings.isStatsEnabled(context))
        assertFalse(SmartSettings.observeStatsEnabled(context).value)

        // 2. Configure a 1,000-byte daily quota while stats toggle has never been touched.
        UsageRepository.setQuotaPolicy(context, pkg, "Telegram", uid, QuotaType.DAILY, 1_000L)
        assertTrue(QuotaWorkScheduler.scheduledForTests)

        // 3. Admit and settle 1,000 bytes of relay traffic.
        val decision = QuotaEnforcementEngine.accountRelayBytes(
            context = context,
            packageNames = setOf(pkg),
            uid = uid,
            direction = QuotaTrafficDirection.DOWNLOAD,
            requestedBytes = 1_000,
            nowMillis = now,
        )
        assertEquals(1_000, decision.allowedBytes)
        QuotaEnforcementEngine.settleRelayBytes(
            context = context,
            packageNames = setOf(pkg),
            uid = uid,
            direction = QuotaTrafficDirection.DOWNLOAD,
            decision = decision,
            outcome = QuotaSettlementOutcome.TCP_WRITE_SUCCEEDED,
            forwardedBytes = 1_000,
            nowMillis = now,
        )

        // 4. Optional live relay display counters must remain 0 because stats are disabled by default.
        assertEquals(0L, AppTrafficManager.liveRelayUsage(uid, pkg, today).totalBytes)

        // 5. Mandatory quota enforcement MUST have counted the 1,000 bytes and blocked the package.
        val policy = UsageRepository.readQuotaPolicies(context, pkg).single()
        assertEquals(1_000L, policy.usedBytes)
        assertTrue(pkg in VpnAppControl.quotaBlockedPackages(context))
    }

    @Test
    fun explicitlyDisabledStats_stopsOptionalCollectionPreservesHistoryAndEnforcesQuotasAcrossRestart() {
        val pkg = "com.instagram.android"
        val uid = 10152
        val now = 1_729_600_100_000L
        val today = UsageDate.today()

        // Enable stats and seed historical daily usage
        SmartSettings.setStatsEnabled(context, true)
        assertTrue(SmartSettings.isStatsEnabled(context))
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(
                date = today,
                packageName = pkg,
                label = "Instagram",
                uid = uid,
                downloadBytes = 10_000_000L,
                uploadBytes = 5_000_000L,
            ),
        )
        assertEquals(15_000_000L, UsageRepository.totalUsage(context, today))

        // Configure quota policy
        UsageRepository.setQuotaPolicy(context, pkg, "Instagram", uid, QuotaType.DAILY, 500L)
        assertTrue(QuotaWorkScheduler.scheduledForTests)

        // User explicitly turns OFF statistics collection
        SmartSettings.setStatsEnabled(context, false)
        assertFalse(SmartSettings.isStatsEnabled(context))
        assertFalse(SmartSettings.observeStatsEnabled(context).value)
        assertFalse(UsageCollectionScheduler.scheduledForTests)
        // QuotaWorkScheduler must remain scheduled because an active quota policy exists
        assertTrue(QuotaWorkScheduler.scheduledForTests)

        // Historical records must remain intact
        assertEquals(15_000_000L, UsageRepository.totalUsage(context, today))

        // Forward 500 bytes -> optional live traffic stays 0, mandatory quota blocks the app
        val decision = QuotaEnforcementEngine.accountRelayBytes(
            context = context,
            packageNames = setOf(pkg),
            uid = uid,
            direction = QuotaTrafficDirection.UPLOAD,
            requestedBytes = 500,
            nowMillis = now,
        )
        QuotaEnforcementEngine.settleRelayBytes(
            context = context,
            packageNames = setOf(pkg),
            uid = uid,
            direction = QuotaTrafficDirection.UPLOAD,
            decision = decision,
            outcome = QuotaSettlementOutcome.TCP_WRITE_SUCCEEDED,
            forwardedBytes = 500,
            nowMillis = now,
        )
        assertEquals(0L, AppTrafficManager.liveRelayUsage(uid, pkg, today).totalBytes)
        assertTrue(pkg in VpnAppControl.quotaBlockedPackages(context))
    }
}
