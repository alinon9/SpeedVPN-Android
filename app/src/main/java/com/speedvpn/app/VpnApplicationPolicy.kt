package com.speedvpn.app

import android.content.Context

/**
 * Android's app allow-list is a routing selector, not a block-list.
 * Runtime routing must send every app through TUN before UID policy can drop it.
 */
internal object VpnApplicationPolicy {
    private const val IPPROTO_UDP = 17
    private const val MAP_DNS_IPV4 = "198.18.0.2"
    private const val MAP_DNS_PORT = 53

    /**
     * Return package blocks that the active VPN must enforce.
     *
     * Individual package blocks do not depend on the optional global firewall
     * preference. Quota blocks are always merged into the enforcement set.
     */
    fun packagesToEnforce(
        settings: VpnAppControlSettings,
        quotaBlockedPackages: Set<String>,
    ): Set<String> = (settings.blockedPackages + quotaBlockedPackages).toSet()

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

    /**
     * Native fail-closed packet filtering is Release-only until Debug UID attribution
     * has a dedicated device-validated implementation. A callback alone must never
     * activate the native drop policy in Debug builds.
     */
    fun shouldEnableNativeUidPolicy(
        isDebugBuild: Boolean,
        callbackRegistered: Boolean,
        blockedUids: Set<Int>,
    ): Boolean = !isDebugBuild && callbackRegistered && blockedUids.isNotEmpty()

    /** True when a resolved owner belongs to the blocked UID set. */
    fun shouldDropUid(uid: Int, blockedUids: Set<Int>): Boolean =
        uid > 0 && uid in blockedUids

    /**
     * True only for UDP queries addressed to the internal TUN MapDNS endpoint (198.18.0.2:53).
     * Android's netd sends system DNS queries without an app UID (uid=-1), and MapDNS terminates
     * them in-memory inside hev-socks5-tunnel without forwarding any packet off-device.
     */
    fun isInternalMapDnsQuery(
        protocol: Int,
        dstIp: String,
        dstPort: Int,
    ): Boolean = protocol == IPPROTO_UDP && dstPort == MAP_DNS_PORT && dstIp == MAP_DNS_IPV4

    /**
     * With a blacklist active, an unresolved flow is denied only after both the Android owner
     * API and the procfs socket-table fallback have been attempted, except for internal MapDNS
     * queries (UDP 198.18.0.2:53) which are terminated locally inside TUN.
     */
    fun shouldDropUnresolvedUid(
        blockedUids: Set<Int>,
        attributionExhausted: Boolean = true,
        protocol: Int = -1,
        dstIp: String = "",
        dstPort: Int = -1,
    ): Boolean =
        attributionExhausted &&
            blockedUids.isNotEmpty() &&
            !isInternalMapDnsQuery(protocol, dstIp, dstPort)

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
