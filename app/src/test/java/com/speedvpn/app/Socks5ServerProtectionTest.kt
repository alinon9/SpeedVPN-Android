package com.speedvpn.app

import android.net.Network
import java.net.DatagramChannel
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Socks5ServerProtectionTest {
    @Test
    fun protectFalseClosesTcpSocketBeforeAnyOutboundIo() {
        val socket = Socket()
        var protectCalls = 0

        val failure = assertThrows(SocketException::class.java) {
            protectSocketOrClose(socket, {
                protectCalls++
                false
            }, "unit TCP socket")
        }

        assertEquals(1, protectCalls)
        assertTrue("unprotected TCP socket must be closed immediately", socket.isClosed)
        assertTrue(failure.message.orEmpty().contains("protect"))
    }

    @Test
    fun protectFalseClosesDatagramChannelAndItsSocketImmediately() {
        val channel = DatagramChannel.open()
        val socket = channel.socket()

        assertThrows(SocketException::class.java) {
            protectSocketOrClose(socket, { false }, "unit UDP channel")
        }

        assertTrue("closing the socket must close its owning channel", !channel.isOpen)
        assertTrue(socket.isClosed)
    }

    @Test
    fun stopClosesActiveClientReleasesPortAndTerminatesWorkerThreads() {
        val preexistingIds = Thread.getAllStackTraces().keys
            .filter { it.name == "socks-worker" || it.name == "socks-accept" }
            .map { it.id }
            .toSet()
        val server = Socks5Server(
            generation = 1L,
            protectTcp = { false },
            protectUdp = { false },
            currentNetwork = { null as Network? },
            ipv6Enabled = false,
        )
        val port = server.port
        val client = Socket()
        try {
            server.start()
            client.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1_000)
            val input = client.getInputStream()
            val output = client.getOutputStream()
            output.write(byteArrayOf(5, 1, 2))
            output.flush()
            assertEquals(5, input.read())
            assertEquals(2, input.read())

            server.stop()
            client.soTimeout = 1_000
            val closedByServer = try {
                input.read() == -1
            } catch (_: SocketException) {
                true
            }
            assertTrue("active SOCKS client must be released by stop()", closedByServer)

            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use {
                assertTrue("listening port must be reusable after stop()", it.isBound)
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline && Thread.getAllStackTraces().keys.any {
                    (it.name == "socks-worker" || it.name == "socks-accept") && it.id !in preexistingIds
                }
            ) {
                Thread.sleep(20)
            }
            assertFalse(
                "stop() must terminate this server's accept/handler workers",
                Thread.getAllStackTraces().keys.any {
                    (it.name == "socks-worker" || it.name == "socks-accept") && it.id !in preexistingIds
                },
            )
        } finally {
            server.stop()
            client.close()
        }
    }
}
