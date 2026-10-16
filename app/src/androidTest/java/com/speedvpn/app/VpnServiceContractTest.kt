package com.speedvpn.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import hev.htproxy.TProxyService
import java.io.File
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnServiceContractTest {
    @Test
    fun vpnServiceIsDeclaredAndProtected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, SpeedVpnService::class.java),
            0,
        )
        assertEquals("android.permission.BIND_VPN_SERVICE", service.permission)
        assertEquals(false, service.exported)
    }

    @Test
    fun vpnPrepareContractIsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent: Intent? = VpnService.prepare(context)
        // Null means this test app already has VPN consent; non-null is the normal
        // pre-consent state. Both are valid platform states.
        assertNotNull(context.packageName)
        @Suppress("UNUSED_VARIABLE")
        val ignored = intent
    }


    @Test
    fun nativeTunnelRapidStartStopRestartKeepsTunDescriptorValidUntilJoin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configFile = File(context.cacheDir, "native-lifecycle-${System.nanoTime()}.yml")
        configFile.writeText(
            """
            tunnel:
              mtu: 1280
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
            socks5:
              port: 39080
              address: 127.0.0.1
              udp: 'udp'
              username: 'native-lifecycle-test'
              password: 'native-lifecycle-test'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 100
            misc:
              task-stack-size: 131072
              tcp-buffer-size: 65535
              udp-recv-buffer-size: 262144
              udp-copy-buffer-nums: 12
              max-session-count: 80
              connect-timeout: 12000
              tcp-read-write-timeout: 600000
              udp-read-write-timeout: 60000
            """.trimIndent(),
        )

        var socket: DatagramSocket? = null
        var tunPfd: ParcelFileDescriptor? = null
        var nativeMayOwnTunFd = false
        try {
            assertFalse(
                "Test will not interfere with an already-running native VPN",
                TProxyService.TProxyIsRunning(),
            )
            assertTrue(
                "Could not reap a previous stopped native worker",
                TProxyService.TProxyStopService(),
            )
            // /proc/self/fd is available to the app process on Android. Permit two
            // framework/runtime descriptors for lazy initialization, but repeated
            // native start/stop cycles must not grow the descriptor count per cycle.
            val baselineFdCount = File("/proc/self/fd").list()?.size

            repeat(8) { cycle ->
                socket = DatagramSocket()
                tunPfd = ParcelFileDescriptor.fromDatagramSocket(socket!!)
                val fd = tunPfd!!.fd

                assertTrue(
                    "Native start failed at rapid lifecycle cycle $cycle",
                    TProxyService.TProxyStartService(configFile.absolutePath, fd),
                )
                nativeMayOwnTunFd = true

                val startDeadline = SystemClock.elapsedRealtime() + 5_000L
                while (!TProxyService.TProxyIsRunning() &&
                    SystemClock.elapsedRealtime() < startDeadline
                ) {
                    Thread.sleep(10L)
                }
                assertTrue(
                    "Native worker never became live at cycle $cycle",
                    TProxyService.TProxyIsRunning(),
                )
                assertFalse(
                    "A second start was accepted while native worker was running",
                    TProxyService.TProxyStartService(configFile.absolutePath, fd),
                )

                val gate = CountDownLatch(1)
                val stopPool = Executors.newFixedThreadPool(2)
                try {
                    val stopOne = stopPool.submit<Boolean> {
                        gate.await()
                        TProxyService.TProxyStopService()
                    }
                    val stopTwo = stopPool.submit<Boolean> {
                        gate.await()
                        TProxyService.TProxyStopService()
                    }
                    gate.countDown()
                    val stopResults = listOf(
                        stopOne.get(10, TimeUnit.SECONDS),
                        stopTwo.get(10, TimeUnit.SECONDS),
                    )
                    assertTrue(
                        "Concurrent stop/join was not confirmed at cycle $cycle: $stopResults",
                        stopResults.all { it },
                    )
                } finally {
                    gate.countDown()
                    stopPool.shutdownNow()
                    assertTrue(
                        "Native stop callers did not terminate at cycle $cycle",
                        stopPool.awaitTermination(5, TimeUnit.SECONDS),
                    )
                }

                // Native ownership is released only after both stop calls report a successful join.
                nativeMayOwnTunFd = false
                assertFalse(
                    "Native worker remained running after join at cycle $cycle",
                    TProxyService.TProxyIsRunning(),
                )
                tunPfd!!.close()
                tunPfd = null
                socket!!.close()
                socket = null
            }

            val finalFdCount = File("/proc/self/fd").list()?.size
            if (baselineFdCount != null && finalFdCount != null) {
                assertTrue(
                    "Native lifecycle leaked file descriptors across eight cycles: " +
                        "baseline=$baselineFdCount final=$finalFdCount",
                    finalFdCount <= baselineFdCount + 2,
                )
            }
        } finally {
            if (nativeMayOwnTunFd || tunPfd != null) {
                var joined = false
                repeat(3) {
                    if (!joined) {
                        joined = runCatching { TProxyService.TProxyStopService() }
                            .getOrDefault(false)
                    }
                }
                if (joined) {
                    runCatching { tunPfd?.close() }
                    runCatching { socket?.close() }
                } else {
                    // Deliberately preserve the descriptor if native thread completion cannot be proven.
                    throw AssertionError(
                        "Native worker could not be joined; TUN descriptor was intentionally not closed",
                    )
                }
            } else {
                runCatching { tunPfd?.close() }
                runCatching { socket?.close() }
            }
            configFile.delete()
        }
    }

}
