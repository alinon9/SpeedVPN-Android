package com.speedvpn.app

import android.content.Context

/**
 * Android's app allow-list is a routing selector, not a block-list.
 * Runtime routing must send every app through TUN before UID policy can drop it.
 */
internal object VpnApplicationPolicy {
    /**
     * Build the effective block-list from independent policy sources.
     *
     * Manual app blocks are active only while the manual firewall is enabled.
     * Persisted quota-block entries are honored only for packages that still have
     * an active configured quota; this prevents stale SharedPreferences from
     * blocking unrelated apps after a quota is removed or disabled.
     */
    fun effectiveBlockedPackages(
        manualFirewallEnabled: Boolean,
        manuallyBlockedPackages: Set<String>,
        quotaBlockedPackages: Set<String>,
        configuredQuotaPackages: Set<String>,
    ): Set<String> = buildSet {
        if (manualFirewallEnabled) addAll(manuallyBlockedPackages)
        addAll(quotaBlockedPackages.intersect(configuredQuotaPackages))
    }

    /** Resolve package names to Android UIDs; absent/uninstalled packages are ignored. */
    fun blockedUids(context: Context, blockedPackages: Set<String>): Set<Int> {
        val packageManager = context.packageManager
        val packageUids = blockedPackages.mapNotNull { pkg ->
            runCatching { pkg to packageManager.getApplicationInfo(pkg, 0).uid }
                .getOrNull()
        }.toMap()
        return blockedUidsForPackages(packageUids, blockedPackages)
    }

    /** Pure policy helper shared by production and unit tests. */
    internal fun blockedUidsForPackages(
        packageUidsByName: Map<String, Int>,
        blockedPackages: Set<String>,
    ): Set<Int> =
        blockedPackages.mapNotNull { pkg ->
            packageUidsByName[pkg]?.takeIf { it > 0 }
        }.toSet()

    /** Unknown, invalid, and non-app UIDs are always fail-open. */
    internal fun isUidBlocked(uid: Int, blockedUids: Set<Int>): Boolean =
        uid > 0 && uid in blockedUids

    // Kept for source compatibility with existing tests/tools. The service does not
    // use this result for routing; it routes all apps through TUN instead.
    fun allowedPackages(
        launchablePackages: Iterable<String>,
        blockedPackages: Set<String>,
        ownPackage: String,
        includeOwnPackageForVerification: Boolean,
    ): Set<String> {
        val allowed = launchablePackages
            .filter { it != ownPackage && it !in blockedPackages }
            .toCollection(LinkedHashSet())
        if (includeOwnPackageForVerification && ownPackage !in blockedPackages) {
            allowed += ownPackage
        }
        return allowed
    }
}
