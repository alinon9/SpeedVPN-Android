package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuotaFinalBoundaryTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun dailyBoundaryIsMidnight() {
        val now = cal(2026, Calendar.OCTOBER, 3, 15, 42)
        val b = QuotaPeriod.current(QuotaType.DAILY, now.timeInMillis)
        val start = cal(2026, Calendar.OCTOBER, 3, 0, 0).timeInMillis
        assertEquals(start, b.startMillis)
        assertEquals(start + 86_400_000L - 1L, b.endMillis)
    }

    @Test
    fun weeklyBoundaryStartsMonday() {
        val now = cal(2026, Calendar.OCTOBER, 7, 12, 0)
        val b = QuotaPeriod.current(QuotaType.WEEKLY, now.timeInMillis)
        val monday = cal(2026, Calendar.OCTOBER, 5, 0, 0).timeInMillis
        assertEquals(monday, b.startMillis)
        assertEquals(monday + 7L * 86_400_000L - 1L, b.endMillis)
    }

    @Test
    fun monthlyBoundaryStartsFirstDay() {
        val now = cal(2026, Calendar.OCTOBER, 20, 12, 0)
        val b = QuotaPeriod.current(QuotaType.MONTHLY, now.timeInMillis)
        assertEquals(cal(2026, Calendar.OCTOBER, 1, 0, 0).timeInMillis, b.startMillis)
    }

    private fun cal(y: Int, m: Int, d: Int, h: Int, min: Int): Calendar =
        Calendar.getInstance(utc).apply {
            set(y, m, d, h, min, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
