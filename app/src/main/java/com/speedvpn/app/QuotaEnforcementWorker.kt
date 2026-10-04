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
        if (!SmartSettings.isStatsEnabled(applicationContext)) return Result.success()
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

    fun schedule(context: Context) {
        val request = androidx.work.PeriodicWorkRequestBuilder<QuotaEnforcementWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES,
        ).setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
        ).build()

        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
