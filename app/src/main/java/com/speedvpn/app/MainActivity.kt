package com.speedvpn.app

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
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
    // UI speeds are expressed as KB/s; share the overflow-safe conversion with unit tests.
    private fun kbpsFromKBps(value: Long): Long = SpeedLimiter.kilobytesPerSecondToKbps(value)
    private val slowPresets = listOf(
        "10 KB" to kbpsFromKBps(10),
        "25 KB" to kbpsFromKBps(25),
        "50 KB" to kbpsFromKBps(50),
        "75 KB" to kbpsFromKBps(75),
        "100 KB" to kbpsFromKBps(100),
        "130 KB" to kbpsFromKBps(130),
        "250 KB" to kbpsFromKBps(250),
        "500 KB" to kbpsFromKBps(500),
        "750 KB" to kbpsFromKBps(750),
        "950 KB" to kbpsFromKBps(950),
    )
    private val mediumPresets = listOf(
        "1 MB" to kbpsFromKBps(1_000),
        "2 MB" to kbpsFromKBps(2_000),
        "3 MB" to kbpsFromKBps(3_000),
        "4 MB" to kbpsFromKBps(4_000),
        "5 MB" to kbpsFromKBps(5_000),
    )
    private val fastPresets = listOf(
        "9 MB" to kbpsFromKBps(9_000),
        "10 MB" to kbpsFromKBps(10_000),
        "11 MB" to kbpsFromKBps(11_000),
    )
    private val unlimitedPreset = "بدون حد"
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = VpnService.prepare(this) == null
        log(if (granted) "VPN permission granted" else "VPN permission denied")
        VpnRuntime.update { it.copy(permissionGranted = granted, lastError = if (granted) it.lastError else "VPN permission denied") }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val usageAccessState = mutableStateOf(false)
    // Refresh the installed-app catalog whenever the Activity resumes.
    private val appCatalogRefreshTrigger = mutableStateOf(0)
    private val showVpnDisclosure = mutableStateOf(false)
    private val exportExcelBusyState = mutableStateOf(false)
    private val exportExcelStatusState = mutableStateOf<String?>(null)
    private val exportExcelErrorState = mutableStateOf(false)
    private val createExcelDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(SpeedHistoryExcelExporter.XLSX_MIME_TYPE),
    ) { uri ->
        if (uri == null) {
            exportExcelBusyState.value = false
            exportExcelErrorState.value = false
            exportExcelStatusState.value = "تم إلغاء عملية تصدير سجل السرعات."
            return@registerForActivityResult
        }
        exportSpeedHistoryToUri(uri)
    }

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
        handleE2eIntent(getIntent())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleE2eIntent(intent)
    }

    /**
     * Debug-only E2E controls. The emulator workflow uses separate-UID fixture APKs.
     * These commands are inert in Release builds.
     */
    private fun handleE2eIntent(command: Intent?) {
        if (!BuildConfig.DEBUG || command == null) return
        val action = command.getStringExtra("e2e_action")?.trim().orEmpty()
        if (action.isBlank()) return
        val eventId = command.getStringExtra("e2e_id").orEmpty().ifBlank { action }
        val pkg = command.getStringExtra("e2e_package").orEmpty()
        val enabled = command.getBooleanExtra("e2e_enabled", false)
        val bytes = command.getLongExtra("e2e_bytes", 0L)
        val appContext = applicationContext

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (action) {
                    "manual_block" -> {
                        require(pkg.isNotBlank())
                        val info = packageManager.getApplicationInfo(pkg, 0)
                        // Individual package blocks must work even when the optional global
                        // firewall preference is off. Do not silently force that preference on.
                        VpnAppControl.setBlocked(appContext, pkg, enabled)
                        UsageRepository.setBlocked(
                            appContext, pkg, info.loadLabel(packageManager).toString(), info.uid, enabled,
                        )
                    }
                    "set_quota" -> {
                        require(pkg.isNotBlank())
                        val info = packageManager.getApplicationInfo(pkg, 0)
                        VpnAppControl.setQuotaBlocked(appContext, pkg, false)
                        UsageRepository.setDailyLimitBytes(
                            appContext, pkg, info.loadLabel(packageManager).toString(), info.uid,
                            bytes.takeIf { it > 0L },
                        )
                    }
                    "reset_policy" -> {
                        require(pkg.isNotBlank())
                        val info = packageManager.getApplicationInfo(pkg, 0)
                        VpnAppControl.setBlocked(appContext, pkg, false)
                        VpnAppControl.setQuotaBlocked(appContext, pkg, false)
                        UsageRepository.readQuotaPolicies(appContext, pkg).forEach { policy ->
                            UsageRepository.deleteQuotaPolicy(appContext, pkg, policy.quotaType)
                        }
                        UsageRepository.setDailyLimitBytes(
                            appContext, pkg, info.loadLabel(packageManager).toString(), info.uid, null,
                        )
                        // Start the E2E policy scenario with the optional global preference off.
                        // The following probe proves that per-app blocking works independently.
                        VpnAppControl.save(
                            appContext,
                            VpnAppControlSettings(
                                firewallEnabled = false,
                                blockedPackages = VpnAppControl.read(appContext).blockedPackages - pkg,
                            ),
                        )
                    }
                    "connect" -> SpeedVpnService.start(appContext)
                    "restart" -> {
                        SpeedVpnService.stop(appContext)
                        if (!waitForE2eTunnel(connected = false)) {
                            throw IllegalStateException("VPN did not stop before restart")
                        }
                        SpeedVpnService.start(appContext)
                    }
                    "set_stats_enabled" -> {
                        SmartSettings.setStatsEnabled(appContext, enabled)
                    }
                    "set_speed_limits" -> {
                        val dl = command.getLongExtra("e2e_download_kbps", -1L).takeIf { it > 0L }
                        val ul = command.getLongExtra("e2e_upload_kbps", -1L).takeIf { it > 0L }
                        SpeedLimiter.setLimitsKbps(dl, ul) {
                            SpeedLimitStore.stageDownload(appContext, dl)
                            SpeedLimitStore.stageUpload(appContext, ul)
                        }
                        SpeedLimitStore.save(appContext, dl, ul)
                    }
                    "export_speed_history_xlsx" -> {
                        val outFile = java.io.File(appContext.cacheDir, "e2e_speed_history.xlsx")
                        SpeedPlanUsageTracker.flushActiveSegment(appContext)
                        val records = UsageRepository.readAllSpeedPlanUsageSessions(appContext)
                        val res = SpeedHistoryExcelExporter.exportToFile(outFile, records)
                        if (res is SpeedHistoryExportResult.Failure) {
                            throw IllegalStateException(res.userMessage)
                        }
                    }
                    "status" -> Unit
                    else -> throw IllegalArgumentException("Unknown E2E action: $action")
                }

                val expectsConnected = action == "connect" || action == "restart"
                val tunnelReady = if (expectsConnected) waitForE2eTunnel(connected = true) else true
                val state = VpnRuntime.state.value
                val settings = VpnAppControl.read(appContext)
                val effectiveBlocked = pkg.isNotBlank() && pkg in settings.blockedPackages
                val quotaBlocked = pkg.isNotBlank() && pkg in VpnAppControl.quotaBlockedPackages(appContext)
                val quotaSummary = if (pkg.isBlank()) "none" else {
                    UsageRepository.readQuotaPolicies(appContext, pkg).joinToString(",") {
                        "${it.quotaType}:${it.limitBytes}:${it.usedBytes}"
                    }.ifBlank { "none" }
                }
                val passed = tunnelReady && (!expectsConnected || state.status == VpnStatus.CONNECTED)
                android.util.Log.i(
                    "SpeedVPN-E2E",
                    "E2E_CONTROL id=$eventId action=$action package=$pkg result=${if (passed) "PASS" else "FAIL"} " +
                        "vpn=${state.status} firewall=${settings.firewallEnabled} " +
                        "effectiveBlocked=$effectiveBlocked quotaBlocked=$quotaBlocked quota=$quotaSummary",
                )
            } catch (error: Exception) {
                android.util.Log.e(
                    "SpeedVPN-E2E",
                    "E2E_CONTROL id=$eventId action=$action package=$pkg result=FAIL error=${error.message}",
                    error,
                )
            }
        }
    }

    private suspend fun waitForE2eTunnel(connected: Boolean, timeoutMs: Long = 30_000L): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val state = VpnRuntime.state.value.status
            val matches = if (connected) {
                state == VpnStatus.CONNECTED
            } else {
                state in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR) &&
                    SpeedVpnService.isNativeTunnelStopped()
            }
            if (matches) return true
            delay(250L)
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        usageAccessState.value = AppTrafficManager.hasUsageAccess(this)
        appCatalogRefreshTrigger.value += 1
        VpnRuntime.update { it.copy(permissionGranted = VpnService.prepare(this) == null) }
    }

    private fun loadLocalLimits() = SpeedLimitStore.restoreToLimiterIfUninitialized(this)

    private val settingsWriteMutex = Mutex()
    private val appControlWriteGeneration = AtomicLong(0L)
    private val limitWriteGeneration = AtomicLong(0L)

    // Keep speed-test and verification UI state across tab navigation and recomposition
    // so switching tabs cannot reset busy flags or launch overlapping runs.
    private val speedTestBusyState = mutableStateOf(false)
    private val speedTestProgressState = mutableStateOf<String?>(null)
    private val lastSpeedTestState = mutableStateOf<SpeedTestResult?>(null)
    private val preflightBusyState = mutableStateOf(false)
    private val preflightProgressState = mutableStateOf<String?>(null)
    private val lastPreflightState = mutableStateOf<SpeedTestResult?>(null)
    private val verifyBusyState = mutableStateOf(false)
    private val verifyProgressState = mutableStateOf<String?>(null)
    private val lastVerificationState = mutableStateOf<SpeedVerificationResult?>(null)

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
        if (SpeedLimiter.verificationInProgress.value ||
            verifyBusyState.value ||
            speedTestBusyState.value ||
            preflightBusyState.value
        ) {
            return
        }
        val applied = if (download) {
            SpeedLimiter.setDownloadKbps(kbps) {
                SpeedLimitStore.stageDownload(this, kbps)
            }
        } else {
            SpeedLimiter.setUploadKbps(kbps) {
                SpeedLimitStore.stageUpload(this, kbps)
            }
        }
        if (!applied) return
        val generation = limitWriteGeneration.incrementAndGet()
        // SharedPreferences.commit() is synchronous. Keep disk I/O off the main
        // thread while serializing writes so rapid slider changes cannot reorder
        // the persisted value or overwrite a newer verification/remote state.
        lifecycleScope.launch(Dispatchers.IO) {
            settingsWriteMutex.withLock {
                if (SpeedLimiter.verificationInProgress.value) return@withLock
                val (stagedDownload, stagedUpload) = SpeedLimitStore.load(this@MainActivity)
                if (generation == limitWriteGeneration.get()) {
                    if (download) {
                        SpeedLimitStore.saveDownload(this@MainActivity, stagedDownload)
                    } else {
                        SpeedLimitStore.saveUpload(this@MainActivity, stagedUpload)
                    }
                }
            }
        }
    }

    private fun saveLocalLimitsBoth(downloadKbps: Long?, uploadKbps: Long?) {
        if (SpeedLimiter.verificationInProgress.value ||
            verifyBusyState.value ||
            speedTestBusyState.value ||
            preflightBusyState.value
        ) {
            return
        }
        val applied = SpeedLimiter.setLimitsKbps(downloadKbps, uploadKbps) {
            SpeedLimitStore.stageDownload(this, downloadKbps)
            SpeedLimitStore.stageUpload(this, uploadKbps)
        }
        if (!applied) return
        val generation = limitWriteGeneration.incrementAndGet()
        lifecycleScope.launch(Dispatchers.IO) {
            settingsWriteMutex.withLock {
                if (SpeedLimiter.verificationInProgress.value) return@withLock
                val (stagedDownload, stagedUpload) = SpeedLimitStore.load(this@MainActivity)
                if (generation == limitWriteGeneration.get()) {
                    SpeedLimitStore.save(this@MainActivity, stagedDownload, stagedUpload)
                }
            }
        }
    }

    private fun launchSpeedHistoryExcelExport() {
        if (exportExcelBusyState.value) return
        exportExcelStatusState.value = null
        exportExcelErrorState.value = false
        try {
            createExcelDocument.launch(SpeedHistoryExcelExporter.suggestedFileName())
        } catch (error: Exception) {
            exportExcelBusyState.value = false
            exportExcelErrorState.value = true
            exportExcelStatusState.value = "تعذر فتح منتقي حفظ الملفات: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun exportSpeedHistoryToUri(uri: android.net.Uri) {
        exportExcelBusyState.value = true
        exportExcelStatusState.value = "جاري إنشاء ملف Excel (.xlsx)…"
        exportExcelErrorState.value = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SpeedHistoryExcelExporter.exportToUri(this@MainActivity, uri)
            }
            exportExcelBusyState.value = false
            when (result) {
                is SpeedHistoryExportResult.Success -> {
                    exportExcelErrorState.value = false
                    exportExcelStatusState.value = result.message
                }
                is SpeedHistoryExportResult.Failure -> {
                    exportExcelErrorState.value = true
                    exportExcelStatusState.value = result.userMessage
                }
            }
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
        val verificationInProgress by SpeedLimiter.verificationInProgress.collectAsStateWithLifecycle()
        val speedActionBusy = verificationInProgress ||
            verifyBusyState.value ||
            speedTestBusyState.value ||
            preflightBusyState.value
        var tab by remember { mutableStateOf(0) }
        Scaffold(
            containerColor = Bg,
            bottomBar = { BottomBar(tab, enabled = !speedActionBusy, onTab = { tab = it }) },
        ) { padding ->
            if (showVpnDisclosure.value) {
                AlertDialog(
                    onDismissRequest = { showVpnDisclosure.value = false },
                    title = { Text("لماذا يحتاج SpeedVPN إلى إذن VPN؟") },
                    text = {
                        Text(
                            "يستخدم SpeedVPN واجهة Android VpnService لإنشاء نفق TUN محلي على الجهاز حتى يتمكن من التحكم في سرعة الاتصال، وإدارة الحظر وحدود استخدام التطبيقات. لا يعني ذلك أن التطبيق يوفر إخفاء هوية أو خادم VPN بعيدًا في الوضع المحلي الحالي. إذا فعّلت إحصاءات التطبيقات ومنحت Usage Access، تُستخدم إحصاءات Android لعرض استخدام التطبيقات وتطبيق حدودها."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showVpnDisclosure.value = false
                                val intent = VpnService.prepare(this@MainActivity)
                                if (intent != null) vpnPermission.launch(intent)
                                else VpnRuntime.update { it.copy(permissionGranted = true) }
                            },
                        ) { Text("أوافق والمتابعة") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showVpnDisclosure.value = false }) { Text("إلغاء") }
                    },
                )
            }
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> HomeScreen(
                        signedIn = signedIn,
                        s = s,
                        onOpenSpeedTab = { if (!speedActionBusy) tab = 1 },
                        requestPermission = { requestVpnPermission() },
                    )
                    1 -> SpeedScreen(s, verificationInProgress)
                    2 -> AppsScreen(s)
                    3 -> SmartScreen(
                        s = s,
                        verificationInProgress = verificationInProgress,
                        onOpenSettingsTab = { if (!speedActionBusy) tab = 4 },
                    )
                    else -> SettingsScreen(s, signedIn, onLink, onSignOut)
                }
            }
        }
    }

    @Composable
    private fun HomeScreen(
        signedIn: Boolean,
        s: Snapshot,
        onOpenSpeedTab: () -> Unit = {},
        requestPermission: () -> Unit,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TopBrandBar(signedIn)
            Spacer(Modifier.height(2.dp))
            StatusHero(s, requestPermission)
            LiveTraffic(s)
            SessionTrafficCard(s)
            QuickSpeedCard(s, onOpenSpeedTab)
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

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun SpeedScreen(s: Snapshot, verificationInProgress: Boolean) {
        var testBusy by speedTestBusyState
        var testProgress by speedTestProgressState
        var lastTest by lastSpeedTestState
        var preflightBusy by preflightBusyState
        var preflightProgress by preflightProgressState
        var lastPreflight by lastPreflightState
        var verifyBusy by verifyBusyState
        var verifyProgress by verifyProgressState
        var lastVerification by lastVerificationState
        var verificationHistory by remember { mutableStateOf(SpeedVerificationStore.loadRecent(this@MainActivity, 8)) }
        var exportBusy by exportExcelBusyState
        val exportStatus by exportExcelStatusState
        val exportIsError by exportExcelErrorState
        val persistedHistoryRevision by SpeedPlanUsageTracker.persistedHistoryRevision.collectAsStateWithLifecycle()
        var persistedPlanHistory by remember { mutableStateOf<List<SpeedPlanUsageRecord>>(emptyList()) }

        // Load persisted history from SQLite only on screen open or when persisted records
        // actually change (connect, plan transition, 5s/256KB checkpoint flush, or disconnect).
        // Never force a synchronous SQLite write or full-table read on every 500ms meter tick.
        LaunchedEffect(
            persistedHistoryRevision,
            s.status,
            s.downloadLimitKbps,
            s.uploadLimitKbps,
            verificationInProgress,
        ) {
            persistedPlanHistory = withContext(Dispatchers.IO) {
                UsageRepository.readAllSpeedPlanUsageSessions(this@MainActivity)
            }
        }

        val mergedPlanHistory = remember(
            persistedPlanHistory,
            s.status,
            s.downloadLimitKbps,
            s.uploadLimitKbps,
            s.sessionDownloadBytes,
            s.sessionUploadBytes,
        ) {
            SpeedPlanUsageTracker.mergePersistedWithLiveActive(persistedPlanHistory)
        }
        val speedPlanHistory = remember(mergedPlanHistory) {
            mergedPlanHistory.sortedWith(
                compareByDescending<SpeedPlanUsageRecord> { it.startedAtMillis }
                    .thenByDescending { it.vpnGeneration }
                    .thenByDescending { it.segmentIndex },
            )
        }
        val speedPlanSummary = remember(mergedPlanHistory) {
            SpeedHistoryExcelExporter.buildSummary(mergedPlanHistory)
        }

        Column(
            Modifier.fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag("speed_screen")
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle("التحكم بالسرعة", "اختر مستوى جاهزًا أو اضبطه بالمؤشر — الوحدات KB/s وMB/s")
            LimitSlider("سرعة التحميل", s.downloadLimitKbps, enabled = !testBusy && !preflightBusy && !verifyBusy && !verificationInProgress) {
                saveLocalLimit(true, it)
            }
            LimitSlider("سرعة الرفع", s.uploadLimitKbps, enabled = !testBusy && !preflightBusy && !verifyBusy && !verificationInProgress) {
                saveLocalLimit(false, it)
            }

            GlassCard {
                SectionLabel("اختبارات الاعتماد • Stage 0", "قياس فعلي قبل بناء الخطط على التحكم بالسرعة")

                if (BuildConfig.DEBUG) {
                    Button(
                        enabled = !testBusy && !preflightBusy && !verifyBusy &&
                            !verificationInProgress && s.status == VpnStatus.DISCONNECTED,
                        onClick = {
                            lifecycleScope.launch {
                                preflightBusy = true
                                lastPreflight = null
                                preflightProgress = "جاري قياس خط الأساس دون VPN…"
                                lastPreflight = SpeedTestEngine.measure(
                                    context = this@MainActivity,
                                    expectedDownloadKbps = 88_000L,
                                    expectedUploadKbps = 88_000L,
                                    verificationProfile = true,
                                ) { phase ->
                                    val label = if (phase == SpeedTestPhase.DOWNLOAD) {
                                        "قياس Download لخط الأساس…"
                                    } else {
                                        "قياس Upload لخط الأساس…"
                                    }
                                    lifecycleScope.launch(Dispatchers.Main.immediate) {
                                        preflightProgress = label
                                    }
                                }
                                preflightBusy = false
                                preflightProgress = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("baseline_preflight_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (preflightBusy) "جاري فحص جاهزية الشبكة…" else "فحص جاهزية خط الأساس")
                    }
                    if (preflightBusy) {
                        Text(preflightProgress ?: "جاري فحص الجاهزية…", color = Cyan, fontSize = 11.sp)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                Button(
                    enabled = !testBusy && !preflightBusy && !verifyBusy && !verificationInProgress,
                    onClick = {
                        lifecycleScope.launch {
                            testBusy = true
                            lastTest = null
                            testProgress = "جاري بدء الفحص…"
                            val measurementStartedAt = android.os.SystemClock.elapsedRealtime()
                            try {
                                // Keep the busy label observable even when a local fixture or
                                // a fast error completes before UIAutomator captures its first
                                // hierarchy. SpeedTestEngine performs network IO on Dispatchers.IO.
                                val result = SpeedTestEngine.measure(this@MainActivity) { phase ->
                                    val label = if (phase == SpeedTestPhase.DOWNLOAD) "جاري قياس Download…" else "جاري قياس Upload…"
                                    lifecycleScope.launch(Dispatchers.Main.immediate) {
                                        if (testBusy) testProgress = label
                                    }
                                }
                                lastTest = result
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                lastTest = SpeedTestResult(
                                    downloadBps = null,
                                    uploadBps = null,
                                    durationMs = android.os.SystemClock.elapsedRealtime() - measurementStartedAt,
                                    error = error.message ?: error.javaClass.simpleName,
                                )
                            } finally {
                                // Even a fast-fail retains a stable loading state for at least
                                // three seconds from click; always update Compose on Main.
                                val elapsed = android.os.SystemClock.elapsedRealtime() - measurementStartedAt
                                val remaining = 3_000L - elapsed
                                if (remaining > 0L) delay(remaining)
                                testBusy = false
                                testProgress = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(if (testBusy) "جاري فحص السرعة…" else "🚀 فحص السرعة")
                }

                Button(
                    enabled = !testBusy && !preflightBusy && !verifyBusy && !verificationInProgress,
                    onClick = {
                        if (!s.permissionGranted) {
                            requestVpnPermission()
                        } else if (!testBusy && !preflightBusy && !verifyBusy && !verificationInProgress) {
                            val selectedDownloadKbps = s.downloadLimitKbps
                            val selectedUploadKbps = s.uploadLimitKbps
                            lifecycleScope.launch {
                                verifyBusy = true
                                // Do not expose a previous run's tagged metrics while the new
                                // baseline/VPN measurements are still in progress.
                                lastVerification = null
                                verifyProgress = "جاري تحضير التحقق…"
                                val verificationStartedAt = System.currentTimeMillis()
                                val loadingStartedAt = android.os.SystemClock.elapsedRealtime()
                                try {
                                    // A fast-fail can otherwise finish before Compose draws the
                                    // loading label. Keep the state observable, then do blocking
                                    // network work away from Main.
                                    delay(2_000L)
                                    val result = withContext(Dispatchers.IO) {
                                        SpeedVerificationEngine.verify(
                                            context = this@MainActivity,
                                            planDownloadKbps = selectedDownloadKbps,
                                            planUploadKbps = selectedUploadKbps,
                                            onProgress = { message ->
                                                lifecycleScope.launch(Dispatchers.Main.immediate) {
                                                    if (verifyBusy) verifyProgress = message
                                                }
                                            },
                                        )
                                    }
                                    lastVerification = result
                                    SpeedVerificationStore.save(this@MainActivity, result)
                                    verificationHistory = SpeedVerificationStore.loadRecent(this@MainActivity, 8)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    val failed = SpeedVerificationEngine.unavailable(
                                        started = verificationStartedAt,
                                        note = "فشل التحقق: ${error.message ?: error.javaClass.simpleName}",
                                        planDownloadKbps = selectedDownloadKbps,
                                        planUploadKbps = selectedUploadKbps,
                                    )
                                    lastVerification = failed
                                    SpeedVerificationStore.save(this@MainActivity, failed)
                                    verificationHistory = SpeedVerificationStore.loadRecent(this@MainActivity, 8)
                                } finally {
                                    // The CI hierarchy dumper polls periodically; preserve a minimum
                                    // visible interval even if the engine returns immediately.
                                    val elapsed = android.os.SystemClock.elapsedRealtime() - loadingStartedAt
                                    val remaining = 2_000L - elapsed
                                    if (remaining > 0L) delay(remaining)
                                    verifyBusy = false
                                    verifyProgress = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("verify_speed_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue2),
                ) {
                    Text(if (verifyBusy) "جاري التحقق من الخطة…" else "✅ تحقق من السرعة")
                }

                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                            runCatching { startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                        } else {
                            SpeedOverlayService.start(this@MainActivity)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("📊 نافذة السرعة الدائمة") }

                if (testBusy) {
                    Text(testProgress ?: "جاري الفحص…", color = Blue, fontSize = 11.sp)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (verifyBusy) {
                    Text(verifyProgress ?: "جاري التحقق…", color = Cyan, fontSize = 11.sp)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            lastTest?.let { result ->
                GlassCard {
                    SectionLabel("نتيجة فحص السرعة", "قياس سرعة الشبكة الفعلية خارج خنق VPN (لاختبار الخطة عبر النفق استخدم تحقق من السرعة)")
                    InfoRow("Download", result.downloadBps?.let(::formatBitRate) ?: "فشل القياس")
                    InfoRow("Upload", result.uploadBps?.let(::formatBitRate) ?: "فشل القياس")
                    InfoRow("المدة", "${result.durationMs / 1000.0} ثانية")
                    result.error?.let { Text("ملاحظة: $it", color = Amber, fontSize = 10.sp) }
                }
            }

            if (BuildConfig.DEBUG) {
                lastPreflight?.let { result ->
                    GlassCard {
                        SectionLabel("نتيجة فحص خط الأساس", "قياس فعلي للتنزيل والرفع دون تشغيل VPN")
                        InfoRow(
                            "Baseline Download",
                            result.downloadBps?.let(::formatBitRate) ?: "فشل القياس",
                            valueTestTag = "baseline_preflight_download_value",
                        )
                        InfoRow(
                            "Baseline Upload",
                            result.uploadBps?.let(::formatBitRate) ?: "فشل القياس",
                            valueTestTag = "baseline_preflight_upload_value",
                        )
                        InfoRow("المدة", "${result.durationMs / 1000.0} ثانية")
                        result.error?.let { Text("ملاحظة: $it", color = Amber, fontSize = 10.sp) }
                    }
                }
            }

            lastVerification?.let { result ->
                GlassCard {
                    SectionLabel("نتيجة التحقق", "الأصلية ← السرعة المحجوزة ← السرعة الفعلية داخل VPN")
                    Text(
                        "الحكم النهائي: " + speedStatusLabel(result.overallStatus),
                        color = speedStatusColor(result.overallStatus),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    SpeedMetricResult("Download", result.download)
                    Spacer(Modifier.height(8.dp))
                    SpeedMetricResult("Upload", result.upload)
                    result.note?.let {
                        Spacer(Modifier.height(5.dp))
                        Text(it, color = TextSecondary, fontSize = 10.sp)
                    }
                }
            }

            if (verificationHistory.isNotEmpty()) {
                GlassCard {
                    SectionLabel("سجل الاعتماد المحلي", "آخر القياسات المحفوظة على الجهاز")
                    verificationHistory.forEach { result ->
                        val plan = result.download.planKbps ?: result.upload.planKbps
                        val accuracy = result.download.accuracyPercent ?: result.upload.accuracyPercent
                        InfoRow(
                            speedStatusLabel(result.overallStatus) + " • " + (plan?.let(::formatPlanBits) ?: "بدون حد"),
                            accuracy?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                        )
                    }
                }
            }

            SpeedPlanUsageHistoryCard(
                records = speedPlanHistory,
                summary = speedPlanSummary,
                exportBusy = exportBusy,
                exportStatus = exportStatus,
                exportIsError = exportIsError,
                controlsEnabled = !testBusy && !preflightBusy && !verifyBusy && !verificationInProgress,
                onExportExcel = { launchSpeedHistoryExcelExport() },
            )

            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💡", fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ملاحظة موثوقية", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "المقارنة تستخدم هامش سماح ±20%. وإذا كانت الشبكة الأصلية أبطأ من الخطة، تكون النتيجة ⚠️ لا يمكن الحكم بدل اتهام الخطة بالفشل.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SpeedPlanUsageHistoryCard(
        records: List<SpeedPlanUsageRecord>,
        summary: SpeedHistoryReportSummary,
        exportBusy: Boolean,
        exportStatus: String?,
        exportIsError: Boolean,
        controlsEnabled: Boolean,
        onExportExcel: () -> Unit,
    ) {
        GlassCard {
            SectionLabel(
                "سجل السرعات واستهلاك الخطط",
                "البيانات الفعلية التي مرّت عبر VPN لكل خيار سرعة مطبق",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill("الفترات: ${summary.totalRecordsCount}", Blue, Modifier.weight(1f))
                InfoPill("الجلسات: ${summary.distinctVpnSessionsCount}", Cyan, Modifier.weight(1f))
                InfoPill("التغييرات: ${summary.planChangesCount}", Purple, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill("↓ ${formatDataBytes(summary.totalDownloadBytes)}", Cyan, Modifier.weight(1f))
                InfoPill("↑ ${formatDataBytes(summary.totalUploadBytes)}", Purple, Modifier.weight(1f))
                InfoPill("الإجمالي ${formatDataBytes(summary.totalBytes)}", Green, Modifier.weight(1f))
            }

            Button(
                enabled = !exportBusy && controlsEnabled,
                onClick = onExportExcel,
                modifier = Modifier.fillMaxWidth().testTag("export_speed_history_excel_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B6B53)),
            ) {
                Text(
                    if (exportBusy) "جاري تصدير سجل السرعات إلى Excel…"
                    else "تصدير سجل السرعات إلى Excel",
                    fontWeight = FontWeight.Bold,
                )
            }

            exportStatus?.let { statusText ->
                Text(
                    text = statusText,
                    color = if (exportIsError) Color(0xFFFF7D88) else Green,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("export_speed_history_status"),
                )
            }

            if (records.isEmpty()) {
                Text(
                    "لا توجد سجلات سرعة محفوظة بعد — يبدأ التسجيل تلقائيًا عند اتصال VPN وتطبيق خيار السرعة.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            } else {
                records.take(10).forEach { record ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0A1426),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StrokeColor.copy(alpha = 0.7f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    record.planOptionLabel,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                                val statusColor = when (record.status) {
                                    SpeedPlanRecordStatus.COMPLETED -> Green
                                    SpeedPlanRecordStatus.ONGOING -> Cyan
                                    SpeedPlanRecordStatus.ABNORMAL_END -> Amber
                                }
                                Text(
                                    record.status.arabicLabel,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                )
                            }
                            Text(
                                "الحد المطبق — تنزيل: ${record.downloadLimitUnitLabel} • رفع: ${record.uploadLimitUnitLabel}",
                                color = TextSecondary,
                                fontSize = 10.sp,
                            )
                            Text(
                                "الفترة: ${SpeedPlanClassifier.formatTimestamp(record.startedAtMillis)} ← " +
                                    (record.endedAtMillis?.let(SpeedPlanClassifier::formatTimestamp) ?: "مستمر") +
                                    " • المدة: ${record.formattedDuration()}",
                                color = TextSecondary,
                                fontSize = 9.5.sp,
                            )
                            Text(
                                "↓ ${formatDataBytes(record.downloadBytes)} (${record.downloadBytes} B) • " +
                                    "↑ ${formatDataBytes(record.uploadBytes)} (${record.uploadBytes} B) • " +
                                    "الإجمالي: ${formatDataBytes(record.totalBytes)}",
                                color = Cyan,
                                fontSize = 10.sp,
                            )
                            Text(
                                "متوسط الفعلي — تنزيل: ${record.formattedAvgDownloadRate()} • رفع: ${record.formattedAvgUploadRate()}",
                                color = TextSecondary,
                                fontSize = 9.5.sp,
                            )
                            if (record.limitExceeded) {
                                Text(
                                    "⚠️ ${record.formattedExceedanceSummary()}",
                                    color = Amber,
                                    fontSize = 9.5.sp,
                                )
                            }
                        }
                    }
                }
                if (records.size > 10) {
                    Text(
                        "يُعرض أحدث 10 سجلات من أصل ${records.size} سجل محفوظ. استخدم «تصدير سجل السرعات إلى Excel» لتصدير السجل الكامل.",
                        color = TextSecondary,
                        fontSize = 9.5.sp,
                    )
                }
            }
        }
    }
    @Composable
    private fun SmartScreen(
        s: Snapshot,
        verificationInProgress: Boolean,
        onOpenSettingsTab: () -> Unit = {},
    ) {
        val statsEnabled by SmartSettings.observeStatsEnabled(this@MainActivity).collectAsStateWithLifecycle()
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

            if (!statsEnabled) {
                GlassCard {
                    Text(
                        "جمع الإحصاءات الاختيارية متوقف حاليًا — لن تُجمع سجلات استهلاك جديدة ما دام الخيار متوقفًا، مع الاحتفاظ بالسجلات التاريخية السابقة واستمرار فرض حصص البيانات.",
                        color = Amber,
                        fontSize = 11.sp,
                    )
                    OutlinedButton(
                        onClick = onOpenSettingsTab,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("إدارة جمع الإحصاءات من صفحة الإعدادات", fontSize = 11.sp)
                    }
                }
            }

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
                    Button(enabled = !verificationInProgress, onClick = {
                        saveLocalLimitsBoth(rec.downloadKbps, rec.uploadKbps)
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
        }
    }

    @Composable
    private fun InternetStatsSettingsCard() {
        val usageAccess = usageAccessState.value
        val statsEnabled by SmartSettings.observeStatsEnabled(this@MainActivity).collectAsStateWithLifecycle()
        GlassCard {
            SectionLabel("إحصاءات الإنترنت", "يوميًا لكل تطبيق مع إجمالي أسبوعي وشهري")
            SettingSwitch("جمع الإحصاءات", "تسجيل استهلاك التطبيقات محليًا", statsEnabled) { enabled ->
                SmartSettings.setStatsEnabled(this@MainActivity, enabled)
                if (enabled) {
                    UsageCollectionScheduler.schedule(this@MainActivity)
                    lifecycleScope.launch(Dispatchers.IO) { UsageCollector.collectToday(this@MainActivity) }
                } else {
                    UsageCollectionScheduler.cancel(this@MainActivity)
                }
            }
            Text(
                "يتحكم هذا المفتاح بجمع وتخزين سجلات استهلاك التطبيقات اليومية وعينات التحليل الذكي الاختيارية. " +
                    "عند إيقافه تتوقف السجلات الاختيارية الجديدة والمهام الدورية المخصصة لها دون حذف السجلات التاريخية، بينما تبقى عدادات فرض حصص البيانات وحظر التطبيقات نشطة.",
                color = TextSecondary,
                fontSize = 10.sp,
            )
            Text(
                if (statsEnabled) {
                    "حالة جمع الإحصاءات: مفعّل — تُحدّث سجلات الاستهلاك الاختيارية ضمن الحدود المعتمدة."
                } else {
                    "حالة جمع الإحصاءات: متوقف — لن تُجمع سجلات اختيارية جديدة ما دام الخيار متوقفًا."
                },
                color = if (statsEnabled) Green else Amber,
                fontSize = 10.sp,
                modifier = Modifier.testTag("stats_collection_status_text"),
            )
            if (!usageAccess) {
                Button(
                    onClick = { startActivity(AppTrafficManager.usageAccessIntent()) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("allow_app_stats_button"),
                ) { Text("السماح بإحصاءات التطبيقات", fontSize = 11.sp) }
            } else {
                Text("وصول NetworkStatsManager مفعّل", color = Green, fontSize = 10.sp)
            }
        }
    }

    @Composable
    private fun GeneralVpnProtectionCard() {
        GlassCard {
            SectionLabel(
                "حظر التطبيقات وحماية VPN",
                "حظر التطبيق المختار مستقل عن إعداد جدار التطبيقات العام",
            )
            Text(
                "يعمل الحظر لكل تطبيق أثناء اتصال SpeedVPN، ولا يلزم تفعيل مفتاح عام. " +
                    "الحماية النظامية الإضافية اختيارية: من إعدادات Android فعّل Always-on VPN وBlock connections without VPN لمنع المرور خارج النفق إذا توقف VPN.",
                color = TextSecondary,
                fontSize = 10.sp,
            )
            OutlinedButton(
                onClick = {
                    runCatching { startActivity(AppTrafficManager.vpnSettingsIntent()) }
                        .onFailure { log("Unable to open Android VPN settings: ${it.message}") }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("open_vpn_settings_button"),
            ) { Text("فتح إعدادات VPN (اختياري)", fontSize = 11.sp) }
        }
    }

    @Composable
    private fun SmartControlsCard() {
        val overlayEnabled by SmartSettings.observeOverlayEnabled(this@MainActivity).collectAsStateWithLifecycle()
        GlassCard {
            SectionLabel("النافذة العائمة", "تحكم سريع بالسرعة أثناء التصفح")
            SettingSwitch("نافذة السرعة العائمة", "تعمل كتحكم عام أو حسب التطبيق الذي فتحت منها", overlayEnabled) {
                if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    runCatching { startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                    return@SettingSwitch
                }
                SmartSettings.setOverlayEnabled(this@MainActivity, it)
                if (it) SpeedOverlayService.start(this@MainActivity) else SpeedOverlayService.stop(this@MainActivity)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun SettingsScreen(s: Snapshot, signedIn: Boolean, onLink: () -> Unit, onSignOut: () -> Unit) {
        Column(
            Modifier.fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag("settings_screen")
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("الإعدادات", "التوافق، إحصاءات الإنترنت، حماية VPN، النافذة العائمة والحساب")
            InternetStatsSettingsCard()
            GeneralVpnProtectionCard()
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
            val safeTotal = if (Long.MAX_VALUE - s.sessionDownloadBytes < s.sessionUploadBytes) Long.MAX_VALUE else s.sessionDownloadBytes + s.sessionUploadBytes
            InfoPill("الإجمالي ${formatDataBytes(safeTotal)}", Blue, Modifier.fillMaxWidth())
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

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun AppsScreen(s: Snapshot) {
        val usageAccess = usageAccessState.value
        val catalogRefreshVersion = appCatalogRefreshTrigger.value
        var isLoadingAppCatalog by remember { mutableStateOf(true) }
        var blockedPackages by remember { mutableStateOf(VpnAppControl.read(this@MainActivity).blockedPackages) }
        var quotaBlockedPackages by remember { mutableStateOf(VpnAppControl.quotaBlockedPackages(this@MainActivity)) }
        var apps by remember { mutableStateOf<List<AppTrafficUsage>>(emptyList()) }
        var policies by remember { mutableStateOf<Map<String, AppPolicy>>(emptyMap()) }
        var quotaPolicies by remember { mutableStateOf<Map<String, List<AppQuotaPolicy>>>(emptyMap()) }
        var appSpeedProfiles by remember { mutableStateOf<Map<String, SpeedProfile>>(emptyMap()) }
        var appQuery by remember { mutableStateOf("") }
        var editingLimitApp by remember { mutableStateOf<AppTrafficUsage?>(null) }
        var editingSpeedApp by remember { mutableStateOf<AppTrafficUsage?>(null) }
        val statsEnabled by SmartSettings.observeStatsEnabled(this@MainActivity).collectAsStateWithLifecycle()

        LaunchedEffect(s.connectedAtMillis, s.status, usageAccess, statsEnabled, catalogRefreshVersion) {
            isLoadingAppCatalog = true
            val installedApps = try {
                withContext(Dispatchers.IO) {
                    AppTrafficManager.installedLaunchableApps(this@MainActivity)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }

            // Publish the complete installed-app list before collecting usage statistics.
            // The catalog and usage refresh share one coroutine so an initial empty catalog
            // can never overwrite the freshly loaded list.
            apps = withContext(Dispatchers.IO) {
                installedApps.map { app ->
                    AppTrafficUsage(
                        packageName = app.packageName,
                        label = app.loadLabel(packageManager).toString(),
                        uid = app.uid,
                        downloadBytes = 0L,
                        uploadBytes = 0L,
                    )
                }
            }
            isLoadingAppCatalog = false

            while (isActive) {
                apps = withContext(Dispatchers.IO) {
                    if (statsEnabled) UsageCollector.collectToday(this@MainActivity)
                    val todayKey = UsageDate.today()
                    val usageByPackage = if (statsEnabled) {
                        UsageRepository.topUsage(this@MainActivity, todayKey, 500)
                            .associateBy { it.packageName }
                    } else emptyMap()
                    installedApps.map { app ->
                        val row = usageByPackage[app.packageName]
                        val live = if (statsEnabled) {
                            AppTrafficManager.liveRelayUsage(app.uid, app.packageName, todayKey)
                        } else {
                            UidTrafficCounters(0L, 0L)
                        }
                        AppTrafficUsage(
                            packageName = app.packageName,
                            label = app.loadLabel(packageManager).toString(),
                            uid = app.uid,
                            downloadBytes = maxOf(row?.downloadBytes ?: 0L, live.downloadBytes),
                            uploadBytes = maxOf(row?.uploadBytes ?: 0L, live.uploadBytes),
                        )
                    }.sortedWith(
                        compareByDescending<AppTrafficUsage> { it.totalBytes }
                            .thenBy { it.label.lowercase(java.util.Locale.getDefault()) }
                    )
                }
                policies = withContext(Dispatchers.IO) {
                    UsageRepository.readPolicies(this@MainActivity).associateBy { it.packageName }
                }
                quotaPolicies = withContext(Dispatchers.IO) {
                    UsageRepository.readQuotaPolicyMap(this@MainActivity)
                }
                appSpeedProfiles = withContext(Dispatchers.IO) {
                    AiRepository.listAppSpeedProfiles(this@MainActivity)
                        .mapNotNull { p -> p.packageName?.let { it to p } }
                        .toMap()
                }
                blockedPackages = withContext(Dispatchers.IO) {
                    VpnAppControl.read(this@MainActivity).blockedPackages
                }
                quotaBlockedPackages = withContext(Dispatchers.IO) {
                    VpnAppControl.quotaBlockedPackages(this@MainActivity)
                }
                if (s.status != VpnStatus.CONNECTED) break
                delay(if (statsEnabled && usageAccess) 5_000L else 15_000L)
            }
        }

        Column(
            Modifier.fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag("apps_screen")
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("التطبيقات", "إدارة التطبيقات المثبتة، الحظر، حدود البيانات، والسرعة الخاصة")

            GlassCard {
                SectionLabel("قائمة التطبيقات", "النسبة محسوبة من إجمالي استهلاك التطبيقات المعروضة")
                if (!statsEnabled) {
                    Text(
                        "جمع الإحصاءات الاختيارية متوقف من الإعدادات — لن تُجمع سجلات استهلاك جديدة، مع استمرار عمل حظر التطبيقات وفرض حصص البيانات.",
                        color = Amber,
                        fontSize = 10.sp,
                    )
                }
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
                if (isLoadingAppCatalog) {
                    Text("جارٍ تحميل التطبيقات المثبّتة...", color = TextSecondary, fontSize = 11.sp)
                } else if (shownApps.isEmpty()) {
                    val emptyMessage = if (appQuery.isBlank()) {
                        "لم يتم العثور على تطبيقات مثبّتة قابلة للتشغيل."
                    } else {
                        "لا توجد تطبيقات تطابق البحث."
                    }
                    Text(emptyMessage, color = TextSecondary, fontSize = 11.sp)
                } else {
                    shownApps.forEach { app ->
                        val policy = policies[app.packageName]
                        val appQuotas = quotaPolicies[app.packageName].orEmpty()
                        val dailyQuota = appQuotas.firstOrNull { it.quotaType == QuotaType.DAILY }
                        val weeklyQuota = appQuotas.firstOrNull { it.quotaType == QuotaType.WEEKLY }
                        val monthlyQuota = appQuotas.firstOrNull { it.quotaType == QuotaType.MONTHLY }
                        val speedProfile = appSpeedProfiles[app.packageName]?.takeIf { it.enabled }
                        val limit = dailyQuota?.limitBytes ?: policy?.dailyLimitBytes
                        val used = maxOf(app.totalBytes, dailyQuota?.usedBytes ?: 0L)
                        val remaining = limit?.let { (it - used).coerceAtLeast(0L) }
                        val isQuotaBlocked = app.packageName in quotaBlockedPackages
                        val isBlocked = app.packageName in blockedPackages
                        val blockStatusLabel = when {
                            isQuotaBlocked -> "محظور لتجاوز الحصة"
                            isBlocked -> "محظور يدويًا"
                            else -> "مسموح"
                        }
                        val blockStatusColor = if (isBlocked || isQuotaBlocked) Color(0xFFFF7D88) else Green
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(app.label, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text(blockStatusLabel, color = blockStatusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "↓ ${formatDataBytes(app.downloadBytes)} • ↑ ${formatDataBytes(app.uploadBytes)} • ${formatDataBytes(used)} • ${usagePercent(used, totalConsumption)}%",
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                )
                                if (limit != null) {
                                    Text(
                                        "حد استخدام التطبيق اليومي ${formatDataBytes(limit)} • المتبقي ${formatDataBytes(remaining ?: 0L)}",
                                        color = if ((remaining ?: 0L) == 0L) Color(0xFFFF7D88) else Amber,
                                        fontSize = 9.sp,
                                    )
                                }
                                if (weeklyQuota != null || monthlyQuota != null) {
                                    Text(
                                        "استخدام التطبيق — أسبوعي: ${weeklyQuota?.let { formatDataBytes(it.limitBytes) } ?: "—"} • شهري: ${monthlyQuota?.let { formatDataBytes(it.limitBytes) } ?: "—"}",
                                        color = TextSecondary,
                                        fontSize = 9.sp,
                                    )
                                }
                                if (speedProfile != null) {
                                    Text(
                                        "سرعة خاصة — تنزيل: ${speedProfile.downloadKbps?.let(::fmt) ?: "بدون حد"} • رفع: ${speedProfile.uploadKbps?.let(::fmt) ?: "بدون حد"}",
                                        color = Blue,
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
                                    checked = isBlocked,
                                    onCheckedChange = { blocked ->
                                        val next = blockedPackages.toMutableSet().apply {
                                            if (blocked) add(app.packageName) else remove(app.packageName)
                                        }.toSet()
                                        blockedPackages = next
                                        saveAppControlSettings(
                                            VpnAppControlSettings(
                                                firewallEnabled = VpnAppControl.read(this@MainActivity).firewallEnabled,
                                                blockedPackages = next,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Text("المعروض: ${shownApps.size} من ${apps.size}", color = TextSecondary, fontSize = 9.sp)
                    Text("التطبيقات تظهر مباشرة، والبحث بالأعلى لتصفية القائمة. عند تعديل الحد يتم تحديث الاستهلاك وإعادة تقييم الحظر فورًا.", color = TextSecondary, fontSize = 9.sp)
                }
            }
        }

        editingLimitApp?.let { app ->
            AppLimitDialog(
                app = app,
                currentPolicies = quotaPolicies[app.packageName].orEmpty(),
                onDismiss = { editingLimitApp = null },
                onSave = { quotaType, bytes ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (quotaType == QuotaType.DAILY) {
                            UsageRepository.setDailyLimitBytes(this@MainActivity, app.packageName, app.label, app.uid, bytes)
                        } else {
                            UsageRepository.setQuotaPolicy(this@MainActivity, app.packageName, app.label, app.uid, quotaType, bytes)
                        }
                        // Always enforce quota limits immediately even when optional statistics collection is off.
                        runCatching { QuotaEnforcementEngine.enforce(this@MainActivity) }
                        runCatching { QuotaWorkScheduler.syncWithConfiguredPolicies(this@MainActivity) }
                        if (statsEnabled) {
                            UsageCollector.collectToday(this@MainActivity)
                        }
                        policies = UsageRepository.readPolicies(this@MainActivity).associateBy { it.packageName }
                        quotaPolicies = UsageRepository.readQuotaPolicyMap(this@MainActivity)
                        blockedPackages = VpnAppControl.read(this@MainActivity).blockedPackages
                        quotaBlockedPackages = VpnAppControl.quotaBlockedPackages(this@MainActivity)
                        if (statsEnabled && usageAccess) {
                            val installed = AppTrafficManager.installedLaunchableApps(this@MainActivity)
                            val usageByPackage = UsageRepository.topUsage(this@MainActivity, UsageDate.today(), 500)
                                .associateBy { it.packageName }
                            apps = installed.map { installedApp ->
                                val row = usageByPackage[installedApp.packageName]
                                AppTrafficUsage(
                                    installedApp.packageName,
                                    installedApp.loadLabel(packageManager).toString(),
                                    installedApp.uid,
                                    row?.downloadBytes ?: 0L,
                                    row?.uploadBytes ?: 0L,
                                )
                            }.sortedWith(
                                compareByDescending<AppTrafficUsage> { it.totalBytes }
                                    .thenBy { it.label.lowercase(java.util.Locale.getDefault()) }
                            )
                        }
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
                        appSpeedProfiles = AiRepository.listAppSpeedProfiles(this@MainActivity)
                            .mapNotNull { p -> p.packageName?.let { it to p } }
                            .toMap()
                    }
                    editingSpeedApp = null
                },
            )
        }
    }

    @Composable
    private fun AppLimitDialog(
        app: AppTrafficUsage,
        currentPolicies: List<AppQuotaPolicy>,
        onDismiss: () -> Unit,
        onSave: (QuotaType, Long?) -> Unit,
    ) {
        var selectedType by remember(currentPolicies) {
            mutableStateOf(currentPolicies.firstOrNull()?.quotaType ?: QuotaType.DAILY)
        }
        val selectedPolicy = currentPolicies.firstOrNull { it.quotaType == selectedType }
        var mbText by remember(selectedType, selectedPolicy?.limitBytes) {
            mutableStateOf(selectedPolicy?.limitBytes?.let {
                String.format(Locale.US, "%.0f", it / 1_000_000.0)
            } ?: "")
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("حد بيانات ${app.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اختر نوع الحد. يمكن للتطبيق نفسه حمل Daily + Weekly + Monthly معًا.", fontSize = 12.sp)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            QuotaType.DAILY to "يومي",
                            QuotaType.WEEKLY to "أسبوعي",
                            QuotaType.MONTHLY to "شهري",
                        ).forEach { (type, label) ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = {
                                    selectedType = type
                                    mbText = currentPolicies.firstOrNull { it.quotaType == type }?.limitBytes?.let {
                                        String.format(Locale.US, "%.0f", it / 1_000_000.0)
                                    } ?: ""
                                },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = mbText,
                        onValueChange = { mbText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("MB") },
                    )
                    val dialogUsedBytes = maxOf(app.totalBytes, selectedPolicy?.usedBytes ?: 0L)
                    Text("الاستهلاك الحالي اليوم: ${formatDataBytes(dialogUsedBytes)}", color = TextSecondary, fontSize = 10.sp)
                    Text("اتركه فارغًا لإلغاء هذا النوع من الحد.", color = TextSecondary, fontSize = 10.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val mb = mbText.toDoubleOrNull()
                    onSave(selectedType, mb?.takeIf { it > 0 }?.let { (it * 1_000_000.0).toLong() })
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

    private fun formatBitRate(bitsPerSecond: Long): String {
        val bps = bitsPerSecond.coerceAtLeast(0L)
        return when {
            bps < 1_000L -> bps.toString() + " bps"
            bps < 1_000_000L -> String.format(Locale.US, "%.0f Kbps", bps / 1_000.0)
            bps < 1_000_000_000L -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
            else -> String.format(Locale.US, "%.2f Gbps", bps / 1_000_000_000.0)
        }
    }

    private fun formatPlanBits(kbps: Long?): String {
        if (kbps == null) return "بدون حد"
        return when {
            kbps < 1_000L -> kbps.toString() + " Kbps"
            kbps < 1_000_000L -> String.format(Locale.US, "%.2f Mbps", kbps / 1_000.0)
            else -> String.format(Locale.US, "%.2f Gbps", kbps / 1_000_000.0)
        }
    }

    private fun speedStatusLabel(status: SpeedVerificationStatus): String = when (status) {
        SpeedVerificationStatus.MATCH -> "✅ السرعة متطابقة"
        SpeedVerificationStatus.MISMATCH -> "❌ السرعة غير متطابقة"
        SpeedVerificationStatus.NOT_VERIFIABLE -> "⚠️ لا يمكن الحكم"
        SpeedVerificationStatus.UNLIMITED_OK -> "✅ لا يظهر سقف واضح"
    }

    private fun speedStatusColor(status: SpeedVerificationStatus): Color = when (status) {
        SpeedVerificationStatus.MATCH, SpeedVerificationStatus.UNLIMITED_OK -> Green
        SpeedVerificationStatus.MISMATCH -> Color(0xFFFF7D88)
        SpeedVerificationStatus.NOT_VERIFIABLE -> Amber
    }

    @Composable
    private fun SpeedMetricResult(title: String, metric: SpeedMetricVerification) {
        val directionTag = if (title == "Download") "download" else "upload"
        Column(
            modifier = Modifier.testTag("verify_${directionTag}_metric"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.testTag("verify_${directionTag}_heading"),
            )
            InfoRow(
                "🌐 سرعة الإنترنت الأصلية",
                metric.baselineBps?.let(::formatBitRate) ?: "فشل القياس",
                valueTestTag = "verify_${directionTag}_baseline_value",
            )
            InfoRow(
                "🔒 السرعة المحجوزة / المحددة",
                formatPlanBits(metric.planKbps),
                valueTestTag = "verify_${directionTag}_plan_value",
            )
            InfoRow(
                "🚀 السرعة الفعلية داخل VPN",
                metric.vpnBps?.let(::formatBitRate) ?: "فشل القياس",
                valueTestTag = "verify_${directionTag}_vpn_value",
            )
            InfoRow(
                "الدقة مقارنة بالخيار",
                metric.accuracyPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                valueTestTag = "verify_${directionTag}_accuracy_value",
            )
            Text(
                speedStatusLabel(metric.status),
                color = speedStatusColor(metric.status),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("verify_${directionTag}_verdict"),
            )
            metric.reason?.let { Text(it, color = TextSecondary, fontSize = 10.sp) }
        }
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
    private fun QuickSpeedCard(s: Snapshot, onOpenSpeedTab: () -> Unit = {}) {
        GlassCard(modifier = Modifier.clickable { onOpenSpeedTab() }) {
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
    private fun PresetGroup(
        label: String,
        values: List<Pair<String, Long>>,
        accent: Color,
        accessibilityPrefix: String,
        enabled: Boolean,
        onApply: (Long?) -> Unit,
    ) {
        val directionTag = if (accessibilityPrefix == "سرعة التحميل") "download" else "upload"
        Column(Modifier.fillMaxWidth()) {
            Text(label, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                values.forEach { (labelText, speedKbps) ->
                    OutlinedButton(
                        enabled = enabled,
                        onClick = { onApply(speedKbps) },
                        shape = RoundedCornerShape(9.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("speed_preset_${directionTag}_${speedKbps}")
                            .semantics {
                                contentDescription = "$accessibilityPrefix: $labelText"
                            },
                    ) {
                        Text(labelText, fontSize = 10.sp, color = TextPrimary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    private fun formatPresetSpeed(kbps: Long): String =
        if (kbps >= 1000L) {
            val mb = kbps / 1000.0
            if (mb == mb.toLong().toDouble()) "${mb.toLong()} MB/s" else String.format(Locale.US, "%.1f MB/s", mb)
        } else "${kbps} KB/s"

    @Composable
    private fun LimitSlider(
        title: String,
        current: Long?,
        enabled: Boolean = true,
        onApply: (Long?) -> Unit,
    ) {
        // 1 KB/s → 100 MB/s on a logarithmic scale. Preset buttons provide exact
        // business-friendly values; the slider remains continuous for fine tuning.
        val minKBps = 1.0
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
            exp(minLog + (maxLog - minLog) * pos.coerceIn(0f, 1f)).roundToLong().coerceIn(1L, 100_000L)
        }
        val kbps: Long? = selectedKBps?.let { it * 8L }
        val directionTag = if (title == "سرعة التحميل") "download" else "upload"

        GlassCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(fmt(kbps), color = Blue, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            PresetGroup("منخفض", slowPresets, Amber, title, enabled, onApply)
            PresetGroup("متوسط", mediumPresets, Blue, title, enabled, onApply)
            PresetGroup("مرتفع", fastPresets, Purple, title, enabled, onApply)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    enabled = enabled,
                    onClick = { onApply(null) },
                    shape = RoundedCornerShape(11.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.45f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("speed_preset_${directionTag}_unlimited")
                        .semantics { contentDescription = "$title: $unlimitedPreset" },
                ) { Text(unlimitedPreset, fontSize = 11.sp, color = TextPrimary) }
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value = pos,
                enabled = enabled,
                onValueChange = { pos = it },
                valueRange = 0f..unlimitedSentinel,
                onValueChangeFinished = { onApply(kbps) },
                colors = SliderDefaults.colors(
                    activeTrackColor = Blue,
                    thumbColor = TextPrimary,
                    inactiveTrackColor = StrokeColor,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 KB/s", color = TextSecondary, fontSize = 10.sp)
                Text("100 MB/s", color = TextSecondary, fontSize = 10.sp)
            }
        }
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
    private fun BottomBar(tab: Int, enabled: Boolean, onTab: (Int) -> Unit) {
        NavigationBar(containerColor = Color(0xFF070D19), tonalElevation = 0.dp) {
            NavigationBarItem(selected = tab == 0, enabled = enabled, onClick = { onTab(0) }, icon = { Text("⌂", fontSize = 20.sp) }, label = { Text("الرئيسية", fontSize = 10.sp) })
            NavigationBarItem(selected = tab == 1, enabled = enabled, onClick = { onTab(1) }, icon = { Text("↯", fontSize = 20.sp) }, label = { Text("السرعة", fontSize = 10.sp) })
            NavigationBarItem(selected = tab == 2, enabled = enabled, onClick = { onTab(2) }, icon = { Text("◉", fontSize = 19.sp) }, label = { Text("التطبيقات", fontSize = 10.sp) })
            NavigationBarItem(selected = tab == 3, enabled = enabled, onClick = { onTab(3) }, icon = { Text("🧠", fontSize = 17.sp) }, label = { Text("الذكي", fontSize = 10.sp) })
            NavigationBarItem(selected = tab == 4, enabled = enabled, onClick = { onTab(4) }, icon = { Text("⚙", fontSize = 19.sp) }, label = { Text("الإعدادات", fontSize = 10.sp) })
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
        if (VpnService.prepare(this) == null) {
            VpnRuntime.update { it.copy(permissionGranted = true) }
            return
        }
        showVpnDisclosure.value = true
    }

    @Composable
    private fun InfoRow(
        label: String,
        value: String,
        labelTestTag: String? = null,
        valueTestTag: String? = null,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = labelTestTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
            Text(
                value,
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = valueTestTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
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
