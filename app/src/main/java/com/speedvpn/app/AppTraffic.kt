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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-app traffic view for the current VPN session and daily statistics.
 *
 * Android's NetworkStatsManager is a historical accounting API whose buckets
 * can lag behind live traffic by several minutes. AppTrafficManager therefore
 * combines NetworkStatsManager snapshots with real-time per-UID/per-package
 * bytes observed directly by Socks5Server during the current calendar day.
 */
internal data class UidTrafficCounters(
    val downloadBytes: Long,
    val uploadBytes: Long,
) {
    val totalBytes: Long
        get() = if (Long.MAX_VALUE - downloadBytes < uploadBytes) Long.MAX_VALUE
        else downloadBytes + uploadBytes
}

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

    private class LiveDirectionalAccumulator {
        val download = AtomicLong(0L)
        val upload = AtomicLong(0L)

        fun add(direction: QuotaTrafficDirection, bytes: Int) {
            if (bytes <= 0) return
            val delta = bytes.toLong()
            val target = if (direction == QuotaTrafficDirection.DOWNLOAD) download else upload
            target.updateAndGet { current ->
                if (Long.MAX_VALUE - current < delta) Long.MAX_VALUE else current + delta
            }
        }

        fun snapshot(): UidTrafficCounters = UidTrafficCounters(download.get(), upload.get())
    }

    @Volatile
    private var liveDayKey: String = ""
    private val liveByUid = ConcurrentHashMap<Int, LiveDirectionalAccumulator>()
    private val liveByPackage = ConcurrentHashMap<String, LiveDirectionalAccumulator>()

    private fun ensureCurrentDay(today: String = UsageDate.today()) {
        if (liveDayKey == today) return
        synchronized(this) {
            if (liveDayKey != today) {
                liveByUid.clear()
                liveByPackage.clear()
                liveDayKey = today
            }
        }
    }

    /**
     * Records bytes forwarded by the local SOCKS5 relay for immediate UI visibility
     * before Android's NetworkStatsManager flushes its periodic accounting buckets.
     */
    internal fun recordLiveRelayTraffic(
        uid: Int?,
        packages: Set<String>,
        direction: QuotaTrafficDirection,
        bytes: Int,
        today: String = UsageDate.today(),
    ) {
        if (bytes <= 0) return
        ensureCurrentDay(today)
        if (uid != null && uid > 0) {
            liveByUid.computeIfAbsent(uid) { LiveDirectionalAccumulator() }.add(direction, bytes)
        }
        packages.forEach { pkg ->
            if (pkg.isNotBlank()) {
                liveByPackage.computeIfAbsent(pkg) { LiveDirectionalAccumulator() }.add(direction, bytes)
            }
        }
    }

    internal fun liveRelayUsage(
        uid: Int,
        packageName: String? = null,
        today: String = UsageDate.today(),
    ): UidTrafficCounters {
        ensureCurrentDay(today)
        val pkgSnapshot = packageName?.let { liveByPackage[it]?.snapshot() }
        val uidSnapshot = if (uid > 0) liveByUid[uid]?.snapshot() else null
        return UidTrafficCounters(
            downloadBytes = maxOf(pkgSnapshot?.downloadBytes ?: 0L, uidSnapshot?.downloadBytes ?: 0L),
            uploadBytes = maxOf(pkgSnapshot?.uploadBytes ?: 0L, uidSnapshot?.uploadBytes ?: 0L),
        )
    }

    internal fun clearLiveRelayTrafficForTests() {
        synchronized(this) {
            liveByUid.clear()
            liveByPackage.clear()
            liveDayKey = ""
        }
    }

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
        val candidates = LinkedHashMap<String, ApplicationInfo>()

        // Keep launcher apps for the normal user-facing list.
        pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .forEach { app ->
                if (app.packageName != context.packageName && app.enabled) {
                    candidates[app.packageName] = app
                }
            }

        // Quota/firewall controls are UID/package based. Launcher-only discovery
        // silently omitted background/system packages that can still generate traffic.
        // Add installed packages that explicitly request INTERNET as well.
        runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrDefault(emptyList()).forEach { info ->
            val app = info.applicationInfo ?: return@forEach
            val hasInternet = info.requestedPermissions?.any {
                it == android.Manifest.permission.INTERNET
            } == true
            if (app.packageName != context.packageName && app.enabled && hasInternet) {
                candidates[app.packageName] = app
            }
        }

        // Always preserve explicitly blocked packages even if their manifest does
        // not advertise INTERNET, so a saved policy never disappears from the UI.
        VpnAppControl.read(context).blockedPackages.forEach { pkg ->
            if (pkg == context.packageName || candidates.containsKey(pkg)) return@forEach
            runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_ALL) }
                .onSuccess { app ->
                    if (app.enabled) candidates[pkg] = app
                }
        }

        return candidates.values
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }
    }

    /**
     * Reads one UID's usage directly from NetworkStatsManager for the requested
     * period. This bypasses the app's daily_usage snapshot when making a quota
     * decision, so enforcement is based on the freshest platform accounting
     * available at that moment.
     *
     * The accounting scope is Android UID traffic on mobile/Wi-Fi/Ethernet.
     * Android does not expose a packet-perfect per-UID counter for the TUN path.
     */
    fun queryUidUsage(
        context: Context,
        uid: Int,
        startTimeMs: Long,
        endTimeMs: Long = System.currentTimeMillis(),
    ): Long? = queryUidUsageBreakdown(context, uid, startTimeMs, endTimeMs)?.totalBytes

    /** One-shot historical baseline for a quota period; live accounting stays in Socks5Server. */
    internal fun queryUidUsageBreakdown(
        context: Context,
        uid: Int,
        startTimeMs: Long,
        endTimeMs: Long = System.currentTimeMillis(),
    ): UidTrafficCounters? {
        if (uid < 0 || endTimeMs <= startTimeMs || !hasUsageAccess(context)) return null
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        var download = 0L
        var upload = 0L
        var queried = false
        for (networkType in NETWORK_TYPES) {
            runCatching {
                manager.queryDetailsForUid(
                    networkType,
                    null,
                    startTimeMs.coerceAtLeast(0L),
                    endTimeMs,
                    uid,
                ).use { stream ->
                    queried = true
                    val bucket = NetworkStats.Bucket()
                    while (stream.hasNextBucket()) {
                        stream.getNextBucket(bucket)
                        download = safeAdd(download, bucket.rxBytes)
                        upload = safeAdd(upload, bucket.txBytes)
                    }
                }
            }.onFailure {
                log("UID quota query failed for uid=" + uid + " type=" + networkType + ": " + it.message)
            }
        }
        return if (queried) UidTrafficCounters(download, upload) else null
    }

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

        val today = UsageDate.today()
        return apps.map { app ->
            val counter = bytesByUid[app.uid]
            val live = liveRelayUsage(app.uid, app.packageName, today)
            AppTrafficUsage(
                packageName = app.packageName,
                label = app.loadLabel(pm).toString(),
                uid = app.uid,
                downloadBytes = maxOf(counter?.getOrNull(0) ?: 0L, live.downloadBytes),
                uploadBytes = maxOf(counter?.getOrNull(1) ?: 0L, live.uploadBytes),
            )
        }.sortedByDescending { it.totalBytes }
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}

data class VpnAppControlSettings(
    /** Legacy global preference retained for compatibility; it does not gate per-app blocks. */
    val firewallEnabled: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
)

object VpnAppControl {
    private val quotaBlockLock = Any()

    const val PREFS = "app_control"
    const val FIREWALL_ENABLED = "firewall_enabled"
    const val BLOCKED_PACKAGES = "blocked_packages"
    const val QUOTA_BLOCKED_PACKAGES = "quota_blocked_packages"

    fun read(context: Context): VpnAppControlSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manualBlocked = p.getStringSet(BLOCKED_PACKAGES, emptySet()).orEmpty()
        val quotaBlocked = p.getStringSet(QUOTA_BLOCKED_PACKAGES, emptySet()).orEmpty()
        return VpnAppControlSettings(
            firewallEnabled = p.getBoolean(FIREWALL_ENABLED, false),
            blockedPackages = (manualBlocked + quotaBlocked).toSet(),
        )
    }

    /** Persists only user-selected/manual blocks. Quota blocks remain independent. */
    fun save(context: Context, settings: VpnAppControlSettings): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val quotaBlocked = p.getStringSet(QUOTA_BLOCKED_PACKAGES, emptySet()).orEmpty()
        val manualBlocked = settings.blockedPackages - quotaBlocked
        return p.edit()
            .putBoolean(FIREWALL_ENABLED, settings.firewallEnabled)
            .putStringSet(BLOCKED_PACKAGES, manualBlocked.toSet())
            .commit()
    }

    fun setBlocked(context: Context, packageName: String, blocked: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manual = p.getStringSet(BLOCKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
        if (blocked) manual.add(packageName) else manual.remove(packageName)
        p.edit().putStringSet(BLOCKED_PACKAGES, manual).commit()
        runCatching {
            val app = context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_ALL)
            UsageRepository.setBlocked(context, packageName, app.loadLabel(context.packageManager).toString(), app.uid, blocked)
        }
    }

    fun quotaBlockedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(QUOTA_BLOCKED_PACKAGES, emptySet()).orEmpty().toSet()

    fun setQuotaBlocked(context: Context, packageName: String, blocked: Boolean) {
        synchronized(quotaBlockLock) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val set = p.getStringSet(QUOTA_BLOCKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
            if (blocked) set.add(packageName) else set.remove(packageName)
            p.edit().putStringSet(QUOTA_BLOCKED_PACKAGES, set).commit()
        }
    }

    /** Atomically merges newly exhausted packages and returns just the newly added blocks. */
    fun addQuotaBlockedPackages(context: Context, packages: Set<String>): Set<String> =
        synchronized(quotaBlockLock) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = p.getStringSet(QUOTA_BLOCKED_PACKAGES, emptySet()).orEmpty().toSet()
            val next = current + packages
            val added = next - current
            if (added.isNotEmpty()) {
                p.edit().putStringSet(QUOTA_BLOCKED_PACKAGES, next).commit()
            }
            added
        }

    fun replaceQuotaBlockedPackages(context: Context, packages: Set<String>) {
        synchronized(quotaBlockLock) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(QUOTA_BLOCKED_PACKAGES, packages.toSet())
                .commit()
        }
    }
}

fun formatDataBytes(bytes: Long): String = when {
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    bytes < 1_000_000_000L -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
}