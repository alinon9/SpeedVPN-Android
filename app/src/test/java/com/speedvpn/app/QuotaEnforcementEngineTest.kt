package com.speedvpn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    }

    @After
    fun tearDown() {
        context.deleteDatabase("speedvpn_usage.db")
        context.getSharedPreferences(VpnAppControl.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
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

        assertEquals(setOf(packageName), QuotaEnforcementEngine.evaluateBlockedPackages(context, now))
    }


    @Test
    fun restartIsThrottledForFiveSeconds() {
        val packageName = "com.example.restart"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Restart", 2010, QuotaType.DAILY, 1_000L,
        )
        val now = System.currentTimeMillis()
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(date(now), packageName, "Restart", 2010, 700L, 400L),
        )

        QuotaEnforcementEngine.resetRestartThrottleForTests()
        var restarts = 0

        assertEquals(
            true,
            QuotaEnforcementEngine.enforce(context, now, restart = { restarts++ }),
        )
        assertEquals(1, restarts)

        assertEquals(
            false,
            QuotaEnforcementEngine.enforce(context, now + 4_999L, restart = { restarts++ }),
        )
        assertEquals(1, restarts)

        VpnAppControl.replaceQuotaBlockedPackages(context, emptySet())
        assertEquals(
            true,
            QuotaEnforcementEngine.enforce(context, now + 5_000L, restart = { restarts++ }),
        )
        assertEquals(2, restarts)
    }

    private fun date(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
