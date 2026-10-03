package com.speedvpn.app

import android.content.Context

internal object QuotaResetEngine {
    fun resetExpiredPolicies(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        var resetCount = 0
        UsageRepository.readQuotaPolicies(context).forEach { policy ->
            val current = QuotaPeriod.current(policy.quotaType, nowMillis)
            if (policy.periodStartMillis != current.startMillis ||
                policy.periodEndMillis != current.endMillis
            ) {
                if (UsageRepository.resetQuotaPeriod(
                        context = context,
                        packageName = policy.packageName,
                        quotaType = policy.quotaType,
                        periodStartMillis = current.startMillis,
                        periodEndMillis = current.endMillis,
                    )
                ) resetCount++
            }
        }
        return resetCount
    }
}
