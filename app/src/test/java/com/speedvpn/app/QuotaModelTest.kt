package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuotaModelTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun dailyPeriodStartsAtLocalMidnight() {
        val now = millis(2026, 10, 3, 14)
        val bounds = QuotaPeriod.current(QuotaType.DAILY, now)
        val start = Calendar.getInstance(utc).apply { timeInMillis = bounds.startMillis }
        val end = Calendar.getInstance(utc).apply { timeInMillis = bounds.endMillis }

        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(Calendar.MINUTE))
        assertEquals(0, start.get(Calendar.SECOND))
        assertEquals(23, end.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, end.get(Calendar.MINUTE))
        assertEquals(59, end.get(Calendar.SECOND))
        assertTrue(bounds.endMillis > bounds.startMillis)
    }

    @Test
    fun weeklyPeriodAlwaysStartsOnMonday() {
        val now = millis(2026, 10, 3, 14) // Saturday
        val bounds = QuotaPeriod.current(QuotaType.WEEKLY, now)
        val start = Calendar.getInstance(utc).apply { timeInMillis = bounds.startMillis }
        val end = Calendar.getInstance(utc).apply { timeInMillis = bounds.endMillis }

        assertEquals(Calendar.MONDAY, start.get(Calendar.DAY_OF_WEEK))
        assertEquals(7, ((end.timeInMillis - start.timeInMillis + 1L) / (24L * 60L * 60L * 1000L)).toInt())
    }

    @Test
    fun monthlyPeriodStartsOnFirstAndEndsBeforeNextMonth() {
        val now = millis(2026, 10, 3, 14)
        val bounds = QuotaPeriod.current(QuotaType.MONTHLY, now)
        val start = Calendar.getInstance(utc).apply { timeInMillis = bounds.startMillis }
        val end = Calendar.getInstance(utc).apply { timeInMillis = bounds.endMillis }

        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.OCTOBER, start.get(Calendar.MONTH))
        assertEquals(Calendar.OCTOBER, end.get(Calendar.MONTH))
        assertEquals(31, end.get(Calendar.DAY_OF_MONTH))
        assertTrue(bounds.endMillis > bounds.startMillis)
    }

    @Test
    fun quotaTypesAreIndependent() {
        val policies = listOf(
            AppQuotaPolicy("com.example", 10001, QuotaType.DAILY, 100L, 1L, 2L, 0L, ResetBehavior.AUTO_RESET),
            AppQuotaPolicy("com.example", 10001, QuotaType.WEEKLY, 200L, 3L, 4L, 0L, ResetBehavior.BLOCK_UNTIL_RESET),
            AppQuotaPolicy("com.example", 10001, QuotaType.MONTHLY, 300L, 5L, 6L, 0L, ResetBehavior.AUTO_RESET),
        )

        assertEquals(3, policies.distinctBy { it.quotaType }.size)
        assertEquals(setOf(QuotaType.DAILY, QuotaType.WEEKLY, QuotaType.MONTHLY), policies.map { it.quotaType }.toSet())
    }
}
