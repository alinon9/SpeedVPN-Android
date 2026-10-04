package com.speedvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max

internal class SpeedOverlayService : Service() {
    companion object {
        private const val CHANNEL = "speed-overlay"
        private const val NOTIF_ID = 302
        private const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val EXTRA_TARGET_LABEL = "target_label"

        fun start(context: Context) = startForTarget(context, null, "التحكم العام")

        fun startForApp(context: Context, packageName: String, label: String) =
            startForTarget(context, packageName, label)

        private fun startForTarget(context: Context, packageName: String?, label: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
            val intent = Intent(context, SpeedOverlayService::class.java).apply {
                putExtra(EXTRA_TARGET_PACKAGE, packageName)
                putExtra(EXTRA_TARGET_LABEL, label)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpeedOverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var root: ViewGroup? = null
    private var title: TextView? = null
    private var downloadValue: TextView? = null
    private var uploadValue: TextView? = null
    private var downloadReserved: TextView? = null
    private var uploadReserved: TextView? = null
    private var targetPackage: String? = null
    private var targetLabel: String = "التحكم العام"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            if (root != null) mainHandler.postDelayed(this, 1000L)
        }
    }
    private val increments = listOf(1L, 10L, 25L, 50L, 75L, 100L, 130L, 250L, 500L, 750L, 950L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 9_000L, 10_000L, 11_000L, 100_000L).map { it * 8L }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            SmartSettings.setOverlayEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        targetLabel = intent?.getStringExtra(EXTRA_TARGET_LABEL) ?: if (targetPackage == null) "التحكم العام" else targetPackage!!
        startForegroundCompat()
        if (root == null) addOverlay()
        refresh()
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.post(refreshTask)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Speed control", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SpeedVPN")
            .setContentText("التحكم ${if (targetPackage == null) "العام" else "لتطبيق $targetLabel"}")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun addOverlay() {
        wm = getSystemService(WindowManager::class.java)
        val size = (238 * resources.displayMetrics.density).toInt()
        val parent = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(7, 15, 30))
                setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.rgb(53, 184, 255))
            }
            elevation = 18f
            setPadding(18, 18, 18, 18)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER }
        downloadValue = TextView(this).apply { setTextColor(Color.rgb(53, 184, 255)); textSize = 14f; gravity = Gravity.CENTER }
        uploadValue = TextView(this).apply { setTextColor(Color.rgb(66, 240, 228)); textSize = 14f; gravity = Gravity.CENTER }
        downloadReserved = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 9f; gravity = Gravity.CENTER }
        uploadReserved = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 9f; gravity = Gravity.CENTER }
        column.addView(title, LinearLayout.LayoutParams(-1, 28))
        column.addView(downloadValue, LinearLayout.LayoutParams(-1, 28))
        column.addView(downloadReserved, LinearLayout.LayoutParams(-1, 20))
        column.addView(controlRow(true), LinearLayout.LayoutParams(-1, 44))
        column.addView(uploadValue, LinearLayout.LayoutParams(-1, 28))
        column.addView(uploadReserved, LinearLayout.LayoutParams(-1, 20))
        column.addView(controlRow(false), LinearLayout.LayoutParams(-1, 44))
        parent.addView(column, FrameLayout.LayoutParams(-1, -1))
        val close = button("×") { stopSelf() }.apply { textSize = 18f; background = circleButtonBackground(Color.rgb(70, 25, 40)) }
        parent.addView(close, FrameLayout.LayoutParams(38, 38, Gravity.TOP or Gravity.END))
        parent.setOnTouchListener(DragListener())
        root = parent
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 14
            y = 140
        }
        runCatching { wm.addView(parent, params) }.onFailure {
            root = null
            DiagnosticsRepository.record(this, "ERROR", "SpeedOverlay", "OVERLAY_ATTACH_FAILED", it.message ?: "unable to attach overlay")
            stopSelf()
        }
    }

    private fun controlRow(download: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(button("−") { change(download, -1) }.apply {
            textSize = 22f
            background = circleButtonBackground(Color.rgb(35, 48, 75))
        }, LinearLayout.LayoutParams(48, 40).apply { marginEnd = 12 })
        addView(TextView(this@SpeedOverlayService).apply {
            text = if (download) "↓" else "↑"
            setTextColor(if (download) Color.rgb(53, 184, 255) else Color.rgb(66, 240, 228))
            textSize = 18f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(34, 40))
        addView(button("+") { change(download, 1) }.apply {
            textSize = 22f
            background = circleButtonBackground(Color.rgb(35, 48, 75))
        }, LinearLayout.LayoutParams(48, 40).apply { marginStart = 12 })
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setOnClickListener { action() }
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = circleButtonBackground(Color.rgb(28, 42, 68))
    }

    private fun circleButtonBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(1, Color.rgb(65, 95, 135))
    }

    private fun current(): Pair<Long?, Long?> = if (targetPackage == null) {
        SpeedLimitStore.load(this)
    } else {
        val p = AiRepository.getSpeedProfile(this, targetPackage)
        p?.downloadKbps to p?.uploadKbps
    }

    private fun change(download: Boolean, direction: Int) {
        val current = if (download) current().first else current().second

        // Unlimited is a real state, not "0". From Unlimited, minus moves to the
        // highest bounded preset; plus keeps Unlimited. This avoids the old jump
        // from Unlimited to 2 MB/s caused by treating null as 1,000 Kbps.
        val next = if (current == null) {
            if (direction < 0) increments.lastOrNull() else null
        } else if (direction > 0) {
            increments.firstOrNull { it > current }
                ?: null
        } else {
            increments.lastOrNull { it < current } ?: increments.firstOrNull()
        }

        set(download, next)
    }

    private fun set(download: Boolean, kbps: Long?) {
        if (targetPackage == null) {
            if (download) SpeedLimitStore.saveDownload(this, kbps) else SpeedLimitStore.saveUpload(this, kbps)
            if (download) SpeedLimiter.setDownloadKbps(kbps) else SpeedLimiter.setUploadKbps(kbps)
            DiagnosticsRepository.record(this, "INFO", "SpeedOverlay", "GLOBAL_SPEED_CHANGED", "Global ${if (download) "download" else "upload"} changed by overlay")
        } else {
            val existing = AiRepository.getSpeedProfile(this, targetPackage)
            AppSpeedProfiles(this).save(
                targetPackage!!,
                true,
                if (download) kbps else existing?.downloadKbps,
                if (download) existing?.uploadKbps else kbps,
            )
            DiagnosticsRepository.record(this, "INFO", "SpeedOverlay", "APP_SPEED_PROFILE_CHANGED", "App speed profile changed for $targetPackage")
        }
        refresh()
    }

    private fun refresh() {
        val (dl, ul) = current()
        val runtime = VpnRuntime.state.value
        title?.text = "SpeedVPN • ${if (targetPackage == null) "عام" else targetLabel}"
        downloadValue?.text = "↓ VPN ${formatBitRate(runtime.downloadBps)}"
        downloadReserved?.text = "محجوز: ${formatRate(dl)}"
        uploadValue?.text = "↑ VPN ${formatBitRate(runtime.uploadBps)}"
        uploadReserved?.text = "محجوز: ${formatRate(ul)}"
    }

    private fun formatRate(kbps: Long?): String {
        if (kbps == null) return "بدون حد"
        val bytes = kbps * 125.0
        return when {
            bytes < 1_000 -> String.format(Locale.US, "%.0f B/s", bytes)
            bytes < 1_000_000 -> String.format(Locale.US, "%.1f KB/s", bytes / 1_000.0)
            else -> String.format(Locale.US, "%.2f MB/s", bytes / 1_000_000.0)
        }
    }

    private fun formatBitRate(bitsPerSecond: Long): String {
        val bps = bitsPerSecond.coerceAtLeast(0L)
        return when {
            bps < 1_000L -> bps.toString() + " bps"
            bps < 1_000_000L -> String.format(Locale.US, "%.0f Kbps", bps / 1_000.0)
            bps < 1_000_000_000L -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
            else -> String.format(Locale.US, "%.2f Gbps", bps / 1_000_000_000.0)
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var initialX = 0
        private var initialY = 0
        private var moved = false
        private val params get() = root?.layoutParams as? WindowManager.LayoutParams
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = p.x; initialY = p.y; moved = false; return true }
                MotionEvent.ACTION_MOVE -> { val dx = (downX - event.rawX).toInt(); val dy = (event.rawY - downY).toInt(); if (kotlin.math.abs(dx) > 4 || kotlin.math.abs(dy) > 4) moved = true; p.x = max(0, initialX + dx); p.y = max(0, initialY + dy); runCatching { wm.updateViewLayout(v, p) }; return true }
                MotionEvent.ACTION_UP -> return moved
            }
            return false
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshTask)
        root?.let { view -> runCatching { wm.removeView(view) } }
        root = null
        super.onDestroy()
    }
}