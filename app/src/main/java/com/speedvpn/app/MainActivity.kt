package com.speedvpn.app

import android.Manifest
import android.content.Context
import android.graphics.Paint
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import java.util.Locale
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sin

private val Bg = Color(0xFF050914)
private val Surface = Color(0xFF0B1221)
private val Surface2 = Color(0xFF101B2F)
private val StrokeColor = Color(0xFF1E3150)
private val Blue = Color(0xFF35B8FF)
private val Blue2 = Color(0xFF4C68FF)
private val Purple = Color(0xFFB063FF)
private val Cyan = Color(0xFF42F0E4)
private val Green = Color(0xFF43E3B2)
private val Amber = Color(0xFFFFC857)
private val TextPrimary = Color(0xFFF3F7FF)
private val TextSecondary = Color(0xFF95A6C3)

class MainActivity : ComponentActivity() {
    // UI speeds are expressed as KB/s and converted to the backend's Kbps representation.
    private fun kbpsFromKBps(value: Long): Long = value.coerceAtLeast(1L) * 8L
    private val slowPresets = listOf(
        "10K" to kbpsFromKBps(10),
        "25K" to kbpsFromKBps(25),
        "50K" to kbpsFromKBps(50),
        "100K" to kbpsFromKBps(100),
        "128K" to kbpsFromKBps(128),
        "256K" to kbpsFromKBps(256),
        "512K" to kbpsFromKBps(512),
        "768K" to kbpsFromKBps(768),
    )
    private val mediumPresets = listOf(
        "1M" to kbpsFromKBps(1_000),
        "2M" to kbpsFromKBps(2_000),
        "4M" to kbpsFromKBps(4_000),
        "6M" to kbpsFromKBps(6_000),
    )
    private val fastPresets = listOf(
        "10M" to kbpsFromKBps(10_000),
        "20M" to kbpsFromKBps(20_000),
    )
    private val unlimitedPreset = "بدون حد"
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = VpnService.prepare(this) == null
        log(if (granted) "VPN permission granted" else "VPN permission denied")
        VpnRuntime.update { it.copy(permissionGranted = granted, lastError = if (granted) it.lastError else "VPN permission denied") }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val usageAccessState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        loadLocalLimits()
        setContent {
            SpeedVpnTheme {
                var signedIn by remember { mutableStateOf<Boolean?>(null) }
                var showLogin by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val signedInNow = withContext(Dispatchers.IO) { Auth.isSignedIn(this@MainActivity) }
                    signedIn = signedInNow
                    if (signedInNow) AgentService.start(this@MainActivity)
                }

                when (signedIn) {
                    null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    false -> {
                        if (showLogin) {
                            LoginScreen(
                                onDone = { signedIn = true; showLogin = false },
                                onCancel = { showLogin = false },
                            )
                        } else {
                            SpeedVpnApp(
                                signedIn = false,
                                onLink = { showLogin = true },
                                onSignOut = { signedIn = false },
                            )
                        }
                    }
                    true -> SpeedVpnApp(
                        signedIn = true,
                        onLink = { showLogin = false },
                        onSignOut = { signedIn = false },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        usageAccessState.value = AppTrafficManager.hasUsageAccess(this)
        VpnRuntime.update { it.copy(permissionGranted = VpnService.prepare(this) == null) }
    }

    private fun loadLocalLimits() = SpeedLimitStore.applyToLimiter(this)

    private val settingsWriteMutex = Mutex()
    private val appControlWriteGeneration = AtomicLong(0L)

    private fun saveAppControlSettings(settings: VpnAppControlSettings) {
        val generation = appControlWriteGeneration.incrementAndGet()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                settingsWriteMutex.withLock {
                    if (generation != appControlWriteGeneration.get()) return@withLock true
                    VpnAppControl.save(this@MainActivity, settings)
                }
            }
            if (!saved) {
                log("Failed to persist app firewall settings")
                return@launch
            }
            if (generation == appControlWriteGeneration.get() &&
                VpnRuntime.state.value.status == VpnStatus.CONNECTED
            ) {
                SpeedVpnService.restartForSettings(this@MainActivity)
            }
        }
    }

    private fun saveLocalLimit(download: Boolean, kbps: Long?) {
        // SharedPreferences.commit() is synchronous. Keep disk I/O off the main
        // thread while serializing writes so rapid slider changes cannot reorder
        // the persisted value.
        lifecycleScope.launch(Dispatchers.IO) {
            settingsWriteMutex.withLock {
                if (download) {
                    SpeedLimitStore.saveDownload(this@MainActivity, kbps)
                } else {
                    SpeedLimitStore.saveUpload(this@MainActivity, kbps)
                }
            }
        }
        if (download) {
            SpeedLimiter.setDownloadKbps(kbps)
        } else {
            SpeedLimiter.setUploadKbps(kbps)
        }
    }

    private fun fmt(kbps: Long?) = when {
        kbps == null -> "بدون حد"
        else -> {
            val bytesPerSec = kbps * 125.0
            when {
                bytesPerSec < 1_000.0 -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
                bytesPerSec < 1_000_000.0 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1_000.0)
                else -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / 1_000_000.0)
            }
        }
    }

    private fun fmtRateBits(bitsPerSecond: Long): String {
        val bytesPerSecond = bitsPerSecond.coerceAtLeast(0L) / 8.0
        return when {
            bytesPerSecond < 1_000.0 -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
            bytesPerSecond < 1_000_000.0 -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1_000.0)
            else -> String.format(Locale.US, "%.2f MB/s", bytesPerSecond / 1_000_000.0)
        }
    }

    @Composable
    private fun SpeedVpnApp(signedIn: Boolean, onLink: () -> Unit, onSignOut: () -> Unit) {
        val s by VpnRuntime.state.collectAsStateWithLifecycle()
        var tab by remember { mutableStateOf(0) }
        Scaffold(
            containerColor = Bg,
            bottomBar = { BottomBar(tab, onTab = { tab = it }) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> HomeScreen(signedIn, s) { requestVpnPermission() }
                    1 -> SpeedScreen(s)
                    2 -> AppsScreen(s)
                    3 -> SmartScreen(s)
                    else -> SettingsScreen(s, signedIn, onLink, onSignOut)
                }
            }
        }
    }

    @Composable
    private fun HomeScreen(signedIn: Boolean, s: Snapshot, requestPermission: () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TopBrandBar(signedIn)
            Spacer(Modifier.height(2.dp))
            StatusHero(s, requestPermission)
            LiveTraffic(s)
            SessionTrafficCard(s)
            QuickSpeedCard(s)
            CompactNetworkCard()
            Text(
                "اتصال محلي • تحكم كامل بالسرعة • بدون خادم VPN خارجي",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    @Composable
    private fun SpeedScreen(s: Snapshot) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle("التحكم بالسرعة", "اختر مستوى جاهزًا أو اضبطه بالمؤشر — الوحدات KB/s وMB/s")
            LimitSlider("سرعة التحميل", s.downloadLimitKbps) { saveLocalLimit(true, it) }
            LimitSlider("سرعة الرفع", s.uploadLimitKbps) { saveLocalLimit(false, it) }
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                        runCatching { startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                    } else {
                        SpeedOverlayService.start(this@MainActivity)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("فتح نافذة السرعة العامة العائمة") }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💡", fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ملاحظة", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "السرعة تحدد سقف الترافيك العام للتطبيقات التي تمر عبر الـVPN.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SmartScreen(s: Snapshot) {
        var statsEnabled by remember { mutableStateOf(SmartSettings.isStatsEnabled(this@MainActivity)) }
        var today by remember { mutableStateOf(0L) }
        var week by remember { mutableStateOf(0L) }
        var month by remember { mutableStateOf(0L) }
        var top by remember { mutableStateOf<List<DailyUsageRow>>(emptyList()) }
        var insights by remember { mutableStateOf<List<AiInsight>>(emptyList()) }
        var errors24h by remember { mutableStateOf(0L) }
        var problems by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
        var recommendation by remember { mutableStateOf<SpeedProfile?>(null) }
        var refreshing by remember { mutableStateOf(false) }

        suspend fun refresh() {
            refreshing = true
            withContext(Dispatchers.IO) {
                if (statsEnabled && usageAccessState.value) UsageCollector.collectToday(this@MainActivity)
                today = UsageRepository.totalUsage(this@MainActivity, UsageDate.today())
                week = UsageRepository.rangeUsage(this@MainActivity, UsageDate.startOfWeek(), UsageDate.today())
                month = UsageRepository.rangeUsage(this@MainActivity, UsageDate.startOfMonth(), UsageDate.today())
                top = UsageRepository.topUsage(this@MainActivity, UsageDate.today(), 8)
                insights = AiRepository.recentInsights(this@MainActivity, 6)
                val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                errors24h = DiagnosticsRepository.countSince(this@MainActivity, since, "ERROR")
                problems = DiagnosticsRepository.topProblems(this@MainActivity, since, 6)
                recommendation = AiDiagnosticsEngine.smartGlobalRecommendation(this@MainActivity)
            }
            refreshing = false
        }

        LaunchedEffect(statsEnabled, usageAccessState.value) { refresh() }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("المركز الذكي", "Usage + AI + Diagnostics في طبقات منفصلة")

            GlassCard {
                SectionLabel("استهلاك الإنترنت", "إحصاءات يومية وأسبوعية وشهرية")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrafficCard("↓", "اليوم", formatDataBytes(today), Blue, Modifier.weight(1f))
                    TrafficCard("↗", "الأسبوع", formatDataBytes(week), Cyan, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrafficCard("◷", "الشهر", formatDataBytes(month), Purple, Modifier.weight(1f))
                    TrafficCard("⚠", "أخطاء 24س", errors24h.toString(), Color(0xFFFF7D88), Modifier.weight(1f))
                }
                Button(enabled = !refreshing, onClick = { lifecycleScope.launch { refresh() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (refreshing) "جاري التحديث…" else "تحديث البيانات")
                }
            }

            GlassCard {
                SectionLabel("Smart Speed Optimizer", "اقتراح محلي مبني على القياسات السابقة")
                val rec = recommendation
                if (rec == null) {
                    Text("تحتاج عدة قياسات قبل إنشاء اقتراح.", color = TextSecondary, fontSize = 10.sp)
                } else {
                    InfoRow("تحميل", rec.downloadKbps?.let { fmt(it) } ?: "بدون حد")
                    InfoRow("رفع", rec.uploadKbps?.let { fmt(it) } ?: "بدون حد")
                    Button(onClick = {
                        saveLocalLimit(true, rec.downloadKbps)
                        saveLocalLimit(false, rec.uploadKbps)
                        DiagnosticsRepository.record(this@MainActivity, "INFO", "SmartOptimizer", "SMART_SPEED_APPLIED", "Smart speed recommendation applied")
                    }, modifier = Modifier.fillMaxWidth()) { Text("تطبيق الاقتراح") }
                }
            }

            GlassCard {
                SectionLabel("AI Diagnostics", "تحليل محلي لأنماط الأخطاء والشبكة")
                if (problems.isEmpty()) Text("لا توجد أنماط أخطاء ملحوظة خلال آخر 24 ساعة.", color = Green, fontSize = 11.sp)
                else problems.forEach { (label, count) -> InfoRow(label, "$count حدث") }
                insights.take(4).forEach { insight ->
                    Text(insight.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(insight.detail, color = TextSecondary, fontSize = 10.sp)
                    Text("الثقة: ${(insight.confidence * 100).roundToLong()}% • ${insight.source}", color = Blue, fontSize = 9.sp)
                }
            }

            GlassCard {
                SectionLabel("أكثر التطبيقات استخدامًا اليوم", "من Usage Database")
                if (top.isEmpty()) Text("لا توجد بيانات بعد.", color = TextSecondary, fontSize = 11.sp)
                else top.forEach { row -> InfoRow(row.label, formatDataBytes(row.totalBytes)) }
            }

            GlassCard {
                SectionLabel("حالة جمع البيانات", "يمكن إيقافها في أي وقت")
                SettingSwitch("إحصاءات الإنترنت", "حفظ استهلاك التطبيقات محليًا", statsEnabled) {
                    statsEnabled = it
                    SmartSettings.setStatsEnabled(this@MainActivity, it)
                    if (it) UsageCollectionScheduler.schedule(this@MainActivity) else UsageCollectionScheduler.cancel(this@MainActivity)
                    lifecycleScope.launch(Dispatchers.IO) { if (it) UsageCollector.collectToday(this@MainActivity) }
                }
            }
        }
    }

    @Composable
    private fun SmartControlsCard() {
        var statsEnabled by remember { mutableStateOf(SmartSettings.isStatsEnabled(this@MainActivity)) }
        var overlayEnabled by remember { mutableStateOf(SmartSettings.isOverlayEnabled(this@MainActivity)) }
        GlassCard {
            SectionLabel("البيانات والنافذة العائمة", "إعدادات مستقلة للإحصاءات والتحكم أثناء التصفح")
            SettingSwitch("إحصاءات الإنترنت", "تسجيل استهلاك التطبيقات يوميًا", statsEnabled) {
                statsEnabled = it
                SmartSettings.setStatsEnabled(this@MainActivity, it)
                if (it) {
                    UsageCollectionScheduler.schedule(this@MainActivity)
                    lifecycleScope.launch(Dispatchers.IO) { UsageCollector.collectToday(this@MainActivity) }
                } else UsageCollectionScheduler.cancel(this@MainActivity)
            }
            SettingSwitch("نافذة السرعة العائمة", "تعمل كتحكم عام أو حسب التطبيق الذي فتحت منها", overlayEnabled) {
                if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    runCatching { startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                    return@SettingSwitch
                }
                overlayEnabled = it
                SmartSettings.setOverlayEnabled(this@MainActivity, it)
                if (it) SpeedOverlayService.start(this@MainActivity) else SpeedOverlayService.stop(this@MainActivity)
            }
        }
    }

    @Composable
    private fun SettingsScreen(s: Snapshot, signedIn: Boolean, onLink: () -> Unit, onSignOut: () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("الإعدادات", "التوافق، البيانات، النافذة العائمة والحساب")
            CompatibilitySettingsCard()
            SmartControlsCard()
            AccountCard(signedIn, onLink, onSignOut)
            DiagnosticsCard(s)
        }
    }

    @Composable
    private fun TopBrandBar(signedIn: Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                BrandWordmark()
                Text(
                    if (signedIn) "مربوط بحسابك" else "الوضع المحلي",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            Surface(
                color = Color(0xFF0D1730),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, StrokeColor),
            ) {
                Text("v${BuildConfig.VERSION_NAME}", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
    }

    @Composable
    private fun ScreenTitle(title: String, subtitle: String) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
    }

    @Composable
    private fun BrandWordmark() {
        Text(
            AnnotatedString(
                text = "SpeedVPN",
                spanStyles = listOf(
                    AnnotatedString.Range(SpanStyle(color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic), 0, 5),
                    AnnotatedString.Range(SpanStyle(color = Blue, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic), 5, 8),
                ),
            ),
            fontSize = 22.sp,
            letterSpacing = (-0.8).sp,
        )
    }

    @Composable
    private fun BrandMark(size: androidx.compose.ui.unit.Dp) {
        Box(
            Modifier.size(size).shadow(12.dp, CircleShape).clip(RoundedCornerShape(size * 0.28f)).background(
                Brush.linearGradient(listOf(Color(0xFF071A3D), Color(0xFF132A74), Color(0xFF4A1D83))),
            ).border(1.dp, Color(0xFF3D8CFF), RoundedCornerShape(size * 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("S", color = TextPrimary, fontSize = (size.value * 0.56f).sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        }
    }

    @Composable
    private fun StatusHero(s: Snapshot, requestPermission: () -> Unit) {
        val connected = s.status == VpnStatus.CONNECTED
        val connecting = s.status == VpnStatus.CONNECTING
        val disconnecting = s.status == VpnStatus.DISCONNECTING
        val progress by animateFloatAsState(if (connected) 1f else if (connecting) 0.68f else 0f, tween(700, easing = FastOutSlowInEasing), label = "hero")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(235.dp)) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension * 0.36f
                    drawCircle(Color(0xFF0A1530), radius, center)
                    drawCircle(Color(0xFF142846), radius, center, style = Stroke(width = 3.dp.toPx()))
                    if (progress > 0f) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(Cyan, Blue, Purple, Cyan)),
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    drawCircle(Blue.copy(alpha = if (connected) 0.14f else 0.05f), radius * 0.78f, center)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandMark(54.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            connected -> "متصل الآن"
                            connecting -> "جاري الاتصال"
                            else -> "غير متصل"
                        },
                        color = if (connected) Green else TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        when {
                            connecting -> "نجهز الاتصال الآمن…"
                            !connected -> "جاهز للتحكم في اتصالك"
                            s.health == VpnHealth.UPSTREAM_READY -> "VPN المحلي نشط — الشبكة الفيزيائية جاهزة"
                            s.health == VpnHealth.DEGRADED -> "VPN المحلي نشط — توجد مشكلة مؤقتة في الشبكة الخارجية"
                            else -> "VPN المحلي نشط — التحقق من الشبكة الخارجية مستمر"
                        },
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = !connecting && !disconnecting,
                        onClick = {
                            if (!s.permissionGranted) requestPermission()
                            else if (connected) SpeedVpnService.stop(this@MainActivity)
                            else SpeedVpnService.start(this@MainActivity)
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (connected) Color(0xFF14243C) else Blue2,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.height(48.dp).widthIn(min = 180.dp),
                    ) {
                        Text(
                            when {
                                !s.permissionGranted -> "منح إذن VPN"
                                connected -> "إيقاف الاتصال"
                                connecting || disconnecting -> "قيد التشغيل…"
                                else -> "بدء الاتصال"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (!s.lastError.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            s.lastError.orEmpty(),
                            color = Color(0xFFFF7D88),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LiveTraffic(s: Snapshot) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrafficCard("↓", "التحميل", fmtRateBits(s.downloadBps), Cyan, Modifier.weight(1f))
            TrafficCard("↑", "الرفع", fmtRateBits(s.uploadBps), Purple, Modifier.weight(1f))
        }
    }

    @Composable
    private fun SessionTrafficCard(s: Snapshot) {
        GlassCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("استهلاك جلسة الـVPN", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("البيانات التي مرّت عبر SpeedVPN في الجلسة الحالية", color = TextSecondary, fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = { SpeedVpnService.resetTraffic(this@MainActivity) },
                    enabled = s.status == VpnStatus.CONNECTED,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) { Text("تصفير", fontSize = 10.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoPill("↓ ${formatDataBytes(s.sessionDownloadBytes)}", Cyan, Modifier.weight(1f))
                InfoPill("↑ ${formatDataBytes(s.sessionUploadBytes)}", Purple, Modifier.weight(1f))
            }
            InfoPill("الإجمالي ${formatDataBytes(s.sessionDownloadBytes + s.sessionUploadBytes)}", Blue, Modifier.fillMaxWidth())
        }
    }

    @Composable
    private fun InfoPill(text: String, accent: Color, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = accent.copy(alpha = 0.08f),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
        ) {
            Text(text, color = TextPrimary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun AppsScreen(s: Snapshot) {
        val usageAccess = usageAccessState.value
        var firewallEnabled by remember { mutableStateOf(VpnAppControl.read(this@MainActivity).firewallEnabled) }
        var blockedPackages by remember { mutableStateOf(VpnAppControl.read(this@MainActivity).blockedPackages) }
        var apps by remember { mutableStateOf<List<AppTrafficUsage>>(emptyList()) }
        var policies by remember { mutableStateOf<Map<String, AppPolicy>>(emptyMap()) }
        var quotaPolicies by remember { mutableStateOf<List<AppQuotaPolicy>>(emptyList()) }
        var appQuery by remember { mutableStateOf("") }
        var editingLimitApp by remember { mutableStateOf<AppTrafficUsage?>(null) }
        var editingSpeedApp by remember { mutableStateOf<AppTrafficUsage?>(null) }
        var statsEnabled by remember { mutableStateOf(SmartSettings.isStatsEnabled(this@MainActivity)) }

        LaunchedEffect(s.connectedAtMillis, s.status, usageAccess, statsEnabled) {
            while (isActive) {
                apps = withContext(Dispatchers.IO) {
                    if (statsEnabled && usageAccess) UsageCollector.collectToday(this@MainActivity)
                    if (statsEnabled) {
                        UsageRepository.topUsage(this@MainActivity, UsageDate.today(), 100)
                            .map { AppTrafficUsage(it.packageName, it.label, it.uid, it.downloadBytes, it.uploadBytes) }
                    } else {
                        AppTrafficManager.installedLaunchableApps(this@MainActivity).map {
                            AppTrafficUsage(it.packageName, it.loadLabel(packageManager).toString(), it.uid, 0L, 0L)
                        }
                    }
                }
                policies = withContext(Dispatchers.IO) {
                    UsageRepository.readPolicies(this@MainActivity).associateBy { it.packageName }
                }
                quotaPolicies = withContext(Dispatchers.IO) {
                    UsageRepository.readQuotaPolicies(this@MainActivity)
                }
                if (s.status != VpnStatus.CONNECTED) break
                delay(60_000)
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("التطبيقات", "حظر، حدود بيانات، سرعة خاصة، ونسبة الاستهلاك")

            GlassCard {
                SectionLabel("إحصاءات الإنترنت", "يوميًا لكل تطبيق مع إجمالي أسبوعي وشهري")
                SettingSwitch("جمع الإحصاءات", "تسجيل استهلاك التطبيقات محليًا", statsEnabled) {
                    statsEnabled = it
                    SmartSettings.setStatsEnabled(this@MainActivity, it)
                    if (it) {
                        UsageCollectionScheduler.schedule(this@MainActivity)
                        lifecycleScope.launch(Dispatchers.IO) { UsageCollector.collectToday(this@MainActivity) }
                    } else UsageCollectionScheduler.cancel(this@MainActivity)
                }
                if (!usageAccess) {
                    Button(
                        onClick = { startActivity(AppTrafficManager.usageAccessIntent()) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("السماح بإحصاءات التطبيقات", fontSize = 11.sp) }
                } else {
                    Text("وصول NetworkStatsManager مفعّل", color = Green, fontSize = 10.sp)
                }
            }

            GlassCard {
                SectionLabel("جدار التطبيقات", "الحظر اليدوي وحد البيانات يعملان كحالتين مستقلتين")
                SettingSwitch("تفعيل جدار التطبيقات", "الحظر الفعلي يحتاج Always-on VPN + Lockdown", firewallEnabled) {
                    firewallEnabled = it
                    saveAppControlSettings(VpnAppControlSettings(it, blockedPackages))
                }
                if (firewallEnabled) {
                    Text("فعّل Always-on VPN وBlock connections without VPN من إعدادات Android حتى يصبح الحظر فعليًا.", color = Amber, fontSize = 10.sp)
                    OutlinedButton(
                        onClick = { runCatching { startActivity(AppTrafficManager.vpnSettingsIntent()) } },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("فتح إعدادات VPN", fontSize = 11.sp) }
                }
            }

            GlassCard {
                SectionLabel("قائمة التطبيقات", "النسبة محسوبة من إجمالي استهلاك التطبيقات المعروضة")
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    singleLine = true,
                    label = { Text("بحث عن تطبيق") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val totalConsumption = apps.sumOf { it.totalBytes.coerceAtLeast(0L) }
                val shownApps = apps.filter {
                    appQuery.isBlank() || it.label.contains(appQuery, true) || it.packageName.contains(appQuery, true)
                }
                if (shownApps.isEmpty()) {
                    Text("لا توجد بيانات بعد. فعّل الإحصاءات ومنح Usage Access.", color = TextSecondary, fontSize = 11.sp)
                } else {
                    shownApps.forEach { app ->
                        val policy = policies[app.packageName]
                        val dailyQuota = quotaPolicies.firstOrNull {
                            it.packageName == app.packageName && it.quotaType == QuotaType.DAILY
                        }
                        val limit = dailyQuota?.limitBytes ?: policy?.dailyLimitBytes
                        val used = app.totalBytes
                        val remaining = limit?.let { (it - used).coerceAtLeast(0L) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(
                                    "↓ ${formatDataBytes(app.downloadBytes)} • ↑ ${formatDataBytes(app.uploadBytes)} • ${formatDataBytes(used)} • ${usagePercent(used, totalConsumption)}%",
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                )
                                if (limit != null) {
                                    Text(
                                        "الحد اليومي ${formatDataBytes(limit)} • المتبقي ${formatDataBytes(remaining ?: 0L)}",
                                        color = if ((remaining ?: 0L) == 0L) Color(0xFFFF7D88) else Amber,
                                        fontSize = 9.sp,
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = { editingLimitApp = app }) { Text("حد", color = Amber, fontSize = 10.sp) }
                                    TextButton(onClick = { editingSpeedApp = app }) { Text("سرعة", color = Blue, fontSize = 10.sp) }
                                }
                                Switch(
                                    checked = app.packageName in blockedPackages,
                                    onCheckedChange = { blocked ->
                                        val next = blockedPackages.toMutableSet().apply {
                                            if (blocked) add(app.packageName) else remove(app.packageName)
                                        }.toSet()
                                        blockedPackages = next
                                        saveAppControlSettings(VpnAppControlSettings(firewallEnabled, next))
                                    },
                                )
                            }
                        }
                    }
                    Text("المعروض: ${shownApps.size} من ${apps.size}", color = TextSecondary, fontSize = 9.sp)
                    Text("حد البيانات يعاد تقييمه يوميًا. الحظر اليدوي لا يُزال عند وصول التطبيق إلى الحد أو عند إعادة الضبط اليومية.", color = Amber, fontSize = 9.sp)
                }
            }
        }

        editingLimitApp?.let { app ->
            AppLimitDialog(
                app = app,
                currentQuotaPolicies = quotaPolicies.filter { it.packageName == app.packageName },
                legacyDailyLimitBytes = policies[app.packageName]?.dailyLimitBytes,
                onDismiss = { editingLimitApp = null },
                onSave = { type, bytes ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        UsageRepository.upsertQuotaPolicy(this@MainActivity, app.packageName, app.label, app.uid, type, bytes)
                        if (type == QuotaType.DAILY) UsageCollector.enforceDailyLimits(this@MainActivity)
                        policies = UsageRepository.readPolicies(this@MainActivity).associateBy { it.packageName }
                        quotaPolicies = UsageRepository.readQuotaPolicies(this@MainActivity)
                    }
                    editingLimitApp = null
                },
            )
        }

        editingSpeedApp?.let { app ->
            val profile = remember(app.packageName) { mutableStateOf<SpeedProfile?>(null) }
            LaunchedEffect(app.packageName) {
                profile.value = withContext(Dispatchers.IO) { AiRepository.getSpeedProfile(this@MainActivity, app.packageName) }
            }
            AppSpeedDialog(
                app = app,
                existing = profile.value,
                onDismiss = { editingSpeedApp = null },
                onSave = { enabled, down, up ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppSpeedProfiles(this@MainActivity).save(app.packageName, enabled, down, up)
                    }
                    editingSpeedApp = null
                },
            )
        }
    }

    private fun quotaTypeLabel(type: QuotaType): String = when (type) {
        QuotaType.DAILY -> "يومي"
        QuotaType.WEEKLY -> "أسبوعي"
        QuotaType.MONTHLY -> "شهري"
    }

    @Composable
    private fun AppLimitDialog(
        app: AppTrafficUsage,
        currentQuotaPolicies: List<AppQuotaPolicy>,
        legacyDailyLimitBytes: Long?,
        onDismiss: () -> Unit,
        onSave: (QuotaType, Long?) -> Unit,
    ) {
        var selectedType by remember(currentQuotaPolicies, legacyDailyLimitBytes) { mutableStateOf(QuotaType.DAILY) }
        val selectedPolicy = currentQuotaPolicies.firstOrNull { it.quotaType == selectedType }
        val currentLimit = selectedPolicy?.limitBytes ?: if (selectedType == QuotaType.DAILY) legacyDailyLimitBytes else null
        var mbText by remember(selectedType, currentLimit) { mutableStateOf(currentLimit?.let { String.format(Locale.US, "%.0f", it / 1_000_000.0) } ?: "") }
        var menuExpanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("حد البيانات • ${app.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("يمكن للتطبيق حمل حد يومي وأسبوعي وشهري في الوقت نفسه.", color = TextSecondary, fontSize = 11.sp)
                    Box {
                        OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("الفترة: ${quotaTypeLabel(selectedType)}")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            QuotaType.values().forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(quotaTypeLabel(type)) },
                                    onClick = { selectedType = type; menuExpanded = false },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = mbText,
                        onValueChange = { mbText = it.filter { ch -> ch.isDigit() }.take(9) },
                        singleLine = true,
                        label = { Text("MB ${quotaTypeLabel(selectedType)}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mb = mbText.toLongOrNull()
                    onSave(selectedType, mb?.takeIf { it > 0L }?.times(1_000_000L))
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        )
    }

    @Composable
    private fun AppSpeedDialog(
        app: AppTrafficUsage,
        existing: SpeedProfile?,
        onDismiss: () -> Unit,
        onSave: (Boolean, Long?, Long?) -> Unit,
    ) {
        var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: false) }
        var down by remember(existing) { mutableStateOf(existing?.downloadKbps?.let { (it / 1000.0).toString() } ?: "") }
        var up by remember(existing) { mutableStateOf(existing?.uploadKbps?.let { (it / 1000.0).toString() } ?: "") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("ملف سرعة ${app.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitch("تفعيل ملف خاص", "حفظ إعداد مستقل لهذا التطبيق", enabled) { enabled = it }
                    OutlinedTextField(
                        value = down,
                        onValueChange = { down = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("تحميل Mbps") },
                    )
                    OutlinedTextField(
                        value = up,
                        onValueChange = { up = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("رفع Mbps") },
                    )
                    Text(
                        "الملف مستقل عن السرعة العامة. النافذة العائمة التي تفتح من هنا تستهدف هذا التطبيق. تطبيق خنق مختلف لكل UID داخل TUN يحتاج تصنيف UID في طبقة النفق.",
                        color = TextSecondary,
                        fontSize = 10.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                runCatching { startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                            } else {
                                SpeedOverlayService.startForApp(this@MainActivity, app.packageName, app.label)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("فتح نافذة السرعة لهذا التطبيق") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val dl = down.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000.0).toLong() }
                    val ul = up.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000.0).toLong() }
                    onSave(enabled, dl, ul)
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        )
    }

    private fun usagePercent(used: Long, total: Long): String =
        if (total <= 0L) "0.0" else String.format(Locale.US, "%.1f", used.toDouble() * 100.0 / total.toDouble())

    @Composable
    private fun TrafficCard(symbol: String, title: String, value: String, accent: Color, modifier: Modifier) {
        GlassCard(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text(symbol, color = accent, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(title, color = TextSecondary, fontSize = 11.sp)
                    Text(value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }

    @Composable
    private fun QuickSpeedCard(s: Snapshot) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Blue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text("↯", color = Blue, fontSize = 22.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("التحكم السريع", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("تحميل ${fmt(s.downloadLimitKbps)} • رفع ${fmt(s.uploadLimitKbps)}", color = TextSecondary, fontSize = 11.sp)
                }
                Text("السرعة ›", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun CompactNetworkCard() {
        val prefs = getSharedPreferences(VpnSettings.PREFS, MODE_PRIVATE)
        val ipv6 = prefs.getBoolean(VpnSettings.IPV6_ENABLED, true)
        val dns4 = prefs.getBoolean(VpnSettings.DNS_IPV4_ONLY, false)
        val mtu = prefs.getInt(VpnSettings.MTU, 1280)
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("◉", color = Purple, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("توافق الشبكة", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("IPv6 ${if (ipv6) "ON" else "OFF"} • DNS ${if (dns4) "IPv4" else "Auto"} • MTU $mtu", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }

    @Composable
    private fun LimitSlider(title: String, current: Long?, onApply: (Long?) -> Unit) {
        // A logarithmic slider keeps the low-speed 10–100 KB/s range usable while
        // still reaching 100 MB/s without compressing all small values into one pixel.
        val minKBps = 10.0
        val maxKBps = 100_000.0
        val minLog = ln(minKBps)
        val maxLog = ln(maxKBps)
        val unlimitedSentinel = 1.01f
        var pos by remember(current) {
            mutableStateOf(
                current?.let {
                    val kb = (it / 8.0).coerceIn(minKBps, maxKBps)
                    ((ln(kb) - minLog) / (maxLog - minLog)).toFloat()
                } ?: unlimitedSentinel
            )
        }
        val unlimited = pos > 1f
        val selectedKBps = if (unlimited) null else {
            exp(minLog + (maxLog - minLog) * pos.coerceIn(0f, 1f)).roundToLong()
        }
        val kbps: Long? = selectedKBps?.let { (it * 8L).coerceIn(80L, 800_000L) }

        GlassCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(fmt(kbps), color = Blue, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            PresetGroup("بطيء", slowPresets, Amber, onApply)
            PresetGroup("متوسط", mediumPresets, Blue, onApply)
            PresetGroup("سريع", fastPresets, Purple, onApply)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = { onApply(null) },
                    shape = RoundedCornerShape(11.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.45f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp),
                ) { Text(unlimitedPreset, fontSize = 11.sp, color = TextPrimary) }
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value = pos,
                onValueChange = { pos = it },
                valueRange = 0f..unlimitedSentinel,
                onValueChangeFinished = { onApply(kbps) },
                colors = SliderDefaults.colors(activeTrackColor = Blue, thumbColor = TextPrimary, inactiveTrackColor = StrokeColor),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("10 KB/s", color = TextSecondary, fontSize = 10.sp)
                Text("100 MB/s → بدون حد", color = TextSecondary, fontSize = 10.sp)
            }
        }
    }

    @Composable
    private fun PresetGroup(title: String, presets: List<Pair<String, Long>>, accent: Color, onApply: (Long?) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { (label, value) ->
                    OutlinedButton(
                        onClick = { onApply(value) },
                        shape = RoundedCornerShape(11.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp),
                    ) { Text(label, fontSize = 11.sp, color = TextPrimary) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    @Composable
    private fun CompatibilitySettingsCard() {
        val prefs = getSharedPreferences(VpnSettings.PREFS, MODE_PRIVATE)
        var ipv6Enabled by remember { mutableStateOf(prefs.getBoolean(VpnSettings.IPV6_ENABLED, true)) }
        var dnsIpv4Only by remember { mutableStateOf(prefs.getBoolean(VpnSettings.DNS_IPV4_ONLY, false)) }
        var mtu by remember { mutableStateOf(prefs.getInt(VpnSettings.MTU, 1280).coerceIn(1280, 1500)) }
        var advanced by remember { mutableStateOf(false) }

        GlassCard {
            SectionLabel("توافق الشبكة", "خيارات لحل مشاكل بعض التطبيقات والشبكات")
            SettingSwitch("IPv6", "السماح باستخدام IPv6 داخل الاتصال", ipv6Enabled) {
                ipv6Enabled = it
                lifecycleScope.launch(Dispatchers.IO) {
                    settingsWriteMutex.withLock {
                        check(prefs.edit().putBoolean(VpnSettings.IPV6_ENABLED, it).commit()) {
                            "Failed to persist IPv6 setting"
                        }
                    }
                }
            }
            SettingSwitch("DNS IPv4 فقط", "استخدمه عند عدم استقرار DNS عبر IPv6", dnsIpv4Only) {
                dnsIpv4Only = it
                lifecycleScope.launch(Dispatchers.IO) {
                    settingsWriteMutex.withLock {
                        check(prefs.edit().putBoolean(VpnSettings.DNS_IPV4_ONLY, it).commit()) {
                            "Failed to persist DNS IPv4-only setting"
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MTU", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("حجم حزمة الشبكة", color = TextSecondary, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(1280, 1400, 1500).forEach { value ->
                        FilterChip(selected = mtu == value, onClick = {
                            mtu = value
                            lifecycleScope.launch(Dispatchers.IO) {
                                settingsWriteMutex.withLock {
                                    check(prefs.edit().putInt(VpnSettings.MTU, value).commit()) {
                                        "Failed to persist MTU setting"
                                    }
                                }
                            }
                        }, label = { Text(value.toString(), fontSize = 10.sp) })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "تطبّق التغييرات عند الاتصال القادم. لا تحتاج لتغييرها إلا عند وجود مشكلة في تطبيق أو شبكة محددة.",
                color = TextSecondary,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "إخفاء التشخيص المتقدم" else "إظهار التشخيص المتقدم", color = Blue)
            }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("وضع التوافق المتقدم", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("ابدأ بالقيم الافتراضية، ثم جرّب IPv6 OFF أو MTU 1280/1400 عند تعطل بعض التطبيقات.", color = TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }

    @Composable
    private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = TextSecondary, fontSize = 10.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun AccountCard(signedIn: Boolean, onLink: () -> Unit, onSignOut: () -> Unit) {
        GlassCard {
            SectionLabel("الحساب", "الربط بالموقع اختياري")
            if (signedIn) {
                Text(Auth.email(this@MainActivity) ?: "حساب مرتبط", color = TextPrimary, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    AgentService.stop(this@MainActivity)
                    lifecycleScope.launch {
                        try {
                            Auth.signOut(this@MainActivity)
                            onSignOut()
                        } catch (_: Exception) {
                            // Keep the current account UI if persistence fails.
                        }
                    }
                }) { Text("فك الربط", color = Color(0xFFFF7D88)) }
            } else {
                Text("التطبيق يعمل محليًا بدون حساب.", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onLink, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("ربط بالموقع (اختياري)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun DiagnosticsCard(s: Snapshot) {
        var show by remember { mutableStateOf(false) }
        GlassCard {
            Row(Modifier.fillMaxWidth().clickable { show = !show }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("التشخيص", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("معلومات مفيدة عند إرسال تقرير مشكلة", color = TextSecondary, fontSize = 10.sp)
                }
                Text(if (show) "▲" else "▼", color = Blue)
            }
            AnimatedVisibility(show) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 10.dp)) {
                    InfoRow("VPN Permission", if (s.permissionGranted) "granted" else "not granted")
                    InfoRow("Agent", if (s.agentOnline) "online" else "offline")
                    InfoRow("Service", if (s.serviceRunning) "running" else "stopped")
                    InfoRow("VPN", s.status.name)
                    InfoRow("Health", s.health.name)
                    InfoRow("Tunnel", s.tunnel)
                    InfoRow("Last Error", s.lastError ?: "—")
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(title: String, subtitle: String) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
    }

    @Composable
    private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier.fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Surface2, Surface)))
                .border(1.dp, StrokeColor.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }

    @Composable
    private fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
        NavigationBar(containerColor = Color(0xFF070D19), tonalElevation = 0.dp) {
            NavigationBarItem(selected = tab == 0, onClick = { onTab(0) }, icon = { Text("⌂", fontSize = 20.sp) }, label = { Text("الرئيسية", fontSize = 10.sp) })
            NavigationBarItem(selected = tab == 1, onClick = { onTab(1) }, icon = { Text("↯", fontSize = 20.sp) }, label = { Text("السرعة", fontSize = 10.sp) })
            NavigationBarItem(selected = tab == 2, onClick = { onTab(2) }, icon = { Text("◉", fontSize = 19.sp) }, label = { Text("التطبيقات", fontSize = 10.sp) })
            NavigationBarItem(selected = tab == 3, onClick = { onTab(3) }, icon = { Text("🧠", fontSize = 17.sp) }, label = { Text("الذكي", fontSize = 10.sp) })
            NavigationBarItem(selected = tab == 4, onClick = { onTab(4) }, icon = { Text("⚙", fontSize = 19.sp) }, label = { Text("الإعدادات", fontSize = 10.sp) })
        }
    }

    @Composable
    private fun LoginScreen(onDone: () -> Unit, onCancel: () -> Unit) {
        var email by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF06132A), Bg))), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandMark(82.dp)
                Spacer(Modifier.height(4.dp))
                BrandWordmark()
                Text("تحكم في اتصال جهازك من أي مكان", color = TextSecondary, textAlign = TextAlign.Center, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                GlassCard {
                    OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = Color(0xFFFF7D88), fontSize = 12.sp) }
                    Button(enabled = !busy && email.isNotBlank() && pass.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), onClick = {
                        busy = true; error = null
                        lifecycleScope.launch {
                            try {
                                Auth.signIn(this@MainActivity, email.trim(), pass)
                                AgentService.start(this@MainActivity)
                                onDone()
                            } catch (e: Exception) { error = e.message } finally { busy = false }
                        }
                    }) { Text(if (busy) "جاري الدخول…" else "تسجيل الدخول", fontWeight = FontWeight.Bold) }
                }
                TextButton(onClick = onCancel) { Text("العودة للوضع المحلي", color = TextSecondary) }
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else VpnRuntime.update { it.copy(permissionGranted = true) }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextSecondary, fontSize = 10.sp)
            Text(value, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SpeedVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            secondary = Purple,
            background = Bg,
            surface = Surface,
            onSurface = TextPrimary,
            onBackground = TextPrimary,
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 14.sp),
            bodyMedium = TextStyle(fontSize = 13.sp),
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
        ),
        content = content,
    )
}
