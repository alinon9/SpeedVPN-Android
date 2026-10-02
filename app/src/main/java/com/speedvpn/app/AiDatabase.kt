package com.speedvpn.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val AI_DB_NAME = "speedvpn_ai.db"
private const val AI_DB_VERSION = 1

internal data class SpeedProfile(
    val packageName: String?,
    val enabled: Boolean,
    val downloadKbps: Long?,
    val uploadKbps: Long?,
    val updatedAt: Long,
)

internal data class AiNetworkSample(
    val timestamp: Long,
    val networkType: String,
    val downloadBps: Long,
    val uploadBps: Long,
    val pingMs: Long,
    val jitterMs: Long,
    val packetLossPct: Double,
    val mtu: Int,
    val ipv6Enabled: Boolean,
    val dnsIpv4Only: Boolean,
)

internal data class AiInsight(
    val id: Long,
    val createdAt: Long,
    val category: String,
    val title: String,
    val detail: String,
    val confidence: Double,
    val source: String,
)

private class AiDbHelper(context: Context) : SQLiteOpenHelper(context, AI_DB_NAME, null, AI_DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE speed_profile (
                package_name TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL DEFAULT 0,
                download_kbps INTEGER,
                upload_kbps INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE network_sample (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                network_type TEXT NOT NULL,
                download_bps INTEGER NOT NULL,
                upload_bps INTEGER NOT NULL,
                ping_ms INTEGER NOT NULL,
                jitter_ms INTEGER NOT NULL,
                packet_loss_pct REAL NOT NULL,
                mtu INTEGER NOT NULL,
                ipv6_enabled INTEGER NOT NULL,
                dns_ipv4_only INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_network_sample_timestamp ON network_sample(timestamp DESC)")
        db.execSQL(
            """
            CREATE TABLE speed_experiment (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                package_name TEXT,
                download_kbps INTEGER,
                upload_kbps INTEGER,
                observed_download_bps INTEGER NOT NULL,
                observed_upload_bps INTEGER NOT NULL,
                ping_ms INTEGER NOT NULL,
                jitter_ms INTEGER NOT NULL,
                packet_loss_pct REAL NOT NULL,
                result_score REAL NOT NULL,
                notes TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_speed_experiment_timestamp ON speed_experiment(timestamp DESC)")
        db.execSQL(
            """
            CREATE TABLE ai_insight (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                confidence REAL NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_ai_insight_created_at ON ai_insight(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }
}

internal object AiRepository {
    private fun helper(context: Context) = AiDbHelper(context.applicationContext)

    fun upsertSpeedProfile(context: Context, profile: SpeedProfile) {
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("package_name", profile.packageName ?: "__GLOBAL__")
                put("enabled", if (profile.enabled) 1 else 0)
                if (profile.downloadKbps == null) putNull("download_kbps") else put("download_kbps", profile.downloadKbps)
                if (profile.uploadKbps == null) putNull("upload_kbps") else put("upload_kbps", profile.uploadKbps)
                put("updated_at", profile.updatedAt)
            }
            h.writableDatabase.insertWithOnConflict("speed_profile", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun getSpeedProfile(context: Context, packageName: String?): SpeedProfile? {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "speed_profile",
                arrayOf("package_name", "enabled", "download_kbps", "upload_kbps", "updated_at"),
                "package_name = ?",
                arrayOf(packageName ?: "__GLOBAL__"),
                null,
                null,
                null,
                "1",
            ).use { c ->
                if (!c.moveToFirst()) return@use null
                SpeedProfile(
                    packageName = c.getString(0).takeUnless { it == "__GLOBAL__" },
                    enabled = c.getInt(1) != 0,
                    downloadKbps = if (c.isNull(2)) null else c.getLong(2),
                    uploadKbps = if (c.isNull(3)) null else c.getLong(3),
                    updatedAt = c.getLong(4),
                )
            }
        }
    }

    fun listAppSpeedProfiles(context: Context): List<SpeedProfile> {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "speed_profile",
                arrayOf("package_name", "enabled", "download_kbps", "upload_kbps", "updated_at"),
                "package_name <> ?",
                arrayOf("__GLOBAL__"),
                null,
                null,
                "package_name COLLATE NOCASE ASC",
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            SpeedProfile(
                                packageName = c.getString(0),
                                enabled = c.getInt(1) != 0,
                                downloadKbps = if (c.isNull(2)) null else c.getLong(2),
                                uploadKbps = if (c.isNull(3)) null else c.getLong(3),
                                updatedAt = c.getLong(4),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun recordNetworkSample(context: Context, sample: AiNetworkSample) {
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("timestamp", sample.timestamp)
                put("network_type", sample.networkType)
                put("download_bps", sample.downloadBps.coerceAtLeast(0L))
                put("upload_bps", sample.uploadBps.coerceAtLeast(0L))
                put("ping_ms", sample.pingMs.coerceAtLeast(0L))
                put("jitter_ms", sample.jitterMs.coerceAtLeast(0L))
                put("packet_loss_pct", sample.packetLossPct.coerceIn(0.0, 100.0))
                put("mtu", sample.mtu)
                put("ipv6_enabled", if (sample.ipv6Enabled) 1 else 0)
                put("dns_ipv4_only", if (sample.dnsIpv4Only) 1 else 0)
            }
            h.writableDatabase.insert("network_sample", null, values)
            pruneOldRows(h.writableDatabase)
        }
    }

    fun recordSpeedExperiment(
        context: Context,
        packageName: String?,
        downloadKbps: Long?,
        uploadKbps: Long?,
        observedDownloadBps: Long,
        observedUploadBps: Long,
        pingMs: Long,
        jitterMs: Long,
        packetLossPct: Double,
        resultScore: Double,
        notes: String = "",
    ) {
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                if (packageName == null) putNull("package_name") else put("package_name", packageName)
                if (downloadKbps == null) putNull("download_kbps") else put("download_kbps", downloadKbps)
                if (uploadKbps == null) putNull("upload_kbps") else put("upload_kbps", uploadKbps)
                put("observed_download_bps", observedDownloadBps.coerceAtLeast(0L))
                put("observed_upload_bps", observedUploadBps.coerceAtLeast(0L))
                put("ping_ms", pingMs.coerceAtLeast(0L))
                put("jitter_ms", jitterMs.coerceAtLeast(0L))
                put("packet_loss_pct", packetLossPct.coerceIn(0.0, 100.0))
                put("result_score", resultScore.coerceIn(0.0, 1.0))
                put("notes", notes.take(500))
            }
            h.writableDatabase.insert("speed_experiment", null, values)
            pruneOldRows(h.writableDatabase)
        }
    }

    fun recordInsight(context: Context, insight: AiInsight) {
        helper(context).use { h ->
            val values = ContentValues().apply {
                put("created_at", insight.createdAt)
                put("category", insight.category)
                put("title", insight.title)
                put("detail", insight.detail)
                put("confidence", insight.confidence.coerceIn(0.0, 1.0))
                put("source", insight.source)
            }
            h.writableDatabase.insert("ai_insight", null, values)
        }
    }

    fun recentInsights(context: Context, limit: Int = 8): List<AiInsight> {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "ai_insight",
                arrayOf("id", "created_at", "category", "title", "detail", "confidence", "source"),
                null,
                null,
                null,
                null,
                "created_at DESC",
                limit.coerceIn(1, 50).toString(),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(AiInsight(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getDouble(5), c.getString(6)))
                    }
                }
            }
        }
    }

    fun recentNetworkSamples(context: Context, limit: Int = 100): List<AiNetworkSample> {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "network_sample",
                arrayOf("timestamp", "network_type", "download_bps", "upload_bps", "ping_ms", "jitter_ms", "packet_loss_pct", "mtu", "ipv6_enabled", "dns_ipv4_only"),
                null,
                null,
                null,
                null,
                "timestamp DESC",
                limit.coerceIn(1, 500).toString(),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            AiNetworkSample(
                                timestamp = c.getLong(0),
                                networkType = c.getString(1),
                                downloadBps = c.getLong(2),
                                uploadBps = c.getLong(3),
                                pingMs = c.getLong(4),
                                jitterMs = c.getLong(5),
                                packetLossPct = c.getDouble(6),
                                mtu = c.getInt(7),
                                ipv6Enabled = c.getInt(8) != 0,
                                dnsIpv4Only = c.getInt(9) != 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun pruneOldRows(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - 1000L * 60L * 60L * 24L * 30L
        db.delete("network_sample", "timestamp < ?", arrayOf(cutoff.toString()))
        db.delete("speed_experiment", "timestamp < ?", arrayOf(cutoff.toString()))
    }
}