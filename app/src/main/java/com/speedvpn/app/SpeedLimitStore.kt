package com.speedvpn.app

import android.content.Context

/** Single persistent source of truth for locally configured speed limits. */
object SpeedLimitStore {
    private const val PREFS = "local_limits"
    private const val KEY_DL = "dl"
    private const val KEY_UL = "ul"

    fun load(context: Context): Pair<Long?, Long?> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SpeedLimiter.normalizeKbps(prefs.getLong(KEY_DL, 0L)) to
            SpeedLimiter.normalizeKbps(prefs.getLong(KEY_UL, 0L))
    }

    fun save(context: Context, downloadKbps: Long?, uploadKbps: Long?) {
        val normalizedDownload = SpeedLimiter.normalizeKbps(downloadKbps)
        val normalizedUpload = SpeedLimiter.normalizeKbps(uploadKbps)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DL, normalizedDownload ?: 0L)
            .putLong(KEY_UL, normalizedUpload ?: 0L)
            .commit()
        runCatching {
            AiRepository.upsertSpeedProfile(
                context,
                SpeedProfile(null, true, normalizedDownload, normalizedUpload, System.currentTimeMillis()),
            )
        }
    }

    fun saveDownload(context: Context, kbps: Long?) {
        val normalized = SpeedLimiter.normalizeKbps(kbps)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DL, normalized ?: 0L).commit()
        val (_, upload) = load(context)
        runCatching {
            AiRepository.upsertSpeedProfile(
                context,
                SpeedProfile(null, true, normalized, upload, System.currentTimeMillis()),
            )
        }
    }

    fun saveUpload(context: Context, kbps: Long?) {
        val normalized = SpeedLimiter.normalizeKbps(kbps)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_UL, normalized ?: 0L).commit()
        val (download, _) = load(context)
        runCatching {
            AiRepository.upsertSpeedProfile(
                context,
                SpeedProfile(null, true, download, normalized, System.currentTimeMillis()),
            )
        }
    }

    fun applyToLimiter(context: Context) {
        val (download, upload) = load(context)
        // Apply both directions under one verification lock so startup cannot expose
        // a transient mixed plan to active TCP or UDP relay workers.
        SpeedLimiter.setLimitsKbps(download, upload)
    }
}
