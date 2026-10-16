package com.speedvpn.app

import android.content.Context

/**
 * Compatibility constants for the existing UID hook path.
 * The POC activity has been removed, but the service still reads this
 * preference key. Keep its name stable so persisted state is not reinterpreted.
 */
internal object UidPocSettings {
    const val PREFS = "uid_detection_poc_settings"
    const val KEY_DISABLE_JNI_HOOK = "disable_jni_hook"
}

/**
 * No-op shims for call sites retained in the untouched VPN/application files.
 * The POC diagnostics writer, file logging, notifications, and log collectors
 * are intentionally removed from this build.
 */
internal object UidPocDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun log(context: Context, message: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun error(context: Context, message: String, throwable: Throwable? = null) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun jniEvent(context: Context, message: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) = Unit
}
