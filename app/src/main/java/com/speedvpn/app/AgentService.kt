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
import java.util.UUID

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
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("agent", "Dashboard link", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel("alerts", "Important alerts", NotificationManager.IMPORTANCE_HIGH))
        }
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
                log("Device registration succeeded")
                break
            } catch (e: Exception) {
                if (e is ApiException && (e.code == 401 || e.code == 403)) {
                    Auth.signOut(this)
                    note(error = "Dashboard session expired")
                    stopSelf()
                    return
                }
                note(error = "Register failed: ${e.message}"); delay(5000)
            }
        }
        initStatsAccounting()
        runCatching {
            val s = api.get("/speed-limit")
            val unit = s.optString("unit", "Mbps")
            val dl = SpeedLimiter.toKbps(s.optDoubleOrNull("download_limit"), unit)
            val ul = SpeedLimiter.toKbps(s.optDoubleOrNull("upload_limit"), unit)
            SpeedLimiter.setDownloadKbps(dl)
            SpeedLimiter.setUploadKbps(ul)
            persistLocalLimit(download = true, kbps = dl)
            persistLocalLimit(download = false, kbps = ul)
        }
        scope.launch { loop(5000) { heartbeat() } }
        scope.launch { loop(30_000) { stats() } }
        loop(2500) { poll() }
    }

    private suspend fun loop(ms: Long, block: suspend () -> Unit) {
        while (scope.isActive) {
            try {
                block()
            } catch (e: Exception) {
                logE("sync: ${e.message}")
                if (e is ApiException && (e.code == 401 || e.code == 403)) {
                    Auth.signOut(this)
                    note(error = "Dashboard session expired")
                    stopSelf()
                    return
                }
            }
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

    private val statsPrefs by lazy { getSharedPreferences("stats_sync", MODE_PRIVATE) }
    private var lastObservedDown = 0L
    private var lastObservedUp = 0L
    private var pendingDown = 0L
    private var pendingUp = 0L
    private var pendingBatchId: String? = null

    private fun initStatsAccounting() {
        val currentDown = SpeedLimiter.download.total.get()
        val currentUp = SpeedLimiter.upload.total.get()
        val storedDown = statsPrefs.getLong("last_observed_down", currentDown)
        val storedUp = statsPrefs.getLong("last_observed_up", currentUp)

        // A process restart resets in-memory totals to zero. Do not turn the old
        // lifetime total into new traffic; preserve only unsent pending deltas.
        lastObservedDown = if (currentDown >= storedDown) storedDown else currentDown
        lastObservedUp = if (currentUp >= storedUp) storedUp else currentUp
        pendingDown = statsPrefs.getLong("pending_down", 0L)
        pendingUp = statsPrefs.getLong("pending_up", 0L)
        pendingBatchId = statsPrefs.getString("pending_batch_id", null)
        if ((pendingDown > 0L || pendingUp > 0L) && pendingBatchId.isNullOrBlank()) {
            pendingBatchId = UUID.randomUUID().toString()
        }
    }

    private fun persistStatsState() {
        statsPrefs.edit()
            .putLong("last_observed_down", lastObservedDown)
            .putLong("last_observed_up", lastObservedUp)
            .putLong("pending_down", pendingDown)
            .putLong("pending_up", pendingUp)
            .putString("pending_batch_id", pendingBatchId)
            .apply()
    }

    private suspend fun stats() {
        val d = SpeedLimiter.download.total.get()
        val u = SpeedLimiter.upload.total.get()
        val newDown = if (d >= lastObservedDown) d - lastObservedDown else d
        val newUp = if (u >= lastObservedUp) u - lastObservedUp else u
        lastObservedDown = d
        lastObservedUp = u
        pendingDown = safeAdd(pendingDown, newDown)
        pendingUp = safeAdd(pendingUp, newUp)
        persistStatsState()

        if (pendingDown == 0L && pendingUp == 0L) {
            pendingBatchId = null
            persistStatsState()
            return
        }
        if (pendingBatchId.isNullOrBlank()) pendingBatchId = UUID.randomUUID().toString()
        persistStatsState()

        api.post(
            "/stats",
            JSONObject()
                .put("device_id", deviceId)
                .put("stats_batch_id", pendingBatchId)
                .put("download_bytes", pendingDown)
                .put("upload_bytes", pendingUp),
        )

        // Client delivery is at-least-once. The backend should deduplicate
        // stats_batch_id to provide exactly-once accounting.
        pendingDown = 0L
        pendingUp = 0L
        pendingBatchId = null
        persistStatsState()
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
                if (dl) {
                    SpeedLimiter.setDownloadKbps(kbps)
                    persistLocalLimit(download = true, kbps = kbps)
                } else {
                    SpeedLimiter.setUploadKbps(kbps)
                    persistLocalLimit(download = false, kbps = kbps)
                }
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
        val requestBaselineGeneration = SpeedVpnService.currentGeneration()
        log("VPN permission check (baseline generation=$requestBaselineGeneration)")
        if (VpnService.prepare(this) != null) {
            log("VPN permission denied / not granted yet")
            askUserForPermission()
            VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTED, lastError = "VPN permission denied") }
            return ack(id, "FAILED", "VPN permission denied")
        }
        log("VPN permission granted")
        // Let SpeedVpnService own CONNECTING/DISCONNECTING transitions. In particular,
        // do not overwrite DISCONNECTING here: the VPN service uses that state to queue
        // a CONNECT behind the in-flight disconnect.
        // A fresh CONNECT begins a new attempt, so stale ERROR state must not leak
        // into the new connection lifecycle.
        VpnRuntime.update {
            it.copy(
                status = if (it.status == VpnStatus.ERROR) VpnStatus.DISCONNECTED else it.status,
                tunnel = if (it.status == VpnStatus.ERROR) "down" else it.tunnel,
                serviceRunning = if (it.status == VpnStatus.ERROR) false else it.serviceRunning,
                lastError = null,
                downloadBps = 0,
                uploadBps = 0,
            )
        }
        ack(id, "PROCESSING")
        heartbeatQuiet()
        val requestId = UUID.randomUUID().toString()
        try {
            SpeedVpnService.start(this, requestId)
        } catch (e: Exception) {
            // A start request can race with another Service generation becoming
            // active. Do not overwrite that generation's CONNECTED/DISCONNECTING
            // state with ERROR merely because this start command was rejected
            // (for example by Android's background-FGS rules).
            logE("VPN service failed to start", e)
            VpnRuntime.update {
                it.copy(lastError = "VPN service failed to start: ${e.javaClass.simpleName}")
            }
            return ack(id, "FAILED", "VPN service failed to start (Android blocked a background start — open the app once)")
        }
        val end = withTimeoutOrNull(30_000) {
            VpnRuntime.state.first { snapshot ->
                val generation = SpeedVpnService.currentGeneration()
                val startedForRequest = SpeedVpnService.currentStartRequestId() == requestId
                when {
                    snapshot.status == VpnStatus.CONNECTED ->
                        startedForRequest && generation > 0L && generation != requestBaselineGeneration
                    snapshot.status == VpnStatus.ERROR ->
                        startedForRequest || (generation == 0L && requestBaselineGeneration == 0L)
                    else -> false
                }
            }
        }
        when {
            end == null -> {
                // Never stop an unrelated/newer VPN generation on behalf of a stale
                // CONNECT command. The VPN service owns its own timeout/cleanup.
                ack(id, "FAILED", "Timeout")
            }
            end.status == VpnStatus.CONNECTED -> ack(id, "SUCCESS") { put("vpn_ip", JSONObject.NULL); put("server_id", JSONObject.NULL) }
            else -> ack(id, "FAILED", end.lastError ?: "Unable to establish tunnel")
        }
        heartbeatQuiet()
    }

    private suspend fun disconnect(id: String) {
        val expectedGeneration = SpeedVpnService.currentGeneration()
        if (expectedGeneration <= 0L) {
            // No process-wide VPN owner exists, so this command is already satisfied.
            return ack(id, "SUCCESS")
        }
        // SpeedVpnService owns DISCONNECTING and all generation-sensitive state.
        // Passing the expected generation prevents a stale queued command from
        // stopping a newer VPN service instance.
        ack(id, "PROCESSING")
        SpeedVpnService.stop(this, expectedGeneration)
        val end = withTimeoutOrNull(15_000) {
            VpnRuntime.state.first {
                it.status == VpnStatus.DISCONNECTED && SpeedVpnService.currentGeneration() == 0L
            }
        }
        if (end == null) ack(id, "FAILED", "Timeout") else ack(id, "SUCCESS")
        heartbeatQuiet()
    }

    private suspend fun heartbeatQuiet() = runCatching { heartbeat() }

    private fun askUserForPermission() {
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(3,
            NotificationCompat.Builder(this, "alerts").setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SpeedVPN يحتاج إذن VPN").setContentText("افتح التطبيق واضغط \"منح إذن VPN\"")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build())
    }

    private fun persistLocalLimit(download: Boolean, kbps: Long?) {
        getSharedPreferences("local_limits", MODE_PRIVATE)
            .edit()
            .putLong(if (download) "dl" else "ul", kbps ?: 0L)
            .apply()
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (b > 0L && Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    private fun note(response: String? = null, error: String? = null) =
        VpnRuntime.update { it.copy(lastResponse = response ?: it.lastResponse, lastError = error ?: it.lastError) }
}

fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key)
