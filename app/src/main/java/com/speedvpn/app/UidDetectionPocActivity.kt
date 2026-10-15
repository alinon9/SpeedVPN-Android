package com.speedvpn.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persisted diagnostic mode; deliberately local to the isolated POC APK. */
internal object UidPocSettings {
    const val PREFS = "uid_detection_poc_settings"
    const val KEY_DISABLE_JNI_HOOK = "disable_jni_hook"
}

/** Launcher activity used only by the isolated UID diagnostic APK on this POC branch. */
class UidDetectionPocActivity : ComponentActivity() {
    private var vpnStartRequested = false
    private val startupError = mutableStateOf<String?>(null)
    private val hookDisabled = mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        UidPocDiagnostics.log(this, "Notification permission result=$granted")
    }

    private val exportLogDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> exportDiagnostic(uri, crash = false) }

    private val exportCrashDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> exportDiagnostic(uri, crash = true) }

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        UidPocDiagnostics.log(this, "VPN consent activity returned; API=${Build.VERSION.SDK_INT}")
        runCatching {
            if (VpnService.prepare(this) == null) {
                startVpn()
            } else {
                vpnStartRequested = false
                startupError.value = "لم يتم منح إذن VPN. لم يُستدعَ native hook."
                Toast.makeText(this, "لم يتم منح إذن VPN. يمكنك المحاولة مجددًا.", Toast.LENGTH_LONG).show()
            }
        }.onFailure {
            vpnStartRequested = false
            startupError.value = "تعذر فحص إذن VPN: ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
            UidPocDiagnostics.error(this, "VPN consent result handling failed", it)
        }
    }

    private val createExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        if (uri != null) {
            val csv = UidDetectionPocResultStore.exportCsv()
            lifecycleScope.launch {
                val exportError = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("تعذر فتح الملف للتصدير")
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                    }.exceptionOrNull()
                }
                Toast.makeText(
                    this@UidDetectionPocActivity,
                    if (exportError == null) "تم تصدير سجل UID بنجاح" else "فشل التصدير: ${exportError.message}",
                    Toast.LENGTH_LONG,
                ).show()
                UidPocDiagnostics.log(this@UidDetectionPocActivity, "CSV export succeeded=${exportError == null}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hookDisabled.value = getSharedPreferences(UidPocSettings.PREFS, MODE_PRIVATE)
            .getBoolean(UidPocSettings.KEY_DISABLE_JNI_HOOK, false)
        UidPocDiagnostics.prepareNativeCrashHandlerStorage(this)
        UidPocDiagnostics.log(
            this,
            "UidDetectionPocActivity.onCreate; API=${Build.VERSION.SDK_INT}; " +
                "supported=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q}; " +
                "abi=${Build.SUPPORTED_ABIS.joinToString(",")}",
        )
        setContent {
            val records by UidDetectionPocResultStore.records.collectAsStateWithLifecycle()
            val recording by UidDetectionPocResultStore.recording.collectAsStateWithLifecycle()
            val vpnState by VpnRuntime.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF080E1A),
                        contentColor = Color(0xFFF2F6FF),
                    ) {
                        UidDetectionPocScreen(
                            records = records,
                            recording = recording,
                            vpnStatus = vpnState.status.name,
                            vpnError = vpnState.lastError,
                            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                            startupError = startupError.value,
                            diagnosticPaths = UidPocDiagnostics.pathsText(this@UidDetectionPocActivity),
                            hookDisabled = hookDisabled.value,
                            onToggleHook = { disabled ->
                                hookDisabled.value = disabled
                                getSharedPreferences(UidPocSettings.PREFS, MODE_PRIVATE).edit()
                                    .putBoolean(UidPocSettings.KEY_DISABLE_JNI_HOOK, disabled)
                                    .apply()
                                UidPocDiagnostics.log(
                                    this@UidDetectionPocActivity,
                                    "JNI_HOOK_MODE disabled=$disabled; applies to next native start",
                                )
                                if (VpnRuntime.state.value.status == VpnStatus.CONNECTED) {
                                    UidPocDiagnostics.log(this@UidDetectionPocActivity, "Restarting VPN to apply JNI hook test mode")
                                    SpeedVpnService.restartForSettings(this@UidDetectionPocActivity)
                                }
                            },
                            onStartVpn = { ensureVpnConnected() },
                            onExportLog = { exportLogDocument.launch("SpeedVPN-POC-Log.txt") },
                            onExportCrash = { collectCrashAndExport() },
                            onToggleRecording = {
                                if (recording) {
                                    UidDetectionPocResultStore.stopRecording()
                                } else {
                                    UidDetectionPocResultStore.startRecording()
                                    if (VpnRuntime.state.value.status != VpnStatus.CONNECTED) ensureVpnConnected()
                                }
                            },
                            onExport = { createExport.launch("uid-detection-results.csv") },
                            onClear = { UidDetectionPocResultStore.clear() },
                        )
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            window.decorView.post {
                runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    .onFailure { UidPocDiagnostics.error(this, "Unable to request notification permission", it) }
            }
        }
        UidPocDiagnostics.log(this, "Activity UI installed; native tunnel was not started automatically")
    }

    override fun onResume() {
        super.onResume()
        UidPocDiagnostics.log(
            this,
            "UidDetectionPocActivity.onResume; API=${Build.VERSION.SDK_INT}; vpn=${VpnRuntime.state.value.status}",
        )
    }

    private fun ensureVpnConnected() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startupError.value = "هذا الاختبار يتطلب Android 10 أو أحدث (API 29+). لم يتم تشغيل VPN أو native."
            UidPocDiagnostics.log(this, "Native start blocked because Android API < 29")
            return
        }
        if (vpnStartRequested) {
            UidPocDiagnostics.log(this, "VPN start request ignored: a consent/start request is already active")
            return
        }
        vpnStartRequested = true
        startupError.value = null
        UidPocDiagnostics.log(this, "User explicitly requested VPN/native start")
        runCatching {
            val consentIntent = VpnService.prepare(this)
            if (consentIntent != null) {
                UidPocDiagnostics.log(this, "Launching Android VPN consent screen")
                vpnConsent.launch(consentIntent)
            } else {
                startVpn()
            }
        }.onFailure {
            vpnStartRequested = false
            startupError.value = "فشل تجهيز VPN: ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
            UidPocDiagnostics.error(this, "ensureVpnConnected failed before native startup", it)
        }
    }

    private fun startVpn() {
        UidPocDiagnostics.log(this, "Calling SpeedVpnService.start; native start will occur asynchronously")
        runCatching { SpeedVpnService.start(this) }
            .onSuccess {
                Toast.makeText(this, "تم طلب تشغيل VPN. راقب الحالة والسجل.", Toast.LENGTH_SHORT).show()
                UidPocDiagnostics.log(this, "SpeedVpnService.start request accepted")
            }
            .onFailure {
                vpnStartRequested = false
                startupError.value = "تعذر تشغيل خدمة VPN: ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                UidPocDiagnostics.error(this, "SpeedVpnService.start threw before service startup", it)
            }
    }

    private fun collectCrashAndExport() {
        UidPocDiagnostics.log(this, "Crash export requested; attempting logcat crash buffer and tombstones")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                UidPocDiagnostics.captureCrashReport(this@UidDetectionPocActivity, hookDisabled.value)
            }
            UidPocDiagnostics.log(
                this@UidDetectionPocActivity,
                "Crash capture saved locally; bytes=${result.toByteArray(Charsets.UTF_8).size}",
            )
            Toast.makeText(
                this@UidDetectionPocActivity,
                "تم حفظ Crash.txt داخل مساحة التطبيق. اختر موقعًا لتصدير نسخة.",
                Toast.LENGTH_LONG,
            ).show()
            exportCrashDocument.launch("Crash.txt")
        }
    }

    private fun exportDiagnostic(uri: Uri?, crash: Boolean) {
        if (uri == null) return
        lifecycleScope.launch {
            val exportError = withContext(Dispatchers.IO) {
                runCatching {
                    val content = if (crash) {
                        UidPocDiagnostics.readCrash(this@UidDetectionPocActivity)
                    } else {
                        UidPocDiagnostics.readLog(this@UidDetectionPocActivity)
                    }
                    if (crash && content.isBlank()) throw IllegalStateException("لا يوجد ملف crash محفوظ بعد")
                    val stream = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("تعذر فتح ملف التصدير")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                }.exceptionOrNull()
            }
            Toast.makeText(
                this@UidDetectionPocActivity,
                when {
                    exportError == null -> "تم تصدير الملف. أرفقه في المحادثة لإرساله."
                    else -> "فشل التصدير: ${exportError.message.orEmpty()}"
                },
                Toast.LENGTH_LONG,
            ).show()
            UidPocDiagnostics.log(
                this@UidDetectionPocActivity,
                "Diagnostic export requested; crash=$crash; succeeded=${exportError == null}",
            )
        }
    }
}

@Composable
private fun UidDetectionPocScreen(
    records: List<UidDetectionRecord>,
    recording: Boolean,
    vpnStatus: String,
    vpnError: String?,
    supported: Boolean,
    startupError: String?,
    diagnosticPaths: String,
    hookDisabled: Boolean,
    onToggleHook: (Boolean) -> Unit,
    onStartVpn: () -> Unit,
    onExportLog: () -> Unit,
    onExportCrash: () -> Unit,
    onToggleRecording: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val matchCount = records.count { it.status == "MATCH" }
    val invalidCount = records.count { it.status == "INVALID" }
    val exceptionCount = records.count { it.status == "EXCEPTION" }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF080E1A)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("UID Detection POC", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("تشخيص استخراج UID من تدفقات TCP/UDP — وضع قراءة فقط", color = Color(0xFF9FB2D0))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121D30)),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("حالة VPN: $vpnStatus", fontWeight = FontWeight.SemiBold)
                Text(
                    if (recording) "التسجيل نشط" else "التسجيل متوقف",
                    color = if (recording) Color(0xFF58E0B5) else Color(0xFFFFC857),
                )
                Text("MATCH: $matchCount   •   INVALID: $invalidCount   •   EXCEPTION: $exceptionCount")
                Text("المعروض: ${records.size} تدفق (أقصى سجل محفوظ: 1000)", color = Color(0xFF9FB2D0), fontSize = 12.sp)
            }
        }

        if (!supported) {
            Text(
                "غير مدعوم: يتطلب استخراج UID واجهة Android 10 أو أحدث (API 29+). لن يتم تشغيل VPN أو native على هذا الجهاز.",
                color = Color(0xFFFFC857),
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Button(modifier = Modifier.fillMaxWidth(), onClick = onStartVpn) {
                Text("بدء اختبار VPN وNative يدويًا")
            }
            Text(
                "تظهر هذه الشاشة أولًا. لن يبدأ النفق تلقائيًا عند فتح التطبيق.",
                color = Color(0xFF9FB2D0),
                fontSize = 12.sp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("تعطيل JNI hook", fontWeight = FontWeight.SemiBold)
                Text(
                    if (hookDisabled) "لن يُسجّل callback؛ النفق الأصلي يستمر" else "سيُفعّل callback التشخيصي عند بدء النفق",
                    color = Color(0xFF9FB2D0),
                    fontSize = 11.sp,
                )
            }
            Switch(checked = hookDisabled, onCheckedChange = onToggleHook)
        }
        startupError?.let {
            Text("خطأ بدء التشغيل: $it", color = Color(0xFFFF7D8A), fontWeight = FontWeight.SemiBold)
        }
        vpnError?.takeIf { it.isNotBlank() }?.let {
            Text("خطأ من خدمة VPN/Native: $it", color = Color(0xFFFF7D8A), fontWeight = FontWeight.SemiBold)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("ملفات التشخيص", fontWeight = FontWeight.SemiBold)
                Text(diagnosticPaths, color = Color(0xFF9FB2D0), fontSize = 10.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = onExportLog) { Text("تصدير Log") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onExportCrash,
                    ) { Text("تصدير Crash") }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onToggleRecording,
                enabled = supported,
            ) { Text(if (recording) "إيقاف التسجيل" else "ابدأ التسجيل") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onExport,
                enabled = records.isNotEmpty(),
            ) { Text("تصدير النتائج") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClear,
            ) { Text("مسح السجل") }
        }
        Spacer(Modifier.height(2.dp))
        if (records.isEmpty()) {
            Text(
                "لا توجد نتائج بعد. بعد اتصال VPN اضغط «ابدأ التسجيل»، ثم افتح Chrome وWhatsApp وYouTube لتوليد حركة مرور.",
                color = Color(0xFFB9C7DB),
                fontSize = 14.sp,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(records.asReversed(), key = { it.id }) { record ->
                val statusColor = when (record.status) {
                    "MATCH" -> Color(0xFF58E0B5)
                    "INVALID" -> Color(0xFFFFC857)
                    else -> Color(0xFFFF7D8A)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF121D30)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(record.packageName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("البروتوكول: ${record.protocol}   •   UID: ${record.uid?.toString() ?: "—"}")
                        Text(
                            "${record.srcIp}:${record.srcPort}  →  ${record.dstIp}:${record.dstPort}",
                            fontSize = 12.sp,
                            color = Color(0xFFB9C7DB),
                        )
                        Text("الحالة: ${record.status}", color = statusColor, fontWeight = FontWeight.SemiBold)
                        if (record.details.isNotBlank()) {
                            Text(record.details, fontSize = 11.sp, color = Color(0xFF9FB2D0))
                        }
                    }
                }
            }
        }
    }
}
