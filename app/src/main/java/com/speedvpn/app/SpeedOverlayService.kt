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
        val dm = resources.displayMetrics
        val width = dp(206)
        val height = dp(178)

        val parent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(10, 18, 34))
                setStroke(dp(1), Color.rgb(55, 83, 120))
            }
            elevation = dp(10).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(28), 1f))

        val close = TextView(this).apply {
            text = "×"
            setTextColor(Color.rgb(220, 230, 242))
            textSize = 20f
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(28, 42, 66), dp(12))
            setOnClickListener { stopSelf() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(28), dp(28)))
        parent.addView(header, LinearLayout.LayoutParams(-1, dp(30)))

        downloadValue = metricText(Color.rgb(53, 184, 255))
        uploadValue = metricText(Color.rgb(66, 240, 228))
        downloadReserved = reservedText()
        uploadReserved = reservedText()

        parent.addView(controlRow(true), LinearLayout.LayoutParams(-1, dp(54)))
        parent.addView(controlRow(false), LinearLayout.LayoutParams(-1, dp(54)))

        val footer = TextView(this).apply {
            text = "اسحب النافذة لتحريكها"
            setTextColor(Color.rgb(130, 150, 176))
            textSize = 8.5f
            gravity = Gravity.CENTER
        }
        parent.addView(footer, LinearLayout.LayoutParams(-1, dp(18)))

        parent.setOnTouchListener(DragListener())
        root = parent

        val params = WindowManager.LayoutParams(
            width, height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(110)
        }

        runCatching { wm.addView(parent, params) }.onFailure {
            root = null
            DiagnosticsRepository.record(this, "ERROR", "SpeedOverlay", "OVERLAY_ATTACH_FAILED", it.message ?: "unable to attach overlay")
            stopSelf()
        }
    }

    private fun controlRow(download: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(2))

        addView(button("−") { change(download, -1) }.apply {
            textSize = 18f
            background = roundedBackground(Color.rgb(24, 38, 62), dp(10))
        }, LinearLayout.LayoutParams(dp(34), dp(38)))

        val center = LinearLayout(this@SpeedOverlayService).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val value = if (download) downloadReserved else uploadReserved
        val live = if (download) downloadValue else uploadValue
        center.addView(TextView(this@SpeedOverlayService).apply {
            text = if (download) "↓ Download" else "↑ Upload"
            setTextColor(if (download) Color.rgb(53, 184, 255) else Color.rgb(66, 240, 228))
            textSize = 9f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(16)))
        center.addView(live, LinearLayout.LayoutParams(-1, dp(19)))
        center.addView(value, LinearLayout.LayoutParams(-1, dp(15)))
        addView(center, LinearLayout.LayoutParams(0, dp(50), 1f))

        addView(button("+") { change(download, 1) }.apply {
            textSize = 18f
            background = roundedBackground(Color.rgb(24, 38, 62), dp(10))
        }, LinearLayout.LayoutParams(dp(34), dp(38)))
    }

    private fun metricText(color: Int) = TextView(this).apply {
        setTextColor(color)
        textSize = 11f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun reservedText() = TextView(this).apply {
        setTextColor(Color.rgb(150, 165, 188))
        textSize = 8.5f
        gravity = Gravity.CENTER
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
        setStroke(dp(1), Color.rgb(55, 78, 108))
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
        if (SpeedLimiter.verificationInProgress.value) return
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
        if (SpeedLimiter.verificationInProgress.value) return
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
        setSpeedControlsEnabled(!SpeedLimiter.verificationInProgress.value)
        title?.text = "SpeedVPN • ${if (targetPackage == null) "عام" else targetLabel}"
        downloadValue?.text = "↓ VPN ${formatBitRate(runtime.downloadBps)}"
        downloadReserved?.text = "محجوز: ${formatRate(dl)}"
        uploadValue?.text = "↑ VPN ${formatBitRate(runtime.uploadBps)}"
        uploadReserved?.text = "محجوز: ${formatRate(ul)}"
    }

    private fun setSpeedControlsEnabled(enabled: Boolean) {
        fun update(view: View) {
            if (view is Button) view.isEnabled = enabled
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) update(view.getChildAt(index))
            }
        }
        root?.let(::update)
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
