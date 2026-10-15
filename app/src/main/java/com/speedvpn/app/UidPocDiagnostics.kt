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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** POC-only crash and lifecycle breadcrumbs. */
internal object UidPocDiagnostics {
    private const val TAG = "UidDetectionPOC"
    private const val JNI_TAG = "SpeedVPN-POC-JNI"
    private const val CHANNEL_ID = "uid-poc-diagnostics"
    private const val NOTIFICATION_ID = 2901
    private const val MAX_JNI_LOG_BYTES = 4L * 1024L * 1024L
    private val lock = Any()
    private val jniQueue = ArrayBlockingQueue<String>(4096)
    private val jniWriterStarted = AtomicBoolean(false)
    private val jniDropped = AtomicLong(0L)

    fun log(context: Context, message: String) {
        val time = timestamp()
        Log.d(TAG, message)
        appendLog(context, "$time D/$TAG: $message\n")
    }

    fun error(context: Context, message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val details = if (throwable == null) "" else "\n§{throwable.stackTraceToString()}"
        appendLog(context, "§{timestamp()} E/$TAG: $message$details\n")
    }

    fun logFile(context: Context): File = File(directory(context), "SpeedVPN-POC-Log.txt")
    fun jniFile(context: Context): File = File(directory(context), "SpeedVPN-POC-JNI.txt")
    fun nativeCrashFile(context: Context): File = File(internalDirectory(context), "Native-Crash.txt")
    fun crashFile(context: Context): File = File(internalDirectory(context), "Crash.txt")

    /** Ensure the private fallback directory exists before the JNI library's constructor runs. */
    fun prepareNativeCrashHandlerStorage(context: Context) {
        internalDirectory(context)
        directory(context)
    }

    fun hasCrashFile(context: Context): Boolean =
        runCatching { crashFile(context).isFile && crashFile(context).length() > 0L }.getOrDefault(false)

    fun readLog(context: Context): String =
        runCatching { logFile(context).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty() }
            .getOrDefault("تعذر قراءة ملف السجل: §{logFile(context).absolutePath}")

    fun readCrash(context: Context): String =
        runCatching { crashFile(context).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty() }
            .getOrDefault("تعذر قراءة ملف الانهيار: §{crashFile(context).absolutePath}")
            .ifBlank { "لا يوجد تقرير Crash محفوظ بعد. اضغط «تصدير Crash» لجمع المصادر المتاحة." }

    fun pathsText(context: Context): String =
        "Crash: §{crashFile(context).absolutePath}\n" +
            "Native: §{nativeCrashFile(context).absolutePath}\n" +
            "JNI: §{jniFile(context).absolutePath}\n" +
            "Log: §{logFile(context).absolutePath}"

    /**
     * Called from the JNI->Kotlin callback. The bounded queue keeps filesystem I/O off
     * the native tunnel thread; dropped entries are counted and reported in a later row.
     */
    fun jniEvent(context: Context, message: String) {
        val line = "§{timestamp()} $JNI_TAG $message\n"
        if (!jniQueue.offer(line)) {
            jniDropped.incrementAndGet()
            return
        }
        if (jniWriterStarted.compareAndSet(false, true)) {
            val appContext = context.applicationContext
            Thread({
                val file = jniFile(appContext)
                runCatching { file.parentFile?.mkdirs() }
                while (!Thread.currentThread().isInterrupted) {
                    val next = try {
                        jniQueue.poll(250, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        null
                    } ?: continue
                    runCatching {
                        if (file.length() > MAX_JNI_LOG_BYTES) {
                            file.writeText("§{timestamp()} JNI log rotated at §{MAX_JNI_LOG_BYTES} bytes\n", Charsets.UTF_8)
                        }
                        FileOutputStream(file, true).use { output ->
                            val dropped = jniDropped.getAndSet(0L)
                            if (dropped > 0L) {
                                output.write("§{timestamp()} $JNI_TAG DROPPED_EVENTS count=$dropped (bounded queue full)\n".toByteArray(Charsets.UTF_8))
                            }
                            output.write(next.toByteArray(Charsets.UTF_8))
                            output.flush()
                        }
                    }.onFailure { Log.e(JNI_TAG, "Unable to append callback event", it) }
                }
            }, "speedvpn-poc-jni-log").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Collects what a normal app process can read; permission failures are part of the report. */
    fun captureCrashReport(context: Context, hookDisabled: Boolean): String {
        prepareNativeCrashHandlerStorage(context)
        val report = buildString {
            appendLine("SpeedVPN UID Detection POC — Crash.txt")
            appendLine("Captured: §{timestamp()}")
            appendLine("Package: §{context.packageName}")
            appendLine("Android API: §{Build.VERSION.SDK_INT}")
            appendLine("Device: §{Build.MANUFACTURER} §{Build.MODEL}")
            appendLine("JNI hook disabled for selected test mode: $hookDisabled")
            appendLine("Process ID: §{android.os.Process.myPid()}")
            appendLine("Note: a normal app sandbox may not read /data/tombstones or the system crash buffer.")
            appendLine()

            appendFileSection(this, "PREVIOUS JAVA/LOCAL CRASH REPORT", crashFile(context), 80, 16000)
            appendFileSection(this, "NATIVE SIGNAL HANDLER OUTPUT", nativeCrashFile(context), 300, 48000)
            appendFileSection(this, "JNI CALLBACK TRACE", jniFile(context), 300, 48000)
            appendFileSection(this, "APPLICATION DIAGNOSTIC LOG", logFile(context), 300, 48000)
            val privateLog = File(internalDirectory(context), "SpeedVPN-POC-Log.txt")
            if (privateLog.absolutePath != logFile(context).absolutePath) {
                appendFileSection(this, "INTERNAL FALLBACK APPLICATION LOG", privateLog, 200, 24000)
            }
            appendLine()
            appendLine(collectCrashLogcat(context))
            appendLine()
            appendLine(collectTombstones())
            appendLine()
            appendLine("END OF REPORT")
        }
        synchronized(lock) {
            runCatching {
                val file = crashFile(context)
                file.parentFile?.mkdirs()
                FileOutputStream(file, false).use { output ->
                    output.write(report.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            }.onFailure { Log.e(TAG, "Could not save Crash.txt locally", it) }
        }
        return report
    }

    private fun appendFileSection(
        output: StringBuilder,
        title: String,
        file: File,
        maxLines: Int,
        maxChars: Int,
    ) {
        output.appendLine("===== $title =====")
        output.appendLine("Path: §{file.absolutePath}")
        output.appendLine("Exists: §{file.exists()} readable=§{file.canRead()}")
        if (!file.isFile) {
            output.appendLine("Status: NOT_AVAILABLE (file does not exist or cannot be listed)")
            output.appendLine()
            return
        }
        val content = runCatching { file.readText(Charsets.UTF_8) }
        if (content.isFailure) {
            output.appendLine("Status: READ_FAILED §{content.exceptionOrNull()?.javaClass?.simpleName}: §{content.exceptionOrNull()?.message}")
        } else {
            val tail = content.getOrThrow().lineSequence().toList().takeLast(maxLines).joinToString("\n").takeLast(maxChars)
            output.appendLine(if (tail.isBlank()) "(empty file)" else tail)
        }
        output.appendLine()
    }

    private fun collectCrashLogcat(context: Context): String {
        val temporary = File(internalDirectory(context), "logcat-crash-capture.tmp")
        val command = "logcat -d -b crash -t 500"
        return try {
            val process = ProcessBuilder("logcat", "-d", "-b", "crash", "-t", "500")
                .redirectErrorStream(true)
                .redirectOutput(temporary)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val code = if (finished) process.exitValue().toString() else "TIMEOUT"
            val output = runCatching { temporary.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty() }
                .getOrElse { "Unable to read captured logcat output: §{it.javaClass.simpleName}: §{it.message}" }
                .lineSequence().toList().takeLast(500).joinToString("\n").takeLast(48000)
            val status = when {
                !finished -> "TIMEOUT after 5 seconds; process forcibly destroyed"
                code != "0" -> "FAILED exitCode=$code (output below may contain permission/error text)"
                output.isBlank() -> "OK exitCode=0 but crash buffer returned no entries"
                else -> "OK exitCode=0; recent crash-buffer entries captured"
            }
            runCatching { temporary.delete() }
            "===== LOGCAT CRASH BUFFER =====\nCommand: $command\nStatus: $status\n$output"
        } catch (t: Throwable) {
            "===== LOGCAT CRASH BUFFER =====\nCommand: $command\nStatus: FAILED §{t.javaClass.simpleName}: §{t.message}"
        }
    }

    private fun collectTombstones(): String {
        val directory = File("/data/tombstones")
        return try {
            val files = directory.listFiles { file ->
                file.isFile && file.name.startsWith("tombstone_")
            }
            if (files == null) {
                "===== TOMBSTONE READER =====\nPath: §{directory.absolutePath}\nStatus: UNREADABLE_OR_DENIED; listFiles() returned null. Android sandbox/SELinux commonly restricts this path; null does not distinguish denial from inaccessible directory."
            } else if (files.isEmpty()) {
                "===== TOMBSTONE READER =====\nPath: §{directory.absolutePath}\nStatus: NO_VISIBLE_FILES; this does not prove that the system created no tombstone."
            } else {
                buildString {
                    appendLine("===== TOMBSTONE READER =====")
                    appendLine("Path: §{directory.absolutePath}")
                    appendLine("Visible tombstones: §{files.size}")
                    files.sortedByDescending { it.lastModified() }.take(2).forEach { file ->
                        appendLine("--- §{file.name}; modified=§{file.lastModified()}; bytes=§{file.length()} ---")
                        val content = runCatching { file.readText(Charsets.UTF_8) }
                        if (content.isSuccess) appendLine(content.getOrThrow().take(32000))
                        else appendLine("READ_FAILED §{content.exceptionOrNull()?.javaClass?.simpleName}: §{content.exceptionOrNull()?.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            "===== TOMBSTONE READER =====\nPath: /data/tombstones\nStatus: FAILED §{t.javaClass.simpleName}: §{t.message}"
        }
    }

    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val stack = buildString {
            appendLine("SpeedVPN UID Detection POC — uncaught Java/Kotlin exception")
            appendLine("Time: §{timestamp()}")
            appendLine("Thread: §{thread.name}")
            appendLine("Android API: §{Build.VERSION.SDK_INT}")
            appendLine("Device: §{Build.MANUFACTURER} §{Build.MODEL}")
            appendLine("Package: §{context.packageName}")
            appendLine()
            append(throwable.stackTraceToString())
            appendLine()
        }
        val crashPath = crashFile(context)
        synchronized(lock) {
            runCatching {
                crashPath.parentFile?.mkdirs()
                crashPath.writeText(stack, Charsets.UTF_8)
            }
            runCatching {
                logFile(context).parentFile?.mkdirs()
                logFile(context).appendText(
                    "§{timestamp()} FATAL: uncaught exception on §{thread.name}; crashFile=§{crashPath.absolutePath}\n$stack\n",
                    Charsets.UTF_8,
                )
            }
        }
        Log.e(TAG, "Uncaught exception saved to §{crashPath.absolutePath}", throwable)
        postCrashNotification(context, crashPath.absolutePath, logFile(context).absolutePath)
    }

    private fun appendLog(context: Context, line: String) {
        synchronized(lock) {
            runCatching {
                logFile(context).parentFile?.mkdirs()
                logFile(context).appendText(line, Charsets.UTF_8)
            }.onFailure {
                Log.e(TAG, "Could not persist diagnostic log", it)
                runCatching {
                    val fallback = File(internalDirectory(context), "SpeedVPN-POC-Log.txt")
                    fallback.appendText("$line LOG_FILE_FALLBACK\n", Charsets.UTF_8)
                }
            }
        }
    }

    private fun internalDirectory(context: Context): File =
        File(context.filesDir, "uid-poc-diagnostics").also { it.mkdirs() }

    private fun directory(context: Context): File {
        internalDirectory(context)
        val external = runCatching { context.getExternalFilesDir("uid-poc-diagnostics") }.getOrNull()
        if (external != null && (external.isDirectory || external.mkdirs()) && external.canWrite()) return external
        return internalDirectory(context)
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
                        "تم حفظ تفاصيل الاستثناء في:\n$crashPath\nالسجل الكامل:\n$logPath\nافتح التطبيق لتصدير الملف وإرساله.",
                    ),
                )
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e(TAG, "Unable to post crash-path notification", it)
            appendLog(context, "§{timestamp()} W/$TAG: Notification unavailable: §{it.javaClass.simpleName}: §{it.message}\n")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
