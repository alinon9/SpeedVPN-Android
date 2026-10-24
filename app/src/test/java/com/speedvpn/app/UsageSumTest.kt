package com.speedvpn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageSumTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase("speedvpn_usage.db")
    }

    @After
    fun tearDown() {
        context.deleteDatabase("speedvpn_usage.db")
    }

    @Test
    fun sumsRowsForPackageWithinInclusiveDateRange() {
        val packageName = "com.example.target"
        insert(packageName, "2026-01-02", 100L, 50L)
        insert(packageName, "2026-01-05", 200L, 100L)
        insert(packageName, "2026-01-08", 300L, 150L)

        assertEquals(
            900L,
            UsageRepository.sumUsageInPeriod(
                context,
                packageName,
                LocalDate.of(2026, 1, 2).toString(),
                LocalDate.of(2026, 1, 8).toString(),
            ),
        )
    }

    @Test
    fun excludesRowsForOtherPackages() {
        insert("com.example.target", "2026-01-05", 200L, 100L)
        insert("com.example.other", "2026-01-05", 900L, 900L)

        assertEquals(
            300L,
            UsageRepository.sumUsageInPeriod(
                context,
                "com.example.target",
                LocalDate.of(2026, 1, 1).toString(),
                LocalDate.of(2026, 1, 10).toString(),
            ),
        )
    }

    @Test
    fun excludesRowsOutsideDateRange() {
        val packageName = "com.example.target"
        insert(packageName, "2025-12-31", 1000L, 1000L)
        insert(packageName, "2026-01-05", 200L, 100L)
        insert(packageName, "2026-01-11", 3000L, 3000L)

        assertEquals(
            300L,
            UsageRepository.sumUsageInPeriod(
                context,
                packageName,
                LocalDate.of(2026, 1, 1).toString(),
                LocalDate.of(2026, 1, 10).toString(),
            ),
        )
    }

    @Test
    fun returnsZeroWhenNoRowsMatch() {
        insert("com.example.other", "2026-01-05", 900L, 900L)

        assertEquals(
            0L,
            UsageRepository.sumUsageInPeriod(
                context,
                "com.example.target",
                LocalDate.of(2026, 1, 1).toString(),
                LocalDate.of(2026, 1, 10).toString(),
            ),
        )
    }

    @Test
    fun batchUpsertPreservesHigherPreviouslyRecordedBytesAndCreatesAppPolicy() {
        val date = "2026-01-09"
        UsageRepository.upsertDailyUsageBatch(
            context,
            listOf(
                DailyUsageRow(date, "com.example.a", "App A", 10101, 5_000L, 2_000L),
                DailyUsageRow(date, "com.example.b", "App B", 10102, 1_000L, 500L),
            ),
        )
        // A lagging NetworkStats snapshot with lower values must not shrink existing daily totals.
        UsageRepository.upsertDailyUsageBatch(
            context,
            listOf(
                DailyUsageRow(date, "com.example.a", "App A", 10101, 3_000L, 4_000L),
                DailyUsageRow(date, "com.example.b", "App B", 10102, 2_500L, 200L),
            ),
        )

        val rows = UsageRepository.topUsage(context, date, 10).associateBy { it.packageName }
        assertEquals(5_000L, rows.getValue("com.example.a").downloadBytes)
        assertEquals(4_000L, rows.getValue("com.example.a").uploadBytes)
        assertEquals(2_500L, rows.getValue("com.example.b").downloadBytes)
        assertEquals(500L, rows.getValue("com.example.b").uploadBytes)
        assertEquals(setOf("com.example.a", "com.example.b"), UsageRepository.readPolicies(context).map { it.packageName }.toSet())
    }

    private fun insert(
        packageName: String,
        date: String,
        downloadBytes: Long,
        uploadBytes: Long,
    ) {
        UsageRepository.upsertDailyUsage(
            context,
            DailyUsageRow(
                date = date,
                packageName = packageName,
                label = packageName,
                uid = 10001,
                downloadBytes = downloadBytes,
                uploadBytes = uploadBytes,
            ),
        )
    }
}
