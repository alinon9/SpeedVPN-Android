package com.speedvpn.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeedPlanRecordStatus(val dbValue: String, val arabicLabel: String) {
    ONGOING("ONGOING", "مستمر"),
    COMPLETED("COMPLETED", "مكتمل"),
    ABNORMAL_END("ABNORMAL_END", "انتهى بصورة غير طبيعية");

    companion object {
        fun fromDb(value: String?): SpeedPlanRecordStatus {
            return entries.firstOrNull { it.dbValue.equals(value?.trim(), ignoreCase = true) }
                ?: COMPLETED
        }
    }
}

enum class SpeedPlanOptionCategory(val dbValue: String, val arabicLabel: String) {
    LOW("LOW", "منخفض"),
    MEDIUM("MEDIUM", "متوسط"),
    HIGH("HIGH", "مرتفع"),
    CUSTOM("CUSTOM", "مخصص"),
    UNLIMITED("UNLIMITED", "Unlimited");

    companion object {
        fun fromDb(value: String?): SpeedPlanOptionCategory {
            return entries.firstOrNull { it.dbValue.equals(value?.trim(), ignoreCase = true) }
                ?: CUSTOM
        }
    }
}

data class SpeedPlanClassification(
    val category: SpeedPlanOptionCategory,
    val optionLabel: String,
    val downloadLimitKbps: Long?,
    val uploadLimitKbps: Long?,
    val downloadLimitUnitLabel: String,
    val uploadLimitUnitLabel: String,
)

data class SpeedPlanUsageRecord(
    val sessionId: String,
    val vpnGeneration: Long,
    val segmentIndex: Int,
    val processToken: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val lastCheckpointMillis: Long,
    val durationMillis: Long,
    val planOptionCategory: SpeedPlanOptionCategory,
    val planOptionLabel: String,
    val downloadLimitKbps: Long?,
    val uploadLimitKbps: Long?,
    val downloadLimitUnitLabel: String,
    val uploadLimitUnitLabel: String,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val totalBytes: Long,
    val avgDownloadBps: Long,
    val avgUploadBps: Long,
    val peakDownloadBps: Long,
    val peakUploadBps: Long,
    val status: SpeedPlanRecordStatus,
    val closeReason: String,
    val limitExceeded: Boolean,
    val maxExceedancePct: Double,
    val exceedanceDurationMillis: Long,
) {
    fun formattedDuration(): String = SpeedPlanClassifier.formatDuration(durationMillis)

    fun formattedAvgDownloadRate(): String =
        if (durationMillis >= 500L) SpeedPlanClassifier.formatRateBps(avgDownloadBps) else "غير متاح (فترة قصيرة)"

    fun formattedAvgUploadRate(): String =
        if (durationMillis >= 500L) SpeedPlanClassifier.formatRateBps(avgUploadBps) else "غير متاح (فترة قصيرة)"

    fun formattedExceedanceSummary(): String {
        if (!limitExceeded) return "ضمن الحد المهيأ"
        val pctStr = String.format(Locale.US, "%.1f%%", maxExceedancePct)
        val durSec = String.format(Locale.US, "%.1f", exceedanceDurationMillis / 1000.0)
        return "تجاوز محتمل بنسبة $pctStr لمدة $durSec ثانية"
    }

    fun formattedDataBreakdown(): String =
        SpeedPlanClassifier.formatBytesAllUnits(totalBytes)
}

object SpeedPlanClassifier {
    // Matches the exact preset groups in MainActivity.kt (where 1 KB/s = 8 Kbps)
    private val lowPresetKbps: Map<Long, String> = mapOf(
        80L to "10 KB/s",
        200L to "25 KB/s",
        400L to "50 KB/s",
        600L to "75 KB/s",
        800L to "100 KB/s",
        1040L to "130 KB/s",
        2000L to "250 KB/s",
        4000L to "500 KB/s",
        6000L to "750 KB/s",
        7600L to "950 KB/s",
    )

    private val mediumPresetKbps: Map<Long, String> = mapOf(
        8_000L to "1 MB/s",
        16_000L to "2 MB/s",
        24_000L to "3 MB/s",
        32_000L to "4 MB/s",
        40_000L to "5 MB/s",
    )

    private val highPresetKbps: Map<Long, String> = mapOf(
        72_000L to "9 MB/s",
        80_000L to "10 MB/s",
        88_000L to "11 MB/s",
    )

    fun classify(downloadKbps: Long?, uploadKbps: Long?): SpeedPlanClassification {
        val dl = downloadKbps?.takeIf { it > 0L }
        val ul = uploadKbps?.takeIf { it > 0L }
        val dlLabel = formatLimitLabel(dl)
        val ulLabel = formatLimitLabel(ul)

        if (dl == null && ul == null) {
            return SpeedPlanClassification(
                category = SpeedPlanOptionCategory.UNLIMITED,
                optionLabel = "Unlimited (بدون حد)",
                downloadLimitKbps = null,
                uploadLimitKbps = null,
                downloadLimitUnitLabel = dlLabel,
                uploadLimitUnitLabel = ulLabel,
            )
        }

        if (dl != null && dl == ul) {
            lowPresetKbps[dl]?.let { preset ->
                return SpeedPlanClassification(
                    category = SpeedPlanOptionCategory.LOW,
                    optionLabel = "منخفض ($preset)",
                    downloadLimitKbps = dl,
                    uploadLimitKbps = ul,
                    downloadLimitUnitLabel = dlLabel,
                    uploadLimitUnitLabel = ulLabel,
                )
            }
            mediumPresetKbps[dl]?.let { preset ->
                return SpeedPlanClassification(
                    category = SpeedPlanOptionCategory.MEDIUM,
                    optionLabel = "متوسط ($preset)",
                    downloadLimitKbps = dl,
                    uploadLimitKbps = ul,
                    downloadLimitUnitLabel = dlLabel,
                    uploadLimitUnitLabel = ulLabel,
                )
            }
            highPresetKbps[dl]?.let { preset ->
                return SpeedPlanClassification(
                    category = SpeedPlanOptionCategory.HIGH,
                    optionLabel = "مرتفع ($preset)",
                    downloadLimitKbps = dl,
                    uploadLimitKbps = ul,
                    downloadLimitUnitLabel = dlLabel,
                    uploadLimitUnitLabel = ulLabel,
                )
            }
            return SpeedPlanClassification(
                category = SpeedPlanOptionCategory.CUSTOM,
                optionLabel = "مخصص ($dlLabel)",
                downloadLimitKbps = dl,
                uploadLimitKbps = ul,
                downloadLimitUnitLabel = dlLabel,
                uploadLimitUnitLabel = ulLabel,
            )
        }

        val dlCat = singleDirectionCategory(dl)
        val ulCat = singleDirectionCategory(ul)
        val category = if (dlCat != null && dlCat == ulCat) {
            dlCat
        } else {
            SpeedPlanOptionCategory.CUSTOM
        }
        val prefix = category.arabicLabel
        val optionLabel = "$prefix (تنزيل: $dlLabel / رفع: $ulLabel)"
        return SpeedPlanClassification(
            category = category,
            optionLabel = optionLabel,
            downloadLimitKbps = dl,
            uploadLimitKbps = ul,
            downloadLimitUnitLabel = dlLabel,
            uploadLimitUnitLabel = ulLabel,
        )
    }

    private fun singleDirectionCategory(kbps: Long?): SpeedPlanOptionCategory? {
        if (kbps == null || kbps <= 0L) return null
        return when {
            lowPresetKbps.containsKey(kbps) -> SpeedPlanOptionCategory.LOW
            mediumPresetKbps.containsKey(kbps) -> SpeedPlanOptionCategory.MEDIUM
            highPresetKbps.containsKey(kbps) -> SpeedPlanOptionCategory.HIGH
            else -> SpeedPlanOptionCategory.CUSTOM
        }
    }

    fun formatLimitLabel(kbps: Long?): String {
        val valid = kbps?.takeIf { it > 0L } ?: return "Unlimited (بدون حد)"
        val bytesPerSec = SpeedLimiter.kbpsToBytesPerSec(valid)
        val byteRateLabel = when {
            bytesPerSec < 1_000L -> "$bytesPerSec B/s"
            bytesPerSec < 1_000_000L -> {
                val kb = bytesPerSec / 1_000.0
                if (kb == kb.toLong().toDouble()) "${kb.toLong()} KB/s" else String.format(Locale.US, "%.1f KB/s", kb)
            }
            else -> {
                val mb = bytesPerSec / 1_000_000.0
                if (mb == mb.toLong().toDouble()) "${mb.toLong()} MB/s" else String.format(Locale.US, "%.2f MB/s", mb)
            }
        }
        val bitRateLabel = if (valid >= 1_000L && valid % 1_000L == 0L) {
            "${valid / 1_000L} Mbps"
        } else {
            "$valid Kbps"
        }
        return "$byteRateLabel ($bitRateLabel)"
    }

    fun formatRateBps(bitsPerSecond: Long): String {
        val bps = bitsPerSecond.coerceAtLeast(0L)
        val bytesPerSecond = bps / 8.0
        val byteLabel = when {
            bytesPerSecond < 1_000.0 -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
            bytesPerSecond < 1_000_000.0 -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1_000.0)
            else -> String.format(Locale.US, "%.2f MB/s", bytesPerSecond / 1_000_000.0)
        }
        val bitLabel = when {
            bps < 1_000L -> "$bps bps"
            bps < 1_000_000L -> String.format(Locale.US, "%.0f Kbps", bps / 1_000.0)
            else -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
        }
        return "$byteLabel ($bitLabel)"
    }

    fun formatBytesAllUnits(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val kb = safe / 1024.0
        val mb = safe / (1024.0 * 1024.0)
        val gb = safe / (1024.0 * 1024.0 * 1024.0)
        return String.format(
            Locale.US,
            "%d B | %.2f KB | %.2f MB | %.4f GB",
            safe,
            kb,
            mb,
            gb,
        )
    }

    fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d (%d س %d د %d ث)", hours, minutes, seconds, hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d (%d د %d ث)", minutes, seconds, minutes, seconds)
        }
    }

    fun formatTimestamp(millis: Long?): String {
        if (millis == null || millis <= 0L) return "مستمر"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }
}

/**
 * Coordinates durable speed-plan usage sessions tied to actual applied limits in `SpeedLimiter`
 * and actual forwarded relay payload bytes in `TokenBucket.planSessionBytes(generation)`.
 *
 * Key guarantees:
 * 1. Opens a new segment only when the VPN tunnel is actively connected (or when the applied
 *    speed plan changes while connected). Failed connections or cancelled changes while
 *    disconnected do not create bogus rows.
 * 2. Uses `TokenBucket.planSessionBytes(generation)` which is monotonic per VPN generation,
 *    unaffected by the Home screen `"تصفير"` button (`resetSession`), and excludes synthetic
 *    `SpeedVerificationEngine` probe traffic while continuing to record real traffic from other
 *    applications even during speed verification.
 * 3. Allocates strictly increasing `segmentIndex` values within each `vpnGeneration` so closing
 *    and resuming a segment in the same generation never produces duplicate segment indices.
 * 4. Tracks full exceedance wave durations (`exceedanceDurationMillis`) from the start of each
 *    candidate wave once the wave persists for `>= 2,000 ms` above the `115%` rate threshold,
 *    and resets unconfirmed candidate waves across unmeasured time gaps (`> 1,500 ms`).
 * 5. Persists periodic checkpoints on a background single-thread executor (every 5 seconds or
 *    >= 256 KB delta) and exposes an in-memory live snapshot + revision flow so the UI never
 *    forces synchronous SQLite writes or full-table reloads on every 500 ms meter tick.
 */
object SpeedPlanUsageTracker {
    internal const val CHECKPOINT_INTERVAL_MS = 5_000L
    internal const val CHECKPOINT_BYTES_DELTA = 256 * 1024L
    internal const val EXCEEDANCE_TOLERANCE_RATIO = 1.15
    internal const val EXCEEDANCE_MIN_DURATION_MS = 2_000L
    internal const val MAX_CONTIGUOUS_SAMPLE_INTERVAL_MS = 1_500L

    private val lock = Any()
    private val ioExecutorThread = AtomicReference<Thread?>()
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            ioExecutorThread.set(Thread.currentThread())
            r.run()
        }, "SpeedPlanUsageTracker-IO").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var processToken: String = UUID.randomUUID().toString()

    @Volatile
    private var recoveryCompletedForToken: String? = null

    private var activeGeneration: Long = 0L
    private var highestSegmentIndexInGeneration: Int = 0
    private var activeSegment: ActiveSegment? = null

    /**
     * Preserves the byte-counter boundary when a segment is closed before the next meter
     * sample. Traffic can continue between those two events, so the next segment must start
     * from the counters captured at close time rather than from a later sample.
     */
    private data class PendingSegmentStart(
        val generation: Long,
        val startedAtMillis: Long,
        val baselineDownBytes: Long,
        val baselineUpBytes: Long,
    )

    private var pendingSegmentStart: PendingSegmentStart? = null

    private val persistenceRevisionCounter = AtomicLong(0L)
    private val _persistedHistoryRevision = MutableStateFlow(0L)
    val persistedHistoryRevision: StateFlow<Long> = _persistedHistoryRevision.asStateFlow()

    private data class ActiveSegment(
        val sessionId: String,
        val vpnGeneration: Long,
        val segmentIndex: Int,
        val processToken: String,
        val startedAtMillis: Long,
        var lastCheckpointMillis: Long,
        val classification: SpeedPlanClassification,
        val baselineDownBytes: Long,
        val baselineUpBytes: Long,
        var currentDownBytes: Long = 0L,
        var currentUpBytes: Long = 0L,
        var lastSampledDownBytes: Long = 0L,
        var lastSampledUpBytes: Long = 0L,
        var peakDownloadBps: Long = 0L,
        var peakUploadBps: Long = 0L,
        var candidateExceedanceStartMillis: Long? = null,
        var candidateExceedanceDurationMillis: Long = 0L,
        var candidateMaxExceedancePct: Double = 0.0,
        var candidateWaveCommitted: Boolean = false,
        var lastExceedanceSampleAtMillis: Long = 0L,
        var exceedanceDurationMillis: Long = 0L,
        var maxExceedancePct: Double = 0.0,
        var limitExceeded: Boolean = false,
        var lastPersistedAtMillis: Long = 0L,
        var lastPersistedTotalBytes: Long = 0L,
    ) {
        fun snapshotRecord(
            status: SpeedPlanRecordStatus,
            closeReason: String,
            endedAtMillis: Long?,
            nowMillis: Long,
        ): SpeedPlanUsageRecord {
            val effectiveEnd = endedAtMillis ?: max(nowMillis, lastCheckpointMillis)
            val duration = (effectiveEnd - startedAtMillis).coerceAtLeast(0L)
            val down = currentDownBytes.coerceAtLeast(0L)
            val up = currentUpBytes.coerceAtLeast(0L)
            val total = if (Long.MAX_VALUE - down < up) Long.MAX_VALUE else down + up
            val avgDownBps = if (duration > 0L) {
                ((down.toDouble() * 8_000.0) / duration.toDouble()).roundToLong().coerceAtLeast(0L)
            } else {
                0L
            }
            val avgUpBps = if (duration > 0L) {
                ((up.toDouble() * 8_000.0) / duration.toDouble()).roundToLong().coerceAtLeast(0L)
            } else {
                0L
            }
            return SpeedPlanUsageRecord(
                sessionId = sessionId,
                vpnGeneration = vpnGeneration,
                segmentIndex = segmentIndex,
                processToken = processToken,
                startedAtMillis = startedAtMillis,
                endedAtMillis = endedAtMillis,
                lastCheckpointMillis = effectiveEnd,
                durationMillis = duration,
                planOptionCategory = classification.category,
                planOptionLabel = classification.optionLabel,
                downloadLimitKbps = classification.downloadLimitKbps,
                uploadLimitKbps = classification.uploadLimitKbps,
                downloadLimitUnitLabel = classification.downloadLimitUnitLabel,
                uploadLimitUnitLabel = classification.uploadLimitUnitLabel,
                downloadBytes = down,
                uploadBytes = up,
                totalBytes = total,
                avgDownloadBps = avgDownBps,
                avgUploadBps = avgUpBps,
                peakDownloadBps = max(peakDownloadBps, avgDownBps),
                peakUploadBps = max(peakUploadBps, avgUpBps),
                status = status,
                closeReason = closeReason,
                limitExceeded = limitExceeded,
                maxExceedancePct = if (limitExceeded) maxExceedancePct else 0.0,
                exceedanceDurationMillis = if (limitExceeded) exceedanceDurationMillis else 0L,
            )
        }
    }

    fun registerContext(context: Context) {
        // Always retain the current application context. Android keeps this stable in
        // production, while Robolectric supplies a fresh context for each test sandbox.
        appContext = context.applicationContext
        SpeedLimiter.registerOwnAppIdentity(context)
    }

    fun ensureRecovered(context: Context, nowMillis: Long = System.currentTimeMillis()): Int {
        registerContext(context)
        val token = processToken
        synchronized(lock) {
            if (recoveryCompletedForToken == token) return 0
            recoveryCompletedForToken = token
        }
        val recovered = runCatching {
            UsageRepository.recoverOrphanedSpeedPlanSessions(
                context = context.applicationContext,
                activeProcessToken = token,
                nowMillis = nowMillis,
            )
        }.getOrDefault(0)
        if (recovered > 0) {
            bumpPersistedRevision()
        }
        return recovered
    }

    fun onVpnConnected(
        context: Context,
        generation: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (generation <= 0L) return
        registerContext(context)
        ensureRecovered(context, nowMillis)

        val recordsToPersist = mutableListOf<SpeedPlanUsageRecord>()
        synchronized(lock) {
            activeSegment?.let { prev ->
                syncCountersFromLimiterLocked(prev, nowMillis)
                recordsToPersist += prev.snapshotRecord(
                    status = SpeedPlanRecordStatus.COMPLETED,
                    closeReason = "NEW_VPN_GENERATION",
                    endedAtMillis = nowMillis,
                    nowMillis = nowMillis,
                )
            }
            if (activeGeneration != generation) {
                activeGeneration = generation
                highestSegmentIndexInGeneration = 0
            }
            pendingSegmentStart = null
            val nextIndex = allocateNextSegmentIndexLocked(generation)
            val opened = openNewSegmentLocked(
                generation = generation,
                segmentIndex = nextIndex,
                downloadKbps = SpeedLimiter.currentDownloadLimitKbps(),
                uploadKbps = SpeedLimiter.currentUploadLimitKbps(),
                nowMillis = nowMillis,
            )
            activeSegment = opened
            recordsToPersist += opened.snapshotRecord(
                status = SpeedPlanRecordStatus.ONGOING,
                closeReason = "ACTIVE",
                endedAtMillis = null,
                nowMillis = nowMillis,
            )
        }
        persistRecords(context.applicationContext, recordsToPersist, synchronous = false)
    }

    fun onAppliedLimitsChanged(
        downloadKbps: Long?,
        uploadKbps: Long?,
        nowMillis: Long = System.currentTimeMillis(),
        contextOverride: Context? = null,
    ) {
        if (SpeedLimiter.isVerificationActive()) return
        val ctx = contextOverride?.applicationContext ?: appContext ?: return
        val normalizedDown = downloadKbps?.takeIf { it > 0L }
        val normalizedUp = uploadKbps?.takeIf { it > 0L }

        val recordsToPersist = mutableListOf<SpeedPlanUsageRecord>()
        synchronized(lock) {
            val gen = activeGeneration
            if (gen <= 0L || !SpeedLimiter.isGenerationActive(gen)) return

            val existing = activeSegment
            if (existing != null &&
                existing.vpnGeneration == gen &&
                existing.classification.downloadLimitKbps == normalizedDown &&
                existing.classification.uploadLimitKbps == normalizedUp
            ) {
                return
            }

            if (existing != null) {
                syncCountersFromLimiterLocked(existing, nowMillis)
                recordsToPersist += existing.snapshotRecord(
                    status = SpeedPlanRecordStatus.COMPLETED,
                    closeReason = "PLAN_CHANGED",
                    endedAtMillis = nowMillis,
                    nowMillis = nowMillis,
                )
            }

            val pendingStart = pendingSegmentStart?.takeIf { it.generation == gen }
            val nextIndex = allocateNextSegmentIndexLocked(gen)
            val opened = openNewSegmentLocked(
                generation = gen,
                segmentIndex = nextIndex,
                downloadKbps = normalizedDown,
                uploadKbps = normalizedUp,
                nowMillis = pendingStart?.startedAtMillis ?: nowMillis,
                baselineDownBytes = pendingStart?.baselineDownBytes
                    ?: SpeedLimiter.download.planSessionBytes(gen),
                baselineUpBytes = pendingStart?.baselineUpBytes
                    ?: SpeedLimiter.upload.planSessionBytes(gen),
            )
            if (pendingStart != null) pendingSegmentStart = null
            activeSegment = opened
            recordsToPersist += opened.snapshotRecord(
                status = SpeedPlanRecordStatus.ONGOING,
                closeReason = "ACTIVE",
                endedAtMillis = null,
                nowMillis = nowMillis,
            )
        }
        persistRecords(ctx, recordsToPersist, synchronous = false)
    }

    /**
     * Closes the active segment without resetting `highestSegmentIndexInGeneration`.
     * If a subsequent segment is opened within the same `vpnGeneration`, it receives
     * `highestSegmentIndexInGeneration + 1`, preventing duplicate segment indices.
     */
    fun closeActiveSegmentForTransition(
        closeReason: String = "SEGMENT_PAUSED",
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val ctx = appContext ?: return
        var closedRecord: SpeedPlanUsageRecord? = null
        synchronized(lock) {
            val current = activeSegment ?: return
            syncCountersFromLimiterLocked(current, nowMillis)
            closedRecord = current.snapshotRecord(
                status = SpeedPlanRecordStatus.COMPLETED,
                closeReason = closeReason,
                endedAtMillis = nowMillis,
                nowMillis = nowMillis,
            )
            pendingSegmentStart = PendingSegmentStart(
                generation = current.vpnGeneration,
                startedAtMillis = nowMillis,
                baselineDownBytes = SpeedLimiter.download.planSessionBytes(current.vpnGeneration),
                baselineUpBytes = SpeedLimiter.upload.planSessionBytes(current.vpnGeneration),
            )
            activeSegment = null
        }
        closedRecord?.let { persistRecords(ctx, listOf(it), synchronous = false) }
    }

    fun onMeterSample(
        context: Context,
        generation: Long,
        rawDownloadBps: Long,
        rawUploadBps: Long,
        sampleElapsedMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (generation <= 0L) return
        registerContext(context)
        var checkpointRecord: SpeedPlanUsageRecord? = null
        synchronized(lock) {
            if (activeGeneration != generation) {
                activeGeneration = generation
                highestSegmentIndexInGeneration = 0
                pendingSegmentStart = null
            }
            val current = activeSegment ?: run {
                val pendingStart = pendingSegmentStart?.takeIf { it.generation == generation }
                val nextIndex = allocateNextSegmentIndexLocked(generation)
                openNewSegmentLocked(
                    generation = generation,
                    segmentIndex = nextIndex,
                    downloadKbps = SpeedLimiter.currentDownloadLimitKbps(),
                    uploadKbps = SpeedLimiter.currentUploadLimitKbps(),
                    nowMillis = pendingStart?.startedAtMillis ?: nowMillis,
                    baselineDownBytes = pendingStart?.baselineDownBytes
                        ?: SpeedLimiter.download.planSessionBytes(generation),
                    baselineUpBytes = pendingStart?.baselineUpBytes
                        ?: SpeedLimiter.upload.planSessionBytes(generation),
                ).also {
                    if (pendingStart != null) pendingSegmentStart = null
                    activeSegment = it
                }
            }

            val prevDown = current.currentDownBytes
            val prevUp = current.currentUpBytes
            syncCountersFromLimiterLocked(current, nowMillis)
            val deltaDown = (current.currentDownBytes - prevDown).coerceAtLeast(0L)
            val deltaUp = (current.currentUpBytes - prevUp).coerceAtLeast(0L)
            val safeElapsed = sampleElapsedMillis.coerceAtLeast(1L)

            // When speed verification is active, rawDownloadBps/rawUploadBps from SpeedLimiter.total
            // include synthetic probe traffic. Derive user-only instantaneous rate from planSessionBytes delta.
            val userSampleDownBps = ((deltaDown.toDouble() * 8_000.0) / safeElapsed.toDouble()).roundToLong().coerceAtLeast(0L)
            val userSampleUpBps = ((deltaUp.toDouble() * 8_000.0) / safeElapsed.toDouble()).roundToLong().coerceAtLeast(0L)
            val effectiveDownBps = if (SpeedLimiter.isVerificationActive()) {
                userSampleDownBps
            } else {
                rawDownloadBps.coerceAtLeast(0L)
            }
            val effectiveUpBps = if (SpeedLimiter.isVerificationActive()) {
                userSampleUpBps
            } else {
                rawUploadBps.coerceAtLeast(0L)
            }

            current.lastSampledDownBytes = current.currentDownBytes
            current.lastSampledUpBytes = current.currentUpBytes
            current.peakDownloadBps = max(current.peakDownloadBps, effectiveDownBps)
            current.peakUploadBps = max(current.peakUploadBps, effectiveUpBps)

            updateExceedanceMetricsLocked(
                segment = current,
                observedDownBps = effectiveDownBps,
                observedUpBps = effectiveUpBps,
                elapsedMillis = safeElapsed,
                nowMillis = nowMillis,
            )

            val totalBytes = current.currentDownBytes + current.currentUpBytes
            val elapsedSincePersist = nowMillis - current.lastPersistedAtMillis
            val bytesSincePersist = totalBytes - current.lastPersistedTotalBytes
            if (elapsedSincePersist >= CHECKPOINT_INTERVAL_MS || bytesSincePersist >= CHECKPOINT_BYTES_DELTA) {
                current.lastPersistedAtMillis = nowMillis
                current.lastPersistedTotalBytes = totalBytes
                checkpointRecord = current.snapshotRecord(
                    status = SpeedPlanRecordStatus.ONGOING,
                    closeReason = "ACTIVE",
                    endedAtMillis = null,
                    nowMillis = nowMillis,
                )
            }
        }
        checkpointRecord?.let { persistRecords(context.applicationContext, listOf(it), synchronous = false) }
    }

    /**
     * Returns an in-memory snapshot of the currently active segment (with up-to-date byte counters
     * from `SpeedLimiter`) without performing any SQLite read or write.
     */
    fun snapshotActiveSegmentRecord(nowMillis: Long = System.currentTimeMillis()): SpeedPlanUsageRecord? {
        synchronized(lock) {
            val current = activeSegment ?: return null
            syncCountersFromLimiterLocked(current, nowMillis)
            return current.snapshotRecord(
                status = SpeedPlanRecordStatus.ONGOING,
                closeReason = "ACTIVE",
                endedAtMillis = null,
                nowMillis = nowMillis,
            )
        }
    }

    /**
     * Overlays the live in-memory active segment onto a cached list of persisted records
     * without hitting SQLite.
     */
    fun mergePersistedWithLiveActive(
        persistedRecords: List<SpeedPlanUsageRecord>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SpeedPlanUsageRecord> {
        val live = snapshotActiveSegmentRecord(nowMillis) ?: return persistedRecords
        var found = false
        val updated = persistedRecords.map { rec ->
            if (rec.sessionId == live.sessionId) {
                found = true
                live
            } else {
                rec
            }
        }
        return if (found) updated else updated + live
    }

    fun flushActiveSegment(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        registerContext(context)
        var record: SpeedPlanUsageRecord? = null
        synchronized(lock) {
            val current = activeSegment ?: return
            syncCountersFromLimiterLocked(current, nowMillis)
            current.lastPersistedAtMillis = nowMillis
            current.lastPersistedTotalBytes = current.currentDownBytes + current.currentUpBytes
            record = current.snapshotRecord(
                status = SpeedPlanRecordStatus.ONGOING,
                closeReason = "ACTIVE",
                endedAtMillis = null,
                nowMillis = nowMillis,
            )
        }
        record?.let { persistRecords(context.applicationContext, listOf(it), synchronous = true) }
    }

    fun onVpnDisconnected(
        context: Context,
        generation: Long,
        closeReason: String = "VPN_DISCONNECTED",
        abnormal: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        registerContext(context)
        var finalRecord: SpeedPlanUsageRecord? = null
        synchronized(lock) {
            val current = activeSegment
            if (current != null && (generation <= 0L || current.vpnGeneration == generation)) {
                syncCountersFromLimiterLocked(current, nowMillis)
                val status = if (abnormal) SpeedPlanRecordStatus.ABNORMAL_END else SpeedPlanRecordStatus.COMPLETED
                finalRecord = current.snapshotRecord(
                    status = status,
                    closeReason = closeReason,
                    endedAtMillis = nowMillis,
                    nowMillis = nowMillis,
                )
                activeSegment = null
            }
            if (generation <= 0L || activeGeneration == generation) {
                activeGeneration = 0L
                highestSegmentIndexInGeneration = 0
            }
        }
        // A terminal VPN snapshot is the durable boundary for this usage segment.
        // Persist it before callers clear the limiter counters or allow the app to restart;
        // this path runs from the service's IO cleanup worker, not the UI meter loop.
        finalRecord?.let { persistRecords(context.applicationContext, listOf(it), synchronous = true) }
    }

    fun onProcessAbnormalExit(
        context: Context,
        closeReason: String = "UNCAUGHT_EXCEPTION",
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        registerContext(context)
        var finalRecord: SpeedPlanUsageRecord? = null
        synchronized(lock) {
            val current = activeSegment ?: return
            syncCountersFromLimiterLocked(current, nowMillis)
            finalRecord = current.snapshotRecord(
                status = SpeedPlanRecordStatus.ABNORMAL_END,
                closeReason = closeReason,
                endedAtMillis = nowMillis,
                nowMillis = nowMillis,
            )
            activeSegment = null
            activeGeneration = 0L
            highestSegmentIndexInGeneration = 0
        }
        finalRecord?.let { persistRecords(context.applicationContext, listOf(it), synchronous = true) }
    }

    internal fun flushPendingWritesForTests() {
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute { latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    internal fun resetForTests(newProcessToken: String = UUID.randomUUID().toString()) {
        flushPendingWritesForTests()
        synchronized(lock) {
            processToken = newProcessToken
            recoveryCompletedForToken = null
            activeGeneration = 0L
            highestSegmentIndexInGeneration = 0
            activeSegment = null
            pendingSegmentStart = null
            appContext = null
        }
    }

    private fun allocateNextSegmentIndexLocked(generation: Long): Int {
        if (activeGeneration != generation) {
            activeGeneration = generation
            highestSegmentIndexInGeneration = 0
        }
        highestSegmentIndexInGeneration += 1
        return highestSegmentIndexInGeneration
    }

    private fun openNewSegmentLocked(
        generation: Long,
        segmentIndex: Int,
        downloadKbps: Long?,
        uploadKbps: Long?,
        nowMillis: Long,
        baselineDownBytes: Long = SpeedLimiter.download.planSessionBytes(generation),
        baselineUpBytes: Long = SpeedLimiter.upload.planSessionBytes(generation),
    ): ActiveSegment {
        val classification = SpeedPlanClassifier.classify(downloadKbps, uploadKbps)
        val baseDown = baselineDownBytes.coerceAtLeast(0L)
        val baseUp = baselineUpBytes.coerceAtLeast(0L)
        if (segmentIndex > highestSegmentIndexInGeneration) {
            highestSegmentIndexInGeneration = segmentIndex
        }
        return ActiveSegment(
            sessionId = UUID.randomUUID().toString(),
            vpnGeneration = generation,
            segmentIndex = segmentIndex,
            processToken = processToken,
            startedAtMillis = nowMillis,
            lastCheckpointMillis = nowMillis,
            classification = classification,
            baselineDownBytes = baseDown,
            baselineUpBytes = baseUp,
            lastPersistedAtMillis = nowMillis,
        )
    }

    private fun syncCountersFromLimiterLocked(segment: ActiveSegment, nowMillis: Long) {
        val gen = segment.vpnGeneration
        val rawDown = SpeedLimiter.download.planSessionBytes(gen)
        val rawUp = SpeedLimiter.upload.planSessionBytes(gen)
        if (rawDown >= segment.baselineDownBytes) {
            segment.currentDownBytes = max(segment.currentDownBytes, rawDown - segment.baselineDownBytes)
        }
        if (rawUp >= segment.baselineUpBytes) {
            segment.currentUpBytes = max(segment.currentUpBytes, rawUp - segment.baselineUpBytes)
        }
        segment.lastCheckpointMillis = max(segment.lastCheckpointMillis, nowMillis)
    }

    private fun resetCandidateExceedanceWaveLocked(segment: ActiveSegment) {
        segment.candidateExceedanceStartMillis = null
        segment.candidateExceedanceDurationMillis = 0L
        segment.candidateMaxExceedancePct = 0.0
        segment.candidateWaveCommitted = false
    }

    private fun updateExceedanceMetricsLocked(
        segment: ActiveSegment,
        observedDownBps: Long,
        observedUpBps: Long,
        elapsedMillis: Long,
        nowMillis: Long,
    ) {
        val dlLimitBps = segment.classification.downloadLimitKbps?.let { it * 1_000L } ?: 0L
        val ulLimitBps = segment.classification.uploadLimitKbps?.let { it * 1_000L } ?: 0L

        // Detect missing samples or unmeasured scheduling gaps (> 1,500 ms).
        // Do not extrapolate continuous exceedance across unmeasured gaps.
        val gapMillis = if (segment.lastExceedanceSampleAtMillis > 0L) {
            (nowMillis - segment.lastExceedanceSampleAtMillis).coerceAtLeast(0L)
        } else {
            elapsedMillis
        }
        val hasTimeGap = gapMillis > MAX_CONTIGUOUS_SAMPLE_INTERVAL_MS ||
            elapsedMillis > MAX_CONTIGUOUS_SAMPLE_INTERVAL_MS
        if (hasTimeGap) {
            resetCandidateExceedanceWaveLocked(segment)
        }
        segment.lastExceedanceSampleAtMillis = nowMillis

        if (elapsedMillis > MAX_CONTIGUOUS_SAMPLE_INTERVAL_MS) {
            return
        }

        var sampleExceedPct = 0.0
        if (dlLimitBps > 0L && observedDownBps > (dlLimitBps * EXCEEDANCE_TOLERANCE_RATIO)) {
            val pct = ((observedDownBps.toDouble() - dlLimitBps.toDouble()) / dlLimitBps.toDouble()) * 100.0
            sampleExceedPct = max(sampleExceedPct, pct)
        }
        if (ulLimitBps > 0L && observedUpBps > (ulLimitBps * EXCEEDANCE_TOLERANCE_RATIO)) {
            val pct = ((observedUpBps.toDouble() - ulLimitBps.toDouble()) / ulLimitBps.toDouble()) * 100.0
            sampleExceedPct = max(sampleExceedPct, pct)
        }

        if (sampleExceedPct > 0.0) {
            if (segment.candidateExceedanceStartMillis == null) {
                segment.candidateExceedanceStartMillis = nowMillis - elapsedMillis
                segment.candidateExceedanceDurationMillis = 0L
                segment.candidateMaxExceedancePct = 0.0
                segment.candidateWaveCommitted = false
            }
            segment.candidateExceedanceDurationMillis += elapsedMillis
            segment.candidateMaxExceedancePct = max(segment.candidateMaxExceedancePct, sampleExceedPct)

            if (segment.candidateExceedanceDurationMillis >= EXCEEDANCE_MIN_DURATION_MS) {
                segment.limitExceeded = true
                segment.maxExceedancePct = max(segment.maxExceedancePct, segment.candidateMaxExceedancePct)
                if (!segment.candidateWaveCommitted) {
                    // Include the full duration of the confirmed wave from its very beginning
                    // (including the initial 2,000 ms verification window).
                    segment.exceedanceDurationMillis += segment.candidateExceedanceDurationMillis
                    segment.candidateWaveCommitted = true
                } else {
                    segment.exceedanceDurationMillis += elapsedMillis
                }
            }
        } else {
            resetCandidateExceedanceWaveLocked(segment)
        }
    }

    private fun bumpPersistedRevision() {
        _persistedHistoryRevision.value = persistenceRevisionCounter.incrementAndGet()
    }

    private fun persistRecords(
        context: Context,
        records: List<SpeedPlanUsageRecord>,
        synchronous: Boolean,
    ) {
        if (records.isEmpty()) return
        val task = Runnable {
            var wroteAny = false
            records.forEach { record ->
                runCatching {
                    UsageRepository.upsertSpeedPlanUsageSession(context, record)
                    wroteAny = true
                }
            }
            if (wroteAny) {
                bumpPersistedRevision()
            }
        }
        if (!synchronous) {
            ioExecutor.execute(task)
            return
        }

        // Serialize terminal snapshots behind any queued ONGOING checkpoints. Running the
        // task directly on the caller could let an older queued checkpoint race after the
        // terminal write (or let the process exit before the queued write is durable).
        if (Thread.currentThread() === ioExecutorThread.get()) {
            task.run()
        } else {
            ioExecutor.submit(task).get(15, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
