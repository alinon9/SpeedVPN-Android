package com.speedvpn.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object QuotaEnforcementEngine {
    /**
     * Evaluates every configured quota against NetworkStats-derived daily_usage.
     * Lazy reset is performed first so a new period starts from clean policy state.
     */
    fun evaluateBlockedPackages(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Set<String> {
        QuotaResetEngine.resetExpiredPolicies(context, nowMillis)
        val blocked = mutableSetOf<String>()

        UsageRepository.readQuotaPolicies(context).forEach { policy ->
            val bounds = QuotaPeriod.current(policy.quotaType, nowMillis)
            val startDate = formatDate(bounds.startMillis)
            val endDate = formatDate(bounds.endMillis)
            val usedBytes = UsageRepository.sumUsageInPeriod(
                context = context,
                packageName = policy.packageName,
                startDate = startDate,
                endDate = endDate,
            )
            if (usedBytes >= policy.limitBytes) {
                blocked += policy.packageName
            }
        }

        return blocked
    }

    /**
     * Applies the evaluated quota block set and restarts the VPN only when it changed.
     */
    fun enforce(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val evaluated = evaluateBlockedPackages(context, nowMillis)
        val current = VpnAppControl.quotaBlockedPackages(context)
        if (evaluated == current) return false

        VpnAppControl.replaceQuotaBlockedPackages(context, evaluated)

        evaluated.minus(current).forEach { packageName ->
            DiagnosticsRepository.record(
                context,
                "WARN",
                "DataLimit",
                "APP_QUOTA_REACHED",
                "$packageName reached its configured quota",
            )
        }
        current.minus(evaluated).forEach { packageName ->
            DiagnosticsRepository.record(
                context,
                "INFO",
                "DataLimit",
                "APP_QUOTA_RESET",
                "$packageName quota block reset for a new period",
            )
        }

        if (VpnRuntime.state.value.status == VpnStatus.CONNECTED) {
            SpeedVpnService.restartForSettings(context)
        }
        return true
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
