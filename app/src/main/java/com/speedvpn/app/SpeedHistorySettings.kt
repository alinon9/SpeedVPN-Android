package com.speedvpn.app

import android.content.Context

/**
 * Controls optional persistence of speed-plan usage history.
 * Independent from app-usage statistics and does not control the VPN itself.
 */
internal object SpeedHistorySettings {
    private const val PREFERENCES_NAME = "speed_history_settings"
    private const val KEY_COLLECTION_ENABLED = "collection_enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COLLECTION_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COLLECTION_ENABLED, enabled)
            .apply()
    }
}
