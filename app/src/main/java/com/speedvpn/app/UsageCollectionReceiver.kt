package com.speedvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class UsageCollectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!SmartSettings.isStatsEnabled(context)) return
        runCatching {
            UsageCollectionScheduler.enqueueNow(context.applicationContext)
        }.onFailure { error ->
            runCatching {
                DiagnosticsRepository.record(
                    context.applicationContext,
                    "WARN",
                    "UsageCollection",
                    "LEGACY_RECEIVER_ENQUEUE_FAILED",
                    error.stackTraceToString(),
                )
            }
        }
    }
}
