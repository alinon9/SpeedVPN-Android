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
 * Temporary POC observer. The native flow cache invokes this synchronous resolver only
 * on a cache miss, using its dedicated JVM-attached worker thread. Never called per packet
 * on the HEV coroutine.
 */
internal class UidDetectionPoc(
    private val service: SpeedVpnService,
) : TProxyService.UidPocCallback, AutoCloseable {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val packageManager = service.packageManager
    private val targets = listOf(
        "com.android.chrome",
        "com.whatsapp",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.google.android.youtube",
        "com.android.vending",
    )
    private val expectedUidPackages: Map<Int, List<String>> = targets
        .mapNotNull { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0).uid to pkg }.getOrNull()
        }
        .groupBy({ it.first }, { it.second })
    private val nativeCallbackSequence = AtomicLong(0L)

    private data class UidResolution(val uid: Int, val blocked: Boolean)

    init {
        Log.i(TAG, "START Android API=${Build.VERSION.SDK_INT}; package=${service.packageName}")
        UidPocDiagnostics.log(
            service,
            "UidDetectionPoc.init; API=${Build.VERSION.SDK_INT}; package=${service.packageName}; resolver=dedicated-native-worker",
        )
        targets.forEach { pkg ->
            val expected = runCatching { packageManager.getApplicationInfo(pkg, 0).uid }
                .getOrNull()
            Log.i(TAG, "EXPECTED package=$pkg uid=${expected ?: "NOT_INSTALLED"}")
        }
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
    ): Int {
        val callId = nativeCallbackSequence.incrementAndGet()
        val protocolName = protocolName(protocol)
        val source = addressText(srcAddress)
        val destination = addressText(dstAddress)
        UidPocDiagnostics.jniEvent(
            service,
            "KOTLIN_RESOLVER_ENTRY id=$callId protocol=$protocolName src=$source:$srcPort dst=$destination:$dstPort srcBytes=${srcAddress.size} dstBytes=${dstAddress.size}",
        )

        var uid = Process.INVALID_UID
        var outcome = "returned"
        try {
            val resolution = resolveUid(protocol, srcAddress, srcPort, dstAddress, dstPort, callId)
            uid = resolution.uid
            return if (resolution.blocked) NATIVE_UID_BLOCKED else resolution.uid
        } catch (error: Throwable) {
            outcome = "throwable:${error.javaClass.simpleName}"
            reportException(protocol, source, srcPort, destination, dstPort, callId, error)
            return Process.INVALID_UID
        } finally {
            UidPocDiagnostics.jniEvent(
                service,
                "KOTLIN_RESOLVER_EXIT id=$callId outcome=$outcome uid=$uid",
            )
        }
    }

    /** Runs only on the dedicated native UID worker, never on the TUN coroutine. */
    private fun resolveUid(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
        callId: Long,
    ): UidResolution {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            UidPocDiagnostics.log(service, "OWNER_UID_QUERY_SKIPPED id=$callId reason=API_BELOW_29")
            return UidResolution(Process.INVALID_UID, false)
        }
        if (protocol != IPPROTO_TCP && protocol != IPPROTO_UDP) {
            UidPocDiagnostics.log(service, "OWNER_UID_QUERY_SKIPPED id=$callId reason=UNSUPPORTED_PROTOCOL protocol=$protocol")
            return UidResolution(Process.INVALID_UID, false)
        }
        if (srcPort !in 0..65535 || dstPort !in 0..65535 ||
            srcAddress.size !in setOf(4, 16) || dstAddress.size != srcAddress.size
        ) {
            UidPocDiagnostics.log(service, "OWNER_UID_QUERY_SKIPPED id=$callId reason=INVALID_TUPLE")
            return UidResolution(Process.INVALID_UID, false)
        }

        val src = InetAddress.getByAddress(srcAddress).hostAddress ?: "unknown"
        val dst = InetAddress.getByAddress(dstAddress).hostAddress ?: "unknown"
        val tuple = "${protocolName(protocol)}|$src|$srcPort|$dst|$dstPort"
        val local = InetSocketAddress(InetAddress.getByAddress(srcAddress), srcPort)
        val remote = InetSocketAddress(InetAddress.getByAddress(dstAddress), dstPort)

        UidPocDiagnostics.log(service, "OWNER_UID_QUERY_BEGIN id=$callId tuple=$tuple thread=${Thread.currentThread().name}")
        val uid = connectivity.getConnectionOwnerUid(protocol, local, remote)
        val packages = if (uid == Process.INVALID_UID) {
            emptyList()
        } else {
            packageManager.getPackagesForUid(uid)?.sorted().orEmpty()
        }
        val targetPackages = expectedUidPackages[uid].orEmpty()
        val targetMatch = targetPackages.joinToString(",").ifBlank { "-" }
        val policyBlockedPackages = packages.filter { it in service.currentBlockedPackages() }
        val shouldBlock = uid != Process.INVALID_UID &&
            (targetPackages.isNotEmpty() || policyBlockedPackages.isNotEmpty())
        val verdict = when {
            uid == Process.INVALID_UID -> "INVALID_UID"
            shouldBlock -> "MATCH_TARGET"
            else -> "UID_FOUND_OTHER_OR_UNLISTED"
        }
        val status = when {
            uid == Process.INVALID_UID -> "INVALID"
            shouldBlock -> "MATCH_TARGET"
            else -> "MATCH"
        }
        val matchedPackages = (targetPackages + policyBlockedPackages).distinct()
        UidDetectionPocResultStore.add(
            protocol, src, srcPort, dst, dstPort, uid, packages, status,
            "resolverCall=$callId; verdict=$verdict; targetMatch=$targetMatch; " +
                "blockedPackages=" + matchedPackages.joinToString(",") +
                "; thread=" + Thread.currentThread().name,
        )
        Log.i(
            TAG,
            "RESULT verdict=$verdict tuple=$tuple uid=$uid " +
                "packages=" + packages.joinToString(",").ifBlank { "-" } +
                " targetMatch=$targetMatch blocked=$shouldBlock " +
                "blockedPackages=" + matchedPackages.joinToString(",").ifBlank { "-" } +
                " resolverCall=$callId",
        )
        UidPocDiagnostics.jniEvent(
            service,
            "OWNER_UID_QUERY_RESULT id=$callId verdict=$verdict uid=$uid " +
                "packages=" + packages.joinToString(",").ifBlank { "-" } +
                " blocked=$shouldBlock",
        )

        // Attach the UID and tuple to any relay sessions already opened for this
        // destination. A block verdict then closes those sessions immediately.
        service.recordDetectedFlow(protocol, src, srcPort, dst, dstPort, uid, packages)
        if (shouldBlock) {
            service.enforceBlockedDetectedFlow(
                protocol = protocol,
                srcIp = src,
                srcPort = srcPort,
                dstIp = dst,
                dstPort = dstPort,
                uid = uid,
                packages = packages,
                policyBlockedPackages = policyBlockedPackages,
                permanent = targetPackages.isNotEmpty(),
            )
        }
        return UidResolution(uid, shouldBlock)
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

    private companion object {
        const val TAG = "UidDetectionPOC"
        const val IPPROTO_TCP = 6
        const val IPPROTO_UDP = 17
        const val NATIVE_UID_BLOCKED = -3
    }
}

