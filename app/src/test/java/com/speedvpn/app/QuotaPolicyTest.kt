package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QuotaPolicyTest {
    @Test
    fun quotaTypesAreIndependent() {
        val types = QuotaType.values().toSet()
        assertEquals(setOf(QuotaType.DAILY, QuotaType.WEEKLY, QuotaType.MONTHLY), types)
    }

    @Test
    fun currentPeriodsContainReferenceTime() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 34)
            set(Calendar.SECOND, 56)
            set(Calendar.MILLISECOND, 789)
        }.timeInMillis

        QuotaType.values().forEach { type ->
            val (start, end) = QuotaPeriod.current(type, now)
            assertTrue("start must be <= now for $type", start <= now)
            assertTrue("end must be > now for $type", end >= now)
        }
    }

    @Test
    fun periodLengthsFollowQuotaType() {
        val now = 1_700_000_000_000L
        val daily = QuotaPeriod.current(QuotaType.DAILY, now)
        val weekly = QuotaPeriod.current(QuotaType.WEEKLY, now)
        val monthly = QuotaPeriod.current(QuotaType.MONTHLY, now)

        assertTrue(daily.second > daily.first)
        assertTrue(weekly.second > weekly.first)
        assertTrue(monthly.second > monthly.first)
        assertTrue(weekly.second - weekly.first >= 6L * 24 * 60 * 60 * 1000)
        assertTrue(monthly.second - monthly.first >= 27L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun policyPreservesIndependentLimits() {
        val policies = QuotaType.values().map {
            AppQuotaPolicy(
                packageName = "com.example.app",
                uid = 12345,
                quotaType = it,
                limitBytes = when (it) {
                    QuotaType.DAILY -> 100_000_000L
                    QuotaType.WEEKLY -> 500_000_000L
                    QuotaType.MONTHLY -> 2_000_000_000L
                },
                periodStartMillis = 0L,
                periodEndMillis = 1L,
                usedBytes = 0L,
                resetBehavior = QuotaResetBehavior.AUTO_RESET,
            )
        }

        assertEquals(3, policies.size)
        assertEquals(100_000_000L, policies.single { it.quotaType == QuotaType.DAILY }.limitBytes)
        assertEquals(500_000_000L, policies.single { it.quotaType == QuotaType.WEEKLY }.limitBytes)
        assertEquals(2_000_000_000L, policies.single { it.quotaType == QuotaType.MONTHLY }.limitBytes)
    }
}
