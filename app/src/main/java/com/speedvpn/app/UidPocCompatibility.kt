package com.speedvpn.app

import android.content.Context
import android.util.Log

/**
 * Lightweight compatibility facade for legacy call sites retained in the VPN
 * service and UID resolver. The POC-only persistent file logger, crash reports,
 * and notification UI are intentionally removed.
 */
@Suppress("UNUSED_PARAMETER")
internal object UidPocCompatibility {
    private const val TAG = "SpeedVPN"
    private const val JNI_TAG = "SpeedVPN-UID"

    fun log(context: Context, message: String) {
        Log.d(TAG, "[${context.packageName}] $message")
    }

    fun error(context: Context, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[${context.packageName}] $message", throwable)
    }

    /**
     * Intentionally does not write files or synchronously log per-flow events
     * from the native callback worker.
     */
    fun jniEvent(context: Context, message: String) = Unit

    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception on thread=${thread.name}", throwable)
    }
}

/**
 * Keeps existing service/application/resolver sources source-compatible without
 * editing the protected VPN service, Java bridge, or native tunnel sources.
 */
@Suppress("UNUSED_PARAMETER")
internal object UidPocDiagnostics {
    fun log(context: Context, message: String) = UidPocCompatibility.log(context, message)
    fun error(context: Context, message: String, throwable: Throwable? = null) =
        UidPocCompatibility.error(context, message, throwable)
    fun jniEvent(context: Context, message: String) = UidPocCompatibility.jniEvent(context, message)
    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) =
        UidPocCompatibility.recordCrash(context, thread, throwable)
}
