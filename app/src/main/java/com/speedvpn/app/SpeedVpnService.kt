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
import java.net.Inet4Address
import java.net.Inet6Address
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
        private const val UPSTREAM_PROBE_HOST = "connectivitycheck.gstatic.com"
        private const val UPSTREAM_PROBE_PORT = 80
        private const val TUN_V4 = "198.18.0.1"
        private const val TUN_V6 = "fc00::1"
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
    @Volatile private var lastDnsSignature = ""
    private val dnsRebuildPending = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var meter: Job? = null
    private var healthMonitor: Job? = null
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
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
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
        val physicalLinkProperties = physicalNetwork?.let { network ->
            runCatching {
                getSystemService(ConnectivityManager::class.java).getLinkProperties(network)
            }.getOrNull()
        }
        // IPv6-disabled mode must also avoid advertising IPv6 DNS servers.
        // This keeps Android's VPN DNS path consistent with the selected IP family.
        val dnsIpv4Only = compatibility.dnsIpv4Only || !compatibility.ipv6Enabled
        val dnsServers = try {
            selectVpnDnsServers(physicalLinkProperties, dnsIpv4Only)
        } catch (e: IllegalStateException) {
            failLocked(e.message ?: "Unable to select VPN DNS servers", e)
            return@withLock
        }
        lastDnsSignature = dnsSignature(dnsServers)

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
                    val firewallRequested = firewall.firewallEnabled && firewall.blockedPackages.isNotEmpty()
                    val lockdownReady = Build.VERSION.SDK_INT >= 29 && runCatching { isLockdownEnabled }.getOrDefault(false)
                    if (firewallRequested && !lockdownReady) {
                        throw IllegalStateException(
                            "App firewall requires Android VPN Lockdown. Enable Always-on VPN and 'Block connections without VPN' in Android VPN settings."
                        )
                    }
                    if (firewallRequested) {
                        var installed = 0
                        firewall.blockedPackages.forEach { pkg ->
                            if (pkg == packageName) return@forEach
                            runCatching { addDisallowedApplication(pkg) }
                                .onSuccess { installed++ }
                                .onFailure { log("Skipping unavailable blocked package $pkg: ${it.message}") }
                        }
                        if (installed == 0) {
                            throw IllegalStateException("No valid blocked applications were installed")
                        }
                        log("App firewall active with Android lockdown: $installed blocked")
                    }
                    // The SpeedVPN process must never be routed back through its own TUN.
                    addDisallowedApplication(packageName)
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

    private fun dnsSignature(servers: List<InetAddress>): String =
        servers.mapNotNull { it.hostAddress }.sorted().joinToString(",")

    private fun handleLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        if (destroying.get() || currentPhysicalNetwork.get() != network) return

        val settings = VpnSettings.read(this)
        val ipv4Only = settings.dnsIpv4Only || !settings.ipv6Enabled
        val dns = runCatching { selectVpnDnsServers(linkProperties, ipv4Only) }.getOrElse { emptyList() }
        // Do not replace a known-good DNS set with an empty set during the short
        // transition window where Android has not published new DNS servers yet.
        if (dns.isEmpty()) return
        val signature = dnsSignature(dns)
        if (signature == lastDnsSignature) return

        lastDnsSignature = signature
        val connected = tun != null && VpnRuntime.state.value.status == VpnStatus.CONNECTED
        if (!connected) return

        // Android's VPN Builder does not expose an in-place DNS mutation. Rebuild
        // the VPN generation so the new DNS servers are installed atomically with
        // a fresh TUN. Debounce callback bursts and bind the disconnect to the
        // generation that observed the DNS change; otherwise a second callback can
        // queue a stale disconnect that tears down the freshly rebuilt generation.
        val expectedGeneration = serviceGeneration
        if (!dnsRebuildPending.compareAndSet(false, true)) return
        log("Physical DNS changed ($signature); scheduling VPN generation rebuild for $expectedGeneration")
        scope.launch {
            try {
                val shouldReconnect = stateMutex.withLock {
                    if (destroying.get() || serviceGeneration != expectedGeneration ||
                        VpnRuntime.state.value.status != VpnStatus.CONNECTED || tun == null
                    ) {
                        false
                    } else {
                        reconnectAfterDisconnect = true
                        true
                    }
                }
                if (shouldReconnect) disconnect(expectedGeneration)
            } finally {
                dnsRebuildPending.set(false)
            }
        }
    }

    private fun selectVpnDnsServers(linkProperties: LinkProperties?, ipv4Only: Boolean): List<InetAddress> {
        if (linkProperties == null) {
            if (ipv4Only) throw IllegalStateException("IPv4-only DNS requested but physical DNS is unavailable")
            return emptyList()
        }
        val result = linkProperties.dnsServers
            .asSequence()
            .filter { !it.isLoopbackAddress && !it.isMulticastAddress && !it.isAnyLocalAddress }
            .filter { it is Inet4Address || it is Inet6Address }
            .filter { !ipv4Only || it is Inet4Address }
            .distinctBy { it.hostAddress }
            .take(4)
            .toList()
        if (ipv4Only && result.isEmpty()) {
            throw IllegalStateException("IPv4-only DNS requested but no IPv4 DNS server is available")
        }
        return result
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
                    handleLinkPropertiesChanged(network, linkProperties)
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
        lastDnsSignature = ""
        dnsRebuildPending.set(false)
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

    private fun startHealthMonitor(mySession: Long) {
        healthMonitor?.cancel()
        healthMonitor = scope.launch {
            var failedControlChecks = 0
            var lastUpstreamProbeAt = 0L
            while (isActive) {
                delay(2_000)
                val result = stateMutex.withLock {
                    if (!sessionIsCurrent(mySession) || tun == null ||
                        VpnRuntime.state.value.status != VpnStatus.CONNECTED
                    ) return@withLock HealthResult.STOP

                    val relay = socks
                    val nativeState = runCatching { probeNativeState(nativeGeneration) }
                        .getOrDefault(NativeProbe.UNKNOWN)
                    if (nativeState == NativeProbe.UNKNOWN) {
                        VpnRuntime.update { it.copy(health = VpnHealth.CONTROL_READY) }
                        log("VPN health indeterminate: native status unavailable")
                        failedControlChecks = 0
                        return@withLock HealthResult.RETRY_LATER
                    }

                    val nativeOk = nativeState == NativeProbe.RUNNING
                    val relayOk = runCatching { relay?.probe() == true }.getOrDefault(false)
                    if (!nativeOk || !relayOk) {
                        failedControlChecks++
                        VpnRuntime.update { it.copy(health = VpnHealth.DEGRADED) }
                        log("VPN control health failed ($failedControlChecks/3): native=$nativeOk relay=$relayOk")
                        if (failedControlChecks >= 3) {
                            return@withLock recoverDataPlaneLocked(mySession)
                        }
                        return@withLock HealthResult.RETRY_LATER
                    }

                    failedControlChecks = 0
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpstreamProbeAt >= HEALTH_INTERVAL_MS) {
                        lastUpstreamProbeAt = now
                        if (upstreamReachabilityProbe()) {
                            VpnRuntime.update { it.copy(health = VpnHealth.UPSTREAM_READY) }
                            log("VPN upstream reachability is ready (data plane not measured by this probe)")
                        } else {
                            VpnRuntime.update { it.copy(health = VpnHealth.DEGRADED) }
                            log("VPN upstream reachability probe failed; TUN data plane is not inferred from this result")
                        }
                    } else if (VpnRuntime.state.value.health == VpnHealth.DISCONNECTED) {
                        VpnRuntime.update { it.copy(health = VpnHealth.CONTROL_READY) }
                    }
                    HealthResult.OK
                }

                when (result) {
                    HealthResult.STOP -> break
                    HealthResult.OK, HealthResult.RETRY_LATER -> Unit
                    HealthResult.RECOVERED -> log("VPN data plane recovered")
                    HealthResult.FAILED -> break
                }
            }
        }
    }

    /**
     * Measures physical upstream readiness only. It deliberately protects and binds
     * the socket so the probe cannot re-enter the VPN TUN. It is NOT a TUN-to-Internet
     * data-plane test; that path must be validated with real client traffic on-device.
     */
    private fun upstreamReachabilityProbe(): Boolean {
        val network = findUnderlyingNetwork() ?: return false
        return try {
            val deadline = SystemClock.elapsedRealtime() + UPSTREAM_PROBE_TIMEOUT_MS
            val dnsRemaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            val dnsFuture: Future<List<InetAddress>> = upstreamProbeDnsExecutor.submit<List<InetAddress>> {
                network.getAllByName(UPSTREAM_PROBE_HOST).toList()
            }
            val addresses = try {
                dnsFuture.get(dnsRemaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .filter { it is Inet4Address || it is Inet6Address }
            } catch (_: TimeoutException) {
                dnsFuture.cancel(true)
                return false
            } catch (_: Exception) {
                dnsFuture.cancel(true)
                return false
            }
            if (addresses.isEmpty()) return false

            for (address in addresses) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
                if (remaining <= 0) break
                val socket = Socket()
                try {
                    network.bindSocket(socket)
                    if (!protect(socket)) continue
                    socket.soTimeout = remaining
                    socket.connect(InetSocketAddress(address, UPSTREAM_PROBE_PORT), remaining)
                    return true
                } catch (_: Throwable) {
                    // Try the next resolved address while the single overall deadline remains.
                } finally {
                    runCatching { socket.close() }
                }
            }
            false
        } catch (t: Throwable) {
            log("Upstream probe failed: ${t.message}")
            false
        }
    }

    private enum class HealthResult { STOP, OK, RETRY_LATER, RECOVERED, FAILED }

    /** Must run while stateMutex is held. */
    private fun recoverDataPlaneLocked(mySession: Long): HealthResult {
        if (!sessionIsCurrent(mySession) || tun == null ||
            VpnRuntime.state.value.status != VpnStatus.CONNECTED
        ) {
            if (!sessionIsCurrent(mySession)) cleanupLocked()
            return HealthResult.STOP
        }

        val pfd = tun ?: return HealthResult.STOP
        val oldRelay = socks
        val nativeStopped = stopNative(endLimiterSession = false)
        if (!nativeStopped) {
            if (!sessionIsCurrent(mySession)) {
                cleanupLocked()
                return HealthResult.STOP
            }
            // Native shutdown could not be confirmed. Never start a replacement
            // over an unknown/running process-wide singleton. Retry health later.
            logE("Recovery deferred: native shutdown was not confirmed")
            return HealthResult.RETRY_LATER
        }

        val relay = if (oldRelay != null && runCatching { oldRelay.probe() }.getOrDefault(false)) {
            oldRelay
        } else {
            runCatching { oldRelay?.stop() }
            val fresh = runCatching {
                Socks5Server(
                    generation = serviceGeneration,
                    protectTcp = { protect(it) },
                    protectUdp = { protect(it) },
                    currentNetwork = { findUnderlyingNetwork() },
                    ipv6Enabled = VpnSettings.read(this@SpeedVpnService).ipv6Enabled,
                ).also { it.start() }
            }.getOrNull() ?: run {
                failLocked("SOCKS relay recovery failed")
                return HealthResult.FAILED
            }

            val freshPublished = synchronized(resourceLifecycleLock) {
                if (!sessionIsCurrent(mySession) || tun !== pfd || socks !== oldRelay) {
                    false
                } else {
                    socks = fresh
                    true
                }
            }
            if (!freshPublished) {
                runCatching { fresh.stop() }
                return HealthResult.STOP
            }
            fresh
        }

        val compatibility = VpnSettings.read(this)
        val conf = try {
            writeTunnelConfig(relay, compatibility)
        } catch (e: Exception) {
            if (sessionIsCurrent(mySession)) failLocked("Unable to write tunnel configuration", e)
            else cleanupLocked()
            return HealthResult.FAILED
        }
        if (!sessionIsCurrent(mySession) || tun !== pfd) {
            if (!sessionIsCurrent(mySession)) cleanupLocked()
            return HealthResult.STOP
        }

        val started = startNativeIfCurrent(mySession, pfd, conf.absolutePath, preserveLimiterSession = true)
        if (!started) {
            if (!sessionIsCurrent(mySession) || tun !== pfd) {
                cleanupLocked()
                return HealthResult.STOP
            }
            failLocked("hev tunnel recovery failed")
            return HealthResult.FAILED
        }

        val nativeState = runCatching { probeNativeState(nativeGeneration) }
            .getOrDefault(NativeProbe.UNKNOWN)
        val relayOk = runCatching { relay.probe() }.getOrDefault(false)
        if (nativeState == NativeProbe.RUNNING && relayOk && sessionIsCurrent(mySession)) {
            return HealthResult.RECOVERED
        }

        if (nativeState == NativeProbe.UNKNOWN || !sessionIsCurrent(mySession)) {
            logE("Recovery verification indeterminate; leaving Native untouched")
            return if (sessionIsCurrent(mySession)) HealthResult.RETRY_LATER else HealthResult.STOP
        }

        // Here Native is known stopped, so no destructive stop is necessary.
        failLocked("VPN data plane could not be recovered")
        return HealthResult.FAILED
    }

    private fun startMeter(mySession: Long, myServiceGeneration: Long) {
        meter?.cancel()
        meter = scope.launch {
            var lastDown = SpeedLimiter.download.total.get()
            var lastUp = SpeedLimiter.upload.total.get()
            while (isActive && sessionId.get() == mySession) {
                delay(1_000)
                if (!sessionIsCurrent(mySession)) break
                val d = SpeedLimiter.download.total.get()
                val u = SpeedLimiter.upload.total.get()
                val sessionDown = SpeedLimiter.download.sessionBytes(myServiceGeneration)
                val sessionUp = SpeedLimiter.upload.sessionBytes(myServiceGeneration)
                synchronized(processNativeLifecycleLock) {
                    if (activeServiceGeneration.get() == myServiceGeneration) {
                        VpnRuntime.update {
                            it.copy(
                                downloadBps = ((d - lastDown).coerceAtLeast(0L)) * 8,
                                uploadBps = ((u - lastUp).coerceAtLeast(0L)) * 8,
                                sessionDownloadBytes = sessionDown,
                                sessionUploadBytes = sessionUp,
                            )
                        }
                    } else {
                        return@launch
                    }
                }
                lastDown = d
                lastUp = u
            }
        }
    }

    private enum class NativeProbe { RUNNING, STOPPED, UNKNOWN }

    private fun probeNativeStateLocked(expectedGeneration: Long? = null): NativeProbe {
        if (expectedGeneration != null && expectedGeneration > 0L &&
            nativeOwnerGeneration.get() != expectedGeneration
        ) {
            return NativeProbe.STOPPED
        }
        return try {
            if (TProxyService.TProxyIsRunning()) NativeProbe.RUNNING else NativeProbe.STOPPED
        } catch (t: Throwable) {
            logE("Unable to determine process-wide native state", t)
            NativeProbe.UNKNOWN
        }
    }

    private fun probeNativeState(expectedGeneration: Long? = null): NativeProbe =
        synchronized(processNativeLifecycleLock) {
            probeNativeStateLocked(expectedGeneration)
        }

    /**
     * Starts the native tunnel only while the supplied VPN session is still current.
     * Process-wide Native ownership and the start/stop calls are serialized by the
     * process-wide lifecycle monitor. A failed status probe is treated as UNKNOWN,
     * never as "stopped", so a new generation cannot take over an uncertain engine.
     */
    private fun startNativeIfCurrent(
        mySession: Long,
        expectedTun: ParcelFileDescriptor,
        configPath: String,
        preserveLimiterSession: Boolean = false,
    ): Boolean = synchronized(processNativeLifecycleLock) {
        if (destroying.get() || sessionId.get() != mySession || tun !== expectedTun ||
            serviceGeneration <= 0L || activeServiceGeneration.get() != serviceGeneration
        ) {
            log("Skipping native start for stale session $mySession")
            return@synchronized false
        }

        val newGeneration = serviceGeneration
        val currentOwner = nativeOwnerGeneration.get()
        if (currentOwner != 0L) {
            val probeBeforeStop = probeNativeStateLocked(currentOwner)
            when (probeBeforeStop) {
                NativeProbe.UNKNOWN, NativeProbe.RUNNING -> {
                    // If ownership is uncertain or the engine is still running,
                    // do not risk starting a second process-wide Native engine.
                    logE("Refusing native takeover: previous generation $currentOwner is $probeBeforeStop")
                    return@synchronized false
                }
                NativeProbe.STOPPED -> {
                    nativeOwnerGeneration.compareAndSet(currentOwner, 0L)
                    if (nativeGeneration == currentOwner) {
                        nativeGeneration = 0L
                        engineRunning = false
                    }
                    SpeedLimiter.endSession(currentOwner)
                }
            }
        }

        if (destroying.get() || sessionId.get() != mySession || tun !== expectedTun ||
            activeServiceGeneration.get() != newGeneration
        ) {
            return@synchronized false
        }

        if (!preserveLimiterSession || !SpeedLimiter.isGenerationActive(newGeneration)) {
            SpeedLimiter.beginSession(newGeneration)
        }
        val started = runCatching {
            TProxyService.TProxyStartService(configPath, expectedTun.fd)
        }.getOrElse {
            logE("Native start threw an exception", it)
            false
        }

        if (started) {
            nativeGeneration = newGeneration
            nativeOwnerGeneration.set(newGeneration)
            engineRunning = true
        } else {
            SpeedLimiter.endSession(newGeneration)
        }
        started
    }

    /** Stops Native only when this service generation still owns the process-wide engine. */
    private fun stopNative(
        expectedGeneration: Long = nativeGeneration,
        endLimiterSession: Boolean = true,
    ): Boolean =
        synchronized(processNativeLifecycleLock) {
            if (expectedGeneration <= 0L) {
                if (nativeOwnerGeneration.get() == 0L) engineRunning = false
                return@synchronized false
            }
            if (nativeOwnerGeneration.get() != expectedGeneration) {
                // An older instance must never stop a newer generation's process-wide engine.
                if (nativeGeneration == expectedGeneration) {
                    nativeGeneration = 0L
                    engineRunning = false
                }
                if (endLimiterSession) SpeedLimiter.endSession(expectedGeneration)
                return@synchronized false
            }

            val stopReported = runCatching { TProxyService.TProxyStopService() }
                .onFailure { logE("Native stop threw for generation $expectedGeneration", it) }
                .getOrNull()
            val probeAfterStop = probeNativeStateLocked(expectedGeneration)
            when (probeAfterStop) {
                NativeProbe.RUNNING -> {
                    logE("Native stop failed for generation $expectedGeneration; engine still running")
                    false
                }
                NativeProbe.UNKNOWN -> {
                    // Fail closed: without a reliable liveness answer, retain ownership
                    // and keep the TUN FD open so we never create use-after-close.
                    logE("Native stop state unknown for generation $expectedGeneration; retaining ownership")
                    false
                }
                NativeProbe.STOPPED -> {
                    nativeOwnerGeneration.compareAndSet(expectedGeneration, 0L)
                    if (nativeGeneration == expectedGeneration) {
                        nativeGeneration = 0L
                        engineRunning = false
                    }
                    if (endLimiterSession) SpeedLimiter.endSession(expectedGeneration)
                    true
                }
            }
        }

    private fun isNativeRunning(expectedGeneration: Long? = null): Boolean =
        probeNativeState(expectedGeneration) == NativeProbe.RUNNING

    private fun cleanupLocked() {
        meter?.cancel(); meter = null
        healthMonitor?.cancel(); healthMonitor = null
        val configFile = tunnelConfigFile
        tunnelConfigFile = null
        val (pfd, relay) = synchronized(resourceLifecycleLock) {
            val detachedTun = tun
            val detachedSocks = socks
            tun = null
            socks = null
            detachedTun to detachedSocks
        }

        // Snapshot only this Service instance's native generation. Never read the
        // process-wide native generation here: a newer Service instance may already
        // own the engine, and stale cleanup must be unable to target that generation.
        val generation = nativeGeneration
        if (generation > 0L) {
            if (!stopNative(generation)) {
                val ownerChanged = synchronized(processNativeLifecycleLock) {
                    nativeOwnerGeneration.get() != generation
                }
                if (ownerChanged) {
                    runCatching { pfd?.close() }
                    runCatching { relay?.stop() }
                    runCatching { configFile?.delete() }
                    return
                }
                // Do not close a TUN FD while the native engine may still own it.
                // Retry asynchronously; owner checks prevent this stale cleanup from
                // touching a newer generation.
                thread(name = "speedvpn-native-stop-retry", isDaemon = true) {
                    repeat(20) {
                        Thread.sleep(100)
                        if (stopNative(generation)) {
                            runCatching { pfd?.close() }
                            runCatching { relay?.stop() }
                            runCatching { configFile?.delete() }
                            return@thread
                        }
                        val ownerNow = synchronized(processNativeLifecycleLock) {
                            nativeOwnerGeneration.get()
                        }
                        if (ownerNow != generation) {
                            runCatching { pfd?.close() }
                            runCatching { relay?.stop() }
                            runCatching { configFile?.delete() }
                            return@thread
                        }
                    }
                    logE("Native generation $generation did not stop after retries; resources retained to avoid TUN use-after-close")
                }
                return
            }
        }
        runCatching { pfd?.close() }
        runCatching { relay?.stop() }
        runCatching { configFile?.delete() }
        if (tunnelConfigFile == configFile) tunnelConfigFile = null
    }

    private fun failLocked(reason: String, t: Throwable? = null) {
        val status = VpnRuntime.state.value.status
        if (status == VpnStatus.DISCONNECTED || status == VpnStatus.DISCONNECTING || destroying.get()) return
        val generation = serviceGeneration
        sessionId.incrementAndGet()
        logE("Connection error: $reason", t)
        cleanupLocked()
        val ownsRuntime = synchronized(processNativeLifecycleLock) {
            if (generation <= 0L || !activeServiceGeneration.compareAndSet(generation, 0L)) {
                false
            } else {
                VpnRuntime.update {
                    it.copy(
                        status = VpnStatus.ERROR,
                        health = VpnHealth.DISCONNECTED,
                        lastError = reason,
                        tunnel = "down",
                        serviceRunning = false,
                        downloadBps = 0,
                        uploadBps = 0,
                    )
                }
                true
            }
        }
        if (ownsRuntime) stopForeground(STOP_FOREGROUND_REMOVE)
        serviceGeneration = 0L
        stopSelf()
    }

    private suspend fun disconnect(expectedGeneration: Long? = null) = stateMutex.withLock {
        val generation = serviceGeneration
        if (expectedGeneration != null) {
            val activeGeneration = synchronized(processNativeLifecycleLock) {
                activeServiceGeneration.get()
            }
            if (generation != expectedGeneration || activeGeneration != expectedGeneration) {
                log("Ignoring stale stop request for generation $expectedGeneration (current=$activeGeneration)")
                return@withLock
            }
        }
        sessionId.incrementAndGet()
        val wasActiveGeneration = synchronized(processNativeLifecycleLock) {
            generation != 0L && activeServiceGeneration.get() == generation
        }
        val hadResources = tun != null || engineRunning || socks != null
        if (wasActiveGeneration && hadResources) {
            synchronized(processNativeLifecycleLock) {
                if (activeServiceGeneration.get() == generation) {
                    VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTING, health = VpnHealth.DISCONNECTED) }
                }
            }
        }

        if (!hadResources) {
            val shouldReconnect = reconnectAfterDisconnect
            reconnectAfterDisconnect = false
            val released = synchronized(processNativeLifecycleLock) {
                activeServiceGeneration.compareAndSet(generation, 0L)
            }
            serviceGeneration = 0L
            lastStartedRequestId.set(null)
            if (shouldReconnect) {
                if (released) {
                    VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTED, tunnel = "down", health = VpnHealth.DISCONNECTED, serviceRunning = true) }
                }
                scope.launch { connect() }
                return@withLock
            }
            if (released) stopForeground(STOP_FOREGROUND_REMOVE)
            if (released) {
                VpnRuntime.update { it.copy(status = VpnStatus.DISCONNECTED, tunnel = "down", health = VpnHealth.DISCONNECTED, serviceRunning = false) }
            }
            stopSelf()
            return@withLock
        }

        cleanupLocked()
        val shouldReconnect = reconnectAfterDisconnect
        reconnectAfterDisconnect = false
        val released = synchronized(processNativeLifecycleLock) {
            activeServiceGeneration.compareAndSet(generation, 0L)
        }
        serviceGeneration = 0L
        lastStartedRequestId.set(null)
        if (shouldReconnect) {
            if (released) {
                VpnRuntime.update {
                    it.copy(status = VpnStatus.DISCONNECTED, tunnel = "down", health = VpnHealth.DISCONNECTED, serviceRunning = true, downloadBps = 0, uploadBps = 0)
                }
            }
            log("Disconnect completed; queued connect will start next")
            scope.launch { connect() }
            return@withLock
        }

        if (released) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            VpnRuntime.update {
                it.copy(status = VpnStatus.DISCONNECTED, tunnel = "down", serviceRunning = false, downloadBps = 0, uploadBps = 0)
            }
        }
        log("Disconnected")
        stopSelf()
    }

    override fun onRevoke() {
        log("VPN revoked by system/user")
        sessionId.incrementAndGet()
        scope.launch { disconnect() }
    }

    override fun onDestroy() {
        destroying.set(true)
        sessionId.incrementAndGet()
        reconnectAfterDisconnect = false
        meter?.cancel(); meter = null
        healthMonitor?.cancel(); healthMonitor = null

        // Snapshot and detach this instance's resources on the main thread, then do
        // blocking teardown off-main. Process-wide ownership prevents stale cleanup
        // from stopping a newer Native generation.
        // Snapshot only this instance's native generation. Reading the process-wide
        // native generation here would let an old Service instance accidentally tear
        // down a newer instance's engine during asynchronous onDestroy().
        // Do not wait for the process-wide Native lock on the Main thread. The actual
        // blocking Native shutdown is dispatched below. The generation fields are
        // atomic/volatile and the CAS ensures an older instance cannot clear a newer
        // service generation.
        val generation = nativeGeneration
        val configFile = tunnelConfigFile
        tunnelConfigFile = null
        val ownedGeneration = serviceGeneration
        activeServiceGeneration.compareAndSet(ownedGeneration, 0L)
        serviceGeneration = 0L
        lastStartedRequestId.set(null)
        val (pfd, relay) = synchronized(resourceLifecycleLock) {
            val detachedTun = tun
            val detachedSocks = socks
            tun = null
            socks = null
            detachedTun to detachedSocks
        }
        if (generation > 0L || pfd != null || relay != null) {
            thread(name = "speedvpn-emergency-cleanup", isDaemon = true) {
                if (generation > 0L) {
                    var stopped = stopNative(generation)
                    var attempts = 0
                    while (!stopped && attempts++ < 20) {
                        Thread.sleep(100)
                        val ownerNow = synchronized(processNativeLifecycleLock) { nativeOwnerGeneration.get() }
                        stopped = if (ownerNow != generation) true else stopNative(generation)
                    }
                    if (!stopped) {
                        logE("Emergency native cleanup could not stop generation $generation; TUN FD retained")
                        return@thread
                    }
                }
                runCatching { pfd?.close() }
                runCatching { relay?.stop() }
                runCatching { configFile?.delete() }
            }
        }

        // This final UI/runtime update is also deliberately lock-free with respect to
        // Native. A newer service generation wins and will publish its own state.
        if (activeServiceGeneration.get() == 0L) {
            // Preserve a real ERROR published by failLocked(). A later CONNECT
            // explicitly clears ERROR before starting a new generation.
            VpnRuntime.update { snapshot ->
                if (snapshot.status == VpnStatus.ERROR) snapshot
                else snapshot.copy(
                    status = VpnStatus.DISCONNECTED,
                    tunnel = "down",
                    health = VpnHealth.DISCONNECTED,
                    serviceRunning = false,
                    downloadBps = 0,
                    uploadBps = 0,
                )
            }
        }
        scope.cancel()
        upstreamProbeDnsExecutor.shutdownNow()
        unregisterPhysicalNetworkCallback()
        super.onDestroy()
    }
}
