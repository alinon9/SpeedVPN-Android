package com.speedvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || !SmartSettings.isStatsEnabled(context)) return
        val appContext = context.applicationContext

        runCatching { UsageCollectionScheduler.schedule(appContext) }
            .onFailure { error ->
                runCatching {
                    DiagnosticsRepository.record(
                        appContext,
                        "WARN",
                        "BootReceiver",
                        "USAGE_WORK_SCHEDULE_FAILED",
                        error.stackTraceToString(),
                    )
                }
            }

        runCatching { QuotaWorkScheduler.schedule(appContext) }
            .onFailure { error ->
                runCatching {
                    DiagnosticsRepository.record(
                        appContext,
                        "WARN",
                        "BootReceiver",
                        "QUOTA_WORK_SCHEDULE_FAILED",
                        error.stackTraceToString(),
                    )
                }
            }
    }
}
