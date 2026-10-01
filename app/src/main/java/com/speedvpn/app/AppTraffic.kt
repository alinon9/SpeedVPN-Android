package com.speedvpn.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.Locale

/**
 * Per-app traffic view for the current VPN session.
 *
 * Android's NetworkStatsManager is a historical accounting API. It is useful for
 * session consumption totals, but the platform can update its buckets with delay,
 * so this screen intentionally labels the values as session usage rather than a
 * packet-perfect live counter.
 */
data class AppTrafficUsage(
    val packageName: String,
    val label: String,
    val uid: Int,
    val downloadBytes: Long,
    val uploadBytes: Long,
) {
    val totalBytes: Long
        get() = safeAdd(downloadBytes, uploadBytes)

    companion object {
        private fun safeAdd(a: Long, b: Long): Long =
            if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}

object AppTrafficManager {
    private val NETWORK_TYPES = intArrayOf(
        android.net.ConnectivityManager.TYPE_MOBILE,
        android.net.ConnectivityManager.TYPE_WIFI,
        android.net.ConnectivityManager.TYPE_ETHERNET,
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun vpnSettingsIntent(): Intent = Intent(Settings.ACTION_VPN_SETTINGS)

    fun installedLaunchableApps(context: Context): List<ApplicationInfo> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .toMutableList()

        // Keep already-blocked packages visible even if their launcher activity is
        // temporarily hidden. Package visibility is still controlled by Android; we
        // only attempt a direct lookup for packages the user explicitly saved.
        val blocked = VpnAppControl.read(context).blockedPackages
        blocked.forEach { pkg ->
            if (pkg == context.packageName || launchable.any { it.packageName == pkg }) return@forEach
            runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_ALL) }
                .onSuccess { launchable += it }
        }

        return launchable
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }
    }

    /**
     * Returns Android-recorded traffic deltas attributed to application UIDs during
     * [startTimeMs]..now. This is session accounting, not packet-perfect TUN accounting.
     * When usage access is unavailable the returned rows remain present with zero
     * counters so the UI can still provide an app firewall list.
     */
    fun querySessionUsage(context: Context, startTimeMs: Long, endTimeMs: Long = System.currentTimeMillis()): List<AppTrafficUsage> {
        val pm = context.packageManager
        val apps = installedLaunchableApps(context)
        if (apps.isEmpty()) return emptyList()

        val bytesByUid = HashMap<Int, LongArray>()
        if (hasUsageAccess(context) && endTimeMs > startTimeMs) {
            val manager = context.getSystemService(NetworkStatsManager::class.java)
            if (manager != null) {
                for (networkType in NETWORK_TYPES) {
                    runCatching {
                        val stats = manager.queryDetails(
                            networkType,
                            null,
                            startTimeMs.coerceAtLeast(0L),
                            endTimeMs,
                        )
                        stats.use { stream ->
                            val bucket = NetworkStats.Bucket()
                            while (stream.hasNextBucket()) {
                                stream.getNextBucket(bucket)
                                val uid = bucket.uid
                                if (uid < 0) continue
                                val counter = bytesByUid.getOrPut(uid) { LongArray(2) }
                                counter[0] = safeAdd(counter[0], bucket.rxBytes)
                                counter[1] = safeAdd(counter[1], bucket.txBytes)
                            }
                        }
                    }.onFailure { log("App traffic query failed for type=$networkType: ${it.message}") }
                }
            }
        }

        return apps.map { app ->
            val counter = bytesByUid[app.uid]
            AppTrafficUsage(
                packageName = app.packageName,
                label = app.loadLabel(pm).toString(),
                uid = app.uid,
                downloadBytes = counter?.getOrNull(0) ?: 0L,
                uploadBytes = counter?.getOrNull(1) ?: 0L,
            )
        }.sortedByDescending { it.totalBytes }
    }


    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}

data class VpnAppControlSettings(
    val firewallEnabled: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
)

object VpnAppControl {
    const val PREFS = "app_control"
    const val FIREWALL_ENABLED = "firewall_enabled"
    const val BLOCKED_PACKAGES = "blocked_packages"

    fun read(context: Context): VpnAppControlSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VpnAppControlSettings(
            firewallEnabled = p.getBoolean(FIREWALL_ENABLED, false),
            blockedPackages = p.getStringSet(BLOCKED_PACKAGES, emptySet())?.toSet().orEmpty(),
        )
    }

    /**
     * Persists firewall settings synchronously. Callers that run on the UI thread
     * must invoke this from Dispatchers.IO because commit() performs disk I/O.
     */
    fun save(context: Context, settings: VpnAppControlSettings): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(FIREWALL_ENABLED, settings.firewallEnabled)
            .putStringSet(BLOCKED_PACKAGES, settings.blockedPackages.toSet())
            .commit()
    }

    fun setBlocked(context: Context, packageName: String, blocked: Boolean) {
        val current = read(context).blockedPackages.toMutableSet()
        if (blocked) current.add(packageName) else current.remove(packageName)
        save(context, read(context).copy(blockedPackages = current))
    }
}

fun formatDataBytes(bytes: Long): String = when {
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    bytes < 1_000_000_000L -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
}
