package com.speedvpn.app

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaDatabaseRobolectricTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()
    private val dbName = "quota_robolectric_test.db"
    private lateinit var db: UsageDbHelper

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationV1ToV2CopiesDailyLimit() {
        val old = context.openOrCreateDatabase(dbName, 0, null)
        old.execSQL("""
            CREATE TABLE app_policy (
                package_name TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                blocked INTEGER NOT NULL DEFAULT 0,
                daily_limit_bytes INTEGER
            )
        """.trimIndent())
        old.insertOrThrow("app_policy", null, ContentValues().apply {
            put("package_name", "com.example.legacy")
            put("label", "Legacy")
            put("uid", 12345)
            put("daily_limit_bytes", 500_000_000L)
        })
        old.close()

        db = UsageDbHelper(context, dbName, 2)
        db.readableDatabase.rawQuery(
            "SELECT quota_type, limit_bytes FROM app_quota_policy WHERE package_name = ?",
            arrayOf("com.example.legacy"),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("DAILY", it.getString(0))
            assertEquals(500_000_000L, it.getLong(1))
        }
    }

    @Test
    fun migrationDoesNotCreateDailyPolicyForZeroLimit() {
        val old = context.openOrCreateDatabase(dbName, 0, null)
        old.execSQL("""
            CREATE TABLE app_policy (
                package_name TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                blocked INTEGER NOT NULL DEFAULT 0,
                daily_limit_bytes INTEGER
            )
        """.trimIndent())
        old.insertOrThrow("app_policy", null, ContentValues().apply {
            put("package_name", "com.example.zero")
            put("label", "Zero")
            put("uid", 12346)
            put("daily_limit_bytes", 0L)
        })
        old.close()

        db = UsageDbHelper(context, dbName, 2)
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM app_quota_policy WHERE package_name = ?",
            arrayOf("com.example.zero"),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
    }

    @Test
    fun repositoryCrudSupportsAllQuotaTypesIndependently() {
        UsageRepository.setQuotaPolicy(context, "com.example.quota", "Quota", 20001, QuotaType.DAILY, 100L)
        UsageRepository.setQuotaPolicy(context, "com.example.quota", "Quota", 20001, QuotaType.WEEKLY, 200L)
        UsageRepository.setQuotaPolicy(context, "com.example.quota", "Quota", 20001, QuotaType.MONTHLY, 300L)

        val policies = UsageRepository.readQuotaPolicies(context, "com.example.quota")
        assertEquals(3, policies.size)
        assertEquals(setOf(100L, 200L, 300L), policies.map { it.limitBytes }.toSet())

        UsageRepository.deleteQuotaPolicy(context, "com.example.quota", QuotaType.WEEKLY)
        assertEquals(
            setOf(QuotaType.DAILY, QuotaType.MONTHLY),
            UsageRepository.readQuotaPolicies(context, "com.example.quota").map { it.quotaType }.toSet(),
        )
    }

    @Test
    fun cascadeDeleteRemovesQuotaPoliciesWhenAppPolicyIsDeleted() {
        UsageRepository.setQuotaPolicy(context, "com.example.cascade", "Cascade", 20002, QuotaType.DAILY, 100L)
        UsageRepository.setQuotaPolicy(context, "com.example.cascade", "Cascade", 20002, QuotaType.WEEKLY, 200L)

        db = UsageDbHelper(context)
        db.writableDatabase.delete("app_policy", "package_name = ?", arrayOf("com.example.cascade"))

        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM app_quota_policy WHERE package_name = ?",
            arrayOf("com.example.cascade"),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
    }

    @Test
    fun foreignKeysAreEnabled() {
        db = UsageDbHelper(context)
        db.readableDatabase.rawQuery("PRAGMA foreign_keys", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun packageNameIsPrimaryKeyInAppPolicy() {
        db = UsageDbHelper(context)
        db.readableDatabase.rawQuery("PRAGMA table_info(app_policy)", null).use {
            var primaryKeyFound = false
            while (it.moveToNext()) {
                if (it.getString(1) == "package_name" && it.getInt(5) > 0) {
                    primaryKeyFound = true
                }
            }
            assertTrue(primaryKeyFound)
        }
    }
}
