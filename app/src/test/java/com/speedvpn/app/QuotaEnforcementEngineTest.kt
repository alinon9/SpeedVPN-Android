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
    fun restartThrottleAllowsFirstBlocksSecondThenAllowsAfterFiveSeconds() {
        QuotaEnforcementEngine.resetRestartThrottleForTests()

        assertEquals(
            true,
            QuotaEnforcementEngine.tryAcquireRestart(System.currentTimeMillis()),
        )
        assertEquals(
            false,
            QuotaEnforcementEngine.tryAcquireRestart(System.currentTimeMillis() + 4_999L),
        )
        assertEquals(
            true,
            QuotaEnforcementEngine.tryAcquireRestart(System.currentTimeMillis() + 5_000L),
        )
    }

    @Test
    fun lazyResetClearsExpiredPolicyOnFirstEnforcement() {
        val packageName = "com.example.lazy"
        UsageRepository.setQuotaPolicy(
            context, packageName, "Lazy", 2011, QuotaType.DAILY, 1_000L,
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
