package com.speedvpn.app

import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpFlowTableTest {
    @Test
    fun ipv4UdpDestinationIsAcceptedWhenIpv6IsDisabled() {
        assertTrue(Socks5Server.acceptsUdpAddressType(1, false))
    }

    @Test
    fun ipv6UdpDestinationIsRejectedWhenIpv6IsDisabled() {
        assertEquals(false, Socks5Server.acceptsUdpAddressType(4, false))
    }

    @Test
    fun ipv6UdpDestinationIsAcceptedWhenIpv6IsEnabled() {
        assertTrue(Socks5Server.acceptsUdpAddressType(4, true))
    }

    private val a = InetAddress.getByName("1.1.1.1")
    private val b = InetAddress.getByName("2.2.2.2")
    private val c = InetAddress.getByName("3.3.3.3")

    @Test
    fun missingFlowDoesNotProduceFallbackEndpoint() {
        val table = UdpFlowTable(maxFlows = 2, ttlMs = 1000)
        assertNull(table.findByDestination(a, 443, 1000))
    }

    @Test
    fun flowExpiresByTtl() {
        val table = UdpFlowTable(maxFlows = 2, ttlMs = 1000)
        table.getOrCreate("host:test", 443, InetSocketAddress("10.0.0.2", 50000), listOf(a), 0)
        assertNull(table.findByDestination(a, 443, 2001))
    }

    @Test
    fun oldestFlowIsEvicted() {
        val table = UdpFlowTable(maxFlows = 2, ttlMs = 60_000)
        table.getOrCreate("a", 1, InetSocketAddress("10.0.0.2", 1), listOf(a), 1)
        table.getOrCreate("b", 2, InetSocketAddress("10.0.0.2", 2), listOf(b), 2)
        table.getOrCreate("c", 3, InetSocketAddress("10.0.0.2", 3), listOf(c), 3)
        assertNull(table.findByDestination(a, 1, 3))
    }

    @Test
    fun unansweredFlowFallsBackToNextCandidate() {
        val table = UdpFlowTable(maxFlows = 2, ttlMs = 60_000)
        val flow = table.getOrCreate(
            "host:443", 443,
            InetSocketAddress("10.0.0.2", 50000),
            listOf(a, b),
            0,
        )!!
        table.touchSent(flow, 100)
        assertTrue(table.advanceCandidateIfUnanswered(flow, 851, 750))
        assertEquals(b, flow.candidates[flow.selectedIndex])
    }

    @Test
    fun recentlyTouchedFlowWinsLruEviction() {
        val table = UdpFlowTable(maxFlows = 2, ttlMs = 60_000)
        table.getOrCreate("a", 1, InetSocketAddress("10.0.0.2", 1), listOf(a), 1)
        table.getOrCreate("b", 2, InetSocketAddress("10.0.0.2", 2), listOf(b), 2)!!
        table.findByDestination(a, 1, 3)
        val cAddr = InetAddress.getByName("3.3.3.3")
        table.getOrCreate("c", 3, InetSocketAddress("10.0.0.2", 3), listOf(cAddr), 4)
        assertNull(table.findByDestination(b, 2, 4))
        assertTrue(table.findByDestination(a, 1, 4) != null)
    }

    @Test
    fun replyPreventsCandidateFallback() {
        val table = UdpFlowTable(maxFlows = 2, ttlMs = 60_000)
        val flow = table.getOrCreate(
            "host:443", 443,
            InetSocketAddress("10.0.0.2", 50000),
            listOf(a, b),
            0,
        )!!
        table.touchSent(flow, 100)
        table.findByDestination(a, 443, 200)
        assertTrue(!table.advanceCandidateIfUnanswered(flow, 1000, 750))
        assertEquals(0, flow.selectedIndex)
    }


    @Test
    fun multipleFlowsCanShareResolvedEndpointWithoutOverwritingEachOther() {
        val table = UdpFlowTable(maxFlows = 4, ttlMs = 60_000)
        val first = table.getOrCreate(
            "host:first:443", 443,
            InetSocketAddress("10.0.0.2", 50001),
            listOf(a),
            1,
        )!!
        val second = table.getOrCreate(
            "host:second:443", 443,
            InetSocketAddress("10.0.0.2", 50002),
            listOf(a),
            2,
        )!!
        table.touchSent(first, 3)
        table.touchSent(second, 4)

        assertEquals(2, table.size(4))
        assertEquals(1L, table.endpointCollisionCount())
        // An ambiguous remote endpoint must fail closed rather than misroute
        // the reply to whichever flow was most recently active.
        assertNull(table.findByDestination(a, 443, 5))
    }

    @Test
    fun uniqueRemoteEndpointStillCorrelatesNormally() {
        val table = UdpFlowTable(maxFlows = 4, ttlMs = 60_000)
        val flow = table.getOrCreate(
            "host:443",
            443,
            InetSocketAddress("10.0.0.2", 50001),
            listOf(a),
            1,
        )!!
        table.touchSent(flow, 2)
        assertEquals(flow, table.findByDestination(a, 443, 3))
    }

    @Test
    fun udpFlowKeySeparatesClientSourceEndpoints() {
        val first = Socks5Server.udpFlowKey(
            InetSocketAddress("10.0.0.2", 50001),
            listOf(a),
            443,
        )
        val second = Socks5Server.udpFlowKey(
            InetSocketAddress("10.0.0.2", 50002),
            listOf(a),
            443,
        )
        assertTrue(first != second)
    }

    @Test
    fun udpFlowKeyIsStableForCandidateOrdering() {
        val first = Socks5Server.udpFlowKey(
            InetSocketAddress("10.0.0.2", 50001),
            listOf(a, b),
            443,
        )
        val second = Socks5Server.udpFlowKey(
            InetSocketAddress("10.0.0.2", 50001),
            listOf(b, a),
            443,
        )
        assertEquals(first, second)
    }
}
