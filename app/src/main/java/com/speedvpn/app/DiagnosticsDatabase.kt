package com.speedvpn.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val DIAG_DB_NAME = "speedvpn_diagnostics.db"
private const val DIAG_DB_VERSION = 1

internal data class DiagnosticEvent(
    val id: Long,
    val timestamp: Long,
    val level: String,
    val component: String,
    val eventType: String,
    val message: String,
    val appVersion: String,
    val androidVersion: Int,
    val networkType: String,
)

private class DiagnosticsDbHelper(context: Context) : SQLiteOpenHelper(context, DIAG_DB_NAME, null, DIAG_DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE diagnostic_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                level TEXT NOT NULL,
                component TEXT NOT NULL,
                event_type TEXT NOT NULL,
                message TEXT NOT NULL,
                app_version TEXT NOT NULL,
                android_version INTEGER NOT NULL,
                network_type TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_diag_timestamp ON diagnostic_event(timestamp DESC)")
        db.execSQL("CREATE INDEX idx_diag_type ON diagnostic_event(event_type, timestamp DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }
}

internal object DiagnosticsRepository {
    private fun helper(context: Context) = DiagnosticsDbHelper(context.applicationContext)

    fun record(
        context: Context,
        level: String,
        component: String,
        eventType: String,
        message: String,
    ) {
        val safeMessage = message.replace(Regex("[\r\n]+"), " ").take(2000)
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("level", level.take(30))
            put("component", component.take(120))
            put("event_type", eventType.take(120))
            put("message", safeMessage)
            put("app_version", BuildConfig.VERSION_NAME)
            put("android_version", android.os.Build.VERSION.SDK_INT)
            put("network_type", NetworkContext.currentType(context))
        }
        helper(context).use { h ->
            h.writableDatabase.insert("diagnostic_event", null, values)
            val cutoff = System.currentTimeMillis() - 1000L * 60L * 60L * 24L * 30L
            h.writableDatabase.delete("diagnostic_event", "timestamp < ?", arrayOf(cutoff.toString()))
        }
    }

    fun recent(context: Context, limit: Int = 30): List<DiagnosticEvent> {
        helper(context).use { h ->
            return h.readableDatabase.query(
                "diagnostic_event",
                arrayOf("id", "timestamp", "level", "component", "event_type", "message", "app_version", "android_version", "network_type"),
                null,
                null,
                null,
                null,
                "timestamp DESC",
                limit.coerceIn(1, 200).toString(),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(DiagnosticEvent(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7), c.getString(8)))
                    }
                }
            }
        }
    }

    fun countSince(context: Context, sinceMillis: Long, level: String? = null): Long {
        helper(context).use { h ->
            val selection = if (level.isNullOrBlank()) "timestamp >= ?" else "timestamp >= ? AND level = ?"
            val args = if (level.isNullOrBlank()) arrayOf(sinceMillis.toString()) else arrayOf(sinceMillis.toString(), level)
            return h.readableDatabase.query(
                "diagnostic_event",
                arrayOf("COUNT(*)"),
                selection,
                args,
                null,
                null,
                null,
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
    }

    fun topProblems(context: Context, sinceMillis: Long, limit: Int = 8): List<Pair<String, Int>> {
        helper(context).use { h ->
            return h.readableDatabase.rawQuery(
                "SELECT component || ': ' || event_type AS label, COUNT(*) AS n FROM diagnostic_event WHERE timestamp >= ? AND level IN ('ERROR','WARN') GROUP BY component, event_type ORDER BY n DESC LIMIT ?",
                arrayOf(sinceMillis.toString(), limit.coerceIn(1, 20).toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.getString(0) to c.getInt(1))
                }
            }
        }
    }
}

internal object NetworkContext {
    fun currentType(context: Context): String {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return "unknown"
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }
}