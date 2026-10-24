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
    // Cached set of packages that actually have configured quota policies. Null means
    // unknown/needs refresh. Allows unquoted relay flows (e.g. YouTube/browser with no
    // quota set) to avoid per-chunk SQLite transactions and lock contention.
    @Volatile
    private var configuredQuotaPackages: Set<String>? = null
    private val uidPackagesCache = ConcurrentHashMap<Int, Set<String>>()
    private val inFlightReservedByPackage = ConcurrentHashMap<String, AtomicLong>()
    private val committedForwardedByPackage = ConcurrentHashMap<String, AtomicLong>()
    @Volatile
    private var currentProcessToken: String = "proc-" + System.nanoTime() + "-" + java.util.UUID.randomUUID()
    @Volatile
    private var recoveredForProcessToken: String = ""
    private const val RESTART_THROTTLE_MS = 5_000L

    /** Invalidated when a quota policy is created, removed, or reconfigured. */
    internal fun invalidateQuotaBaselineCache(packageName: String? = null) {
        if (packageName == null) {
            quotaBaselineDayByPackage.clear()
            inFlightReservedByPackage.clear()
            committedForwardedByPackage.clear()
        } else {
            quotaBaselineDayByPackage.remove(packageName)
            inFlightReservedByPackage.remove(packageName)
            committedForwardedByPackage.remove(packageName)
        }
        configuredQuotaPackages = null
    }

    /**
     * Simulates an abrupt process termination and restart in tests by rotating the
     * active process token and clearing in-memory volatile state without running
     * normal [settleRelayBytes] callbacks for in-flight reservations.
     */
    internal fun simulateProcessRestartForTests(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        synchronized(quotaStateLock) {
            inFlightReservedByPackage.clear()
            committedForwardedByPackage.clear()
            quotaBaselineDayByPackage.clear()
            configuredQuotaPackages = null
            currentProcessToken = "proc-" + System.nanoTime() + "-" + java.util.UUID.randomUUID()
            recoveredForProcessToken = ""
            ensureOrphanedUdpReservationsRecoveredLocked(context, nowMillis)
        }
    }

    private fun ensureOrphanedUdpReservationsRecoveredLocked(
        context: Context,
        nowMillis: Long,
    ) {
        val token = currentProcessToken
        if (recoveredForProcessToken == token) return
        val dbFile = runCatching { context.getDatabasePath("speedvpn_usage.db") }.getOrNull()
        if (dbFile != null && !dbFile.exists()) {
            recoveredForProcessToken = token
            return
        }
        val (affectedPackages, stillExhausted) = runCatching {
            UsageRepository.recoverOrphanedUdpReservations(context, token, nowMillis)
        }.getOrDefault(emptySet<String>() to emptySet())
        if (stillExhausted.isNotEmpty()) {
            VpnAppControl.addQuotaBlockedPackages(context, stillExhausted)
        }
        if (affectedPackages.isNotEmpty()) {
            (affectedPackages - stillExhausted).forEach { packageName ->
                VpnAppControl.setQuotaBlocked(context, packageName, false)
            }
        }
        recoveredForProcessToken = token
    }

    internal fun inFlightReservedBytes(packageName: String): Long =
        inFlightReservedByPackage[packageName]?.get()?.coerceAtLeast(0L) ?: 0L

    internal fun committedForwardedBytes(packageName: String): Long =
        committedForwardedByPackage[packageName]?.get()?.coerceAtLeast(0L) ?: 0L

    private fun addInFlightReservation(packages: Set<String>, bytes: Int) {
        if (bytes <= 0) return
        val delta = bytes.toLong()
        packages.forEach { pkg ->
            if (pkg.isNotBlank()) {
                inFlightReservedByPackage.computeIfAbsent(pkg) { AtomicLong(0L) }
                    .updateAndGet { current ->
                        if (Long.MAX_VALUE - current < delta) Long.MAX_VALUE else current + delta
                    }
            }
        }
    }

    private fun releaseInFlightReservation(packages: Set<String>, bytes: Int) {
        if (bytes <= 0) return
        val delta = bytes.toLong()
        packages.forEach { pkg ->
            if (pkg.isNotBlank()) {
                inFlightReservedByPackage[pkg]?.updateAndGet { current ->
                    (current - delta).coerceAtLeast(0L)
                }
            }
        }
    }

    private fun addCommittedForwarded(packages: Set<String>, bytes: Int) {
        if (bytes <= 0) return
        val delta = bytes.toLong()
        packages.forEach { pkg ->
            if (pkg.isNotBlank()) {
                committedForwardedByPackage.computeIfAbsent(pkg) { AtomicLong(0L) }
                    .updateAndGet { current ->
                        if (Long.MAX_VALUE - current < delta) Long.MAX_VALUE else current + delta
                    }
            }
        }
    }

    internal fun resolvePackagesForUidCached(context: Context, uid: Int?): Set<String> {
        val validUid = uid?.takeIf { it > 0 } ?: return emptySet()
        uidPackagesCache[validUid]?.let { return it }
        val resolved = runCatching {
            context.packageManager.getPackagesForUid(validUid)?.filter { it.isNotBlank() }?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        if (resolved.isNotEmpty()) {
            uidPackagesCache[validUid] = resolved
        }
        return resolved
    }

    private fun currentQuotaPackages(context: Context): Set<String> {
        val dbFile = runCatching { context.getDatabasePath("speedvpn_usage.db") }.getOrNull()
        if (dbFile != null && !dbFile.exists()) {
            quotaBaselineDayByPackage.clear()
            configuredQuotaPackages = emptySet()
            return emptySet()
        }
        configuredQuotaPackages?.let { return it }
        return synchronized(quotaStateLock) {
            configuredQuotaPackages?.let { return@synchronized it }
            val loaded = runCatching {
                UsageRepository.readQuotaPolicies(context).map { it.packageName }.toSet()
            }.getOrDefault(emptySet())
            configuredQuotaPackages = loaded
            loaded
        }
    }

    internal fun hasAnyConfiguredQuotaPolicies(context: Context): Boolean =
        currentQuotaPackages(context).isNotEmpty()

    private val lastRestartAtMillis = AtomicLong(Long.MIN_VALUE)
    private val restartPending = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    internal fun resetRestartThrottleForTests() {
        mainHandler.removeCallbacksAndMessages(null)
        restartPending.set(0L)
        lastRestartAtMillis.set(Long.MIN_VALUE)
        invalidateQuotaBaselineCache()
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
     * flows when a quota is configured for the package, while flows belonging to apps with
     * no quota policy return immediately in O(1) memory.
     *
     * Live UI traffic counters are intentionally NOT updated here: the relay records only
     * the bytes that are actually written/delivered via [recordForwardedRelayBytes] so a
     * partial TCP write or a dropped UDP datagram never inflates per-app usage statistics.
     */
    fun accountRelayBytes(
        context: Context,
        packageNames: Set<String>,
        uid: Int?,
        direction: QuotaTrafficDirection,
        requestedBytes: Int,
        nowMillis: Long = System.currentTimeMillis(),
        transport: QuotaTransport = QuotaTransport.TCP,
    ): QuotaTrafficDecision {
        val requested = requestedBytes.coerceAtLeast(0)
        if (requested == 0) return QuotaTrafficDecision(0)
        val resolvedPackages = packageNames.filter { it.isNotBlank() }.toSet().ifEmpty {
            resolvePackagesForUidCached(context, uid)
        }
        if (resolvedPackages.isEmpty()) return QuotaTrafficDecision(requested)

        // Fast-path: skip quotaStateLock and SQLite transactions when none of the
        // resolved packages have an active quota policy.
        val quotaPackages = currentQuotaPackages(context)
        if (quotaPackages.isEmpty() || resolvedPackages.none { it in quotaPackages }) {
            addInFlightReservation(resolvedPackages, requested)
            return QuotaTrafficDecision(requested)
        }

        return synchronized(quotaStateLock) {
            ensureOrphanedUdpReservationsRecoveredLocked(context, nowMillis)
            val dayStart = QuotaPeriod.current(QuotaType.DAILY, nowMillis).startMillis
            if (resolvedPackages.any { quotaBaselineDayByPackage[it] != dayStart }) {
                initializeMissingQuotaBaselines(context, nowMillis, resolvedPackages)
            }
            val decision = UsageRepository.reserveQuotaBytes(
                context = context,
                packageNames = resolvedPackages,
                direction = direction,
                requestedBytes = requested,
                nowMillis = nowMillis,
                transport = transport,
                processToken = currentProcessToken,
            )
            addInFlightReservation(resolvedPackages, decision.allowedBytes)
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
    }

    /**
     * Settles an admitted relay reservation after socket/channel I/O completes or fails:
     * - [QuotaSettlementOutcome.TCP_WRITE_SUCCEEDED] and [QuotaSettlementOutcome.UDP_DELIVERED]
     *   commit the forwarded byte count and update live per-app traffic counters.
     * - [QuotaSettlementOutcome.TCP_WRITE_FAILED_CONSERVATIVE] retains the durable SQLite
     *   reservation conservatively (because OutputStream.write() does not report partial bytes
     *   copied to the kernel before throwing) while recording 0 live-forwarded bytes.
     * - [QuotaSettlementOutcome.UDP_DROPPED_OR_SEND_FAILED] releases the uncommitted durable
     *   reservation in SQLite and reconciles [VpnAppControl.quotaBlockedPackages] so an atomic
     *   UDP datagram that was never transmitted does not consume quota or prematurely block the app.
     */
    internal fun settleRelayBytes(
        context: Context,
        packageNames: Set<String>,
        uid: Int?,
        direction: QuotaTrafficDirection,
        decision: QuotaTrafficDecision,
        outcome: QuotaSettlementOutcome,
        forwardedBytes: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): QuotaTrafficDecision {
        val resolvedPackages = packageNames.filter { it.isNotBlank() }.toSet().ifEmpty {
            resolvePackagesForUidCached(context, uid)
        }
        releaseInFlightReservation(resolvedPackages, decision.allowedBytes)

        return when (outcome) {
            QuotaSettlementOutcome.TCP_WRITE_SUCCEEDED,
            QuotaSettlementOutcome.UDP_DELIVERED -> {
                val maxCommit = if (decision.allowedBytes > 0) decision.allowedBytes else forwardedBytes.coerceAtLeast(0)
                val committed = forwardedBytes.coerceIn(0, maxCommit)
                val unusedReserved = (decision.allowedBytes - committed).coerceAtLeast(0)
                val settled = if (unusedReserved > 0 && decision.reservations.isNotEmpty()) {
                    releaseReservationAndReconcileBlocks(context, decision, unusedReserved, nowMillis)
                        .copy(allowedBytes = committed)
                } else {
                    if (decision.reservations.any { it.reservationId > 0L }) {
                        synchronized(quotaStateLock) {
                            runCatching { UsageRepository.commitQuotaReservation(context, decision) }
                        }
                    }
                    decision.copy(allowedBytes = committed)
                }
                if (committed > 0) {
                    addCommittedForwarded(resolvedPackages, committed)
                    if (SmartSettings.isStatsEnabled(context)) {
                        AppTrafficManager.recordLiveRelayTraffic(
                            uid = uid,
                            packages = resolvedPackages,
                            direction = direction,
                            bytes = committed,
                            today = formatDate(nowMillis),
                        )
                    }
                }
                settled
            }
            QuotaSettlementOutcome.TCP_WRITE_FAILED_CONSERVATIVE -> {
                // Conservative TCP settlement: keep the durable reservation in SQLite
                // because OutputStream.write() may have flushed part or all of the slice
                // before throwing, but do not count unconfirmed bytes in live UI traffic.
                decision
            }
            QuotaSettlementOutcome.UDP_DROPPED_OR_SEND_FAILED -> {
                if (decision.reservations.isNotEmpty()) {
                    releaseReservationAndReconcileBlocks(
                        context = context,
                        decision = decision,
                        bytesToRelease = decision.allowedBytes,
                        nowMillis = nowMillis,
                    )
                } else {
                    decision.copy(allowedBytes = 0)
                }
            }
        }
    }

    private fun releaseReservationAndReconcileBlocks(
        context: Context,
        decision: QuotaTrafficDecision,
        bytesToRelease: Int,
        nowMillis: Long,
    ): QuotaTrafficDecision = synchronized(quotaStateLock) {
        val stillExhausted = UsageRepository.releaseQuotaReservation(
            context = context,
            decision = decision,
            bytesToRelease = bytesToRelease,
            nowMillis = nowMillis,
        )
        val noLongerExhausted = decision.exhaustedPackages - stillExhausted
        noLongerExhausted.forEach { packageName ->
            VpnAppControl.setQuotaBlocked(context, packageName, false)
        }
        QuotaTrafficDecision(
            allowedBytes = (decision.allowedBytes - bytesToRelease).coerceAtLeast(0),
            exhaustedPackages = stillExhausted,
            reservations = emptyList(),
        )
    }

    /**
     * Records only bytes that were actually written/forwarded to the destination socket or
     * datagram channel after quota admission and I/O completion. Partial TCP writes record
     * only the written prefix (allowedBytes), and dropped UDP datagrams record zero bytes.
     */
    internal fun recordForwardedRelayBytes(
        context: Context,
        packageNames: Set<String>,
        uid: Int?,
        direction: QuotaTrafficDirection,
        forwardedBytes: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val actual = forwardedBytes.coerceAtLeast(0)
        if (actual == 0) return
        settleRelayBytes(
            context = context,
            packageNames = packageNames,
            uid = uid,
            direction = direction,
            decision = QuotaTrafficDecision(actual),
            outcome = QuotaSettlementOutcome.TCP_WRITE_SUCCEEDED,
            forwardedBytes = actual,
            nowMillis = nowMillis,
        )
    }

    /**
     * Rebuilds the persistent block set from durable counters. Called on VPN startup and as a
     * recovery/period-reset path; real-time enforcement no longer waits for this check.
     */
    fun evaluateBlockedPackages(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Set<String> = synchronized(quotaStateLock) {
        ensureOrphanedUdpReservationsRecoveredLocked(context, nowMillis)
        initializeMissingQuotaBaselines(context, nowMillis, packageNames = null)
        val policies = UsageRepository.readQuotaPolicies(context)
        configuredQuotaPackages = policies.map { it.packageName }.toSet()
        return policies
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
