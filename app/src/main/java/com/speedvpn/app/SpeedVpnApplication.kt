package com.speedvpn.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log

class SpeedVpnApplication : Application() {
    private fun logHistoricalProcessExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d("SpeedVPN", "EXIT_INFO_UNAVAILABLE reason=API_BELOW_30")
            return
        }
        runCatching {
            val manager = getSystemService(ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(packageName, 0, 5)
            if (exits.isEmpty()) {
                Log.e("SpeedVPN", "EXIT_INFO: no historical process exit entries returned")
            }
            exits.forEachIndexed { index, info ->
                val reasonName = when (info.reason) {
                    1 -> "SELF"
                    2 -> "SIGNALED"
                    3 -> "LOW_MEMORY"
                    4 -> "JAVA_CRASH"
                    5 -> "NATIVE_CRASH"
                    6 -> "ANR"
                    7 -> "INITIALIZATION_FAILURE"
                    8 -> "PERMISSION_CHANGE"
                    9 -> "EXCESSIVE_RESOURCE_USAGE"
                    10 -> "USER_REQUESTED"
                    11 -> "USER_STOPPED"
                    12 -> "DEPENDENCY_DIED"
                    13 -> "OTHER"
                    else -> "UNKNOWN"
                }
                val line = "EXIT[$index]: reason=${info.reason}($reasonName) status=${info.status} " +
                    "description=${info.description} process=${info.processName} timestamp=${info.timestamp} " +
                    "importance=${info.importance} pss=${info.pss} rss=${info.rss}"
                Log.e("SpeedVPN", line)
            }
        }.onFailure {
            Log.e("SpeedVPN", "Unable to read historical process exit reasons", it)
        }
    }

    override fun onCreate() {
        super.onCreate()
        logHistoricalProcessExitReasons()
        Log.d("SpeedVPN", "Application.onCreate; API=${Build.VERSION.SDK_INT}; package=$packageName")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SpeedVPN", "Uncaught exception on thread=${thread.name}", throwable)
            runCatching {
                DiagnosticsRepository.record(
                    this,
                    level = "ERROR",
                    component = thread.name.take(120),
                    eventType = "UNCAUGHT_EXCEPTION",
                    message = throwable.stackTraceToString(),
                )
            }
            defaultHandler?.uncaughtException(thread, throwable) ?: Process.killProcess(Process.myPid())
        }
        Log.d("SpeedVPN", "Default uncaught-exception handler installed")
        if (SmartSettings.isStatsEnabled(this)) {
            runCatching {
                UsageCollectionScheduler.schedule(this)
                QuotaWorkScheduler.schedule(this)
            }.onFailure { Log.e("SpeedVPN", "Unable to schedule application workers", it) }
        }
    }
}