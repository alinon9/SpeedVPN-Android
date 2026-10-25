package com.speedvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { SpeedPlanUsageTracker.ensureRecovered(context) }
            if (SmartSettings.isStatsEnabled(context)) {
                UsageCollectionScheduler.schedule(context)
            } else {
                UsageCollectionScheduler.cancel(context)
            }
            // Quota enforcement must remain active across device reboots whenever any
            // app quota policy is configured, regardless of the optional stats toggle.
            QuotaWorkScheduler.syncWithConfiguredPolicies(context)
        }
    }
}