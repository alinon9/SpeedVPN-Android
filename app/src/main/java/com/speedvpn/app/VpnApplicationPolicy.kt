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
     * Only the VPN app itself is excluded from the VPN interface to avoid routing loops.
     * Blocked apps must remain in TUN; disallowing them here creates a bypass.
     */
    fun routingExemptPackages(vpnPackageName: String): Set<String> = setOf(vpnPackageName)

    /** True when a resolved owner belongs to the blocked UID set. */
    fun shouldDropUid(uid: Int, blockedUids: Set<Int>): Boolean =
        uid > 0 && uid in blockedUids

    /** Never silently allow an unresolved owner while a block policy is active. */
    fun shouldDropUnresolvedUid(blockedPackages: Set<String>): Boolean =
        blockedPackages.isNotEmpty()

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
