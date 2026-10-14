package com.speedvpn.app

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import hev.htproxy.TProxyService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Temporary diagnostic only. This class observes tuples reported at the native TUN read
 * point and asks Android which UID owns each tuple. Results are logged under
 * UidDetectionPOC and never affect packet forwarding.
 */
internal class UidDetectionPoc(
    private val service: SpeedVpnService,
) : TProxyService.UidPocCallback, AutoCloseable {
    private data class Tuple(
        val protocol: Int,
        val src: String,
        val srcPort: Int,
        val dst: String,
        val dstPort: Int,
    ) {
        override fun toString(): String =
            "${protocolName(protocol)}|$src|$srcPort|$dst|$dstPort"
    }

    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val packageManager = service.packageManager
    private val targets = listOf(
        "com.android.chrome",
        "com.whatsapp",
        "com.google.android.youtube", // Replace with an installed third-party test app if absent.
    )
    private val expectedUidPackages: Map<Int, List<String>> = targets
        .mapNotNull { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0).uid to pkg }.getOrNull()
        }
        .groupBy({ it.first }, { it.second })
    private val lastQueuedAt = ConcurrentHashMap<Tuple, Long>()
    private val attempts = ConcurrentHashMap<Tuple, AtomicInteger>()
    private val resolved = ConcurrentHashMap.newKeySet<Tuple>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(128),
        { task -> Thread(task, "uid-detection-poc").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    init {
        Log.i(TAG, "START Android API=${Build.VERSION.SDK_INT}; package=${service.packageName}")
        targets.forEach { pkg ->
            val expected = runCatching { packageManager.getApplicationInfo(pkg, 0).uid }
                .getOrNull()
            Log.i(TAG, "EXPECTED package=$pkg uid=${expected ?: "NOT_INSTALLED"}")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "UNSUPPORTED getConnectionOwnerUid requires Android 10 / API 29+")
        }
    }

    override fun onTunPacket(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (protocol != IPPROTO_TCP && protocol != IPPROTO_UDP) return
        if (srcPort !in 0..65535 || dstPort !in 0..65535) return
        if (srcAddress.size !in setOf(4, 16) || dstAddress.size != srcAddress.size) return

        val src = runCatching { InetAddress.getByAddress(srcAddress).hostAddress }.getOrNull() ?: return
        val dst = runCatching { InetAddress.getByAddress(dstAddress).hostAddress }.getOrNull() ?: return
        val tuple = Tuple(protocol, src, srcPort, dst, dstPort)
        if (tuple in resolved) return

        val now = SystemClock.elapsedRealtime()
        val previous = lastQueuedAt.put(tuple, now)
        if (previous != null && now - previous < RETRY_INTERVAL_MS) return

        val attempt = attempts.computeIfAbsent(tuple) { AtomicInteger() }.incrementAndGet()
        if (attempt > MAX_ATTEMPTS) return

        // Never perform the Binder-backed Android UID lookup on the TUN engine thread.
        runCatching {
            executor.execute {
                try {
                    val local = InetSocketAddress(InetAddress.getByAddress(srcAddress), srcPort)
                    val remote = InetSocketAddress(InetAddress.getByAddress(dstAddress), dstPort)
                    val uid = connectivity.getConnectionOwnerUid(protocol, local, remote)
                    val packages = if (uid == Process.INVALID_UID) {
                        emptyList()
                    } else {
                        packageManager.getPackagesForUid(uid)?.sorted().orEmpty()
                    }
                    val targetMatch = expectedUidPackages[uid]?.joinToString(",") ?: "-"
                    val verdict = when {
                        uid == Process.INVALID_UID -> "INVALID_UID"
                        targetMatch != "-" -> "MATCH_TARGET"
                        else -> "UID_FOUND_OTHER_OR_UNLISTED"
                    }
                    Log.i(
                        TAG,
                        "RESULT verdict=$verdict tuple=$tuple uid=$uid " +
                            "packages=${packages.joinToString(",").ifBlank { "-" }} " +
                            "targetMatch=$targetMatch attempt=$attempt",
                    )
                    if (uid != Process.INVALID_UID) resolved.add(tuple)
                } catch (e: SecurityException) {
                    Log.e(TAG, "RESULT verdict=SECURITY_EXCEPTION tuple=$tuple attempt=$attempt", e)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "RESULT verdict=BAD_ARGUMENT tuple=$tuple attempt=$attempt", e)
                } catch (e: Exception) {
                    Log.e(TAG, "RESULT verdict=ERROR tuple=$tuple attempt=$attempt", e)
                }
            }
        }.onFailure {
            Log.w(TAG, "QUEUE_DROP tuple=$tuple reason=${it.javaClass.simpleName}")
        }
    }

    override fun close() {
        executor.shutdownNow()
        Log.i(TAG, "STOP")
    }

    private companion object {
        const val TAG = "UidDetectionPOC"
        const val IPPROTO_TCP = 6
        const val IPPROTO_UDP = 17
        const val RETRY_INTERVAL_MS = 750L
        const val MAX_ATTEMPTS = 4

        fun protocolName(protocol: Int): String = when (protocol) {
            IPPROTO_TCP -> "TCP"
            IPPROTO_UDP -> "UDP"
            else -> protocol.toString()
        }
    }
}
