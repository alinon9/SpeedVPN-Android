package com.speedvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import hev.htproxy.TProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Local speed-shaping VPN:
 * Android VpnService -> TUN -> hev tun2socks -> authenticated local SOCKS5
 * -> protected outbound sockets -> current physical network.
 */
class SpeedVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.speedvpn.START"
        const val ACTION_STOP = "com.speedvpn.STOP"
        const val ACTION_RESET_STATS = "com.speedvpn.RESET_STATS"
        const val ACTION_RESTART_FOR_SETTINGS = "com.speedvpn.RESTART_FOR_SETTINGS"
        private const val EXTRA_EXPECTED_GENERATION = "expected_generation"
        private const val EXTRA_START_REQUEST_ID = "start_request_id"
        private const val NOTIF_ID = 1
        private const val HEALTH_INTERVAL_MS = 30_000L
        private const val UPSTREAM_PROBE_TIMEOUT_MS = 3_000
        private const val UPSTREAM_PROBE_IP_1 = "1.1.1.1"
        private const val UPSTREAM_PROBE_IP_2 = "1.0.0.1"
        private const val UPSTREAM_PROBE_PORT = 443
        private const val TUN_V4 = "198.18.0.1"
        private const val TUN_V6 = "fc00::1"
        private const val MAP_DNS_V4 = "198.18.0.2"
        private const val MAP_DNS_FAKE_NETWORK = "100.64.0.0"
        private const val MAP_DNS_FAKE_NETMASK = "255.192.0.0"
        // hev-socks5-tunnel exposes process-wide/static native lifecycle calls.
        // A Service instance must therefore not own an instance-local native lock.
        // The generation token prevents teardown from an older Service instance from
        // stopping a newer instance's native engine.
        private val processNativeLifecycleLock = Any()
        // One logical VPN generation is shared across Service instances. Starting a
        // newer instance immediately invalidates older instance work, even before the
        // newer instance reaches native startup.
        private val nextServiceGeneration = AtomicLong(0L)
        private val activeServiceGeneration = AtomicLong(0L)
        private val nativeOwnerGeneration = AtomicLong(0L)
        private val lastStartedRequestId = AtomicReference<String?>(null)

        fun start(ctx: Context, requestId: String? = null) =
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, SpeedVpnService::class.java).setAction(ACTION_START).apply {
                    if (!requestId.isNullOrBlank()) putExtra(EXTRA_START_REQUEST_ID, requestId)
                },
            )

        fun resetTraffic(ctx: Context) {
            ctx.startService(Intent(ctx, SpeedVpnService::class.java).setAction(ACTION_RESET_STATS))
        }

        fun restartForSettings(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, SpeedVpnService::class.java).setAction(ACTION_RESTART_FOR_SETTINGS),
            )
        }

        fun stop(ctx: Context, expectedGeneration: Long? = null) {
            // Invalidate a connection attempt immediately, before the coroutine
            // waiting for the state mutex gets its turn. An optional generation
            // guard prevents a stale AgentService command from stopping a newer VPN.
            val intent = Intent(ctx, SpeedVpnService::class.java).setAction(ACTION_STOP)
            if (expectedGeneration != null && expectedGeneration > 0L) {
                intent.putExtra(EXTRA_EXPECTED_GENERATION, expectedGeneration)
            }
            ctx.startService(intent)
        }

        /** Process-wide generation currently owning the VPN service, or 0 if none. */
        fun currentGeneration(): Long = activeServiceGeneration.get()

        /** Request token attached to the generation that last began CONNECT. */
        fun currentStartRequestId(): String? = lastStartedRequestId.get()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val resourceLifecycleLock = Any()
    private val sessionId = AtomicLong(0L)
    private val destroying = AtomicBoolean(false)

    // Identifies the process-wide logical session generation owned by this service
    // instance. It is checked against activeServiceGeneration before any data-plane
    // work can continue after another Service instance takes over.
    @Volatile private var serviceGeneration: Long = 0L
    // Non-zero only after this generation has successfully started Native.
    @Volatile private var nativeGeneration: Long = 0L
    @Volatile private var reconnectAfterDisconnect = false
    @Volatile private var tunnelConfigFile: File? = null
    @Volatile private var requestedStartRequestId: String? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var socks: Socks5Server? = null
    @Volatile private var engineRunning = false
    private val currentPhysicalNetwork = AtomicReference<Network?>(null)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var meter: Job? = null
    private var healthMonitor: Job? = null
    private var quotaMonitor: Job? = null
    private val upstreamProbeDnsExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vpn-upstream-dns").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        registerPhysicalNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (destroying.get()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_RESET_STATS -> {
                synchronized(processNativeLifecycleLock) {
                    if (serviceGeneration > 0L && activeServiceGeneration.get() == serviceGeneration) {
                        SpeedLimiter.resetSessionCounters(serviceGeneration)
                        VpnRuntime.update { it.copy(sessionDownloadBytes = 0L, sessionUploadBytes = 0L) }
                    }
                }
            }
            ACTION_RESTART_FOR_SETTINGS -> {
                reconnectAfterDisconnect = true
                goForeground()
                scope.launch { disconnect() }
            }
            ACTION_STOP -> {
                val expectedGeneration = intent?.getLongExtra(EXTRA_EXPECTED_GENERATION, 0L) ?: 0L
                // The generation check must be repeated while holding stateMutex.
                // Checking only here races with a newer CONNECT that can acquire
                // stateMutex and replace serviceGeneration before disconnect() runs.
                scope.launch { disconnect(expectedGeneration.takeIf { it > 0L }) }
            }
            else -> {
                requestedStartRequestId = intent?.getStringExtra(EXTRA_START_REQUEST_ID)
                goForeground()
                scope.launch { connect() }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, "vpn")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("SpeedVPN")
            .setContentText("التحكم بسرعة الإنترنت يعمل")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private suspend fun connect() = stateMutex.withLock {
        if (destroying.get()) return@withLock

        val currentStatus = VpnRuntime.state.value.status
        if (currentStatus == VpnStatus.DISCONNECTING) {
            // Do not lose a user/agent CONNECT that races with an in-flight disconnect.
            // The disconnect path will launch a fresh connect after cleanup completes.
            reconnectAfterDisconnect = true
            return@withLock
        }
        if (tun != null || engineRunning) return@withLock

        val mySession = sessionId.incrementAndGet()
        val myServiceGeneration = synchronized(processNativeLifecycleLock) {
            if (destroying.get()) return@withLock
            val generation = nextServiceGeneration.incrementAndGet()
            serviceGeneration = generation
            lastStartedRequestId.set(requestedStartRequestId)
            requestedStartRequestId = null
            activeServiceGeneration.set(generation)
            VpnRuntime.update {
                it.copy(status = VpnStatus.CONNECTING, serviceRunning = true, lastError = null, tunnel = "starting")
            }
            generation
        }
        log("Starting VPN session $mySession")

        if (prepare(this) != null) {
            failLocked("VPN permission denied")
            return@withLock
        }

        val compatibility = VpnSettings.read(this)
        val firewall = VpnAppControl.read(this)
        val relay = try {
            Socks5Server(
                generation = myServiceGeneration,
                protectTcp = { protect(it) },
                protectUdp = { protect(it) },
                currentNetwork = { findUnderlyingNetwork() },
                ipv6Enabled = compatibility.ipv6Enabled,
            ).also { it.start() }
        } catch (e: Exception) {
            failLocked("VPN relay failed to start", e)
            return@withLock
        }
        val relayPublished = synchronized(resourceLifecycleLock) {
            if (!sessionIsCurrent(mySession)) {
                false
            } else {
                socks = relay
                true
            }
        }
        if (!relayPublished) {
            runCatching { relay.stop() }
            return@withLock
        }

        val physicalNetwork = findUnderlyingNetwork()
        // DNS is terminated inside the TUN by hev MapDNS. Physical DNS changes must not
        // trigger a VPN generation rebuild because the VPN-facing resolver is fixed.
        val dnsServers = listOf(InetAddress.getByName(MAP_DNS_V4))

        val pfd = try {
            val builder = Builder()
                .setSession("SpeedVPN")
                .setMtu(compatibility.mtu)
                .setUnderlyingNetworks(physicalNetwork?.let { arrayOf(it) } ?: emptyArray())
                .addAddress(TUN_V4, 32)
                .addRoute("0.0.0.0", 0)
                // Feed Android the actual DNS servers from the current physical link.
                // No public resolver is hard-coded.
                .apply { dnsServers.forEach { addDnsServer(it) } }
                .apply {
                    if (compatibility.ipv6Enabled) {
                        addAddress(TUN_V6, 128)
                        addRoute("::", 0)
                    }
                }
                .apply {
                    // Quota-blocked apps are enforced independently from the manual App Firewall.
                    val quotaBlocked = VpnAppControl.quotaBlockedPackages(this@SpeedVpnService)
                    val manualBlocked = if (firewall.firewallEnabled) {
                        firewall.blockedPackages - quotaBlocked
                    } else {
                        emptySet()
                    }
                    val effectiveBlocked = manualBlocked + quotaBlocked

                    val firewallRequested = firewall.firewallEnabled && manualBlocked.isNotEmpty()
                    val quotaEnforcementRequested = quotaBlocked.isNotEmpty()
                    val needsAllowList = firewallRequested || quotaEnforcementRequested

                    val lockdownReady =
                        Build.VERSION.SDK_INT >= 29 &&
                            runCatching { isLockdownEnabled }.getOrDefault(false)

                    if (needsAllowList && !lockdownReady) {
                        val message = when {
                            quotaEnforcementRequested && firewallRequested ->
                                "Quota enforcement and app firewall require Android VPN Lockdown. " +
                                    "Enable Always-on VPN and 'Block connections without VPN' in Android VPN settings."
                            quotaEnforcementRequested ->
                                "Quota enforcement requires Android VPN Lockdown. " +
                                    "Enable Always-on VPN and 'Block connections without VPN' in Android VPN settings."
                            else ->
                                "App firewall requires Android VPN Lockdown. " +
                                    "Enable Always-on VPN and 'Block connections without VPN' in Android VPN settings."
                        }
                        throw IllegalStateException(message)
                    }

                    if (needsAllowList) {
                        val launchable =
                            AppTrafficManager.installedLaunchableApps(this@SpeedVpnService)
                        var allowed = 0

                        launchable.forEach { app ->
                            val pkg = app.packageName

                            if (pkg == packageName || pkg in effectiveBlocked) {
                                return@forEach
                            }

                            runCatching {
                                addAllowedApplication(pkg)
                            }.onSuccess {
                                allowed++
                            }.onFailure {
                                log(
                                    "Skipping unavailable allowed package $pkg: ${it.message}"
                                )
                            }
                        }

                        if (allowed == 0) {
                            throw IllegalStateException(
                                "No valid applications are available for the VPN allow-list"
                            )
                        }

                        val reason = when {
                            quotaEnforcementRequested && firewallRequested -> "quota+firewall"
                            quotaEnforcementRequested -> "quota"
                            else -> "firewall"
                        }
                        log(
                            "VPN allow-list active ($reason): " +
                                "$allowed apps allowed; ${effectiveBlocked.size} blocked"
                        )
                    }

                    // SpeedVPN itself must never be routed back through its own TUN.
                    // Keep SpeedVPN traffic routable through the TUN so the built-in
                    // speed test measures the real VPN path and is subject to SpeedLimiter.
                    // VPN control/native sockets are protected before connecting, so this
                    // does not create a routing loop.
                }

            builder.establish()
        } catch (e: IllegalStateException) {
            failLocked(e.message ?: "Unable to establish VPN interface", e)
            return@withLock
        } catch (e: Exception) {
            failLocked("Unable to establish VPN interface", e)
            return@withLock
        } ?: run {
            failLocked("VPN interface establishment returned null")
            return@withLock
        }
        val tunPublished = synchronized(resourceLifecycleLock) {
            if (!sessionIsCurrent(mySession)) {
                false
            } else {
                tun = pfd
                true
            }
        }
        if (!tunPublished) {
            runCatching { pfd.close() }
            cleanupLocked()
            return@withLock
        }

        val conf = try {
            writeTunnelConfig(relay, compatibility)
        } catch (e: Exception) {
            if (sessionIsCurrent(mySession)) failLocked("Unable to write tunnel configuration", e)
            else cleanupLocked()
            return@withLock
        }

        // Restore persistent local limits inside the Service so background/Always-on
        // starts do not depend on MainActivity having been recreated first.
        SpeedLimitStore.applyToLimiter(this)
        log("Restored local speed limits before native data plane start")

        log("Starting hev tunnel")
        val started = startNativeIfCurrent(mySession, pfd, conf.absolutePath)
        if (!started) {
            // A stale session must not be turned into a new global error. Clean only
            // this instance's detached resources; owner-aware native cleanup will not
            // touch a newer process generation.
            if (!sessionIsCurrent(mySession) || tun !== pfd) {
                cleanupLocked()
                return@withLock
            }
            failLocked("hev tunnel failed to start")
            return@withLock
        }

        if (!waitForReady(mySession, relay, 4_000L)) {
            if (!sessionIsCurrent(mySession)) {
                cleanupLocked()
                return@withLock
            }
            failLocked("VPN data plane failed to become ready")
            return@withLock
        }

        val becameConnected = synchronized(processNativeLifecycleLock) {
            if (!sessionIsCurrent(mySession) || nativeOwnerGeneration.get() != nativeGeneration) {
                false
            } else {
                VpnRuntime.update {
                    it.copy(
                        status = VpnStatus.CONNECTED,
                        tunnel = "up",
                        health = VpnHealth.CONTROL_READY,
                        lastError = null,
                        sessionDownloadBytes = 0L,
                        sessionUploadBytes = 0L,
                        connectedAtMillis = System.currentTimeMillis(),
                    )
                }
                true
            }
        }
        if (!becameConnected) {
            cleanupLocked()
            return@withLock
        }
        log("VPN session $mySession connected")
        startMeter(mySession, myServiceGeneration)
        startQuotaMonitor(mySession)
        startHealthMonitor(mySession)
    }

    private fun sessionIsCurrent(mySession: Long): Boolean =
        !destroying.get() &&
            sessionId.get() == mySession &&
            serviceGeneration != 0L &&
            activeServiceGeneration.get() == serviceGeneration

    private fun writeTunnelConfig(relay: Socks5Server, compatibility: VpnCompatibilitySettings): File {
        val conf = File(filesDir, "tunnel-${serviceGeneration}.yml")
        tunnelConfigFile = conf
        val configText = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${compatibility.mtu}")
            appendLine("  ipv4: $TUN_V4")
            if (compatibility.ipv6Enabled) appendLine("  ipv6: '$TUN_V6'")
            appendLine("socks5:")
            appendLine("  port: ${relay.port}")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("  username: '${relay.username}'")
            appendLine("  password: '${relay.password}'")
            appendLine("mapdns:")
            appendLine("  address: $MAP_DNS_V4")
            appendLine("  port: 53")
            appendLine("  network: $MAP_DNS_FAKE_NETWORK")
            appendLine("  netmask: $MAP_DNS_FAKE_NETMASK")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  task-stack-size: 86016")
            appendLine("  tcp-buffer-size: 65536")
            appendLine("  udp-recv-buffer-size: 262144")
            appendLine("  udp-copy-buffer-nums: 12")
            appendLine("  max-session-count: 80")
            appendLine("  connect-timeout: 12000")
            appendLine("  tcp-read-write-timeout: 600000")
            appendLine("  udp-read-write-timeout: 60000")
        }
        conf.writeText(configText)
        return conf
    }

    private fun isUsablePhysicalNetwork(cm: ConnectivityManager, network: Network?): Boolean {
        if (network == null) return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun discoverPhysicalNetwork(cm: ConnectivityManager): Network? {
        // Prefer the current default network for this UID. SpeedVPN excludes its
        // own package from the VPN, so activeNetwork is the relevant physical default.
        val active = cm.activeNetwork
        if (isUsablePhysicalNetwork(cm, active)) return active

        // During a transition activeNetwork can be briefly unavailable. Fall back
        // to available non-VPN networks, preferring validated networks.
        return cm.allNetworks.asSequence()
            .mapNotNull { network -> cm.getNetworkCapabilities(network)?.let { network to it } }
            .filter { (_, caps) ->
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            .sortedWith(
                compareByDescending<Pair<Network, NetworkCapabilities>> { (_, caps) ->
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0
                }.thenByDescending { (_, caps) ->
                    when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                        else -> 1
                    }
                },
            )
            .map { it.first }
            .firstOrNull()
    }

    private fun publishPhysicalNetwork(discovered: Network?) {
        val old = currentPhysicalNetwork.getAndSet(discovered)
        if (old == discovered) return
        log("Physical network updated -> ${discovered ?: "none"}")
        socks?.onUnderlyingNetworkChanged()
        if (tun != null) {
            runCatching {
                setUnderlyingNetworks(discovered?.let { arrayOf(it) } ?: emptyArray())
            }.onFailure {
                logE("Unable to publish VPN underlying network", it)
            }
        }
    }

    private fun findUnderlyingNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cached = currentPhysicalNetwork.get()
        if (isUsablePhysicalNetwork(cm, cached)) return cached
        val discovered = discoverPhysicalNetwork(cm)
        publishPhysicalNetwork(discovered)
        return discovered
    }

    private fun refreshPhysicalNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        publishPhysicalNetwork(discoverPhysicalNetwork(cm))
    }

    private fun registerPhysicalNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isUsablePhysicalNetwork(cm, network)) refreshPhysicalNetwork()
            }

            override fun onLost(network: Network) {
                if (currentPhysicalNetwork.get() == network) refreshPhysicalNetwork()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (currentPhysicalNetwork.get() == network ||
                    (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))) {
                    refreshPhysicalNetwork()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (isUsablePhysicalNetwork(cm, network)) {
                    if (currentPhysicalNetwork.get() != network) refreshPhysicalNetwork()
                }
            }
        }
        networkCallback = callback
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            refreshPhysicalNetwork()
        }.onFailure {
            networkCallback = null
            logE("Unable to register default network callback", it)
        }
    }

    private fun unregisterPhysicalNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }
        currentPhysicalNetwork.set(null)
    }

    private fun waitForReady(mySession: Long, relay: Socks5Server, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!sessionIsCurrent(mySession) || tun == null || !engineRunning) return false

            val nativeState = runCatching { probeNativeState(nativeGeneration) }
                .getOrDefault(NativeProbe.UNKNOWN)
            if (nativeState == NativeProbe.STOPPED) return false

            // UNKNOWN is deliberately non-destructive: do not turn an inability
            // to query the process-wide native singleton into a false "stopped"
            // result that could trigger teardown or replacement.
            val socksOk = runCatching { relay.probe() }.getOrDefault(false)
            if (nativeState == NativeProbe.RUNNING && socksOk) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private data class HealthSnapshot(
        val relay: Socks5Server?,
        val nativeGeneration: Long,
    )

    private fun startHealthMonitor(mySession: Long) {
        healthMonitor?.cancel()
        healthMonitor = scope.launch {
            var failedControlChecks = 0
            var lastUpstreamProbeAt = 0L
            while (isActive) {
                delay(2_000)

                val snapshot = stateMutex.withLock {
                    if (!sessionIsCurrent(mySession) || tun == null ||
                        VpnRuntime.state.value.status != VpnStatus.CONNECTED
                    ) {
                        null
                    } else {
                        HealthSnapshot(socks, nativeGeneration)
                    }
                } ?: break

                // Probes may block (SOCKS timeout / physical DNS / TCP connect). Never
                // hold stateMutex across those operations; CONNECT/DISCONNECT and network
                // callbacks must remain responsive while health is being measured.
                val nativeState = runCatching { probeNativeState(snapshot.nativeGeneration) }
                    .getOrDefault(NativeProbe.UNKNOWN)
                val relayOk = runCatching { snapshot.relay?.probe() == true }.getOrDefault(false)

                if (nativeState == NativeProbe.UNKNOWN) {
                    stateMutex.withLock {
                        if (sessionIsCurrent(mySession) && socks === snapshot.relay && nativeGeneration == snapshot.nativeGeneration) {
                            VpnRuntime.update { it.copy(health = VpnHealth.CONTROL_READY) }
                            log("VPN health indeterminate: native status unavailable")
                        }
                    }
                    failedControlChecks = 0
                    continue
                }

                val nativeOk = nativeState == NativeProbe.RUNNING
                if (!nativeOk || !relayOk) {
                    failedControlChecks++
                    val result = stateMutex.withLock {
                        if (!sessionIsCurrent(mySession) || socks !== snapshot.relay ||
                            nativeGeneration != snapshot.nativeGeneration || tun == null ||
                            VpnRuntime.state.value.status != VpnStatus.CONNECTED
                        ) {
                            HealthResult.STOP
                        } else {
                            VpnRuntime.update { it.copy(health = VpnHealth.DEGRADED) }
                            log("VPN control health failed ($failedControlChecks/3): native=$nativeOk relay=$relayOk")
                            if (failedControlChecks >= 3) {
                                recoverDataPlaneLocked(mySession, relayHealthy = relayOk)
                            } else {
                                HealthResult.RETRY_LATER
                            }
                        }
                    }
                    when (result) {
                        HealthResult.STOP -> break
                        HealthResult.OK, HealthResult.RETRY_LATER -> Unit
                        HealthResult.RECOVERY_STARTED -> {
                            failedControlChecks = 0
                            log("VPN data plane recovery started; verification will run outside stateMutex")
                        }
                        HealthResult.FAILED -> break
                    }
                    continue
                }

                failedControlChecks = 0
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpstreamProbeAt >= HEALTH_INTERVAL_MS) {