package com.speedvpn.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val USAGE_DB_NAME = "speedvpn_usage.db"
private const val USAGE_DB_VERSION = 1

internal data class AppPolicy(
    val packageName: String,
    val label: String,
    val uid: Int,
    val blocked: Boolean,
    val dailyLimitBytes: Long?,
)

internal data class DailyUsageRow(
    val date: String,
    val packageName: String,
    val label: String,
    val uid: Int,
    val downloadBytes: Long,
    val uploadBytes: Long,
) {
    val totalBytes: Long
        get() = safeAdd(downloadBytes, uploadBytes)

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}

private class UsageDbHelper(context: Context) : SQLiteOpenHelper(context, USAGE_DB_NAME, null, USAGE_DB_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE app_policy (
                package_name TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                blocked INTEGER NOT NULL DEFAULT 0,
                daily_limit_bytes INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE daily_usage (
                date TEXT NOT NULL,
                package_name TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                download_bytes INTEGER NOT NULL DEFAULT 0,
                upload_bytes INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (date, package_name)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_daily_usage_date_total ON daily_usage(date, download_bytes, upload_bytes)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations are intentionally additive. No schema changes exist yet.
    }
}

internal object UsageRepository {
    private fun helper(context: Context) = UsageDbHelper(context.applicationContext)

    fun upsertAppPolicy(
        context: Context,
        packageName: String,
        label: String,
        uid: Int,
        blocked: Boolean? = null,
        dailyLimitBytes: Long? = null,
    ) {
        helper(context).use { h ->
            val db = h.writableDatabase
            val existing = db.query(
                "app_policy",
                arrayOf("label", "uid", "blocked", "daily_limit_bytes"),
                "package_name = ?",
                arrayOf(packageName),
                null,
                null,
                null,
            ).use { c ->
                if (!c.moveToFirst()) null else arrayOf(
                    c.getString(0), c.getInt(1), c.getInt(2) != 0,
                    if (c.isNull(3)) null else c.getLong(3),
                )
            }
            val values = ContentValues().apply {
                put("package_name", packageName)
                put("label", label)
                put("uid", uid)
                put("blocked", if (blocked ?: (existing?.get(2) as? Boolean ?: false)) 1 else 0)
                val limit = dailyLimitBytes ?: (existing?.get(3) as? Long)
                if (limit == null || limit <= 0L) putNull("daily_limit_bytes") else put("daily_limit_bytes", limit)
            }
            db.insertWithOnConflict("app_policy", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun setBlocked(context: Context, packageName: String, label: String, uid: Int, blocked: Boolean) {
        ensureApp(context, packageName, label, uid)
        helper(context).use { h ->
            h.writableDatabase.update(
                "app_policy",
                ContentValues().apply { put("blocked", if (blocked) 1 else 0) },
                "package_name = ?",
                arrayOf(packageName),
            )
        }
    }

    fun setDailyLimitBytes(context: Context, packageName: String, label: String, uid: Int, limitBytes: Long?) {
        ensureApp(context, packageName, label, uid)
        helper(context).use { h ->
            val values = ContentValues().apply {
                if (limitBytes == null || limitBytes <= 0L) putNull("daily_limit_bytes") else put("daily_limit_bytes", limitBytes)
            }
            h.writableDatabase.update("app_policy", values, "package_name = ?", arrayOf(packageName))
        }
    }

    fun ensureApp(context: Context, packageName: String, label: String, uid: Int) {
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("package_name", packageName)
                put("label", label)
                put("uid", uid)
            }
            h.writableDatabase.insertWithOnConflict("app_policy", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun readPolicies(context: Context): List<AppPolicy> {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "app_policy",
                arrayOf("package_name", "label", "uid", "blocked", "daily_limit_bytes"),
                null,
                null,
                null,
                null,
                "label COLLATE NOCASE ASC",
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            AppPolicy(
                                packageName = c.getString(0),
                                label = c.getString(1),
                                uid = c.getInt(2),
                                blocked = c.getInt(3) != 0,
                                dailyLimitBytes = if (c.isNull(4)) null else c.getLong(4),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun upsertDailyUsage(context: Context, row: DailyUsageRow) {
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("date", row.date)
                put("package_name", row.packageName)
                put("label", row.label)
                put("uid", row.uid)
                put("download_bytes", row.downloadBytes.coerceAtLeast(0L))
                put("upload_bytes", row.uploadBytes.coerceAtLeast(0L))
                put("updated_at", System.currentTimeMillis())
            }
            h.writableDatabase.insertWithOnConflict("daily_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun topUsage(context: Context, date: String, limit: Int = 20): List<DailyUsageRow> {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "daily_usage",
                arrayOf("date", "package_name", "label", "uid", "download_bytes", "upload_bytes"),
                "date = ?",
                arrayOf(date),
                null,
                null,
                "(download_bytes + upload_bytes) DESC",
                limit.coerceIn(1, 100).toString(),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(DailyUsageRow(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getLong(5)))
                    }
                }
            }
        }
    }

    fun totalUsage(context: Context, date: String): Long {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "daily_usage",
                arrayOf("SUM(download_bytes + upload_bytes)"),
                "date = ?",
                arrayOf(date),
                null,
                null,
                null,
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
        }
    }

    fun rangeUsage(context: Context, startDate: String, endDate: String): Long {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "daily_usage",
                arrayOf("SUM(download_bytes + upload_bytes)"),
                "date >= ? AND date <= ?",
                arrayOf(startDate, endDate),
                null, null, null,
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
        }
    }
}

internal object UsageDate {
    private fun format(cal: java.util.Calendar): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)

    fun today(): String = format(java.util.Calendar.getInstance())

    fun startOfWeek(): String = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.let(::format)

    fun startOfMonth(): String = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.DAY_OF_MONTH, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.let(::format)
}

internal object UsageDateOffset {
    fun value(offsetDays: Int): String {
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offsetDays) }
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }
}