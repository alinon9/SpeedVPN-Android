package com.speedvpn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
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
