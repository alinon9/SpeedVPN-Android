package com.speedvpn.app

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

internal object SmartSettings {
    private const val PREFS = "smart_settings"
    private const val STATS_ENABLED = "stats_enabled"
    private const val OVERLAY_ENABLED = "overlay_enabled"

    fun isStatsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(STATS_ENABLED, false)

    fun setStatsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(STATS_ENABLED, enabled).apply()
        if (enabled) {
            UsageCollectionScheduler.schedule(context)
            QuotaWorkScheduler.schedule(context)
        } else {
            UsageCollectionScheduler.cancel(context)
            QuotaWorkScheduler.cancel(context)
        }
    }

    fun isOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OVERLAY_ENABLED, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(OVERLAY_ENABLED, enabled).apply()
    }
}

internal object UsageCollector {
    private val networkTypes = intArrayOf(
        ConnectivityManager.TYPE_MOBILE,
        ConnectivityManager.TYPE_WIFI,
        ConnectivityManager.TYPE_ETHERNET,
    )

    fun collectToday(context: Context) {
        if (!SmartSettings.isStatsEnabled(context)) return
        val now = System.currentTimeMillis()
        collectDay(context, UsageDate.today(), startOfDayMillis(0), now)
        collectDay(context, UsageDateOffset.value(-1), startOfDayMillis(-1), startOfDayMillis(0))
        enforceDailyLimits(context)
        maybeAnalyze(context)
    }

    private fun collectDay(context: Context, date: String, start: Long, end: Long) {
        if (end <= start) return
        val apps = AppTrafficManager.installedLaunchableApps(context)
        if (apps.isEmpty() || !AppTrafficManager.hasUsageAccess(context)) return
        val byUid = HashMap<Int, LongArray>()
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return
        for (networkType in networkTypes) {
            runCatching {
                val stats = manager.queryDetails(networkType, null, start, end)
                stats.use { stream ->
                    val bucket = NetworkStats.Bucket()
                    while (stream.hasNextBucket()) {
                        stream.getNextBucket(bucket)
                        if (bucket.uid < 0) continue
                        val totals = byUid.getOrPut(bucket.uid) { LongArray(2) }
                        totals[0] = safeAdd(totals[0], bucket.rxBytes)
                        totals[1] = safeAdd(totals[1], bucket.txBytes)
                    }
                }
            }.onFailure { log("Daily usage query failed: ${it.message}") }
        }
        apps.forEach { app ->
            val values = byUid[app.uid] ?: LongArray(2)
            val label = app.loadLabel(context.packageManager).toString()
            UsageRepository.upsertDailyUsage(
                context,
                DailyUsageRow(date, app.packageName, label, app.uid, values[0], values[1]),
            )
            UsageRepository.ensureApp(context, app.packageName, label, app.uid)
        }
    }

    private fun maybeAnalyze(context: Context) {
        val prefs = context.getSharedPreferences("smart_settings", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_ai_analysis", 0L)
        if (now - last < 6L * 60L * 60L * 1000L) return
        prefs.edit().putLong("last_ai_analysis", now).apply()
        AiDiagnosticsEngine.analyze(context)
    }

    fun enforceDailyLimits(context: Context) {
        QuotaEnforcementEngine.enforce(context)
    }

    private fun Long?.isNullOrBlankLimit(): Boolean = this != null && this > 0L

    private fun startOfDayMillis(offsetDays: Int): Long {
        val now = Calendar.getInstance()
        now.add(Calendar.DAY_OF_YEAR, offsetDays)
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        return now.timeInMillis
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}

internal object UsageCollectionScheduler {
    private const val REQUEST_CODE = 7302

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val intent = android.content.Intent(context, UsageCollectionReceiver::class.java)
        val pending = android.app.PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pending)
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        alarmManager.setInexactRepeating(
            android.app.AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            android.app.AlarmManager.INTERVAL_DAY,
            pending,
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pending = android.app.PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            android.content.Intent(context, UsageCollectionReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pending)
    }
}

internal object AiDiagnosticsEngine {
    fun analyze(context: Context) {
        val recent = DiagnosticsRepository.recent(context, 500)
        if (recent.isEmpty()) return
        val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val problems = DiagnosticsRepository.topProblems(context, since, 6)
        problems.forEach { (label, count) ->
            val confidence = min(0.98, 0.55 + count / 100.0)
            AiRepository.recordInsight(
                context,
                AiInsight(
                    id = 0L,
                    createdAt = System.currentTimeMillis(),
                    category = "diagnostics",
                    title = "نمط خطأ متكرر: $label",
                    detail = "تم تسجيل $count حدثًا خلال آخر 24 ساعة. راجع هذا المكوّن في الإصدار القادم قبل زيادة التغييرات الأخرى.",
                    confidence = confidence,
                    source = "local-ai-heuristic-v1",
                ),
            )
        }

        val networkSamples = AiRepository.recentNetworkSamples(context, 30)
        val degraded = networkSamples.count { it.packetLossPct >= 3.0 || it.jitterMs >= 100 }
        if (networkSamples.size >= 5 && degraded >= 3) {
            AiRepository.recordInsight(
                context,
                AiInsight(
                    id = 0L,
                    createdAt = System.currentTimeMillis(),
                    category = "network",
                    title = "تم اكتشاف تدهور متكرر في الشبكة",
                    detail = "عدة قياسات حديثة تحتوي فقد حزم أو تذبذبًا مرتفعًا. لا تغيّر MTU/DNS تلقائيًا قبل مقارنة الإعدادات المسجلة.",
                    confidence = min(0.95, 0.6 + degraded / networkSamples.size.toDouble() * 0.3),
                    source = "local-ai-heuristic-v1",
                ),
            )
        }
    }

    fun smartGlobalRecommendation(context: Context): SpeedProfile? {
        val global = AiRepository.getSpeedProfile(context, null)
        val samples = AiRepository.recentNetworkSamples(context, 12)
        if (samples.size < 3) return global
        val avgDown = samples.map { it.downloadBps }.filter { it > 0 }.average()
        val avgUp = samples.map { it.uploadBps }.filter { it > 0 }.average()
        if (!avgDown.isFinite() || avgDown <= 0) return global
        val downKbps = (avgDown / 1000.0 * 0.8).toLong().coerceIn(64, 100_000_000)
        val upKbps = if (avgUp.isFinite() && avgUp > 0) (avgUp / 1000.0 * 0.8).toLong().coerceIn(32, 100_000_000) else null
        return SpeedProfile(null, true, downKbps, upKbps, System.currentTimeMillis())
    }
}

internal class SmartSampler(private val context: Context) {
    private var lastDown = 0L
    private var lastUp = 0L
    private var lastAt = 0L

    fun sample() {
        if (!SmartSettings.isStatsEnabled(context)) return
        val now = System.currentTimeMillis()
        if (lastAt > 0L && now - lastAt < 60_000L) return
        val s = VpnRuntime.state.value
        val dt = now - lastAt
        val downBps = if (dt > 0 && lastAt > 0) ((s.sessionDownloadBytes - lastDown).coerceAtLeast(0L) * 1000L / dt) else s.downloadBps
        val upBps = if (dt > 0 && lastAt > 0) ((s.sessionUploadBytes - lastUp).coerceAtLeast(0L) * 1000L / dt) else s.uploadBps
        lastDown = s.sessionDownloadBytes
        lastUp = s.sessionUploadBytes
        lastAt = now
        val compatibility = VpnSettings.read(context)
        AiRepository.recordNetworkSample(
            context,
            AiNetworkSample(
                timestamp = now,
                networkType = NetworkContext.currentType(context),
                downloadBps = max(0L, downBps),
                uploadBps = max(0L, upBps),
                pingMs = 0L,
                jitterMs = 0L,
                packetLossPct = 0.0,
                mtu = compatibility.mtu,
                ipv6Enabled = compatibility.ipv6Enabled,
                dnsIpv4Only = compatibility.dnsIpv4Only,
            ),
        )
        val global = AiRepository.getSpeedProfile(context, null)
        AiRepository.recordSpeedExperiment(
            context,
            packageName = null,
            downloadKbps = global?.downloadKbps ?: s.downloadLimitKbps,
            uploadKbps = global?.uploadKbps ?: s.uploadLimitKbps,
            observedDownloadBps = downBps,
            observedUploadBps = upBps,
            pingMs = 0L,
            jitterMs = 0L,
            packetLossPct = 0.0,
            resultScore = qualityScore(downBps, upBps),
            notes = "session sample",
        )
    }

    private fun qualityScore(down: Long, up: Long): Double {
        val downScore = min(1.0, down / 5_000_000.0)
        val upScore = min(1.0, up / 1_000_000.0)
        return (downScore * 0.7 + upScore * 0.3).coerceIn(0.0, 1.0)
    }
}

internal class AppSpeedProfiles(private val context: Context) {
    fun save(packageName: String, enabled: Boolean, downloadKbps: Long?, uploadKbps: Long?) {
        AiRepository.upsertSpeedProfile(
            context,
            SpeedProfile(packageName, enabled, downloadKbps, uploadKbps, System.currentTimeMillis()),
        )
        DiagnosticsRepository.record(context, "INFO", "SpeedProfile", "APP_SPEED_PROFILE_CHANGED", "Profile changed for $packageName")
    }

    fun get(packageName: String): SpeedProfile? = AiRepository.getSpeedProfile(context, packageName)
}