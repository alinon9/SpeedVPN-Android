package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5SessionPolicyTest {
    @Test
    fun udpAssociationIsRejectedWhenItsUidBecomesBlocked() {
        assertTrue(Socks5Server.isSessionUidBlocked(10_123, setOf(10_123)))
        assertEquals(false, Socks5Server.isSessionUidBlocked(10_124, setOf(10_123)))
        assertEquals(false, Socks5Server.isSessionUidBlocked(null, setOf(10_123)))
        assertEquals(false, Socks5Server.isSessionUidBlocked(-1, setOf(10_123)))
    }

    @Test
    fun healthProbeIdentityRemainsAllowedWhenUidAttributionIsRequired() {
        val probe = Socks5Server.healthProbeUsername("relay-token")
        assertTrue(
            Socks5Server.usernameShapeAllowed(
                suppliedUsername = probe,
                baseUsername = "relay-token",
                sessionUid = null,
                requireUidAttribution = true,
            ),
        )
    }

    @Test
    fun ownerlessUserSessionIsRejectedWhenUidAttributionIsRequired() {
        assertEquals(
            false,
            Socks5Server.usernameShapeAllowed(
                suppliedUsername = "relay-token",
                baseUsername = "relay-token",
                sessionUid = null,
                requireUidAttribution = true,
            ),
        )
    }

    @Test
    fun uidTaggedSessionMustMatchTheResolvedOwner() {
        assertTrue(
            Socks5Server.usernameShapeAllowed(
                suppliedUsername = "relay-token#10123",
                baseUsername = "relay-token",
                sessionUid = 10_123,
                requireUidAttribution = true,
            ),
        )
        assertEquals(
            false,
            Socks5Server.usernameShapeAllowed(
                suppliedUsername = "relay-token#10124",
                baseUsername = "relay-token",
                sessionUid = 10_123,
                requireUidAttribution = true,
            ),
        )
    }
}
