package com.speedvpn.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

private const val USAGE_DB_NAME = "speedvpn_usage.db"
private const val USAGE_DB_VERSION = 3

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

internal class UsageDbHelper(
    context: Context,
    dbName: String = USAGE_DB_NAME,
    dbVersion: Int = USAGE_DB_VERSION,
) : SQLiteOpenHelper(context, dbName, null, dbVersion) {
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
        createQuotaTable(db)
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

    private fun createQuotaTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_quota_policy (
                package_name TEXT NOT NULL,
                quota_type TEXT NOT NULL CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
                limit_bytes INTEGER NOT NULL CHECK (limit_bytes > 0),
                period_start_millis INTEGER NOT NULL,
                period_end_millis INTEGER NOT NULL,
                used_bytes INTEGER NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
                download_bytes INTEGER NOT NULL DEFAULT 0 CHECK (download_bytes >= 0),
                upload_bytes INTEGER NOT NULL DEFAULT 0 CHECK (upload_bytes >= 0),
                reset_behavior TEXT NOT NULL DEFAULT 'AUTO_RESET'
                    CHECK (reset_behavior IN ('AUTO_RESET', 'BLOCK_UNTIL_RESET')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),
                PRIMARY KEY (package_name, quota_type),
                FOREIGN KEY (package_name) REFERENCES app_policy(package_name) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_app_quota_type ON app_quota_policy(package_name, quota_type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.beginTransaction()
            try {
                createQuotaTable(db)
                val daily = QuotaPeriod.current(QuotaType.DAILY)
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO app_quota_policy
                    (package_name, quota_type, limit_bytes, period_start_millis, period_end_millis, used_bytes, reset_behavior, updated_at)
                    SELECT package_name, 'DAILY', daily_limit_bytes, ?, ?, 0, 'AUTO_RESET', ?
                    FROM app_policy
                    WHERE daily_limit_bytes IS NOT NULL AND daily_limit_bytes > 0
                    """.trimIndent(),
                    arrayOf(daily.startMillis, daily.endMillis, System.currentTimeMillis()),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        // Version 2 stored only a combined quota counter. Add durable directional
        // counters without resetting the pre-existing total.
        if (oldVersion >= 2 && oldVersion < 3) {
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE app_quota_policy ADD COLUMN download_bytes INTEGER NOT NULL DEFAULT 0 CHECK (download_bytes >= 0)")
                db.execSQL("ALTER TABLE app_quota_policy ADD COLUMN upload_bytes INTEGER NOT NULL DEFAULT 0 CHECK (upload_bytes >= 0)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
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
        if (dailyLimitBytes != null) {
            setQuotaPolicy(context, packageName, label, uid, QuotaType.DAILY, dailyLimitBytes)
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
        if (limitBytes == null || limitBytes <= 0L) {
            deleteQuotaPolicy(context, packageName, QuotaType.DAILY)
        } else {
            setQuotaPolicy(context, packageName, label, uid, QuotaType.DAILY, limitBytes)
        }
    }

    fun setQuotaPolicy(
        context: Context,
        packageName: String,
        label: String,
        uid: Int,
        quotaType: QuotaType,
        limitBytes: Long?,
        resetBehavior: ResetBehavior = ResetBehavior.AUTO_RESET,
    ) {
        ensureApp(context, packageName, label, uid)
        if (limitBytes == null || limitBytes <= 0L) {
            deleteQuotaPolicy(context, packageName, quotaType)
            if (quotaType == QuotaType.DAILY) {
                helper(context).use { h ->
                    h.writableDatabase.update(
                        "app_policy",
                        ContentValues().apply { putNull("daily_limit_bytes") },
                        "package_name = ?",
                        arrayOf(packageName),
                    )
                }
            }
            return
        }
        val bounds = QuotaPeriod.current(quotaType)
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("package_name", packageName)
                put("quota_type", quotaType.name)
                put("limit_bytes", limitBytes)
                put("period_start_millis", bounds.startMillis)
                put("period_end_millis", bounds.endMillis)
                put("used_bytes", 0L)
                put("reset_behavior", resetBehavior.name)
                put("updated_at", System.currentTimeMillis())
            }
            h.writableDatabase.insertWithOnConflict(
                "app_quota_policy",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            if (quotaType == QuotaType.DAILY) {
                h.writableDatabase.update(
                    "app_policy",
                    ContentValues().apply { put("daily_limit_bytes", limitBytes) },
                    "package_name = ?",
                    arrayOf(packageName),
                )
            }
        }
    }

    fun deleteQuotaPolicy(context: Context, packageName: String, quotaType: QuotaType) {
        helper(context).use { h ->
            h.writableDatabase.delete(
                "app_quota_policy",
                "package_name = ? AND quota_type = ?",
                arrayOf(packageName, quotaType.name),
            )
        }
    }

    fun resetQuotaPeriod(
        context: Context,
        packageName: String,
        quotaType: QuotaType,
        periodStartMillis: Long,
        periodEndMillis: Long,
    ): Boolean {
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("period_start_millis", periodStartMillis)
                put("period_end_millis", periodEndMillis)
                put("used_bytes", 0L)
                put("download_bytes", 0L)
                put("upload_bytes", 0L)
                put("updated_at", System.currentTimeMillis())
            }
            return h.writableDatabase.update(
                "app_quota_policy",
                values,
                "package_name = ? AND quota_type = ?",
                arrayOf(packageName, quotaType.name),
            ) == 1
        }
    }

    fun readQuotaPolicies(context: Context, packageName: String? = null): List<AppQuotaPolicy> {
        helper(context).use { h ->
            val selection = packageName?.let { "package_name = ?" }
            val args = packageName?.let { arrayOf(it) }
            return h.readableDatabase.query(
                "app_quota_policy",
                arrayOf(
                    "package_name", "quota_type", "limit_bytes",
                    "period_start_millis", "period_end_millis", "used_bytes", "reset_behavior",
                    "download_bytes", "upload_bytes",
                ),
                selection,
                args,
                null,
                null,
                "package_name COLLATE NOCASE ASC, quota_type ASC",
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val type = runCatching { QuotaType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                        val reset = runCatching { ResetBehavior.valueOf(c.getString(6)) }.getOrDefault(ResetBehavior.AUTO_RESET)
                        add(
                            AppQuotaPolicy(
                                packageName = c.getString(0),
                                quotaType = type,
                                limitBytes = c.getLong(2),
                                periodStartMillis = c.getLong(3),
                                periodEndMillis = c.getLong(4),
                                usedBytes = c.getLong(5),
                                resetBehavior = reset,
                                downloadBytes = c.getLong(7),
                                uploadBytes = c.getLong(8),
                            ),
                        )
                    }
                }
            }
        }
    }


    /**
     * Atomically reserves relay payload bytes against every current quota for the resolved
     * owner packages. The DB transaction serializes concurrent TCP/UDP flows and persists
     * directional counters before the relay forwards any admitted bytes.
     */
    fun reserveQuotaBytes(
        context: Context,
        packageNames: Set<String>,
        direction: QuotaTrafficDirection,
        requestedBytes: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): QuotaTrafficDecision {
        val requested = requestedBytes.coerceAtLeast(0)
        val packages = packageNames.filter { it.isNotBlank() }.toSet()
        if (requested == 0 || packages.isEmpty()) return QuotaTrafficDecision(requested)

        data class Counter(
            val packageName: String,
            val quotaType: QuotaType,
            val limitBytes: Long,
            val periodStartMillis: Long,
            val usedBytes: Long,
            val downloadBytes: Long,
            val uploadBytes: Long,
        )

        return helper(context).use { h ->
            val db = h.writableDatabase
            db.beginTransaction()
            try {
                val placeholders = packages.joinToString(",") { "?" }
                val sql = """
                    SELECT package_name, quota_type, limit_bytes, period_start_millis,
                           period_end_millis, used_bytes, download_bytes, upload_bytes
                    FROM app_quota_policy
                    WHERE package_name IN ($placeholders)
                """.trimIndent()
                val counters = db.rawQuery(sql, packages.toTypedArray()).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val packageName = c.getString(0)
                            val type = runCatching { QuotaType.valueOf(c.getString(1)) }.getOrNull()
                                ?: continue
                            val limit = c.getLong(2)
                            val period = QuotaPeriod.current(type, nowMillis)
                            val stale = c.getLong(3) != period.startMillis ||
                                c.getLong(4) != period.endMillis
                            val used = if (stale) 0L else c.getLong(5)
                            val download = if (stale) 0L else c.getLong(6)
                            val upload = if (stale) 0L else c.getLong(7)

                            if (stale) {
                                db.update(
                                    "app_quota_policy",
                                    ContentValues().apply {
                                        put("period_start_millis", period.startMillis)
                                        put("period_end_millis", period.endMillis)
                                        put("used_bytes", 0L)
                                        put("download_bytes", 0L)
                                        put("upload_bytes", 0L)
                                        put("updated_at", nowMillis)
                                    },
                                    "package_name = ? AND quota_type = ?",
                                    arrayOf(packageName, type.name),
                                )
                            }
                            add(
                                Counter(
                                    packageName = packageName,
                                    quotaType = type,
                                    limitBytes = limit,
                                    periodStartMillis = period.startMillis,
                                    usedBytes = used,
                                    downloadBytes = download,
                                    uploadBytes = upload,
                                ),
                            )
                        }
                    }
                }

                val decision = if (counters.isEmpty()) {
                    QuotaTrafficDecision(requested)
                } else {
                    val remaining = counters.minOf { (it.limitBytes - it.usedBytes).coerceAtLeast(0L) }
                    val allowed = minOf(requested.toLong(), remaining).toInt()
                    val newlyReached = counters
                        .filter { it.usedBytes >= it.limitBytes || it.usedBytes + allowed.toLong() >= it.limitBytes }
                        .map { it.packageName }
                        .toSet()

                    if (allowed > 0) {
                        val delta = allowed.toLong()
                        counters.forEach { counter ->
                            val nextUsed = counter.usedBytes + delta
                            val nextDownload = counter.downloadBytes +
                                if (direction == QuotaTrafficDirection.DOWNLOAD) delta else 0L
                            val nextUpload = counter.uploadBytes +
                                if (direction == QuotaTrafficDirection.UPLOAD) delta else 0L
                            db.update(
                                "app_quota_policy",
                                ContentValues().apply {
                                    put("used_bytes", nextUsed)
                                    put("download_bytes", nextDownload)
                                    put("upload_bytes", nextUpload)
                                    put("updated_at", nowMillis)
                                },
                                "package_name = ? AND quota_type = ? AND period_start_millis = ?",
                                arrayOf(
                                    counter.packageName,
                                    counter.quotaType.name,
                                    counter.periodStartMillis.toString(),
                                ),
                            )
                        }
                    }
                    QuotaTrafficDecision(allowed, newlyReached)
                }
                db.setTransactionSuccessful()
                decision
            } finally {
                db.endTransaction()
            }
        }
    }

    fun readQuotaPolicyMap(context: Context): Map<String, List<AppQuotaPolicy>> =
        readQuotaPolicies(context).groupBy { it.packageName }

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

    fun sumUsageInPeriod(
        context: Context,
        packageName: String,
        startDate: String,
        endDate: String,
    ): Long {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "daily_usage",
                arrayOf("SUM(download_bytes + upload_bytes)"),
                "package_name = ? AND date >= ? AND date <= ?",
                arrayOf(packageName, startDate, endDate),
                null,
                null,
                null,
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
        }
    }
}

internal object UsageDate {
    private fun format(cal: Calendar): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)

    fun today(): String = format(Calendar.getInstance())

    fun startOfWeek(): String = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.let(::format)

    fun startOfMonth(): String = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.let(::format)
}

internal object UsageDateOffset {
    fun value(offsetDays: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offsetDays) }
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }
}
