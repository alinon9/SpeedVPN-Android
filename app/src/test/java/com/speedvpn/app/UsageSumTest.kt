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
