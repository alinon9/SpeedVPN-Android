package com.speedvpn.app

import android.app.Application
import io.sentry.android.core.SentryAndroid
import android.os.Process

class SpeedVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.SENTRY_DSN.isNotBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                options.isSendDefaultPii = false
                options.isEnableAutoSessionTracking = true
                options.tracesSampleRate = 0.0
                options.environment = BuildConfig.BUILD_ENV
                options.release = "com.speedvpn.app@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            }
        }
        if (SmartSettings.isStatsEnabled(this)) {
            UsageCollectionScheduler.schedule(this)
            QuotaWorkScheduler.schedule(this)
        }
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