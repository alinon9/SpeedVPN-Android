package com.speedvpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Keeps the phone linked to the dashboard: register, heartbeat 5s, commands 2.5s, stats 30s. */
class AgentService : Service() {
    companion object {
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, AgentService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AgentService::class.java))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var api: Api
    private lateinit var deviceId: String
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("agent", "Dashboard link", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(
            this, 2,
            NotificationCompat.Builder(this, "agent").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SpeedVPN").setContentText("متصل بلوحة التحكم").setContentIntent(open).build(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!started) {
            started = true
            api = Api(this)
            deviceId = Auth.deviceId(this)
            scope.launch { run() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        VpnRuntime.update { it.copy(agentOnline = false) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        while (scope.isActive) {
            try {
                api.post("/register", JSONObject().put("device_id", deviceId)
                    .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}").put("app_version", BuildConfig.VERSION_NAME))
                log("Registered device $deviceId")
                break
            } catch (e: Exception) {
                note(error = "Register failed: ${e.message}"); delay(5000)
            }
        }
        runCatching {
            val s = api.get("/speed-limit")
            val unit = s.optString("unit", "Mbps")
            SpeedLimiter.setDownloadKbps(SpeedLimiter.toKbps(s.optDoubleOrNull("download_limit"), unit))
            SpeedLimiter.setUploadKbps(SpeedLimiter.toKbps(s.optDoubleOrNull("upload_limit"), unit))
        }
        scope.launch { loop(5000) { heartbeat() } }
        scope.launch { loop(30_000) { stats() } }
        loop(2500) { poll() }
    }

    private suspend fun loop(ms: Long, block: suspend () -> Unit) {
        while (scope.isActive) {
            try { block() } catch (e: Exception) { logE("sync: ${e.message}") }
            delay(ms)
        }
    }

    private suspend fun heartbeat() {
        val s = VpnRuntime.state.value
        try {
            api.post("/heartbeat", JSONObject()
                .put("device_id", deviceId).put("app_version", BuildConfig.VERSION_NAME)
                .put("vpn_status", s.status.name).put("tunnel_status", "local:${s.tunnel}")
                .put("download_bps", s.downloadBps).put("upload_bps", s.uploadBps)
                .put("bytes_in", SpeedLimiter.download.total.get()).put("bytes_out", SpeedLimiter.upload.total.get())
                .put("last_error", s.lastError ?: JSONObject.NULL))
            VpnRuntime.update { it.copy(agentOnline = true) }
        } catch (e: Exception) {
            VpnRuntime.update { it.copy(agentOnline = false) }
            throw e
        }
    }

    private var sentDown = 0L
    private var sentUp = 0L
    private suspend fun stats() {
        val d = SpeedLimiter.download.total.get(); val u = SpeedLimiter.upload.total.get()
        if (d == sentDown && u == sentUp) return
        api.post("/stats", JSONObject().put("device_id", deviceId).put("download_bytes", d - sentDown).put("upload_bytes", u - sentUp))
        sentDown = d; sentUp = u
    }

    private suspend fun poll() {
        val cmds = api.get("/commands?device_id=$deviceId").optJSONArray("commands") ?: return
        for (i in 0 until cmds.length()) handle(cmds.getJSONObject(i))
    }

    private suspend fun ack(id: String, status: String, error: String? = null, extra: JSONObject.() -> Unit = {}) {
        val s = VpnRuntime.state.value
        val body = JSONObject().put("command_id", id).put("device_id", deviceId).put("status", status)
            .put("vpn_status", s.status.name).put("tunnel_status", "local:${s.tunnel}")
        if (error != null) body.put("error", error)
        body.extra()
        runCatching { api.post("/ack", body) }.onFailure { logE("ack failed: ${it.message}") }
        note(response = "$status${error?.let { " — $it" } ?: ""}")
    }

    private suspend fun handle(c: JSONObject) {
        val id = c.getString("command_id")
        val cmd = c.getString("command")
        val payload = c.optJSONObject("payload") ?: JSONObject()
        log("Command received: $cmd")
        VpnRuntime.update { it.copy(lastCommand = cmd) }
        when (cmd) {
            "GET_STATUS" -> ack(id, "SUCCESS")
            "CONNECT" -> connect(id)
            "DISCONNECT" -> disconnect(id)
            "SET_DOWNLOAD_LIMIT", "SET_UPLOAD_LIMIT" -> {
                ack(id, "PROCESSING")
                val dl = cmd == "SET_DOWNLOAD_LIMIT"
                val unit = payload.optString("unit", "Mbps")
                val kbps = SpeedLimiter.toKbps(payload.optDoubleOrNull(if (dl) "download_limit" else "upload_limit"), unit)
                if (dl) SpeedLimiter.setDownloadKbps(kbps) else SpeedLimiter.setUploadKbps(kbps)
                ack(id, "SUCCESS") {
                    put("applied", JSONObject().put(if (dl) "download_limit" else "upload_limit", kbps ?: JSONObject.NULL).put("unit", "Kbps"))
                }
            }
            else -> ack(id, "FAILED", "Unknown command $cmd")
        }
    }

    private suspend fun connect(id: String) {
        log("CONNECT requested")
        if (VpnRuntime.state.value.status == VpnStatus.CONNECTED) return ack(id, "SUCCESS")
        log("VPN permission check")
        if (VpnService.prepare(this) != null) {
            log("VPN permission denied / not granted yet")
            askUserForPermission()
            VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTED, lastError = "VPN permission denied") }
            return ack(id, "FAILED", "VPN permission denied")
        }
        log("VPN permission granted")
        VpnRuntime.update { it.copy(status = VpnStatus.CONNECTING, lastError = null) }
        ack(id, "PROCESSING")
        heartbeatQuiet()
        try {
            SpeedVpnService.start(this)
        } catch (e: Exception) {
            logE("VPN service failed to start", e)
            VpnRuntime.update { it.copy(status = VpnStatus.ERROR, lastError = "VPN service failed to start: ${e.javaClass.simpleName}") }
            return ack(id, "FAILED", "VPN service failed to start (Android blocked a background start — open the app once)")
        }
        val end = withTimeoutOrNull(30_000) {
            VpnRuntime.state.first { it.status == VpnStatus.CONNECTED || it.status == VpnStatus.ERROR }
        }
        when {
            end == null -> { SpeedVpnService.stop(this); ack(id, "FAILED", "Timeout") }
            end.status == VpnStatus.CONNECTED -> ack(id, "SUCCESS") { put("vpn_ip", JSONObject.NULL); put("server_id", JSONObject.NULL) }
            else -> ack(id, "FAILED", end.lastError ?: "Unable to establish tunnel")
        }
        heartbeatQuiet()
    }

    private suspend fun disconnect(id: String) {
        val st = VpnRuntime.state.value.status
        if (st == VpnStatus.DISCONNECTED || st == VpnStatus.ERROR || st == VpnStatus.UNKNOWN) {
            VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTED) }
            return ack(id, "SUCCESS")
        }
        VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTING) }
        ack(id, "PROCESSING")
        SpeedVpnService.stop(this)
        val end = withTimeoutOrNull(15_000) { VpnRuntime.state.first { it.status == VpnStatus.DISCONNECTED } }
        if (end == null) ack(id, "FAILED", "Timeout") else ack(id, "SUCCESS")
        heartbeatQuiet()
    }

    private suspend fun heartbeatQuiet() = runCatching { heartbeat() }

    private fun askUserForPermission() {
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(3,
            NotificationCompat.Builder(this, "agent").setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SpeedVPN يحتاج إذن VPN").setContentText("افتح التطبيق واضغط \"منح إذن VPN\"")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build())
    }

    private fun note(response: String? = null, error: String? = null) =
        VpnRuntime.update { it.copy(lastResponse = response ?: it.lastResponse, lastError = error ?: it.lastError) }
}

fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key)
