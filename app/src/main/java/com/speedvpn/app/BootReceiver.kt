package com.speedvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && SmartSettings.isStatsEnabled(context)) {
            UsageCollectionScheduler.schedule(context)
        }
    }
}