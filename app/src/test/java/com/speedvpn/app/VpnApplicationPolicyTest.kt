package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnApplicationPolicyTest {
    @Test
    fun onlyTheVpnApplicationIsExemptedFromTunRouting() {
        assertEquals(
            setOf("com.speedvpn.app"),
            VpnApplicationPolicy.routingExemptPackages("com.speedvpn.app"),
        )
        assertFalse(
            "A blocked target app must remain routed through TUN",
            "com.example.blocked" in VpnApplicationPolicy.routingExemptPackages("com.speedvpn.app"),
        )
    }

    @Test
    fun knownBlockedUidIsDroppedButAllowedUidIsNot() {
        val blockedUids = setOf(10_249, 10_333)
        assertTrue(VpnApplicationPolicy.shouldDropUid(10_249, blockedUids))
        assertFalse(VpnApplicationPolicy.shouldDropUid(10_250, blockedUids))
        assertFalse(VpnApplicationPolicy.shouldDropUid(-1, blockedUids))
    }

    @Test
    fun unresolvedUidFailsClosedOnlyWhenAnInstalledBlockedUidExists() {
        assertTrue(VpnApplicationPolicy.shouldDropUnresolvedUid(setOf(10_249)))
        assertFalse(VpnApplicationPolicy.shouldDropUnresolvedUid(emptySet()))
    }

    @Test
    fun unrelatedBlockedPackageDoesNotMakeKnownAllowedUidBlocked() {
        assertFalse(VpnApplicationPolicy.shouldDropUid(10_250, setOf(10_249)))
    }

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

}
