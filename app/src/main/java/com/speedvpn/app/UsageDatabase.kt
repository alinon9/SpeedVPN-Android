package com.speedvpn.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

private const val USAGE_DB_NAME = "speedvpn_usage.db"
private const val USAGE_DB_VERSION = 4

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
                baseline_initialized INTEGER NOT NULL DEFAULT 0 CHECK (baseline_initialized IN (0, 1)),
                reset_behavior TEXT NOT NULL DEFAULT 'AUTO_RESET'
                    CHECK (reset_behavior IN ('AUTO_RESET', 'BLOCK_UNTIL_RESET')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),
                PRIMARY KEY (package_name, quota_type),
                FOREIGN KEY (package_name) REFERENCES app_policy(package_name) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_app_quota_type ON app_quota_policy(package_name, quota_type)")
        createPendingReservationTable(db)
    }

    internal fun createPendingReservationTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_quota_pending_reservation (
                reservation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                process_token TEXT NOT NULL,
                transport TEXT NOT NULL CHECK (transport IN ('TCP', 'UDP')),
                package_name TEXT NOT NULL,
                quota_type TEXT NOT NULL CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
                period_start_millis INTEGER NOT NULL,
                direction TEXT NOT NULL CHECK (direction IN ('DOWNLOAD', 'UPLOAD')),
                reserved_bytes INTEGER NOT NULL CHECK (reserved_bytes > 0),
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_quota_pending_token ON app_quota_pending_reservation(process_token, transport)",
        )
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
        if (oldVersion >= 2 && oldVersion < 4) {
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE app_quota_policy ADD COLUMN baseline_initialized INTEGER NOT NULL DEFAULT 0 CHECK (baseline_initialized IN (0, 1))")
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
        val nowMillis = System.currentTimeMillis()
        val bounds = QuotaPeriod.current(quotaType, nowMillis)
        val existingPolicy = readQuotaPolicies(context, packageName)
            .firstOrNull { it.quotaType == quotaType }
            ?.takeIf {
                it.periodStartMillis == bounds.startMillis && it.periodEndMillis == bounds.endMillis
            }
        val historical = if (existingPolicy == null) {
            val startDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(bounds.startMillis))
            val endDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(nowMillis))
            runCatching {
                AppTrafficManager.queryUidUsageBreakdown(context, uid, bounds.startMillis, nowMillis)
            }.getOrNull() ?: sumDirectionalUsageInPeriod(context, packageName, startDate, endDate)
        } else {
            UidTrafficCounters(existingPolicy.downloadBytes, existingPolicy.uploadBytes)
        }
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("package_name", packageName)
                put("quota_type", quotaType.name)
                put("limit_bytes", limitBytes)
                put("period_start_millis", bounds.startMillis)
                put("period_end_millis", bounds.endMillis)
                // Editing a quota must not erase bytes already consumed in the same period.
                // New or expired policies are seeded once from historical usage before live relay
                // byte accounting takes over.
                put("used_bytes", existingPolicy?.usedBytes ?: historical.totalBytes)
                put("download_bytes", if (existingPolicy != null) existingPolicy.downloadBytes else historical.downloadBytes)
                put("upload_bytes", if (existingPolicy != null) existingPolicy.uploadBytes else historical.uploadBytes)
                put("baseline_initialized", if (existingPolicy != null) {
                    if (existingPolicy.baselineInitialized) 1 else 0
                } else 1)
                put("reset_behavior", resetBehavior.name)
                put("updated_at", nowMillis)
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
        QuotaEnforcementEngine.invalidateQuotaBaselineCache(packageName)
    }

    fun deleteQuotaPolicy(context: Context, packageName: String, quotaType: QuotaType) {
        helper(context).use { h ->
            h.writableDatabase.delete(
                "app_quota_policy",
                "package_name = ? AND quota_type = ?",
                arrayOf(packageName, quotaType.name),
            )
        }
        QuotaEnforcementEngine.invalidateQuotaBaselineCache(packageName)
    }

    fun resetQuotaPeriod(
        context: Context,
        packageName: String,
        quotaType: QuotaType,
        periodStartMillis: Long,
        periodEndMillis: Long,
    ): Boolean {
        val updated = helper(context).use { h ->
            val values = ContentValues().apply {
                put("period_start_millis", periodStartMillis)
                put("period_end_millis", periodEndMillis)
                put("used_bytes", 0L)
                put("download_bytes", 0L)
                put("upload_bytes", 0L)
                put("baseline_initialized", 0)
                put("updated_at", System.currentTimeMillis())
            }
            h.writableDatabase.update(
                "app_quota_policy",
                values,
                "package_name = ? AND quota_type = ?",
                arrayOf(packageName, quotaType.name),
            ) == 1
        }
        QuotaEnforcementEngine.invalidateQuotaBaselineCache(packageName)
        return updated
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
                    "download_bytes", "upload_bytes", "baseline_initialized",
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
                                baselineInitialized = c.getInt(9) != 0,
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
        transport: QuotaTransport = QuotaTransport.TCP,
        processToken: String = "",
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
            h.createPendingReservationTable(db)
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
                                        put("baseline_initialized", 0)
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
                    // Atomic UDP datagrams cannot be truncated to a partial prefix. If the
                    // full datagram does not fit in the remaining quota, admit 0 bytes
                    // immediately without charging the uncommitted prefix to used_bytes.
                    val rawAllowed = minOf(requested.toLong(), remaining).toInt()
                    val allowed = if (transport == QuotaTransport.UDP && rawAllowed < requested) {
                        0
                    } else {
                        rawAllowed
                    }
                    val newlyReached = counters
                        .filter { it.usedBytes >= it.limitBytes || (allowed > 0 && it.usedBytes + allowed.toLong() >= it.limitBytes) }
                        .map { it.packageName }
                        .toSet()

                    val reservations = ArrayList<QuotaPolicyReservation>(counters.size)
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
                            val reservationId = if (transport == QuotaTransport.UDP && processToken.isNotBlank()) {
                                db.insert(
                                    "app_quota_pending_reservation",
                                    null,
                                    ContentValues().apply {
                                        put("process_token", processToken)
                                        put("transport", transport.name)
                                        put("package_name", counter.packageName)
                                        put("quota_type", counter.quotaType.name)
                                        put("period_start_millis", counter.periodStartMillis)
                                        put("direction", direction.name)
                                        put("reserved_bytes", allowed)
                                        put("created_at", nowMillis)
                                    },
                                ).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                            reservations.add(
                                QuotaPolicyReservation(
                                    packageName = counter.packageName,
                                    quotaType = counter.quotaType,
                                    periodStartMillis = counter.periodStartMillis,
                                    direction = direction,
                                    reservedBytes = allowed,
                                    transport = transport,
                                    reservationId = reservationId,
                                ),
                            )
                        }
                    } else {
                        counters.forEach { counter ->
                            reservations.add(
                                QuotaPolicyReservation(
                                    packageName = counter.packageName,
                                    quotaType = counter.quotaType,
                                    periodStartMillis = counter.periodStartMillis,
                                    direction = direction,
                                    reservedBytes = 0,
                                    transport = transport,
                                    reservationId = 0L,
                                ),
                            )
                        }
                    }
                    QuotaTrafficDecision(allowed, newlyReached, reservations)
                }
                db.setTransactionSuccessful()
                decision
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Atomically releases uncommitted reserved bytes (e.g. when an atomic UDP datagram is
     * dropped because allowedBytes < payloadBytes or its socket send fails) for the exact
     * policy period rows that were incremented, and returns the packages that remain at or
     * above their quota limit after the release.
     */
    fun releaseQuotaReservation(
        context: Context,
        decision: QuotaTrafficDecision,
        bytesToRelease: Int = decision.allowedBytes,
        nowMillis: Long = System.currentTimeMillis(),
    ): Set<String> {
        val candidatePackages = (
            decision.exhaustedPackages + decision.reservations.map { it.packageName }
        ).filter { it.isNotBlank() }.toSet()
        if (candidatePackages.isEmpty()) return emptySet()

        return helper(context).use { h ->
            val db = h.writableDatabase
            h.createPendingReservationTable(db)
            db.beginTransaction()
            try {
                val reservationIds = decision.reservations.mapNotNull { it.reservationId.takeIf { id -> id > 0L } }
                if (reservationIds.isNotEmpty()) {
                    val idPlaceholders = reservationIds.joinToString(",") { "?" }
                    db.delete(
                        "app_quota_pending_reservation",
                        "reservation_id IN ($idPlaceholders)",
                        reservationIds.map { it.toString() }.toTypedArray(),
                    )
                }
                val releaseBounded = bytesToRelease.coerceAtLeast(0)
                if (releaseBounded > 0) {
                    decision.reservations.forEach { reservation ->
                        val delta = minOf(releaseBounded, reservation.reservedBytes.coerceAtLeast(0)).toLong()
                        if (delta > 0L) {
                            val directionalColumn = if (reservation.direction == QuotaTrafficDirection.DOWNLOAD) {
                                "download_bytes"
                            } else {
                                "upload_bytes"
                            }
                            db.execSQL(
                                """
                                UPDATE app_quota_policy
                                SET used_bytes = CASE WHEN used_bytes > ? THEN used_bytes - ? ELSE 0 END,
                                    $directionalColumn = CASE WHEN $directionalColumn > ? THEN $directionalColumn - ? ELSE 0 END,
                                    updated_at = ?
                                WHERE package_name = ? AND quota_type = ? AND period_start_millis = ?
                                """.trimIndent(),
                                arrayOf<Any>(
                                    delta,
                                    delta,
                                    delta,
                                    delta,
                                    nowMillis,
                                    reservation.packageName,
                                    reservation.quotaType.name,
                                    reservation.periodStartMillis,
                                ),
                            )
                        }
                    }
                }

                val placeholders = candidatePackages.joinToString(",") { "?" }
                val sql = """
                    SELECT package_name, quota_type, limit_bytes, period_start_millis, period_end_millis, used_bytes
                    FROM app_quota_policy
                    WHERE package_name IN ($placeholders)
                """.trimIndent()
                val stillExhausted = mutableSetOf<String>()
                db.rawQuery(sql, candidatePackages.toTypedArray()).use { c ->
                    while (c.moveToNext()) {
                        val pkg = c.getString(0)
                        val type = runCatching { QuotaType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                        val limit = c.getLong(2)
                        val startMillis = c.getLong(3)
                        val endMillis = c.getLong(4)
                        val used = c.getLong(5)
                        val currentPeriod = QuotaPeriod.current(type, nowMillis)
                        val activeUsed = if (startMillis == currentPeriod.startMillis && endMillis == currentPeriod.endMillis) {
                            used
                        } else {
                            0L
                        }
                        if (activeUsed >= limit) {
                            stillExhausted.add(pkg)
                        }
                    }
                }
                db.setTransactionSuccessful()
                stillExhausted
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Marks a pending UDP reservation as durably committed by removing its journal entry
     * from [app_quota_pending_reservation] once the datagram has been sent on the wire.
     */
    fun commitQuotaReservation(
        context: Context,
        decision: QuotaTrafficDecision,
    ) {
        val reservationIds = decision.reservations.mapNotNull { it.reservationId.takeIf { id -> id > 0L } }
        if (reservationIds.isEmpty()) return
        helper(context).use { h ->
            val db = h.writableDatabase
            h.createPendingReservationTable(db)
            val idPlaceholders = reservationIds.joinToString(",") { "?" }
            db.delete(
                "app_quota_pending_reservation",
                "reservation_id IN ($idPlaceholders)",
                reservationIds.map { it.toString() }.toTypedArray(),
            )
        }
    }

    /**
     * Resolves orphaned pending UDP reservation journal entries from a previous process
     * instance that terminated between [reserveQuotaBytes] and [QuotaEnforcementEngine.settleRelayBytes].
     *
     * Fundamental crash-safety invariant:
     * Because kernel socket transmission (DatagramChannel.write / DatagramSocket.send) and
     * user-space SQLite commits are not mutually atomic, an orphaned reservation in
     * [app_quota_pending_reservation] has an UNCERTAIN wire outcome — the datagram may have
     * already been transmitted right before the process died. Rolling back used_bytes on
     * process restart would undercount delivered UDP traffic and allow quota bypass.
     *
     * Therefore:
     * 1. Oversized UDP datagrams (rawAllowed < requested) are rejected synchronously at
     *    admission before any reservation is written.
     * 2. Confirmed UDP send failures observed while the process is alive are rolled back
     *    via [releaseQuotaReservation].
     * 3. Orphaned reservations from a dead process are CONSERVATIVELY RETAINED in
     *    [app_quota_policy.used_bytes], their stale journal rows are retired from
     *    [app_quota_pending_reservation], and any package at or above its limit remains blocked.
     */
    fun recoverOrphanedUdpReservations(
        context: Context,
        activeProcessToken: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<Set<String>, Set<String>> {
        if (activeProcessToken.isBlank()) return emptySet<String>() to emptySet()
        return helper(context).use { h ->
            val db = h.writableDatabase
            h.createPendingReservationTable(db)
            db.beginTransaction()
            try {
                val affectedPackages = db.rawQuery(
                    """
                    SELECT DISTINCT package_name
                    FROM app_quota_pending_reservation
                    WHERE process_token != ?
                    """.trimIndent(),
                    arrayOf(activeProcessToken),
                ).use { c ->
                    buildSet {
                        while (c.moveToNext()) {
                            val pkg = c.getString(0)
                            if (!pkg.isNullOrBlank()) add(pkg)
                        }
                    }
                }

                if (affectedPackages.isEmpty()) {
                    db.setTransactionSuccessful()
                    return@use emptySet<String>() to emptySet()
                }

                // Retire stale pending journal entries from the dead process without
                // subtracting their bytes from app_quota_policy.used_bytes.
                db.delete(
                    "app_quota_pending_reservation",
                    "process_token != ?",
                    arrayOf(activeProcessToken),
                )

                val placeholders = affectedPackages.joinToString(",") { "?" }
                val stillExhausted = mutableSetOf<String>()
                db.rawQuery(
                    """
                    SELECT package_name, quota_type, limit_bytes, period_start_millis, period_end_millis, used_bytes
                    FROM app_quota_policy
                    WHERE package_name IN ($placeholders)
                    """.trimIndent(),
                    affectedPackages.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        val pkg = c.getString(0)
                        val type = runCatching { QuotaType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                        val limit = c.getLong(2)
                        val startMillis = c.getLong(3)
                        val endMillis = c.getLong(4)
                        val used = c.getLong(5)
                        val currentPeriod = QuotaPeriod.current(type, nowMillis)
                        val activeUsed = if (startMillis == currentPeriod.startMillis && endMillis == currentPeriod.endMillis) {
                            used
                        } else {
                            0L
                        }
                        if (activeUsed >= limit) {
                            stillExhausted.add(pkg)
                        }
                    }
                }

                db.setTransactionSuccessful()
                affectedPackages to stillExhausted
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

    /**
     * Batches all daily usage and app_policy rows for a collection cycle into a single
     * SQLite connection and transaction, avoiding hundreds of per-app open/commit/close
     * cycles while preserving higher previously-recorded live relay counters.
     */
    fun upsertDailyUsageBatch(context: Context, rows: List<DailyUsageRow>) {
        if (rows.isEmpty()) return
        val nowMillis = System.currentTimeMillis()
        helper(context).use { h ->
            val db = h.writableDatabase
            db.beginTransaction()
            try {
                val dates = rows.map { it.date }.distinct()
                val existingByKey = HashMap<String, Pair<Long, Long>>()
                if (dates.isNotEmpty()) {
                    val placeholders = dates.joinToString(",") { "?" }
                    db.rawQuery(
                        "SELECT date, package_name, download_bytes, upload_bytes FROM daily_usage WHERE date IN ($placeholders)",
                        dates.toTypedArray(),
                    ).use { c ->
                        while (c.moveToNext()) {
                            existingByKey["${c.getString(0)}|${c.getString(1)}"] =
                                c.getLong(2) to c.getLong(3)
                        }
                    }
                }
                rows.forEach { row ->
                    val existing = existingByKey["${row.date}|${row.packageName}"]
                    val download = maxOf(row.downloadBytes.coerceAtLeast(0L), existing?.first ?: 0L)
                    val upload = maxOf(row.uploadBytes.coerceAtLeast(0L), existing?.second ?: 0L)
                    val usageValues = ContentValues().apply {
                        put("date", row.date)
                        put("package_name", row.packageName)
                        put("label", row.label)
                        put("uid", row.uid)
                        put("download_bytes", download)
                        put("upload_bytes", upload)
                        put("updated_at", nowMillis)
                    }
                    db.insertWithOnConflict("daily_usage", null, usageValues, SQLiteDatabase.CONFLICT_REPLACE)
                    val appValues = ContentValues().apply {
                        put("package_name", row.packageName)
                        put("label", row.label)
                        put("uid", row.uid)
                    }
                    db.insertWithOnConflict("app_policy", null, appValues, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
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
                limit.coerceIn(1, 500).toString(),
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

    fun sumDirectionalUsageInPeriod(
        context: Context,
        packageName: String,
        startDate: String,
        endDate: String,
    ): UidTrafficCounters {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "daily_usage",
                arrayOf("SUM(download_bytes)", "SUM(upload_bytes)"),
                "package_name = ? AND date >= ? AND date <= ?",
                arrayOf(packageName, startDate, endDate),
                null,
                null,
                null,
            ).use { c ->
                if (c.moveToFirst()) {
                    UidTrafficCounters(
                        downloadBytes = if (c.isNull(0)) 0L else c.getLong(0),
                        uploadBytes = if (c.isNull(1)) 0L else c.getLong(1),
                    )
                } else {
                    UidTrafficCounters(0L, 0L)
                }
            }
        }
    }

    /** Compare-and-set so a one-shot baseline is never applied twice to a concurrent relay. */
    fun initializeQuotaBaseline(
        context: Context,
        policy: AppQuotaPolicy,
        counters: UidTrafficCounters,
        minimumUsedBytes: Long = 0L,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (policy.baselineInitialized) return false
        helper(context).use { h ->
            return h.writableDatabase.update(
                "app_quota_policy",
                ContentValues().apply {
                    put("used_bytes", maxOf(counters.totalBytes, minimumUsedBytes))
                    put("download_bytes", counters.downloadBytes)
                    put("upload_bytes", counters.uploadBytes)
                    put("baseline_initialized", 1)
                    put("updated_at", nowMillis)
                },
                "package_name = ? AND quota_type = ? AND period_start_millis = ? " +
                    "AND period_end_millis = ? AND baseline_initialized = 0",
                arrayOf(
                    policy.packageName,
                    policy.quotaType.name,
                    policy.periodStartMillis.toString(),
                    policy.periodEndMillis.toString(),
                ),
            ) == 1
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
