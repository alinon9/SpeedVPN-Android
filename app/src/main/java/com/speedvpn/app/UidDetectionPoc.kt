package com.speedvpn.app

import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import hev.htproxy.TProxyService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class UidDetectionRecord(
    val id: Long,
    val timestampMillis: Long,
    val packageName: String,
    val protocol: String,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val uid: Int?,
    val status: String,
    val details: String = "",
)

/** In-process POC UI model. No packet is blocked or modified by these diagnostics. */
internal object UidDetectionPocResultStore {
    private const val MAX_RECORDS = 1000
    private val nextId = AtomicLong(0L)
    private val _recording = MutableStateFlow(false)
    private val _records = MutableStateFlow<List<UidDetectionRecord>>(emptyList())

    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    val records: StateFlow<List<UidDetectionRecord>> = _records.asStateFlow()
    val isRecording: Boolean get() = _recording.value

    fun startRecording() { _recording.value = true }
    fun stopRecording() { _recording.value = false }
    fun clear() { _records.value = emptyList() }

    fun add(
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        uid: Int?,
        packages: List<String>,
        status: String,
        details: String = "",
    ) {
        if (!_recording.value) return
        val packageLabel = when {
            packages.isNotEmpty() -> packages.sorted().distinct().joinToString(", ")
            uid != null && uid != Process.INVALID_UID -> "UID:$uid (package name unavailable)"
            else -> "—"
        }
        val record = UidDetectionRecord(
            id = nextId.incrementAndGet(),
            timestampMillis = System.currentTimeMillis(),
            packageName = packageLabel,
            protocol = when (protocol) { 6 -> "TCP"; 17 -> "UDP"; else -> protocol.toString() },
            srcIp = srcIp,
            srcPort = srcPort,
            dstIp = dstIp,
            dstPort = dstPort,
            uid = uid,
            status = status,
            details = details,
        )
        _records.update { (it + record).takeLast(MAX_RECORDS) }
    }

    fun exportCsv(): String {
        fun cell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
        val header = listOf(
            "timestamp", "packageName", "protocol", "srcIp", "srcPort",
            "dstIp", "dstPort", "uid", "status", "details",
        ).joinToString(",")
        val rows = _records.value.map { r ->
            listOf(
                cell(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(r.timestampMillis))),
                cell(r.packageName),
                cell(r.protocol),
                cell(r.srcIp),
                r.srcPort.toString(),
                cell(r.dstIp),
                r.dstPort.toString(),
                r.uid?.toString() ?: "",
                cell(r.status),
                cell(r.details),
            ).joinToString(",")
        }
        return (listOf(header) + rows).joinToString("\n", postfix = "\n")
    }
}

/**
 * Per-flow UID resolver and policy decision. The native flow cache invokes this synchronous
 * resolver only on a cache miss, using its dedicated JVM-attached worker thread. Packet
 * dropping itself is performed by the native TUN read path, not by this resolver.
 */
internal class UidDetectionPoc(
    private val service: SpeedVpnService,
) : TProxyService.UidPocCallback, AutoCloseable {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val packageManager = service.packageManager
    private data class BlockPolicySnapshot(
        val packages: Set<String>,
        val uids: Set<Int>,
        val packagesByUid: Map<Int, Set<String>>,
    )

    @Volatile private var blockedUids: Set<Int> = emptySet()
    @Volatile private var blockedPackages: Set<String> = emptySet()
    @Volatile private var blockPolicy = BlockPolicySnapshot(emptySet(), emptySet(), emptyMap())
    private val nativeCallbackSequence = AtomicLong(0L)

    internal fun updateBlockedPackages(packages: Set<String>) {
        val normalized = packages.toSet()
        val byUid = normalized.mapNotNull { pkg ->
            runCatching { pkg to packageManager.getApplicationInfo(pkg, 0).uid }
                .getOrNull()
        }.filter { it.second > 0 }
            .groupBy({ it.second }, { it.first })
            .mapValues { (_, names) -> names.toSet() }
        val nextUids = VpnApplicationPolicy.blockedUids(service, normalized)
        blockPolicy = BlockPolicySnapshot(normalized, nextUids, byUid)
        blockedPackages = normalized
        blockedUids = nextUids
        Log.i(TAG, "BLOCK_POLICY_UPDATED packages=${normalized.sorted().joinToString(",")} uids=${nextUids.sorted().joinToString(",")}")
    }

    internal fun isUidBlocked(uid: Int): Boolean =
        VpnApplicationPolicy.shouldDropUid(uid, blockedUids)

    init {
        Log.i(TAG, "START Android API=${Build.VERSION.SDK_INT}; package=${service.packageName}")
        UidPocDiagnostics.log(
            service,
            "UidDetectionPoc.init; API=${Build.VERSION.SDK_INT}; package=${service.packageName}; resolver=dedicated-native-worker",
        )
        updateBlockedPackages(service.currentBlockedPackages())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "UNSUPPORTED getConnectionOwnerUid requires Android 10 / API 29+")
            UidPocDiagnostics.log(service, "UNSUPPORTED: getConnectionOwnerUid requires API 29+")
        }
    }

    override fun onTunPacket(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
    ): Long {
        val callId = nativeCallbackSequence.incrementAndGet()
        val protocolName = protocolName(protocol)
        val source = addressText(srcAddress)
        val destination = addressText(dstAddress)
        UidPocDiagnostics.jniEvent(
            service,
            "KOTLIN_RESOLVER_ENTRY id=$callId protocol=$protocolName src=$source:$srcPort dst=$destination:$dstPort srcBytes=${srcAddress.size} dstBytes=${dstAddress.size}",
        )

        val isMarkedProbeForProtocol = SpeedLimiter.isMarkedProbeClientSocket(
            localPort = srcPort,
            remoteHostOrIp = destination,
            remotePort = dstPort,
            protocol = protocol,
        )
        var uid = Process.INVALID_UID
        var outcome = "returned"
        try {
            val resolution = resolveUid(
                protocol = protocol,
                srcAddress = srcAddress,
                srcPort = srcPort,
                dstAddress = dstAddress,
                dstPort = dstPort,
                callId = callId,
                isMarkedProbeForProtocol = isMarkedProbeForProtocol,
            )
            uid = resolution.uid
            val action = if (resolution.shouldBlock) "BLOCK" else "ALLOW"
            val encoded = encodeUidDecision(resolution.uid, resolution.shouldBlock, resolution.isVerificationProbe)
            UidPocDiagnostics.jniEvent(
                service,
                "KOTLIN_RESOLVER_DECISION id=$callId verdict=${resolution.verdict} uid=${resolution.uid} action=$action hookMode=HOOK_BLOCKING packetBlocking=IMPLEMENTED",
            )
            outcome = "returned:$action"
            return encoded
        } catch (error: Throwable) {
            outcome = "throwable:${error.javaClass.simpleName}"
            reportException(protocol, source, srcPort, destination, dstPort, callId, error)
            return encodeUidDecision(
                Process.INVALID_UID,
                VpnApplicationPolicy.shouldDropUnresolvedUid(
                    blockedUids = blockPolicy.uids,
                    attributionExhausted = true,
                    protocol = protocol,
                    dstIp = destination,
                    dstPort = dstPort,
                ),
                isVerificationProbe = isMarkedProbeForProtocol,
            )
        } finally {
            UidPocDiagnostics.jniEvent(
                service,
                "KOTLIN_RESOLVER_EXIT id=$callId outcome=$outcome uid=$uid",
            )
        }
    }

    private data class UidResolution(
        val uid: Int,
        val verdict: String,
        val shouldBlock: Boolean,
        val isVerificationProbe: Boolean = false,
    )

    /**
     * JNI wire format:
     * - High 32 bits: signed UID (`-1` when unresolved).
     * - Bit 1 (`2L`): `1` when the packet's exact 5-tuple belongs to a verified probe socket
     *   registered via `SpeedLimiter.markProbeClientSocket`, `0` for normal user/app traffic.
     * - Bit 0 (`1L`): `1` = BLOCK/DROP, `0` = ALLOW.
     */
    internal fun encodeUidDecision(
        uid: Int,
        shouldBlock: Boolean,
        isVerificationProbe: Boolean = false,
    ): Long =
        (uid.toLong() shl 32) or
            (if (isVerificationProbe) 2L else 0L) or
            (if (shouldBlock) 1L else 0L)

    private fun unresolvedUid(
        protocol: Int = -1,
        dstIp: String = "",
        dstPort: Int = -1,
    ): UidResolution {
        val shouldBlock = VpnApplicationPolicy.shouldDropUnresolvedUid(
            blockedUids = blockPolicy.uids,
            attributionExhausted = true,
            protocol = protocol,
            dstIp = dstIp,
            dstPort = dstPort,
        )
        return UidResolution(
            Process.INVALID_UID,
            if (shouldBlock) "UID_UNRESOLVED_FAIL_CLOSED" else "INVALID_UID",
            shouldBlock,
        )
    }

    /** Runs only on the dedicated native UID worker, never on the TUN coroutine. */
    private fun resolveUid(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
        callId: Long,
        isMarkedProbeForProtocol: Boolean = false,
    ): UidResolution {
        if (protocol != IPPROTO_TCP && protocol != IPPROTO_UDP) {
            UidPocDiagnostics.log(service, "OWNER_UID_QUERY_SKIPPED id=$callId reason=UNSUPPORTED_PROTOCOL protocol=$protocol")
            return unresolvedUid()
        }
        if (srcPort !in 0..65535 || dstPort !in 0..65535 ||
            srcAddress.size !in setOf(4, 16) || dstAddress.size != srcAddress.size
        ) {
            UidPocDiagnostics.log(service, "OWNER_UID_QUERY_SKIPPED id=$callId reason=INVALID_TUPLE")
            return unresolvedUid()
        }

        val src = InetAddress.getByAddress(srcAddress).hostAddress ?: "unknown"
        val dst = InetAddress.getByAddress(dstAddress).hostAddress ?: "unknown"
        val tuple = "${protocolName(protocol)}|$src|$srcPort|$dst|$dstPort"
        val local = InetSocketAddress(InetAddress.getByAddress(srcAddress), srcPort)
        val remote = InetSocketAddress(InetAddress.getByAddress(dstAddress), dstPort)

        UidPocDiagnostics.log(service, "OWNER_UID_QUERY_BEGIN id=$callId tuple=$tuple thread=${Thread.currentThread().name}")
        val androidUid = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            UidPocDiagnostics.log(service, "OWNER_UID_QUERY_SKIPPED id=$callId reason=API_BELOW_29; trying procfs")
            Process.INVALID_UID
        } else {
            runCatching { connectivity.getConnectionOwnerUid(protocol, local, remote) }
                .onFailure {
                    UidPocDiagnostics.error(service, "OWNER_UID_API_FAILED id=$callId; trying procfs", it)
                }
                .getOrDefault(Process.INVALID_UID)
        }
        val procUid = if (androidUid == Process.INVALID_UID) {
            runCatching {
                ProcSocketUidResolver.resolve(
                    protocol = protocol,
                    srcAddress = srcAddress,
                    srcPort = srcPort,
                    dstAddress = dstAddress,
                    dstPort = dstPort,
                )
            }.onFailure {
                UidPocDiagnostics.error(service, "PROC_UID_FALLBACK_FAILED id=$callId tuple=$tuple", it)
            }.getOrNull().also { fallbackUid ->
                UidPocDiagnostics.log(
                    service,
                    "PROC_UID_FALLBACK id=$callId tuple=$tuple uid=${fallbackUid ?: Process.INVALID_UID} " +
                        "result=${if (fallbackUid == null) "UNRESOLVED" else "MATCH"}",
                )
            }
        } else {
            null
        }
        val uid = androidUid.takeIf { it != Process.INVALID_UID }
            ?: procUid
            ?: Process.INVALID_UID
        val uidSource = when {
            androidUid != Process.INVALID_UID -> "ANDROID_API"
            procUid != null -> "PROC_NET"
            else -> "UNRESOLVED"
        }
        val packages = if (uid == Process.INVALID_UID) {
            emptyList()
        } else {
            runCatching { packageManager.getPackagesForUid(uid)?.sorted().orEmpty() }
                .getOrDefault(emptyList())
        }
        val policy = blockPolicy
        val policyPackagesForUid = policy.packagesByUid[uid].orEmpty()
        val knownPackages = (packages + policyPackagesForUid).distinct().sorted()
        val blockedPackageMatches = knownPackages.filter { it in policy.packages }
        val shouldBlock = if (uid == Process.INVALID_UID) {
            VpnApplicationPolicy.shouldDropUnresolvedUid(
                blockedUids = policy.uids,
                attributionExhausted = true,
                protocol = protocol,
                dstIp = dst,
                dstPort = dstPort,
            )
        } else {
            VpnApplicationPolicy.shouldDropUid(uid, policy.uids)
        }
        val targetMatch = blockedPackageMatches.joinToString(",").ifBlank { "-" }
        val verdict = when {
            uid == Process.INVALID_UID && shouldBlock -> "UID_UNRESOLVED_FAIL_CLOSED"
            uid == Process.INVALID_UID -> "INVALID_UID"
            shouldBlock -> "BLOCKED_UID"
            else -> "UID_FOUND_OTHER_OR_UNLISTED"
        }
        val action = if (shouldBlock) "BLOCK" else "ALLOW"
        UidDetectionPocResultStore.add(
            protocol, src, srcPort, dst, dstPort, uid, knownPackages,
            when {
                uid == Process.INVALID_UID && shouldBlock -> "BLOCKED_UNRESOLVED"
                uid == Process.INVALID_UID -> "INVALID"
                shouldBlock -> "BLOCKED"
                else -> "MATCH"
            },
            "resolverCall=$callId; uidSource=$uidSource; targetMatch=$targetMatch; action=$action; thread=${Thread.currentThread().name}",
        )
        Log.i(
            TAG,
            "RESULT verdict=$verdict tuple=$tuple uid=$uid " +
                "packages=${knownPackages.joinToString(",").ifBlank { "-" }} " +
                "targetMatch=$targetMatch action=$action hookMode=HOOK_BLOCKING packetBlocking=IMPLEMENTED resolverCall=$callId",
        )
        UidPocDiagnostics.jniEvent(
            service,
            "OWNER_UID_QUERY_RESULT id=$callId verdict=$verdict uid=$uid packages=${knownPackages.joinToString(",").ifBlank { "-" }} action=$action hookMode=HOOK_BLOCKING packetBlocking=IMPLEMENTED",
        )
        val isProbeClientSocket = isMarkedProbeForProtocol ||
            SpeedLimiter.isMarkedProbeClientSocket(
                localPort = srcPort,
                remoteHostOrIp = dst,
                remotePort = dstPort,
                protocol = protocol,
            )
        if (uid != Process.INVALID_UID || isProbeClientSocket) {
            service.recordDetectedFlow(
                protocol = protocol,
                srcIp = src,
                srcPort = srcPort,
                dstIp = dst,
                dstPort = dstPort,
                uid = uid,
                packages = knownPackages,
                isVerificationProbe = isProbeClientSocket,
            )
        }
        if (shouldBlock) {
            Log.w(TAG, "BLOCKED_UID uid=$uid packages=${blockedPackageMatches.joinToString(",").ifBlank { "-" }} tuple=$tuple hookMode=HOOK_BLOCKING packetBlocking=IMPLEMENTED")
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
        }
        return UidResolution(uid, verdict, shouldBlock, isVerificationProbe = isProbeClientSocket)
    }

    private fun reportException(
        protocol: Int,
        src: String,
        srcPort: Int,
        dst: String,
        dstPort: Int,
        callId: Long,
        error: Throwable,
    ) {
        val details = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        UidDetectionPocResultStore.add(
            protocol, src, srcPort, dst, dstPort, null, emptyList(), "EXCEPTION",
            "resolverCall=$callId; $details",
        )
        Log.e(
            TAG,
            "RESULT verdict=EXCEPTION tuple=${protocol}|$src|$srcPort|$dst|$dstPort resolverCall=$callId details=$details",
            error,
        )
        UidPocDiagnostics.error(
            service,
            "OWNER_UID_QUERY_EXCEPTION tuple=${protocol}|$src|$srcPort|$dst|$dstPort resolverCall=$callId",
            error,
        )
    }

    private fun protocolName(protocol: Int): String = when (protocol) {
        IPPROTO_TCP -> "TCP"
        IPPROTO_UDP -> "UDP"
        else -> protocol.toString()
    }

    private fun addressText(address: ByteArray): String =
        runCatching { InetAddress.getByAddress(address).hostAddress ?: "unknown" }
            .getOrElse { "invalid-address-${address.size}-bytes" }

    override fun close() {
        // The native setter joins the worker before the Service releases this callback.
        Log.i(TAG, "STOP resolverCalls=${nativeCallbackSequence.get()}")
    }

    companion object {
        private const val TAG = "UidDetectionPOC"
        internal const val IPPROTO_TCP = 6
        internal const val IPPROTO_UDP = 17

        /**
         * Mirrors the native C `hev_socks5_session_run` 5-tuple session bridge:
         * converts the packed 64-bit `onTunPacket` decision for an exact 5-tuple into
         * the per-session SOCKS5 authentication username (`user#uid#probe`, `user#probe`,
         * `user#uid`, or `user`).
         */
        internal fun socksUsernameForDecision(baseUsername: String, encodedDecision: Long): String {
            val uid = (encodedDecision ushr 32).toInt().takeIf { it >= 0 }
            val isProbe = (encodedDecision and 2L) != 0L
            return when {
                isProbe -> Socks5Server.probeUsername(baseUsername, uid)
                uid != null -> "$baseUsername#$uid"
                else -> baseUsername
            }
        }
    }
}
