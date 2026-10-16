package com.speedvpn.app

import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import hev.htproxy.TProxyService
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Resolves packet flow owners and returns the allow/drop decision to the native TUN hook.
 * The callback runs on the dedicated native UID worker, never on the HEV coroutine.
 */
internal class AppUidBlocker(
    private val service: SpeedVpnService,
) : TProxyService.AppUidBlockCallback, AutoCloseable {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val packageManager = service.packageManager

    private data class BlockPolicySnapshot(
        val packages: Set<String>,
        val uids: Set<Int>,
        val packagesByUid: Map<Int, Set<String>>,
    )

    @Volatile private var blockedUids: Set<Int> = emptySet()
    @Volatile private var blockPolicy =
        BlockPolicySnapshot(emptySet(), emptySet(), emptyMap())

    internal fun updateBlockedPackages(packages: Set<String>) {
        val normalized = packages.toSet()
        val byUid = normalized.mapNotNull { pkg ->
            runCatching { pkg to packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
        }.filter { it.second > 0 }
            .groupBy({ it.second }, { it.first })
            .mapValues { (_, names) -> names.toSet() }
        val nextUids = VpnApplicationPolicy.blockedUids(service, normalized)
        blockPolicy = BlockPolicySnapshot(normalized, nextUids, byUid)
        blockedUids = nextUids
    }

    override fun onTunPacket(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
    ): Long {
        val resolution = try {
            resolveUid(protocol, srcAddress, srcPort, dstAddress, dstPort)
        } catch (error: Throwable) {
            Log.w(TAG, "UID owner lookup failed; unresolved flow is allowed", error)
            UidResolution(Process.INVALID_UID, false)
        }
        // JNI wire format: signed UID in high 32 bits; low bit 1 means DROP, 0 means ALLOW.
        return (resolution.uid.toLong() shl 32) or (if (resolution.shouldBlock) 1L else 0L)
    }

    private data class UidResolution(val uid: Int, val shouldBlock: Boolean)

    private fun resolveUid(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
    ): UidResolution {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return UidResolution(Process.INVALID_UID, false)
        }
        if (protocol != IPPROTO_TCP && protocol != IPPROTO_UDP) {
            return UidResolution(Process.INVALID_UID, false)
        }
        if (srcPort !in 0..65535 || dstPort !in 0..65535 ||
            srcAddress.size !in setOf(4, 16) || dstAddress.size != srcAddress.size
        ) {
            return UidResolution(Process.INVALID_UID, false)
        }

        val srcInet = InetAddress.getByAddress(srcAddress)
        val dstInet = InetAddress.getByAddress(dstAddress)
        val src = srcInet.hostAddress ?: "unknown"
        val dst = dstInet.hostAddress ?: "unknown"
        val tuple = "${protocolName(protocol)}|$src:$srcPort->$dst:$dstPort"
        val uid = connectivity.getConnectionOwnerUid(
            protocol,
            InetSocketAddress(srcInet, srcPort),
            InetSocketAddress(dstInet, dstPort),
        )

        val installedPackages = if (uid == Process.INVALID_UID) {
            emptyList()
        } else {
            packageManager.getPackagesForUid(uid)?.sorted().orEmpty()
        }
        val policy = blockPolicy
        val expectedPackages = policy.packagesByUid[uid].orEmpty()
        val knownPackages = (installedPackages + expectedPackages).distinct().sorted()
        val blockedPackageMatches = knownPackages.filter { it in policy.packages }
        val shouldBlock = uid != Process.INVALID_UID && uid in policy.uids

        if (uid != Process.INVALID_UID) {
            service.recordDetectedFlow(
                protocol = protocol,
                srcIp = src,
                srcPort = srcPort,
                dstIp = dst,
                dstPort = dstPort,
                uid = uid,
                packages = knownPackages,
            )
        }
        if (shouldBlock) {
            service.enforceBlockedDetectedFlow(
                protocol = protocol,
                srcIp = src,
                srcPort = srcPort,
                dstIp = dst,
                dstPort = dstPort,
                uid = uid,
                packages = knownPackages,
                policyBlockedPackages = blockedPackageMatches,
                permanent = false,
            )
            Log.i(TAG, "APP_PACKET_POLICY action=DROP uid=$uid tuple=$tuple")
        }
        return UidResolution(uid, shouldBlock)
    }

    private fun protocolName(protocol: Int): String = when (protocol) {
        IPPROTO_TCP -> "TCP"
        IPPROTO_UDP -> "UDP"
        else -> protocol.toString()
    }

    override fun close() = Unit

    private companion object {
        const val TAG = "SpeedVPN-AppBlock"
        const val IPPROTO_TCP = 6
        const val IPPROTO_UDP = 17
    }
}
