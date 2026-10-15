package com.speedvpn.app

import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import hev.htproxy.TProxyService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * Temporary diagnostic only. It observes tuples reported at the native TUN read point and
 * asks Android which UID owns each tuple. The records are shown in the POC screen and are
 * never used to change packet forwarding or enforce a firewall.
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
            "${when (protocol) { 6 -> "TCP"; 17 -> "UDP"; else -> protocol.toString() }}|" +
                "$src|$srcPort|$dst|$dstPort"
    }

    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val packageManager = service.packageManager
    private val targets = listOf(
        "com.android.chrome",
        "com.whatsapp",
        "com.google.android.youtube",
        "com.android.vending",
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
        UidPocDiagnostics.log(service, "UidDetectionPoc.init; API=${Build.VERSION.SDK_INT}; package=${service.packageName}")
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

    private val nativeCallbackSequence = AtomicLong(0L)

    override fun onTunPacket(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
    ) {
        val callId = nativeCallbackSequence.incrementAndGet()
        val protocolName = when (protocol) { 6 -> "TCP"; 17 -> "UDP"; else -> protocol.toString() }
        val source = runCatching { InetAddress.getByAddress(srcAddress).hostAddress }
            .getOrElse { "invalid-address-${srcAddress.size}-bytes" }
        val destination = runCatching { InetAddress.getByAddress(dstAddress).hostAddress }
            .getOrElse { "invalid-address-${dstAddress.size}-bytes" }
        UidPocDiagnostics.jniEvent(
            service,
            "KOTLIN_CALLBACK_ENTRY id=$callId protocol=$protocolName src=$source:$srcPort dst=$destination:$dstPort srcBytes=${srcAddress.size} dstBytes=${dstAddress.size}",
        )
        var outcome = "returned"
        try {
            handleTunPacket(protocol, srcAddress, srcPort, dstAddress, dstPort)
        } catch (t: Throwable) {
            outcome = "throwable:${t.javaClass.simpleName}"
            UidPocDiagnostics.error(service, "Unhandled error inside native UID callback id=$callId", t)
        } finally {
            UidPocDiagnostics.jniEvent(service, "KOTLIN_CALLBACK_EXIT id=$callId outcome=$outcome")
        }
    }

    private fun handleTunPacket(
        protocol: Int,
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
    ) {
        if (!UidDetectionPocResultStore.isRecording) return
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

        val attemptCounter = attempts.computeIfAbsent(tuple) { AtomicInteger() }
        if (attemptCounter.get() >= MAX_ATTEMPTS) return
        val attempt = attemptCounter.incrementAndGet()

        // Never perform the Binder-backed Android UID lookup on the TUN engine thread.
        runCatching {
            executor.execute {
                try {
                    val local = InetSocketAddress(InetAddress.getByAddress(srcAddress), srcPort)
                    val remote = InetSocketAddress(InetAddress.getByAddress(dstAddress), dstPort)
                    UidPocDiagnostics.log(service, "OWNER_UID_QUERY_BEGIN tuple=$tuple attempt=$attempt")
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
                    UidDetectionPocResultStore.add(
                        protocol, src, srcPort, dst, dstPort, uid, packages,
                        if (uid == Process.INVALID_UID) "INVALID" else "MATCH",
                        "attempt=$attempt; targetMatch=$targetMatch",
                    )
                    Log.i(
                        TAG,
                        "RESULT verdict=$verdict tuple=$tuple uid=$uid " +
                            "packages=${packages.joinToString(",").ifBlank { "-" }} " +
                            "targetMatch=$targetMatch attempt=$attempt",
                    )
                    if (uid != Process.INVALID_UID) resolved.add(tuple)
                } catch (e: SecurityException) {
                    reportException(protocol, src, srcPort, dst, dstPort, attempt, e)
                } catch (e: IllegalArgumentException) {
                    reportException(protocol, src, srcPort, dst, dstPort, attempt, e)
                } catch (e: Exception) {
                    reportException(protocol, src, srcPort, dst, dstPort, attempt, e)
                } catch (t: Throwable) {
                    reportException(protocol, src, srcPort, dst, dstPort, attempt, t)
                }
            }
        }.onFailure {
            UidDetectionPocResultStore.add(
                protocol, src, srcPort, dst, dstPort, null, emptyList(), "EXCEPTION",
                "Queue error: ${it.javaClass.simpleName}: ${it.message.orEmpty()}",
            )
            Log.w(TAG, "QUEUE_DROP tuple=$tuple reason=${it.javaClass.simpleName}")
        }
    }

    private fun reportException(
        protocol: Int,
        src: String,
        srcPort: Int,
        dst: String,
        dstPort: Int,
        attempt: Int,
        error: Throwable,
    ) {
        val details = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        UidDetectionPocResultStore.add(
            protocol, src, srcPort, dst, dstPort, null, emptyList(), "EXCEPTION",
            "attempt=$attempt; $details",
        )
        Log.e(TAG, "RESULT verdict=EXCEPTION tuple=${protocol}|$src|$srcPort|$dst|$dstPort attempt=$attempt details=$details", error)
        UidPocDiagnostics.error(service, "OWNER_UID_QUERY_EXCEPTION tuple=${protocol}|$src|$srcPort|$dst|$dstPort attempt=$attempt", error)
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
    }
}
