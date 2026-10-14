package com.speedvpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** POC-only durable lifecycle breadcrumbs and Java/native crash file discovery. */
internal object UidPocDiagnostics {
    private const val TAG = "UidDetectionPOC"
    private const val CHANNEL_ID = "uid-poc-diagnostics"
    private const val NOTIFICATION_ID = 2901
    private val lock = Any()

    fun log(context: Context, message: String) {
        val line = "${timestamp()} D/$TAG: $message\n"
        Log.d(TAG, message)
        appendLog(context, line)
    }

    fun error(context: Context, message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val details = if (throwable == null) "" else "\n${throwable.stackTraceToString()}"
        appendLog(context, "${timestamp()} E/$TAG: $message$details\n")
    }

    /** Persist a last-stage marker before the risky operation. */
    fun stage(context: Context, message: String) {
        val line = "${timestamp()} STAGE: $message\n"
        Log.i(TAG, line.trimEnd())
        synchronized(lock) {
            runCatching {
                val lastStage = File(directory(context), "SpeedVPN-POC-Last-Stage.txt")
                FileOutputStream(lastStage, false).use { stream ->
                    stream.write(line.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
            }.onFailure { Log.e(TAG, "Unable to persist last stage '$message'", it) }
            appendLog(context, line)
        }
    }

    /** The native handler writes to this exact app-private directory. */
    fun nativeDirectoryPath(context: Context): String = nativeDirectory(context).absolutePath

    fun logFile(context: Context): File = File(directory(context), "SpeedVPN-POC-Log.txt")
    fun crashFile(context: Context): File = File(directory(context), "SpeedVPN-POC-Crash.txt")
    fun nativeCrashFile(context: Context): File = File(nativeDirectory(context), "SpeedVPN-POC-Native-Crash.txt")
    fun nativeStageFile(context: Context): File = File(nativeDirectory(context), "SpeedVPN-POC-Native-Stage.txt")
    fun nativeLastStageFile(context: Context): File = File(nativeDirectory(context), "SpeedVPN-POC-Native-Last-Stage.txt")
    fun lastStageFile(context: Context): File = File(directory(context), "SpeedVPN-POC-Last-Stage.txt")

    fun hasCrashFile(context: Context): Boolean =
        listOf(crashFile(context), nativeCrashFile(context)).any { file ->
            runCatching { file.isFile && file.length() > 0L }.getOrDefault(false)
        }

    fun readLog(context: Context): String = buildString {
        append(readFile(logFile(context)))
        appendSection(this, "Kotlin last stage", readFile(lastStageFile(context)))
        appendSection(this, "Native stage trace", readFile(nativeStageFile(context)))
        appendSection(this, "Native last stage", readFile(nativeLastStageFile(context)))
    }.ifBlank { "لا يوجد سجل محفوظ بعد: ${logFile(context).absolutePath}" }

    fun readCrash(context: Context): String = buildString {
        appendSection(this, "Java/Kotlin uncaught exception", readFile(crashFile(context)))
        appendSection(this, "Native fatal signal", readFile(nativeCrashFile(context)))
    }.ifBlank { "لا يوجد ملف انهيار محفوظ بعد. راجع ${nativeLastStageFile(context).absolutePath}" }

    fun pathsText(context: Context): String =
        "Log: ${logFile(context).absolutePath}\n" +
            "Java Crash: ${crashFile(context).absolutePath}\n" +
            "Native Crash: ${nativeCrashFile(context).absolutePath}\n" +
            "Kotlin last stage: ${lastStageFile(context).absolutePath}\n" +
            "Native stage trace: ${nativeStageFile(context).absolutePath}\n" +
            "Native last stage: ${nativeLastStageFile(context).absolutePath}"

    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val stack = buildString {
            appendLine("SpeedVPN UID Detection POC — uncaught exception")
            appendLine("Time: ${timestamp()}")
            appendLine("Thread: ${thread.name}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Package: ${context.packageName}")
            appendLine()
            append(throwable.stackTraceToString())
            appendLine()
        }
        val crashPath = crashFile(context)
        synchronized(lock) {
            runCatching {
                crashPath.parentFile?.mkdirs()
                FileOutputStream(crashPath, false).use { stream ->
                    stream.write(stack.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
            }
            runCatching {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                file.appendText(
                    "${timestamp()} FATAL: uncaught exception on ${thread.name}; crashFile=${crashPath.absolutePath}\n$stack\n",
                    Charsets.UTF_8,
                )
            }
        }
        Log.e(TAG, "Uncaught exception saved to ${crashPath.absolutePath}", throwable)
        postCrashNotification(context, crashPath.absolutePath, logFile(context).absolutePath)
    }

    private fun appendSection(target: StringBuilder, title: String, contents: String) {
        if (contents.isNotBlank()) {
            target.append("===== $title =====\n")
            target.append(contents)
            if (!contents.endsWith("\n")) target.append('\n')
            target.append('\n')
        }
    }

    private fun readFile(file: File): String =
        runCatching { file.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty() }
            .getOrDefault("Unable to read ${file.absolutePath}")

    private fun appendLog(context: Context, line: String) {
        synchronized(lock) {
            runCatching {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { stream ->
                    stream.write(line.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
            }.onFailure {
                Log.e(
                    TAG,
                    "Could not persist diagnostic log; fallback=${File(context.filesDir, "uid-poc-diagnostics/SpeedVPN-POC-Log.txt").absolutePath}",
                    it,
                )
                runCatching {
                    val fallback = File(context.filesDir, "uid-poc-diagnostics/SpeedVPN-POC-Log.txt")
                    fallback.parentFile?.mkdirs()
                    fallback.appendText("$line LOG_FILE_FALLBACK\n", Charsets.UTF_8)
                }
            }
        }
    }

    private fun nativeDirectory(context: Context): File =
        File(context.filesDir, "uid-poc-diagnostics").also { it.mkdirs() }

    private fun directory(context: Context): File {
        val external = runCatching { context.getExternalFilesDir("uid-poc-diagnostics") }.getOrNull()
        if (external != null && (external.isDirectory || external.mkdirs()) && external.canWrite()) return external
        return nativeDirectory(context)
    }

    private fun postCrashNotification(context: Context, crashPath: String, logPath: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "UID POC crash diagnostics", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Location of UID POC crash and log files" },
                )
            }
            val launchIntent = Intent(context, UidDetectionPocActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, NOTIFICATION_ID, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("تم حفظ تشخيص انهيار POC")
                .setContentText("Crash: $crashPath")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "تم حفظ تفاصيل الانهيار في:\n$crashPath\nالسجل الكامل:\n$logPath\nافتح التطبيق لتصدير الملف وإرساله.",
                    ),
                )
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e(TAG, "Unable to post crash-path notification", it)
            appendLog(context, "${timestamp()} W/$TAG: Notification unavailable: ${it.javaClass.simpleName}: ${it.message}\n")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
