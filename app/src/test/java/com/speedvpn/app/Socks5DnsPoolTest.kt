package com.speedvpn.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5DnsPoolTest {
    @Test
    fun queuesConcurrentDnsLookupsInsteadOfRejectingAfterFourBusyWorkers() {
        val workerCount = 4
        val queueCapacity = 64
        val executor = Socks5Server.createDnsExecutor(workerCount, queueCapacity)
        val workersStarted = CountDownLatch(workerCount)
        val releaseWorkers = CountDownLatch(1)

        try {
            repeat(workerCount) {
                executor.execute {
                    workersStarted.countDown()
                    check(releaseWorkers.await(5, TimeUnit.SECONDS)) {
                        "Test did not release the DNS worker in time"
                    }
                }
            }

            assertTrue(
                "All configured DNS workers should start before the queue is exercised",
                workersStarted.await(2, TimeUnit.SECONDS),
            )

            val queuedLookups = (0 until queueCapacity).map {
                executor.submit { Unit }
            }
            assertEquals("All bounded queue slots should accept pending DNS work", queueCapacity, executor.queue.size)

            var rejectedWhenFull = false
            try {
                executor.execute { Unit }
            } catch (_: RejectedExecutionException) {
                rejectedWhenFull = true
            }
            assertTrue("Reject only after all workers and bounded queue slots are occupied", rejectedWhenFull)

            releaseWorkers.countDown()
            queuedLookups.forEach { it.get(2, TimeUnit.SECONDS) }
            assertTrue(queuedLookups.all { it.isDone })
        } finally {
            releaseWorkers.countDown()
            executor.shutdownNow()
            assertTrue("DNS executor should shut down cleanly", executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
