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

    // Retained for non-firewall callers; firewall routing is enforced by UID policy in TUN.
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
