package com.speedvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnApplicationPolicyTest {
    @Test
    fun ownPackageStaysExcludedOutsideVerification() {
        val allowed = VpnApplicationPolicy.allowedPackages(
            launchablePackages = listOf("com.example.allowed", "com.example.blocked", "com.speedvpn.app"),
            blockedPackages = setOf("com.example.blocked"),
            ownPackage = "com.speedvpn.app",
            includeOwnPackageForVerification = false,
        )

        assertEquals(setOf("com.example.allowed"), allowed)
    }

    @Test
    fun ownPackageIsIncludedForVerificationWhileBlockedAppsStayExcluded() {
        val allowed = VpnApplicationPolicy.allowedPackages(
            launchablePackages = listOf("com.example.allowed", "com.example.blocked"),
            blockedPackages = setOf("com.example.blocked"),
            ownPackage = "com.speedvpn.app",
            includeOwnPackageForVerification = true,
        )

        assertEquals(setOf("com.example.allowed", "com.speedvpn.app"), allowed)
    }

    @Test
    fun blockedOwnPackageIsNotAddedToVerificationAllowList() {
        val allowed = VpnApplicationPolicy.allowedPackages(
            launchablePackages = listOf("com.example.allowed"),
            blockedPackages = setOf("com.speedvpn.app"),
            ownPackage = "com.speedvpn.app",
            includeOwnPackageForVerification = true,
        )

        assertEquals(setOf("com.example.allowed"), allowed)
    }

    @Test
    fun disablingManualFirewallRemovesManualBlocksButKeepsActiveQuotaBlocks() {
        val effective = VpnApplicationPolicy.effectiveBlockedPackages(
            manualFirewallEnabled = false,
            manuallyBlockedPackages = setOf("com.example.manual"),
            quotaBlockedPackages = setOf("com.example.quota"),
            configuredQuotaPackages = setOf("com.example.quota"),
        )

        assertEquals(setOf("com.example.quota"), effective)
    }

    @Test
    fun staleQuotaBlocksAreIgnoredWhenQuotaIsRemovedOrDisabled() {
        val effective = VpnApplicationPolicy.effectiveBlockedPackages(
            manualFirewallEnabled = false,
            manuallyBlockedPackages = setOf("com.example.manual"),
            quotaBlockedPackages = setOf("com.facebook.katana", "com.google.android.youtube"),
            configuredQuotaPackages = emptySet(),
        )

        assertTrue(effective.isEmpty())
    }

    @Test
    fun onlyConfiguredQuotaPackagesCanRemainQuotaBlocked() {
        val effective = VpnApplicationPolicy.effectiveBlockedPackages(
            manualFirewallEnabled = true,
            manuallyBlockedPackages = setOf("com.example.manual"),
            quotaBlockedPackages = setOf("com.example.active", "com.example.stale"),
            configuredQuotaPackages = setOf("com.example.active"),
        )

        assertEquals(setOf("com.example.manual", "com.example.active"), effective)
    }

    @Test
    fun blockedUidIsBlockedAndUnlistedUidIsAllowed() {
        val blockedUids = setOf(10_123)

        assertTrue(VpnApplicationPolicy.isUidBlocked(10_123, blockedUids))
        assertFalse(VpnApplicationPolicy.isUidBlocked(10_124, blockedUids))
    }

    @Test
    fun invalidAndNonAppUidsFailOpen() {
        val blockedUids = setOf(0, 10_123)

        assertFalse(VpnApplicationPolicy.isUidBlocked(-1, blockedUids))
        assertFalse(VpnApplicationPolicy.isUidBlocked(0, blockedUids))
    }

    @Test
    fun sharedUidIsBlockedWhenAnyPackageOnThatUidIsBlocked() {
        val blockedUids = VpnApplicationPolicy.blockedUidsForPackages(
            packageUidsByName = mapOf(
                "com.example.blocked" to 10_123,
                "com.example.shared" to 10_123,
                "com.example.allowed" to 10_124,
            ),
            blockedPackages = setOf("com.example.blocked"),
        )

        assertEquals(setOf(10_123), blockedUids)
        assertTrue(VpnApplicationPolicy.isUidBlocked(10_123, blockedUids))
        assertFalse(VpnApplicationPolicy.isUidBlocked(10_124, blockedUids))
    }

    @Test
    fun replacingPolicyRemovesOldUidDecision() {
        val packageUids = mapOf("com.example.target" to 10_123)
        val oldBlockedUids = VpnApplicationPolicy.blockedUidsForPackages(
            packageUidsByName = packageUids,
            blockedPackages = setOf("com.example.target"),
        )
        val newBlockedUids = VpnApplicationPolicy.blockedUidsForPackages(
            packageUidsByName = packageUids,
            blockedPackages = emptySet(),
        )

        assertTrue(VpnApplicationPolicy.isUidBlocked(10_123, oldBlockedUids))
        assertFalse(VpnApplicationPolicy.isUidBlocked(10_123, newBlockedUids))
    }

    @Test
    fun ipv4MappedIpv6AddressNormalizesToIpv4ForOwnerUidLookup() {
        val mapped = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xff.toByte(), 0xff.toByte(),
            192.toByte(), 0, 2, 10,
        )

        assertArrayEquals(
            byteArrayOf(192.toByte(), 0, 2, 10),
            VpnApplicationPolicy.normalizeUidQueryAddressBytes(mapped),
        )
    }
}
