package com.speedvpn.app

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

const val TAG = "SpeedVPN"
fun log(msg: String) = Log.i(TAG, msg)
fun logE(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)

/** Values match the backend's vpn_status enum. */
enum class VpnStatus { UNKNOWN, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

data class Snapshot(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val permissionGranted: Boolean = false,
    val agentOnline: Boolean = false,
    val serviceRunning: Boolean = false,
    val tunnel: String = "down",
    val lastCommand: String? = null,
    val lastResponse: String? = null,
    val lastError: String? = null,
    val downloadLimitKbps: Long? = null,
    val uploadLimitKbps: Long? = null,
    val downloadBps: Long = 0,
    val uploadBps: Long = 0,
)

/** Single in-process source of truth for the real VPN state (read by UI + agent). */
object VpnRuntime {
    val state = MutableStateFlow(Snapshot())
    fun update(f: (Snapshot) -> Snapshot) = state.update(f)
}
