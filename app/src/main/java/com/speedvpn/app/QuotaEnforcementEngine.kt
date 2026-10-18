package com.speedvpn.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object QuotaEnforcementEngine {
    // Serializes the durable quota decision with persistence of the package block set, so a
    // stale periodic evaluation cannot clear a block reached concurrently by a live relay.
    private val quotaStateLock = Any()
    private val quotaBaselineDayByPackage = ConcurrentHashMap<String, Long>()
    private const val RESTART_THROTTLE_MS = 5_000L

    /** Invalidated when a quota policy is created, removed, or reconfigured. */
    internal fun invalidateQuotaBaselineCache(packageName: String) {
        quotaBaselineDayByPackage.remove(packageName)
    }
    private val lastRestartAtMillis = AtomicLong(Long.MIN_VALUE)
    private val restartPending = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    internal fun resetRestartThrottleForTests() {
        mainHandler.removeCallbacksAndMessages(null)
        restartPending.set(0L)
        lastRestartAtMillis.set(Long.MIN_VALUE)
    }

    internal fun tryAcquireRestart(nowMillis: Long): Boolean {
        while (true) {
            val previous = lastRestartAtMillis.get()
            if (previous != Long.MIN_VALUE && nowMillis - previous < RESTART_THROTTLE_MS) {
                return false
            }
            if (lastRestartAtMillis.compareAndSet(previous, nowMillis)) {
                return true
            }
        }
    }

    /**
     * Reserves bytes at the actual SOCKS relay boundary. The database serializes concurrent
     * flows, and a caller may forward only the admitted prefix returned by this method.
     */
    fun accountRelayBytes(
        context: Context,
        packageNames: Set<String>,
        uid: Int?,
        direction: QuotaTrafficDirection,
        requestedBytes: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): QuotaTrafficDecision = synchronized(quotaStateLock) {
        val resolvedPackages = packageNames.filter { it.isNotBlank() }.toSet().ifEmpty {
            uid?.takeIf { it > 0 }?.let { appUid ->
                runCatching { context.packageManager.getPackagesForUid(appUid)?.toSet().orEmpty() }
                    .getOrDefault(emptySet())
            }.orEmpty()
        }
        val dayStart = QuotaPeriod.current(QuotaType.DAILY, nowMillis).startMillis
        if (resolvedPackages.any { quotaBaselineDayByPackage[it] != dayStart }) {
            initializeMissingQuotaBaselines(context, nowMillis, resolvedPackages)
        }
        val decision = UsageRepository.reserveQuotaBytes(
            context = context,
            packageNames = resolvedPackages,
            direction = direction,
            requestedBytes = requestedBytes,
            nowMillis = nowMillis,
        )
        if (decision.exhaustedPackages.isNotEmpty()) {
            VpnAppControl.addQuotaBlockedPackages(context, decision.exhaustedPackages).forEach { packageName ->
                DiagnosticsRepository.record(
                    context,
                    "WARN",
                    "DataLimit",
                    "APP_QUOTA_REACHED",
                    "$packageName reached its configured relay-byte quota; uid=" + (uid ?: -1),
                )
            }
        }
        decision
    }

    /**
     * Rebuilds the persistent block set from durable counters. Called on VPN startup and as a
     * recovery/period-reset path; real-time enforcement no longer waits for this check.
     */
    fun evaluateBlockedPackages(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Set<String> {
        initializeMissingQuotaBaselines(context, nowMillis, packageNames = null)
        return UsageRepository.readQuotaPolicies(context)
            .filter { policy -> policy.usedBytes >= policy.limitBytes }
            .map { it.packageName }
            .toSet()
    }

    /**
     * Seeds a migrated/new-period policy exactly once with usage that happened before the live
     * SOCKS byte gate could count it. Android UID stats are preferred; the DB snapshot is the
     * fallback. After this flag is set, all further usage comes from the transactional relay gate.
     */
    private fun initializeMissingQuotaBaselines(
        context: Context,
        nowMillis: Long,
        packageNames: Set<String>?,
    ) {
        val requestedPackages = packageNames?.filter { it.isNotBlank() }?.toSet()
        if (requestedPackages != null && requestedPackages.isEmpty()) return

        val beforeReset = if (requestedPackages == null) {
            UsageRepository.readQuotaPolicies(context)
        } else {
            requestedPackages.flatMap { UsageRepository.readQuotaPolicies(context, it) }
        }
        val stalePolicies = beforeReset.filter { policy ->
            val current = QuotaPeriod.current(policy.quotaType, nowMillis)
            policy.periodStartMillis != current.startMillis || policy.periodEndMillis != current.endMillis
        }
        val dayStart = QuotaPeriod.current(QuotaType.DAILY, nowMillis).startMillis

        // Hot path: once each package is seeded for the current calendar day, avoid repeated
        // NetworkStatsManager calls and policy-wide reads for every TCP buffer/UDP datagram.
        if (stalePolicies.isEmpty() && beforeReset.all { it.baselineInitialized }) {
            val packagesToMark = requestedPackages ?: beforeReset.map { it.packageName }.toSet()
            packagesToMark.forEach { quotaBaselineDayByPackage[it] = dayStart }
            return
        }

        if (stalePolicies.isNotEmpty()) {
            if (requestedPackages == null) {
                QuotaResetEngine.resetExpiredPolicies(context, nowMillis)
            } else {
                stalePolicies.forEach { policy ->
                    val current = QuotaPeriod.current(policy.quotaType, nowMillis)
                    UsageRepository.resetQuotaPeriod(
                        context, policy.packageName, policy.quotaType,
                        current.startMillis, current.endMillis,
                    )
                }
            }
        }

        val previousBlocks = VpnAppControl.quotaBlockedPackages(context)
        val appUids = UsageRepository.readPolicies(context).associate { it.packageName to it.uid }
        val policiesToSeed = if (requestedPackages == null) {
            UsageRepository.readQuotaPolicies(context)
        } else {
            requestedPackages.flatMap { UsageRepository.readQuotaPolicies(context, it) }
        }
        val resetPolicyKeys = stalePolicies.map { it.packageName + "|" + it.quotaType.name }.toSet()
        policiesToSeed.filter { !it.baselineInitialized }.forEach { policy ->
            val bounds = QuotaPeriod.current(policy.quotaType, nowMillis)
            val uid = runCatching {
                context.packageManager.getApplicationInfo(
                    policy.packageName, android.content.pm.PackageManager.MATCH_ALL,
                ).uid
            }.getOrNull() ?: appUids[policy.packageName]

            val platform = uid?.let { appUid ->
                runCatching {
                    AppTrafficManager.queryUidUsageBreakdown(context, appUid, bounds.startMillis, nowMillis)
                }.getOrNull()
            }
            val snapshot = UsageRepository.sumDirectionalUsageInPeriod(
                context, policy.packageName, formatDate(bounds.startMillis), formatDate(bounds.endMillis),
            )
            val baseline = platform ?: snapshot

            // Older builds could persist a block without persisting these new byte counters.
            // Keep such a block fail-closed if history is unavailable, unless this policy just
            // rolled into a new period and its prior-period block should expire.
            val preserveUnknownLegacyBlock = platform == null &&
                policy.packageName in previousBlocks &&
                (policy.packageName + "|" + policy.quotaType.name) !in resetPolicyKeys
            UsageRepository.initializeQuotaBaseline(
                context = context,
                policy = policy,
                counters = baseline,
                minimumUsedBytes = if (preserveUnknownLegacyBlock) maxOf(policy.limitBytes, policy.usedBytes)
                    else policy.usedBytes,
                nowMillis = nowMillis,
            )
        }

        val finalPolicies = if (requestedPackages == null) {
            UsageRepository.readQuotaPolicies(context)
        } else {
            requestedPackages.flatMap { UsageRepository.readQuotaPolicies(context, it) }
        }
        val packagesToMark = requestedPackages ?: finalPolicies.map { it.packageName }.toSet()
        packagesToMark.forEach { packageName ->
            val currentPolicies = finalPolicies.filter { it.packageName == packageName }
            if (currentPolicies.all { policy ->
                    val current = QuotaPeriod.current(policy.quotaType, nowMillis)
                    policy.baselineInitialized && policy.periodStartMillis == current.startMillis &&
                        policy.periodEndMillis == current.endMillis
                }) {
                quotaBaselineDayByPackage[packageName] = dayStart
            } else {
                quotaBaselineDayByPackage.remove(packageName)
            }
        }
    }
    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

    /**
     * Applies the evaluated quota block set and restarts the VPN only when it changed.
     */
    fun enforce(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        restart: (Context) -> Unit = SpeedVpnService::restartForSettings,
    ): Boolean = synchronized(quotaStateLock) {
        val evaluated = evaluateBlockedPackages(context, nowMillis)
        val current = VpnAppControl.quotaBlockedPackages(context)
        if (evaluated == current) return false

        VpnAppControl.replaceQuotaBlockedPackages(context, evaluated)

        evaluated.minus(current).forEach { packageName ->
            DiagnosticsRepository.record(
                context,
                "WARN",
                "DataLimit",
                "APP_QUOTA_REACHED",
                "$packageName reached its configured app-usage quota",
            )
        }
        current.minus(evaluated).forEach { packageName ->
            DiagnosticsRepository.record(
                context,
                "INFO",
                "DataLimit",
                "APP_QUOTA_RESET",
                "$packageName quota block reset for a new period",
            )
        }

        if (VpnRuntime.state.value.status == VpnStatus.CONNECTED) {
            // Android requires recreating the VPN connection when the per-app
            // allow/disallow set changes. Make that behavior explicit to the UI
            // and rely on the existing 5s coalescing/debounce guard.
            val newlyBlocked = evaluated.minus(current)
            if (newlyBlocked.isNotEmpty()) {
                VpnRuntime.update {
                    it.copy(
                        lastResponse = "تم بلوغ حد استخدام التطبيق — سيتم إعادة الاتصال لتطبيق الحظر",
                    )
                }
            } else if (current.minus(evaluated).isNotEmpty()) {
                VpnRuntime.update {
                    it.copy(
                        lastResponse = "تمت إعادة ضبط حد استخدام التطبيق — سيتم إعادة الاتصال",
                    )
                }
            }
            requestRestart(context, nowMillis, restart)
        }
        return true
    }

    private fun requestRestart(context: Context, nowMillis: Long, restart: (Context) -> Unit) {
        requestRestartInternal(
            context = context,
            nowMillis = nowMillis,
            restart = restart,
            isConnected = { VpnRuntime.state.value.status == VpnStatus.CONNECTED },
            postDelayed = { delay, action -> mainHandler.postDelayed(action, delay) },
            cancelPending = { mainHandler.removeCallbacksAndMessages(null) },
            clock = System::currentTimeMillis,
        )
    }

    /**
     * Deterministic seam for JVM tests. It exercises the same coalescing path used by Android,
     * without requiring a real VpnService connection or sleeping for five seconds.
     */
    internal fun requestRestartForTests(
        context: Context,
        nowMillis: Long,
        restart: (Context) -> Unit,
        postDelayed: (Long, () -> Unit) -> Unit,
        cancelPending: () -> Unit = {},
        clock: () -> Long,
    ) {
        requestRestartInternal(
            context = context,
            nowMillis = nowMillis,
            restart = restart,
            isConnected = { true },
            postDelayed = postDelayed,
            cancelPending = cancelPending,
            clock = clock,
        )
    }

    private fun requestRestartInternal(
        context: Context,
        nowMillis: Long,
        restart: (Context) -> Unit,
        isConnected: () -> Boolean,
        postDelayed: (Long, () -> Unit) -> Unit,
        cancelPending: () -> Unit,
        clock: () -> Long,
    ) {
        if (tryAcquireRestart(nowMillis)) {
            cancelPending()
            restartPending.set(0L)
            restart(context)
            return
        }

        if (restartPending.compareAndSet(0L, 1L)) {
            val delay = (RESTART_THROTTLE_MS - (nowMillis - lastRestartAtMillis.get()))
                .coerceAtLeast(1L)
            postDelayed(delay) {
                restartPending.set(0L)
                val retryNow = clock()
                if (isConnected() && tryAcquireRestart(retryNow)) {
                    restart(context)
                }
            }
        }
    }
}
