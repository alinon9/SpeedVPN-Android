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
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaResetEngineTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { context.deleteDatabase("speedvpn_usage.db") }
    @After fun tearDown() { context.deleteDatabase("speedvpn_usage.db") }

    @Test
    fun engineResetsDailyWeeklyAndMonthlyPolicies() {
        val p = "com.example.allperiods"
        UsageRepository.setQuotaPolicy(context,p,"All",1002,QuotaType.DAILY,100L)
        UsageRepository.setQuotaPolicy(context,p,"All",1002,QuotaType.WEEKLY,200L)
        UsageRepository.setQuotaPolicy(context,p,"All",1002,QuotaType.MONTHLY,300L)
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 2, 12, 0, 0); set(Calendar.MILLISECOND,0)
        }.timeInMillis
        assertEquals(3, QuotaResetEngine.resetExpiredPolicies(context, now))
        UsageRepository.readQuotaPolicies(context,p).forEach {
            val b=QuotaPeriod.current(it.quotaType,now)
            assertEquals(b.startMillis,it.periodStartMillis)
            assertEquals(b.endMillis,it.periodEndMillis)
            assertEquals(0L,it.usedBytes)
        }
    }

    @Test
    fun resetIsIdempotentWithinSamePeriod() {
        val p="com.example.idempotent"
        UsageRepository.setQuotaPolicy(context,p,"Idempotent",1003,QuotaType.DAILY,100L)
        val now=System.currentTimeMillis()
        assertEquals(0,QuotaResetEngine.resetExpiredPolicies(context,now))
        assertEquals(0,QuotaResetEngine.resetExpiredPolicies(context,now))
    }

    @Test
    fun blockUntilResetPreservesResetBehavior() {
        val p="com.example.block"
        UsageRepository.setQuotaPolicy(context,p,"Block",1004,QuotaType.DAILY,100L,ResetBehavior.BLOCK_UNTIL_RESET)
        val policy=UsageRepository.readQuotaPolicies(context,p).single()
        UsageDbHelper(context).use { db ->
            db.writableDatabase.update(
                "app_quota_policy",
                android.content.ContentValues().apply { put("used_bytes", 999L) },
                "package_name = ? AND quota_type = ?",
                arrayOf(p, QuotaType.DAILY.name),
            )
        }
        assertEquals(1,QuotaResetEngine.resetExpiredPolicies(context,policy.periodEndMillis+1L))
        val reset=UsageRepository.readQuotaPolicies(context,p).single()
        assertEquals(0L,reset.usedBytes)
        assertEquals(ResetBehavior.BLOCK_UNTIL_RESET,reset.resetBehavior)
    }
}
