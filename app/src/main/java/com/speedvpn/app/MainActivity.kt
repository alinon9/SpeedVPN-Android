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
    private fun SettingsScreen(s: Snapshot, signedIn: Boolean, onLink: () -> Unit, onSignOut: () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("الإعدادات", "التوافق، الحساب والتشخيص")
            CompatibilitySettingsCard()
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
        var appQuery by remember { mutableStateOf("") }

        LaunchedEffect(s.connectedAtMillis, s.status, usageAccess) {
            val start = s.connectedAtMillis
            if (start <= 0L) {
                apps = AppTrafficManager.installedLaunchableApps(this@MainActivity).map {
                    AppTrafficUsage(it.packageName, it.loadLabel(packageManager).toString(), it.uid, 0L, 0L)
                }
                return@LaunchedEffect
            }
            while (isActive) {
                apps = withContext(Dispatchers.IO) {
                    AppTrafficManager.querySessionUsage(this@MainActivity, start)
                }
                if (s.status != VpnStatus.CONNECTED) break
                delay(60_000)
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("التطبيقات", "استهلاك البيانات والتحكم في وصول التطبيقات")

            GlassCard {
                SectionLabel("إحصاءات التطبيقات", "قد تتأخر أرقام Android قليلًا لأنها مبنية على NetworkStatsManager")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!usageAccess) {
                        Button(
                            onClick = { startActivity(AppTrafficManager.usageAccessIntent()) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("السماح بالإحصاءات", fontSize = 11.sp) }
                    } else {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = Green.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.25f)),
                        ) {
                            Text("وصول الإحصاءات مفعّل", color = Green, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp), textAlign = TextAlign.Center)
                        }
                    }
                }
                Text(
                    "يُعرض الاستهلاك للجلسة الحالية؛ Android قد لا يحدّث عدادات كل تطبيق لحظيًا.",
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
            }

            GlassCard {
                SectionLabel("جدار التطبيقات", "حظر فعلي للتطبيقات المحددة عبر Android VPN Lockdown")
                SettingSwitch("تفعيل جدار التطبيقات", "الحظر الفعلي عند الاتصال القادم مع Lockdown", firewallEnabled) {
                    firewallEnabled = it
                    saveAppControlSettings(VpnAppControlSettings(it, blockedPackages))
                }
                if (firewallEnabled) {
                    Text(
                        "الحظر الحقيقي يتطلب Always-on VPN + Block connections without VPN. عند تفعيل الجدار لن يسمح SpeedVPN بالاتصال قبل توفر Lockdown، لمنع أي تجاوز صامت.",
                        color = Amber,
                        fontSize = 10.sp,
                    )
                    OutlinedButton(
                        onClick = { runCatching { startActivity(AppTrafficManager.vpnSettingsIntent()) } },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("فتح إعدادات VPN", fontSize = 11.sp) }
                }
            }

            GlassCard {
                SectionLabel("التطبيقات", "الحظر الفعلي يحتاج Lockdown في إعدادات Android")
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    singleLine = true,
                    label = { Text("بحث عن تطبيق") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val shownApps = apps.filter {
                    appQuery.isBlank() ||
                        it.label.contains(appQuery, ignoreCase = true) ||
                        it.packageName.contains(appQuery, ignoreCase = true)
                }
                if (shownApps.isEmpty()) {
                    Text("لا توجد تطبيقات لعرضها.", color = TextSecondary, fontSize = 11.sp)
                } else {
                    shownApps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(
                                    "↓ ${formatDataBytes(app.downloadBytes)}  ↑ ${formatDataBytes(app.uploadBytes)}  •  ${formatDataBytes(app.totalBytes)}",
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                )
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
                    Text(
                        "المعروض: ${shownApps.size} من ${apps.size} تطبيق",
                        color = TextSecondary,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }

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
            NavigationBarItem(selected = tab == 3, onClick = { onTab(3) }, icon = { Text("⚙", fontSize = 19.sp) }, label = { Text("الإعدادات", fontSize = 10.sp) })
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
