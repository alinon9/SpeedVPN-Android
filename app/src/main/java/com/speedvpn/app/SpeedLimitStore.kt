package com.speedvpn.app

import android.content.Context

/** Single persistent source of truth for locally configured speed limits. */
object SpeedLimitStore {
    private const val PREFS = "local_limits"
    private const val KEY_DL = "dl"
    private const val KEY_UL = "ul"

    fun load(context: Context): Pair<Long?, Long?> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_DL, 0L).takeIf { it > 0L } to
            prefs.getLong(KEY_UL, 0L).takeIf { it > 0L }
    }

    fun save(context: Context, downloadKbps: Long?, uploadKbps: Long?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DL, downloadKbps?.coerceAtLeast(0L) ?: 0L)
            .putLong(KEY_UL, uploadKbps?.coerceAtLeast(0L) ?: 0L)
            .commit()
        runCatching {
            AiRepository.upsertSpeedProfile(
                context,
                SpeedProfile(null, true, downloadKbps, uploadKbps, System.currentTimeMillis()),
            )
        }
    }

    fun saveDownload(context: Context, kbps: Long?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DL, kbps?.coerceAtLeast(0L) ?: 0L).commit()
        val (_, upload) = load(context)
        runCatching { AiRepository.upsertSpeedProfile(context, SpeedProfile(null, true, kbps, upload, System.currentTimeMillis())) }
    }

    fun saveUpload(context: Context, kbps: Long?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_UL, kbps?.coerceAtLeast(0L) ?: 0L).commit()
        val (download, _) = load(context)
        runCatching { AiRepository.upsertSpeedProfile(context, SpeedProfile(null, true, download, kbps, System.currentTimeMillis())) }
    }

    fun applyToLimiter(context: Context) {
        val (download, upload) = load(context)
        SpeedLimiter.setDownloadKbps(download)
        SpeedLimiter.setUploadKbps(upload)
    }
}
