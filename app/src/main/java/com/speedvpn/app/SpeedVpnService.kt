package com.speedvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import hev.htproxy.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

/**
 * Real android.net.VpnService. Local mode: all phone traffic enters the TUN interface,
 * is converted to sockets by the native tun2socks engine, passes the speed limiter, and
 * leaves through the phone's normal internet connection. No remote VPN server needed.
 */
class SpeedVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.speedvpn.START"
        const val ACTION_STOP = "com.speedvpn.STOP"
        private const val NOTIF_ID = 1
        private const val TUN_V4 = "198.18.0.1"
        private const val TUN_V6 = "fc00::1"

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, SpeedVpnService::class.java).setAction(ACTION_START))

        fun stop(ctx: Context) = ctx.startService(Intent(ctx, SpeedVpnService::class.java).setAction(ACTION_STOP))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var socks: Socks5Server? = null
    private var meter: Job? = null
    private var engineRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { disconnect() }
            else -> {
                goForeground()
                if (tun == null) scope.launch { connect() }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "vpn")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("SpeedVPN")
            .setContentText("التحكم بالسرعة يعمل")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
    }

    private fun fail(reason: String, t: Throwable? = null) {
        logE("Connection error: $reason", t)
        cleanup()
        VpnRuntime.update { it.copy(status = VpnStatus.ERROR, lastError = reason, tunnel = "down", serviceRunning = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun connect() {
        VpnRuntime.update { it.copy(status = VpnStatus.CONNECTING, serviceRunning = true, lastError = null, tunnel = "starting") }
        log("Starting service")
        if (prepare(this) != null) return fail("VPN permission denied")

        val relay = try {
            Socks5Server({ protect(it) }, { protect(it) }).also { it.start() }
        } catch (e: Exception) { return fail("VPN service failed to start", e) }
        socks = relay

        log("Creating interface")
        val pfd = try {
            Builder()
                .setSession("SpeedVPN")
                .setMtu(8500)
                .addAddress(TUN_V4, 32)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_V6, 128)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .establish()
        } catch (e: Exception) { return fail("Unable to establish tunnel", e) }
            ?: return fail("VPN permission denied")
        tun = pfd

        val conf = File(filesDir, "tunnel.yml")
        conf.writeText(
            """
            tunnel:
              mtu: 8500
              ipv4: $TUN_V4
              ipv6: '$TUN_V6'
            socks5:
              port: ${relay.port}
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 81920
            """.trimIndent(),
        )
        log("Starting tunnel engine")
        val started = try { TProxyService.TProxyStartService(conf.absolutePath, pfd.fd) } catch (e: Throwable) {
            return fail("Unable to establish tunnel", e)
        }
        if (!started) return fail("Unable to establish tunnel")
        engineRunning = true
        VpnRuntime.update { it.copy(tunnel = "verifying") }

        // Real check: this app is NOT excluded from the VPN, so this request goes
        // phone -> TUN -> tun2socks -> limiter -> internet. Establish() alone proves nothing.
        log("Verifying internet through tunnel")
        var ok = false
        for (i in 1..4) {
            delay(1000)
            if (!vpnNetworkUp()) continue
            ok = runCatching {
                http.newCall(Request.Builder().url("https://connectivitycheck.gstatic.com/generate_204").build())
                    .execute().use { it.code == 204 }
            }.getOrDefault(false)
            val rx = runCatching { TProxyService.TProxyGetStats()[3] }.getOrDefault(0L)
            if (ok && rx > 0) break
            ok = false
        }
        if (!ok) return fail("Timeout")

        log("Tunnel established — Connected")
        VpnRuntime.update { it.copy(status = VpnStatus.CONNECTED, tunnel = "up", lastError = null) }
        startMeter()
    }

    private fun vpnNetworkUp(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    private fun startMeter() {
        meter = scope.launch {
            var lastDown = SpeedLimiter.download.total.get()
            var lastUp = SpeedLimiter.upload.total.get()
            while (isActive) {
                delay(1000)
                val d = SpeedLimiter.download.total.get(); val u = SpeedLimiter.upload.total.get()
                VpnRuntime.update { it.copy(downloadBps = (d - lastDown) * 8, uploadBps = (u - lastUp) * 8) }
                lastDown = d; lastUp = u
            }
        }
    }

    private fun cleanup() {
        meter?.cancel(); meter = null
        if (engineRunning) { runCatching { TProxyService.TProxyStopService() }; engineRunning = false }
        runCatching { tun?.close() }; tun = null
        socks?.stop(); socks = null
    }

    private suspend fun disconnect() {
        log("Disconnect requested")
        VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTING) }
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        for (i in 1..10) { if (!vpnNetworkUp()) break; delay(300) }
        log("Disconnected")
        VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTED, tunnel = "down", serviceRunning = false, downloadBps = 0, uploadBps = 0) }
        stopSelf()
    }

    /** User switched the VPN off from Android settings, or another VPN took over. */
    override fun onRevoke() {
        log("VPN revoked by system/user")
        scope.launch { disconnect() }
    }

    override fun onDestroy() {
        cleanup()
        if (VpnRuntime.state.value.status == VpnStatus.CONNECTED)
            VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTED, tunnel = "down", serviceRunning = false) }
        scope.cancel()
        super.onDestroy()
    }
}
