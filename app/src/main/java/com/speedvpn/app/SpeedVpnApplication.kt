package com.speedvpn.app

import android.app.Application
import android.os.Process

class SpeedVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (SmartSettings.isStatsEnabled(this)) UsageCollectionScheduler.schedule(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
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
    }
}