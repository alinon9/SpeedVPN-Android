package com.speedvpn.app

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = VpnService.prepare(this) == null
        log(if (granted) "VPN permission granted" else "VPN permission denied")
        VpnRuntime.update { it.copy(permissionGranted = granted, lastError = if (granted) it.lastError else "VPN permission denied") }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        loadLocalLimits()
        if (Auth.isSignedIn(this)) AgentService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var signedIn by remember { mutableStateOf(Auth.isSignedIn(this)) }
                    var showLogin by remember { mutableStateOf(false) }
                    if (showLogin && !signedIn) LoginScreen(onDone = { signedIn = true; showLogin = false }, onCancel = { showLogin = false })
                    else StatusScreen(signedIn = signedIn, onLink = { showLogin = true }, onSignOut = { signedIn = false })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        VpnRuntime.update { it.copy(permissionGranted = VpnService.prepare(this) == null) }
    }

    private fun limitPrefs() = getSharedPreferences("local_limits", MODE_PRIVATE)

    /** Local limits work without any account: saved on the phone and applied at startup. */
    private fun loadLocalLimits() {
        val p = limitPrefs()
        SpeedLimiter.setDownloadKbps(p.getLong("dl", 0).takeIf { it > 0 })
        SpeedLimiter.setUploadKbps(p.getLong("ul", 0).takeIf { it > 0 })
    }

    private fun saveLocalLimit(download: Boolean, kbps: Long?) {
        limitPrefs().edit().putLong(if (download) "dl" else "ul", kbps ?: 0).apply()
        if (download) SpeedLimiter.setDownloadKbps(kbps) else SpeedLimiter.setUploadKbps(kbps)
    }

    private fun fmt(kbps: Long?) = when {
        kbps == null -> "بدون حد"
        kbps < 1000 -> "$kbps Kbps"
        else -> String.format("%.1f Mbps", kbps / 1000.0)
    }

    /** Slider 100 Kbps..100 Mbps; far right = unlimited. */
    @Composable
    private fun LimitSlider(title: String, current: Long?, onApply: (Long?) -> Unit) {
        var pos by remember(current) { mutableStateOf(((current ?: 100_000L).coerceIn(100, 100_000)) / 1000f) }
        val unlimited = pos >= 100f
        val kbps: Long? = if (unlimited) null else (pos * 1000).toLong().coerceAtLeast(100)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(fmt(kbps), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Slider(value = pos, onValueChange = { pos = it }, valueRange = 0.1f..100f,
                    onValueChangeFinished = { onApply(kbps) })
                Text("اسحب لأقصى اليمين = بدون حد", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else VpnRuntime.update { it.copy(permissionGranted = true) }
    }

    @Composable
    private fun LoginScreen(onDone: () -> Unit, onCancel: () -> Unit) {
        var email by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SpeedVPN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("اختياري: سجّل الدخول بنفس حساب الموقع لتتحكم بالجوال من الموقع")
            OutlinedTextField(email, { email = it }, label = { Text("البريد") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && email.isNotBlank() && pass.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = null
                lifecycleScope.launch {
                    try {
                        Auth.signIn(this@MainActivity, email.trim(), pass)
                        AgentService.start(this@MainActivity)
                        onDone()
                    } catch (e: Exception) { error = e.message } finally { busy = false }
                }
            }) { Text("دخول") }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("رجوع") }
        }
    }

    @Composable
    private fun StatusScreen(signedIn: Boolean, onLink: () -> Unit, onSignOut: () -> Unit) {
        val s by VpnRuntime.state.collectAsStateWithLifecycle()
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SpeedVPN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(if (signedIn) "مربوط بالموقع: ${Auth.email(this@MainActivity) ?: ""}" else "وضع محلي — بدون حساب")
            if (!s.permissionGranted) {
                Button(onClick = { requestVpnPermission() }, modifier = Modifier.fillMaxWidth()) { Text("منح إذن VPN") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = s.permissionGranted && s.status != VpnStatus.CONNECTED && s.status != VpnStatus.CONNECTING,
                    onClick = { SpeedVpnService.start(this@MainActivity) }) { Text("اتصال") }
                OutlinedButton(enabled = s.status == VpnStatus.CONNECTED,
                    onClick = { SpeedVpnService.stop(this@MainActivity) }) { Text("فصل") }
            }
            LimitSlider("سرعة التحميل", s.downloadLimitKbps) { saveLocalLimit(true, it) }
            LimitSlider("سرعة الرفع", s.uploadLimitKbps) { saveLocalLimit(false, it) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Debug", fontWeight = FontWeight.Bold)
                    InfoRow("VPN Permission", if (s.permissionGranted) "granted" else "not granted")
                    InfoRow("Android Client Status", if (s.agentOnline) "online (dashboard linked)" else "offline")
                    InfoRow("VPN Service Status", if (s.serviceRunning) "running" else "stopped")
                    InfoRow("VPN Status", s.status.name)
                    InfoRow("Tunnel Status", s.tunnel)
                    InfoRow("Server", "Local (no remote server)")
                    InfoRow("Download limit", s.downloadLimitKbps?.let { "$it Kbps" } ?: "unlimited")
                    InfoRow("Upload limit", s.uploadLimitKbps?.let { "$it Kbps" } ?: "unlimited")
                    InfoRow("Download now", "${s.downloadBps / 1000} Kbps")
                    InfoRow("Upload now", "${s.uploadBps / 1000} Kbps")
                    InfoRow("Last Command", s.lastCommand ?: "—")
                    InfoRow("Last Response", s.lastResponse ?: "—")
                    InfoRow("Last Error", s.lastError ?: "—")
                }
            }
            if (signedIn) TextButton(onClick = {
                AgentService.stop(this@MainActivity)
                Auth.signOut(this@MainActivity); onSignOut()
            }) { Text("فك الربط بالموقع") }
            else OutlinedButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) { Text("ربط بالموقع (اختياري)") }
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
