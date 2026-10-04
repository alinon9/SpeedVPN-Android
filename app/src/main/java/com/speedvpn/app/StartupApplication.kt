package com.speedvpn.app

import android.app.Application
import android.os.Process

/** Minimal application used by the startup diagnostic launcher. */
class StartupApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                getSharedPreferences("crash_recovery", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", throwable.stackTraceToString().take(16000))
                    .putLong("last_crash_at", System.currentTimeMillis())
                    .commit()
            }
            previous?.uncaughtException(thread, throwable)
                ?: Process.killProcess(Process.myPid())
        }
    }
}