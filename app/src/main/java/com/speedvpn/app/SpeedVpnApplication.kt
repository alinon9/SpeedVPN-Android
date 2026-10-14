package com.speedvpn.app

import android.app.Application
import android.os.Build
import android.os.Process

class SpeedVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UidPocDiagnostics.log(this, "Application.onCreate; API=${Build.VERSION.SDK_INT}; package=$packageName")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            UidPocDiagnostics.recordCrash(this, thread, throwable)
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
        UidPocDiagnostics.log(this, "Default uncaught-exception handler installed")
        if (SmartSettings.isStatsEnabled(this)) {
            runCatching {
                UsageCollectionScheduler.schedule(this)
                QuotaWorkScheduler.schedule(this)
            }.onFailure { UidPocDiagnostics.error(this, "Unable to schedule application workers", it) }
        }
    }
}