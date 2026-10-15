package com.speedvpn.app

internal object VpnApplicationPolicy {
    fun allowedPackages(
        launchablePackages: Iterable<String>,
        blockedPackages: Set<String>,
        ownPackage: String,
        includeOwnPackageForVerification: Boolean,
    ): Set<String> {
        // addAllowedApplication() is a routing allow-list, not a block-list.
        // Include blocked packages so their packets still enter TUN and are dropped
        // by native flow enforcement instead of escaping directly to the physical network.
        val allowed = launchablePackages
            .filter { it != ownPackage }
            .toCollection(LinkedHashSet())
        blockedPackages
            .filter { it != ownPackage }
            .forEach { allowed += it }
        if (includeOwnPackageForVerification && ownPackage !in blockedPackages) {
            allowed += ownPackage
        }
        return allowed
    }
}
