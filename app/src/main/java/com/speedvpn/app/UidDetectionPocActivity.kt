package com.speedvpn.app

import android.net.VpnService
import android.net.Uri
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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

/** Launcher activity used only by the isolated UID diagnostic APK on this POC branch. */
class UidDetectionPocActivity : ComponentActivity() {
    private var vpnStartRequested = false

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (VpnService.prepare(this) == null) {
            startVpn()
        } else {
            vpnStartRequested = false
            Toast.makeText(this, "لم يتم منح إذن VPN. يمكنك المحاولة مجددًا.", Toast.LENGTH_LONG).show()
        }
    }

    private val createExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        if (uri != null) {
            val csv = UidDetectionPocResultStore.exportCsv()
            lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = contentResolver.openOutputStream(uri)
                            ?: error("تعذر فتح الملف للتصدير")
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                    }.exceptionOrNull()
                }
                Toast.makeText(
                    this@UidDetectionPocActivity,
                    if (error == null) "تم تصدير سجل UID بنجاح" else "فشل التصدير: ${error.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            onToggleRecording = {
                                if (recording) {
                                    UidDetectionPocResultStore.stopRecording()
                                } else {
                                    UidDetectionPocResultStore.startRecording()
                                    ensureVpnConnected()
                                }
                            },
                            onExport = { createExport.launch("uid-detection-results.csv") },
                            onClear = { UidDetectionPocResultStore.clear() },
                        )
                    }
                }
            }
        }

        // A first launch requests Android's VPN consent once and starts the local tunnel
        // automatically after consent is granted. UID capture remains opt-in via the button.
        ensureVpnConnected()
    }

    private fun ensureVpnConnected() {
        if (vpnStartRequested) return
        vpnStartRequested = true
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnConsent.launch(consentIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        SpeedVpnService.start(this)
        Toast.makeText(this, "تم طلب تشغيل SpeedVPN", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun UidDetectionPocScreen(
    records: List<UidDetectionRecord>,
    recording: Boolean,
    vpnStatus: String,
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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onToggleRecording,
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
