package com.speedvpn.app

import android.content.Context

/** Single persistent source of truth for locally configured speed limits. */
object SpeedLimitStore {
    private const val PREFS = "local_limits"
    private const val KEY_DL = "dl"
    private const val KEY_UL = "ul"
    private val lock = Any()
    private var stagedLimits: Pair<Long?, Long?>? = null

    private fun normalize(kbps: Long?): Long? = kbps?.takeIf { it > 0L }

    private fun readPersisted(context: Context): Pair<Long?, Long?> {
        SpeedPlanUsageTracker.registerContext(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_DL, 0L).takeIf { it > 0L } to
            prefs.getLong(KEY_UL, 0L).takeIf { it > 0L }
    }

    fun load(context: Context): Pair<Long?, Long?> = synchronized(lock) {
        SpeedPlanUsageTracker.registerContext(context)
        stagedLimits?.let { return@synchronized it }
        readPersisted(context).also { stagedLimits = it }
    }

    fun stageDownload(context: Context, kbps: Long?) {
        SpeedPlanUsageTracker.registerContext(context)
        synchronized(lock) {
            val (_, currentUpload) = stagedLimits ?: readPersisted(context)
            stagedLimits = normalize(kbps) to currentUpload
        }
    }

    fun stageUpload(context: Context, kbps: Long?) {
        SpeedPlanUsageTracker.registerContext(context)
        synchronized(lock) {
            val (currentDownload, _) = stagedLimits ?: readPersisted(context)
            stagedLimits = currentDownload to normalize(kbps)
        }
    }

    fun save(context: Context, downloadKbps: Long?, uploadKbps: Long?) {
        SpeedPlanUsageTracker.registerContext(context)
        val normalizedDownload = normalize(downloadKbps)
        val normalizedUpload = normalize(uploadKbps)
        synchronized(lock) {
            stagedLimits = normalizedDownload to normalizedUpload
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_DL, normalizedDownload ?: 0L)
                .putLong(KEY_UL, normalizedUpload ?: 0L)
                .commit()
        }
        runCatching {
            AiRepository.upsertSpeedProfile(
                context,
                SpeedProfile(null, true, normalizedDownload, normalizedUpload, System.currentTimeMillis()),
            )
        }
    }

    fun saveDownload(context: Context, kbps: Long?) {
        SpeedPlanUsageTracker.registerContext(context)
        val normalizedDownload = normalize(kbps)
        val upload = synchronized(lock) {
            val (_, currentUpload) = stagedLimits ?: readPersisted(context)
            stagedLimits = normalizedDownload to currentUpload
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_DL, normalizedDownload ?: 0L).commit()
            currentUpload
        }
        runCatching {
            AiRepository.upsertSpeedProfile(
                context,
                SpeedProfile(null, true, normalizedDownload, upload, System.currentTimeMillis()),
            )
        }
    }

    fun saveUpload(context: Context, kbps: Long?) {
        SpeedPlanUsageTracker.registerContext(context)
        val normalizedUpload = normalize(kbps)
        val download = synchronized(lock) {
            val (currentDownload, _) = stagedLimits ?: readPersisted(context)
            stagedLimits = currentDownload to normalizedUpload
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_UL, normalizedUpload ?: 0L).commit()
            currentDownload
        }
        runCatching {
            AiRepository.upsertSpeedProfile(
                context,
                SpeedProfile(null, true, download, normalizedUpload, System.currentTimeMillis()),
            )
        }
    }

    fun applyToLimiter(context: Context) {
        SpeedPlanUsageTracker.registerContext(context)
        val (download, upload) = load(context)
        SpeedLimiter.setLimitsKbps(download, upload)
    }

    /** Restore saved rates only if this process has not accepted a newer live plan. */
    fun restoreToLimiterIfUninitialized(context: Context) {
        SpeedPlanUsageTracker.registerContext(context)
        val (download, upload) = load(context)
        SpeedLimiter.initializeLimitsIfUnset(download, upload)
    }

    internal fun resetStagedForTests() {
        synchronized(lock) {
            stagedLimits = null
        }
    }
}
