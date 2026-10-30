package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnApplicationPolicyTest {
    @Test
    fun individualPackageBlocksDoNotDependOnTheGlobalFirewallPreference() {
        val blockedPackage = "com.example.blocked"
        for (firewallEnabled in listOf(false, true)) {
            assertEquals(
                setOf(blockedPackage),
                VpnApplicationPolicy.packagesToEnforce(
                    VpnAppControlSettings(
                        firewallEnabled = firewallEnabled,
                        blockedPackages = setOf(blockedPackage),
                    ),
                    emptySet(),
                ),
            )
        }
    }

    @Test
    fun quotaBlocksRemainEnforcedWhenTheGlobalFirewallPreferenceIsOff() {
        assertEquals(
            setOf("com.example.quota"),
            VpnApplicationPolicy.packagesToEnforce(
                VpnAppControlSettings(firewallEnabled = false, blockedPackages = emptySet()),
                setOf("com.example.quota"),
            ),
        )
    }

    @Test
    fun onlyTheVpnApplicationIsExemptedFromTunRoutingOutsideVerification() {
        assertEquals(
            setOf("com.speedvpn.app"),
            VpnApplicationPolicy.routingExemptPackages(
                "com.speedvpn.app",
                includeOwnPackageForVerification = false,
            ),
        )
        assertFalse(
            "A blocked target app must remain routed through TUN",
            "com.example.blocked" in VpnApplicationPolicy.routingExemptPackages(
                "com.speedvpn.app",
                includeOwnPackageForVerification = false,
            ),
        )
    }

    @Test
    fun ownPackageIsRoutedThroughTunDuringVerificationSoTheLimiterCanBeMeasured() {
        assertEquals(
            emptySet<String>(),
            VpnApplicationPolicy.routingExemptPackages(
                "com.speedvpn.app",
                includeOwnPackageForVerification = true,
            ),
        )
    }

    @Test
    fun nativeUidDropPolicyStaysDisabledInDebugEvenWhenBlockedAppsExist() {
        assertFalse(
            VpnApplicationPolicy.shouldEnableNativeUidPolicy(
                isDebugBuild = true,
                callbackRegistered = true,
                blockedUids = setOf(10_249),
            ),
        )
    }

    @Test
    fun nativeUidDropPolicyRequiresReleaseBuildCallbackAndBlockedUid() {
        assertTrue(
            VpnApplicationPolicy.shouldEnableNativeUidPolicy(
                isDebugBuild = false,
                callbackRegistered = true,
                blockedUids = setOf(10_249),
            ),
        )
        assertFalse(
            VpnApplicationPolicy.shouldEnableNativeUidPolicy(
                isDebugBuild = false,
                callbackRegistered = false,
                blockedUids = setOf(10_249),
            ),
        )
        assertFalse(
            VpnApplicationPolicy.shouldEnableNativeUidPolicy(
                isDebugBuild = false,
                callbackRegistered = true,
                blockedUids = emptySet(),
            ),
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
    fun unresolvedUidFailsClosedOnlyAfterAllAttributionSourcesWereExhausted() {
        assertFalse(
            VpnApplicationPolicy.shouldDropUnresolvedUid(
                blockedUids = setOf(10_249),
                attributionExhausted = false,
            ),
        )
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
