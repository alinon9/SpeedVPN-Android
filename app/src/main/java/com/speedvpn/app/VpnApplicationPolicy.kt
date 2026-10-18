package com.speedvpn.app

import android.content.Context

/**
 * Android's app allow-list is a routing selector, not a block-list.
 * Runtime routing must send every app through TUN before UID policy can drop it.
 */
internal object VpnApplicationPolicy {
    /** Resolve package names to Android UIDs; absent/uninstalled packages are ignored. */
    fun blockedUids(context: Context, blockedPackages: Set<String>): Set<Int> {
        val packageManager = context.packageManager
        return blockedPackages.mapNotNull { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0).uid }
                .getOrNull()
                ?.takeIf { it > 0 }
        }.toSet()
    }

    /**
     * Exclude the VPN app itself during normal operation to prevent a routing loop.
     * For a speed-verification run, route the app's own HTTP probe through TUN so
     * the measured traffic crosses the SOCKS relay and the configured token buckets.
     * The relay protects its upstream sockets with VpnService.protect(), preventing loops.
     * Blocked target apps are never added to this routing-exemption set.
     */
    fun routingExemptPackages(
        vpnPackageName: String,
        includeOwnPackageForVerification: Boolean,
    ): Set<String> = if (includeOwnPackageForVerification) emptySet() else setOf(vpnPackageName)

    /** True when a resolved owner belongs to the blocked UID set. */
    fun shouldDropUid(uid: Int, blockedUids: Set<Int>): Boolean =
        uid > 0 && uid in blockedUids

    /**
     * With a blacklist active, an unresolved flow is denied only after both the Android owner
     * API and the procfs socket-table fallback have been attempted. Callers must not make this
     * decision before fallback attribution has completed.
     */
    fun shouldDropUnresolvedUid(
        blockedUids: Set<Int>,
        attributionExhausted: Boolean = true,
    ): Boolean = attributionExhausted && blockedUids.isNotEmpty()

    // Legacy helper retained for source compatibility; never use it to configure Builder.
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
