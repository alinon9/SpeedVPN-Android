package com.speedvpn.app

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

const val TAG = "SpeedVPN"
fun log(msg: String) = Log.i(TAG, msg)
fun logE(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)

/** Values match the backend's vpn_status enum. */
enum class VpnStatus { UNKNOWN, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

enum class VpnHealth {
    DISCONNECTED,
    CONTROL_READY,
    UPSTREAM_READY,
    DEGRADED,
}

data class Snapshot(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val permissionGranted: Boolean = false,
    val agentOnline: Boolean = false,
    val serviceRunning: Boolean = false,
    val tunnel: String = "down",
    val health: VpnHealth = VpnHealth.DISCONNECTED,
    val lastCommand: String? = null,
    val lastResponse: String? = null,
    val lastError: String? = null,
    val downloadLimitKbps: Long? = null,
    val uploadLimitKbps: Long? = null,
    val downloadBps: Long = 0,
    val uploadBps: Long = 0,
    val sessionDownloadBytes: Long = 0,
    val sessionUploadBytes: Long = 0,
    val connectedAtMillis: Long = 0,
)

/** Single in-process source of truth for the real VPN state (read by UI + agent). */
object VpnRuntime {
    val state = MutableStateFlow(Snapshot())
    fun update(f: (Snapshot) -> Snapshot) = state.update(f)
}

data class VpnCompatibilitySettings(
    val ipv6Enabled: Boolean = true,
    val dnsIpv4Only: Boolean = false,
    // 1280 is a conservative mobile-compatible default; 1400/1500 remain available
    // as explicit user choices for networks that support larger packets.
    val mtu: Int = 1280,
)

object VpnSettings {
    const val PREFS = "vpn_settings"
    const val IPV6_ENABLED = "ipv6_enabled"
    const val DNS_IPV4_ONLY = "dns_ipv4_only"
    const val MTU = "mtu"

    fun read(context: android.content.Context): VpnCompatibilitySettings {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val mtu = p.getInt(MTU, 1280).coerceIn(1280, 1500)
        return VpnCompatibilitySettings(
            ipv6Enabled = p.getBoolean(IPV6_ENABLED, true),
            dnsIpv4Only = p.getBoolean(DNS_IPV4_ONLY, false),
            mtu = mtu,
        )
    }
}
