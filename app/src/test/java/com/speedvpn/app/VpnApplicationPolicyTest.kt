package com.speedvpn.app

import org.junit.Assert.assertEquals
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
}
