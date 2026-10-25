package com.speedvpn.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

internal class QuotaEnforcementWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            QuotaEnforcementEngine.enforce(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagnosticsRepository.record(
                applicationContext,
                "WARN",
                "Quota",
                "WORKMANAGER_ENFORCEMENT_FAILED",
                error.stackTraceToString(),
            )
            Result.retry()
        }
    }
}

internal object QuotaWorkScheduler {
    private const val WORK_NAME = "quota_enforcement_optimizer"

    @Volatile
    internal var scheduledForTests: Boolean = false
        private set

    fun schedule(context: Context) {
        scheduledForTests = true
        runCatching {
            // Enforcement reads durable local counters and also needs to run while offline.
            val request = androidx.work.PeriodicWorkRequestBuilder<QuotaEnforcementWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES,
            ).build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    fun cancel(context: Context) {
        scheduledForTests = false
        runCatching {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    /**
     * Keeps periodic quota enforcement active whenever any app quota policy is configured,
     * even if the user disables optional usage statistics (`جمع الإحصاءات`).
     */
    fun syncWithConfiguredPolicies(context: Context) {
        val hasQuotas = runCatching {
            QuotaEnforcementEngine.hasAnyConfiguredQuotaPolicies(context)
        }.getOrDefault(false)
        if (hasQuotas || SmartSettings.isStatsEnabled(context)) {
            schedule(context)
        } else {
            cancel(context)
        }
    }
}
