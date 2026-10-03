package com.speedvpn.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal object QuotaEnforcementEngine {
    private const val RESTART_THROTTLE_MS = 5_000L
    private val lastRestartAtMillis = AtomicLong(Long.MIN_VALUE)
    private val restartPending = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    internal fun resetRestartThrottleForTests() {
        lastRestartAtMillis.set(Long.MIN_VALUE)
    }

    internal fun tryAcquireRestart(nowMillis: Long): Boolean {
        while (true) {
            val previous = lastRestartAtMillis.get()
            if (previous != Long.MIN_VALUE && nowMillis - previous < RESTART_THROTTLE_MS) {
                return false
            }
            if (lastRestartAtMillis.compareAndSet(previous, nowMillis)) {
                return true
            }
        }
    }

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
        restart: (Context) -> Unit = SpeedVpnService::restartForSettings,
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
            requestRestart(context, nowMillis, restart)
        }
        return true
    }

    private fun requestRestart(context: Context, nowMillis: Long, restart: (Context) -> Unit) {
        if (tryAcquireRestart(nowMillis)) {
            restart(context)
            return
        }

        if (restartPending.compareAndSet(0L, 1L)) {
            val delay = (RESTART_THROTTLE_MS - (nowMillis - lastRestartAtMillis.get()))
                .coerceAtLeast(1L)
            mainHandler.postDelayed({
                restartPending.set(0L)
                val retryNow = System.currentTimeMillis()
                if (VpnRuntime.state.value.status == VpnStatus.CONNECTED &&
                    tryAcquireRestart(retryNow)
                ) {
                    restart(context)
                }
            }, delay)
        }
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
