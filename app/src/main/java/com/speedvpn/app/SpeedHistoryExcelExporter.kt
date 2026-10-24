package com.speedvpn.app

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class SpeedHistoryExportResult {
    data class Success(
        val recordCount: Int,
        val totalBytes: Long,
        val message: String,
    ) : SpeedHistoryExportResult()

    data class Failure(
        val userMessage: String,
        val technicalMessage: String? = null,
    ) : SpeedHistoryExportResult()
}

data class SpeedHistoryCategorySummary(
    val category: SpeedPlanOptionCategory,
    val planOptionLabel: String,
    val segmentsCount: Int,
    val totalDurationMillis: Long,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val totalBytes: Long,
    val sharePercent: Double,
)

data class SpeedHistoryReportSummary(
    val totalRecordsCount: Int,
    val distinctVpnSessionsCount: Int,
    val planChangesCount: Int,
    val totalDownloadBytes: Long,
    val totalUploadBytes: Long,
    val totalBytes: Long,
    val totalDurationMillis: Long,
    val completedCount: Int,
    val ongoingCount: Int,
    val abnormalCount: Int,
    val exceededCount: Int,
    val periodStartMillis: Long?,
    val periodEndMillis: Long?,
    val byCategory: List<SpeedHistoryCategorySummary>,
)

/**
 * Generates a real OpenXML SpreadsheetML (`.xlsx`) workbook with two RTL Arabic worksheets:
 * - Sheet 1 (`سجل السرعات`): One row per speed-plan session segment + totals row.
 * - Sheet 2 (`الملخص`): Overall totals, duration, session & plan-change counts, status counts,
 *   and consumption breakdown by speed option category and applied plan.
 */
object SpeedHistoryExcelExporter {
    const val XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val MIN_FREE_SPACE_BYTES = 2L * 1024L * 1024L

    private const val STYLE_TITLE = 1
    private const val STYLE_META = 2
    private const val STYLE_HEADER = 3
    private const val STYLE_TEXT = 4
    private const val STYLE_INT = 5
    private const val STYLE_DEC = 6
    private const val STYLE_TOTAL_TEXT = 7
    private const val STYLE_TOTAL_INT = 8
    private const val STYLE_TOTAL_DEC = 9

    fun suggestedFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis))
        return "SpeedVPN_Speed_History_" + stamp + ".xlsx"
    }

    private fun compareRecordsChronologically(a: SpeedPlanUsageRecord, b: SpeedPlanUsageRecord): Int {
        if (a.startedAtMillis < b.startedAtMillis) return -1
        if (a.startedAtMillis > b.startedAtMillis) return 1
        if (a.vpnGeneration < b.vpnGeneration) return -1
        if (a.vpnGeneration > b.vpnGeneration) return 1
        if (a.segmentIndex < b.segmentIndex) return -1
        if (a.segmentIndex > b.segmentIndex) return 1
        return 0
    }

    private fun compareSegmentsInSession(a: SpeedPlanUsageRecord, b: SpeedPlanUsageRecord): Int {
        if (a.segmentIndex < b.segmentIndex) return -1
        if (a.segmentIndex > b.segmentIndex) return 1
        if (a.startedAtMillis < b.startedAtMillis) return -1
        if (a.startedAtMillis > b.startedAtMillis) return 1
        return 0
    }

    fun sortRecordsChronologically(records: List<SpeedPlanUsageRecord>): List<SpeedPlanUsageRecord> {
        val copy = ArrayList<SpeedPlanUsageRecord>(records)
        for (i in 1 until copy.size) {
            val keyRec = copy.get(i)
            var j = i - 1
            while (j >= 0 && compareRecordsChronologically(copy.get(j), keyRec) > 0) {
                copy.set(j + 1, copy.get(j))
                j -= 1
            }
            copy.set(j + 1, keyRec)
        }
        return copy
    }

    /**
     * Counts actual speed-plan transitions within each VPN connection session (`processToken` + `vpnGeneration`).
     * Disconnecting and reconnecting on a new VPN generation is a new session, not a mid-session plan change.
     */
    fun countPlanTransitions(records: List<SpeedPlanUsageRecord>): Int {
        if (records.isEmpty()) return 0
        val bySession = LinkedHashMap<String, ArrayList<SpeedPlanUsageRecord>>()
        for (i in 0 until records.size) {
            val rec = records.get(i)
            val key = rec.processToken + ":" + rec.vpnGeneration
            var list = bySession.get(key)
            if (list == null) {
                list = ArrayList<SpeedPlanUsageRecord>()
                bySession.put(key, list)
            }
            list.add(rec)
        }

        var transitions = 0
        for (sessionRecords in bySession.values) {
            for (i in 1 until sessionRecords.size) {
                val keyRec = sessionRecords.get(i)
                var j = i - 1
                while (j >= 0 && compareSegmentsInSession(sessionRecords.get(j), keyRec) > 0) {
                    sessionRecords.set(j + 1, sessionRecords.get(j))
                    j -= 1
                }
                sessionRecords.set(j + 1, keyRec)
            }
            if (sessionRecords.size == 1) {
                if ("PLAN_CHANGED".equals(sessionRecords.get(0).closeReason, ignoreCase = true)) {
                    transitions += 1
                }
            } else {
                for (i in 0 until sessionRecords.size - 1) {
                    val prev = sessionRecords.get(i)
                    val next = sessionRecords.get(i + 1)
                    val dlChanged = prev.downloadLimitKbps != next.downloadLimitKbps
                    val ulChanged = prev.uploadLimitKbps != next.uploadLimitKbps
                    val markedChanged = "PLAN_CHANGED".equals(prev.closeReason, ignoreCase = true)
                    if (dlChanged || ulChanged || markedChanged) {
                        transitions += 1
                    }
                }
            }
        }
        return transitions
    }

    fun buildSummary(records: List<SpeedPlanUsageRecord>): SpeedHistoryReportSummary {
        val sorted = sortRecordsChronologically(records)
        var totalDl = 0L
        var totalUl = 0L
        var totalAll = 0L
        var totalDur = 0L
        var completed = 0
        var ongoing = 0
        var abnormal = 0
        var exceeded = 0
        var periodStart: Long? = null
        var periodEnd: Long? = null
        val sessionKeys = LinkedHashSet<String>()

        for (i in 0 until sorted.size) {
            val rec = sorted.get(i)
            val dl = if (rec.downloadBytes > 0L) rec.downloadBytes else 0L
            val ul = if (rec.uploadBytes > 0L) rec.uploadBytes else 0L
            val tot = if (rec.totalBytes > 0L) rec.totalBytes else (dl + ul)
            val dur = if (rec.durationMillis > 0L) rec.durationMillis else 0L
            totalDl += dl
            totalUl += ul
            totalAll += tot
            totalDur += dur
            sessionKeys.add(rec.processToken + ":" + rec.vpnGeneration)

            if (rec.status == SpeedPlanRecordStatus.COMPLETED) completed += 1
            else if (rec.status == SpeedPlanRecordStatus.ONGOING) ongoing += 1
            else if (rec.status == SpeedPlanRecordStatus.ABNORMAL_END) abnormal += 1

            if (rec.limitExceeded) exceeded += 1

            if (periodStart == null || rec.startedAtMillis < periodStart) {
                periodStart = rec.startedAtMillis
            }
            val effectiveEnd = if (rec.endedAtMillis != null) rec.endedAtMillis else rec.lastCheckpointMillis
            if (periodEnd == null || effectiveEnd > periodEnd) {
                periodEnd = effectiveEnd
            }
        }

        val planChanges = countPlanTransitions(sorted)
        val breakdown = ArrayList<SpeedHistoryCategorySummary>()
        val categories = SpeedPlanOptionCategory.values()

        for (cIdx in 0 until categories.size) {
            val cat = categories[cIdx]
            val byLabel = LinkedHashMap<String, ArrayList<SpeedPlanUsageRecord>>()
            for (rIdx in 0 until sorted.size) {
                val rec = sorted.get(rIdx)
                if (rec.planOptionCategory == cat) {
                    var group = byLabel.get(rec.planOptionLabel)
                    if (group == null) {
                        group = ArrayList<SpeedPlanUsageRecord>()
                        byLabel.put(rec.planOptionLabel, group)
                    }
                    group.add(rec)
                }
            }

            if (byLabel.isEmpty()) {
                breakdown.add(
                    SpeedHistoryCategorySummary(
                        category = cat,
                        planOptionLabel = cat.arabicLabel + " (بدون استخدام مسجل)",
                        segmentsCount = 0,
                        totalDurationMillis = 0L,
                        downloadBytes = 0L,
                        uploadBytes = 0L,
                        totalBytes = 0L,
                        sharePercent = 0.0,
                    ),
                )
            } else {
                for (label in byLabel.keys) {
                    val group = byLabel.get(label)!!
                    var gDl = 0L
                    var gUl = 0L
                    var gTot = 0L
                    var gDur = 0L
                    for (gIdx in 0 until group.size) {
                        val item = group.get(gIdx)
                        val dl = if (item.downloadBytes > 0L) item.downloadBytes else 0L
                        val ul = if (item.uploadBytes > 0L) item.uploadBytes else 0L
                        val tot = if (item.totalBytes > 0L) item.totalBytes else (dl + ul)
                        val dur = if (item.durationMillis > 0L) item.durationMillis else 0L
                        gDl += dl
                        gUl += ul
                        gTot += tot
                        gDur += dur
                    }
                    val share = if (totalAll > 0L) (gTot.toDouble() * 100.0) / totalAll.toDouble() else 0.0
                    breakdown.add(
                        SpeedHistoryCategorySummary(
                            category = cat,
                            planOptionLabel = label,
                            segmentsCount = group.size,
                            totalDurationMillis = gDur,
                            downloadBytes = gDl,
                            uploadBytes = gUl,
                            totalBytes = gTot,
                            sharePercent = share,
                        ),
                    )
                }
            }
        }

        return SpeedHistoryReportSummary(
            totalRecordsCount = sorted.size,
            distinctVpnSessionsCount = sessionKeys.size,
            planChangesCount = planChanges,
            totalDownloadBytes = totalDl,
            totalUploadBytes = totalUl,
            totalBytes = totalAll,
            totalDurationMillis = totalDur,
            completedCount = completed,
            ongoingCount = ongoing,
            abnormalCount = abnormal,
            exceededCount = exceeded,
            periodStartMillis = periodStart,
            periodEndMillis = periodEnd,
            byCategory = breakdown,
        )
    }

    fun exportToUri(
        context: Context,
        uri: Uri,
        generatedAtMillis: Long = System.currentTimeMillis(),
        openDestinationStream: (Context, Uri) -> OutputStream? = { ctx, targetUri ->
            ctx.contentResolver.openOutputStream(targetUri)
        },
    ): SpeedHistoryExportResult {
        return try {
            // Do not use context.cacheDir.usableSpace to judge a user-selected SAF destination URI,
            // as SAF destinations may reside on external storage or a document provider.
            SpeedPlanUsageTracker.flushActiveSegment(context, generatedAtMillis)
            val records = UsageRepository.readAllSpeedPlanUsageSessions(context)

            val out = try {
                openDestinationStream(context, uri)
            } catch (se: SecurityException) {
                return SpeedHistoryExportResult.Failure(
                    userMessage = "تعذر الوصول إلى الوجهة المختارة: تم رفض صلاحية الكتابة من مزود المستندات.",
                    technicalMessage = se.message ?: "SECURITY_EXCEPTION",
                )
            } catch (fnf: java.io.FileNotFoundException) {
                return SpeedHistoryExportResult.Failure(
                    userMessage = "تعذر فتح الوجهة المختارة: الملف أو المجلد غير متاح لدى مزود المستندات.",
                    technicalMessage = fnf.message ?: "FILE_NOT_FOUND",
                )
            } ?: return SpeedHistoryExportResult.Failure(
                userMessage = "تعذر فتح مجرى الكتابة للوجهة المختارة من مزود المستندات.",
                technicalMessage = "NULL_OUTPUT_STREAM",
            )

            out.use { raw ->
                BufferedOutputStream(raw).use { bos ->
                    writeWorkbookToStream(
                        outputStream = bos,
                        records = records,
                        generatedAtMillis = generatedAtMillis,
                    )
                    bos.flush()
                }
            }

            val summary = buildSummary(records)
            val msg = if (records.isEmpty()) {
                "تم تصدير ملف Excel (.xlsx) بنجاح — لا توجد سجلات محفوظة بعد (يحتوي الملف على ترويسة وملخص صفري واضح)."
            } else {
                "تم تصدير " + records.size + " سجل سرعة إلى ملف Excel (.xlsx) بنجاح."
            }
            SpeedHistoryExportResult.Success(
                recordCount = records.size,
                totalBytes = summary.totalBytes,
                message = msg,
            )
        } catch (se: SecurityException) {
            SpeedHistoryExportResult.Failure(
                userMessage = "تعذر حفظ ملف Excel: تم رفض الوصول إلى وجهة الحفظ المختارة.",
                technicalMessage = se.message,
            )
        } catch (ioe: IOException) {
            val noSpace = ioe.message?.contains("ENOSPC", ignoreCase = true) == true ||
                ioe.message?.contains("No space", ignoreCase = true) == true
            val userMsg = if (noSpace) {
                "فشل كتابة ملف Excel: أبلغ نظام التخزين عن عدم توفر مساحة كافية أثناء الحفظ."
            } else {
                "فشل حفظ ملف Excel أثناء الكتابة إلى الوجهة المختارة: " + (ioe.localizedMessage ?: "خطأ في الإدخال/الإخراج")
            }
            SpeedHistoryExportResult.Failure(userMessage = userMsg, technicalMessage = ioe.message)
        } catch (t: Throwable) {
            SpeedHistoryExportResult.Failure(
                userMessage = "حدث خطأ غير متوقع أثناء تصدير ملف Excel: " + (t.localizedMessage ?: t.javaClass.simpleName),
                technicalMessage = t.message,
            )
        }
    }

    fun exportToFile(
        file: File,
        records: List<SpeedPlanUsageRecord>,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): SpeedHistoryExportResult {
        return try {
            if (file.parentFile != null) {
                file.parentFile.mkdirs()
            }
            val freeBytes = if (file.parentFile != null) file.parentFile.usableSpace else Long.MAX_VALUE
            if (freeBytes > 0L && freeBytes < MIN_FREE_SPACE_BYTES) {
                return SpeedHistoryExportResult.Failure(
                    userMessage = "تعذر تصدير ملف Excel: المساحة المتاحة غير كافية.",
                    technicalMessage = "INSUFFICIENT_STORAGE",
                )
            }
            val fos = FileOutputStream(file)
            try {
                val bos = BufferedOutputStream(fos)
                try {
                    writeWorkbookToStream(bos, records, generatedAtMillis)
                    bos.flush()
                } finally {
                    bos.close()
                }
            } finally {
                fos.close()
            }
            val summary = buildSummary(records)
            SpeedHistoryExportResult.Success(
                recordCount = records.size,
                totalBytes = summary.totalBytes,
                message = "تم تصدير " + records.size + " سجل إلى ملف Excel بنجاح.",
            )
        } catch (t: Throwable) {
            SpeedHistoryExportResult.Failure(
                userMessage = "فشل تصدير ملف Excel: " + t.message,
                technicalMessage = t.message,
            )
        }
    }

    fun writeWorkbookToStream(
        outputStream: OutputStream,
        records: List<SpeedPlanUsageRecord>,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val sorted = sortRecordsChronologically(records)
        val summary = buildSummary(sorted)
        val sst = SharedStringTable()

        val sheet1Xml = buildHistorySheetXml(sorted, summary, generatedAtMillis, sst)
        val sheet2Xml = buildSummarySheetXml(summary, generatedAtMillis, sst)
        val sharedStringsXml = sst.toXml()

        val zip = ZipOutputStream(outputStream)
        writeZipEntry(zip, "[Content_Types].xml", buildContentTypesXml())
        writeZipEntry(zip, "_rels/.rels", buildRootRelsXml())
        writeZipEntry(zip, "xl/workbook.xml", buildWorkbookXml())
        writeZipEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml())
        writeZipEntry(zip, "xl/styles.xml", buildStylesXml())
        writeZipEntry(zip, "xl/sharedStrings.xml", sharedStringsXml)
        writeZipEntry(zip, "xl/worksheets/sheet1.xml", sheet1Xml)
        writeZipEntry(zip, "xl/worksheets/sheet2.xml", sheet2Xml)
        zip.finish()
        zip.flush()
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildHistorySheetXml(
        records: List<SpeedPlanUsageRecord>,
        summary: SpeedHistoryReportSummary,
        generatedAtMillis: Long,
        sst: SharedStringTable,
    ): String {
        val generatedStr = SpeedPlanClassifier.formatTimestamp(generatedAtMillis)
        val periodStartStr = if (summary.periodStartMillis != null) {
            SpeedPlanClassifier.formatTimestamp(summary.periodStartMillis)
        } else {
            "لا توجد سجلات"
        }
        val periodEndStr = if (summary.periodEndMillis != null) {
            SpeedPlanClassifier.formatTimestamp(summary.periodEndMillis)
        } else {
            "لا توجد سجلات"
        }

        val headers = arrayOf(
            "رقم التسلسل",
            "معرّف السجل (Session ID)",
            "تاريخ ووقت البداية",
            "تاريخ ووقت النهاية",
            "مدة الاستخدام (ثانية)",
            "مدة الاستخدام (منسقة)",
            "فئة خيار السرعة",
            "تفصيل الخطة المطبقة",
            "حد التنزيل الفعلي المطبق",
            "حد الرفع الفعلي المطبق",
            "بيانات التنزيل (بايت)",
            "بيانات التنزيل (MB)",
            "بيانات التنزيل (منسق)",
            "بيانات الرفع (بايت)",
            "بيانات الرفع (MB)",
            "بيانات الرفع (منسق)",
            "إجمالي البيانات (بايت)",
            "إجمالي البيانات (MB)",
            "إجمالي البيانات (GB)",
            "إجمالي البيانات (KB / MB / GB)",
            "متوسط سرعة التنزيل الفعلية (KB/s)",
            "متوسط سرعة التنزيل (منسق)",
            "متوسط سرعة الرفع الفعلية (KB/s)",
            "متوسط سرعة الرفع (منسق)",
            "حالة السجل",
            "تجاوز محتمل للحد؟",
            "نسبة التجاوز القصوى (%)",
            "مدة التجاوز (ثانية)",
        )

        val widths = intArrayOf(
            12, 36, 22, 22, 18, 22, 20, 30, 24, 24,
            20, 18, 22, 20, 18, 22, 22, 18, 18, 32,
            24, 22, 24, 22, 24, 18, 20, 18,
        )

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheetViews><sheetView rightToLeft=\"1\" tabSelected=\"1\" workbookViewId=\"0\"><pane ySplit=\"4\" topLeftCell=\"A5\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        sb.append("<cols>")
        for (idx in 0 until widths.size) {
            val c = idx + 1
            val w = widths[idx]
            sb.append("<col min=\"").append(c).append("\" max=\"").append(c).append("\" width=\"").append(w).append("\" customWidth=\"1\"/>")
        }
        sb.append("</cols>")
        sb.append("<sheetData>")

        sb.append("<row r=\"1\" ht=\"28\" customHeight=\"1\">")
        sb.append(stringCell("A", 1, "سجل استهلاك السرعات الفعلي عبر VPN — SpeedVPN Android", STYLE_TITLE, sst))
        for (c in 2..headers.size) sb.append(emptyStyledCell(colName(c), 1, STYLE_TITLE))
        sb.append("</row>")

        val metaText = "تاريخ إنشاء التقرير: " + generatedStr + " | الفترة المغطاة: من " + periodStartStr + " إلى " + periodEndStr + " | عدد الفترات: " + records.size
        sb.append("<row r=\"2\" ht=\"22\" customHeight=\"1\">")
        sb.append(stringCell("A", 2, metaText, STYLE_META, sst))
        for (c in 2..headers.size) sb.append(emptyStyledCell(colName(c), 2, STYLE_META))
        sb.append("</row>")

        val basisText = "مصدر القياس: عدادات البايتات الفعلية المنقولة عبر مرحّل النفق (SOCKS5 TCP + UDP Relay) مع استثناء بيانات فحص السرعة التجريبي."
        sb.append("<row r=\"3\" ht=\"20\" customHeight=\"1\">")
        sb.append(stringCell("A", 3, basisText, STYLE_META, sst))
        for (c in 2..headers.size) sb.append(emptyStyledCell(colName(c), 3, STYLE_META))
        sb.append("</row>")

        sb.append("<row r=\"4\" ht=\"26\" customHeight=\"1\">")
        for (idx in 0 until headers.size) {
            sb.append(stringCell(colName(idx + 1), 4, headers[idx], STYLE_HEADER, sst))
        }
        sb.append("</row>")

        var currentRow = 5
        if (records.isEmpty()) {
            sb.append("<row r=\"").append(currentRow).append("\" ht=\"22\" customHeight=\"1\">")
            sb.append(stringCell("A", currentRow, "لا توجد سجلات محفوظة حتى الآن", STYLE_TEXT, sst))
            sb.append(stringCell("B", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(stringCell("C", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(stringCell("D", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(numberCell("E", currentRow, 0L, STYLE_INT))
            sb.append(stringCell("F", currentRow, "00:00 (0 د 0 ث)", STYLE_TEXT, sst))
            sb.append(stringCell("G", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(stringCell("H", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(stringCell("I", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(stringCell("J", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(numberCell("K", currentRow, 0L, STYLE_INT))
            sb.append(decimalCell("L", currentRow, 0.0, STYLE_DEC))
            sb.append(stringCell("M", currentRow, "0 B", STYLE_TEXT, sst))
            sb.append(numberCell("N", currentRow, 0L, STYLE_INT))
            sb.append(decimalCell("O", currentRow, 0.0, STYLE_DEC))
            sb.append(stringCell("P", currentRow, "0 B", STYLE_TEXT, sst))
            sb.append(numberCell("Q", currentRow, 0L, STYLE_INT))
            sb.append(decimalCell("R", currentRow, 0.0, STYLE_DEC))
            sb.append(decimalCell("S", currentRow, 0.0, STYLE_DEC))
            sb.append(stringCell("T", currentRow, "0 B | 0.00 KB | 0.00 MB | 0.0000 GB", STYLE_TEXT, sst))
            sb.append(decimalCell("U", currentRow, 0.0, STYLE_DEC))
            sb.append(stringCell("V", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(decimalCell("W", currentRow, 0.0, STYLE_DEC))
            sb.append(stringCell("X", currentRow, "غير متاح", STYLE_TEXT, sst))
            sb.append(stringCell("Y", currentRow, "قاعدة البيانات فارغة", STYLE_TEXT, sst))
            sb.append(stringCell("Z", currentRow, "لا", STYLE_TEXT, sst))
            sb.append(decimalCell("AA", currentRow, 0.0, STYLE_DEC))
            sb.append(decimalCell("AB", currentRow, 0.0, STYLE_DEC))
            sb.append("</row>")
        } else {
            val firstDataRow = currentRow
            var maxExceedPct = 0.0
            var totalExceedMillis = 0L

            for (idx in 0 until records.size) {
                val rec = records.get(idx)
                val r = currentRow
                val safeDurMillis = if (rec.durationMillis > 0L) rec.durationMillis else 0L
                val durSec = safeDurMillis / 1_000L
                val endText = if (rec.endedAtMillis != null && rec.endedAtMillis > 0L) {
                    SpeedPlanClassifier.formatTimestamp(rec.endedAtMillis)
                } else {
                    "غير متاح (جلسة مستمرة)"
                }
                val dlB = if (rec.downloadBytes > 0L) rec.downloadBytes else 0L
                val ulB = if (rec.uploadBytes > 0L) rec.uploadBytes else 0L
                val totB = if (rec.totalBytes >= dlB + ulB) rec.totalBytes else (dlB + ulB)
                val dlMb = dlB.toDouble() / (1024.0 * 1024.0)
                val ulMb = ulB.toDouble() / (1024.0 * 1024.0)
                val totMb = totB.toDouble() / (1024.0 * 1024.0)
                val totGb = totB.toDouble() / (1024.0 * 1024.0 * 1024.0)
                val safeAvgDlBps = if (rec.avgDownloadBps > 0L) rec.avgDownloadBps else 0L
                val avgDlKBs = (safeAvgDlBps.toDouble() / 8.0) / 1000.0
                val safeAvgUlBps = if (rec.avgUploadBps > 0L) rec.avgUploadBps else 0L
                val avgUlKBs = (safeAvgUlBps.toDouble() / 8.0) / 1000.0
                val exceedLabel = if (rec.downloadLimitKbps == null && rec.uploadLimitKbps == null) {
                    "غير منطبق (Unlimited)"
                } else if (rec.limitExceeded) {
                    "نعم"
                } else {
                    "لا"
                }
                val exceedPct = if (rec.limitExceeded) rec.maxExceedancePct else 0.0
                val exceedSec = if (rec.limitExceeded) rec.exceedanceDurationMillis.toDouble() / 1_000.0 else 0.0
                if (rec.limitExceeded) {
                    if (rec.maxExceedancePct > maxExceedPct) maxExceedPct = rec.maxExceedancePct
                    totalExceedMillis += rec.exceedanceDurationMillis
                }

                sb.append("<row r=\"").append(r).append("\" ht=\"20\" customHeight=\"1\">")
                sb.append(numberCell("A", r, (idx + 1).toLong(), STYLE_INT))
                sb.append(stringCell("B", r, rec.sessionId, STYLE_TEXT, sst))
                sb.append(stringCell("C", r, SpeedPlanClassifier.formatTimestamp(rec.startedAtMillis), STYLE_TEXT, sst))
                sb.append(stringCell("D", r, endText, STYLE_TEXT, sst))
                sb.append(numberCell("E", r, durSec, STYLE_INT))
                sb.append(stringCell("F", r, rec.formattedDuration(), STYLE_TEXT, sst))
                sb.append(stringCell("G", r, rec.planOptionCategory.arabicLabel, STYLE_TEXT, sst))
                sb.append(stringCell("H", r, rec.planOptionLabel, STYLE_TEXT, sst))
                sb.append(stringCell("I", r, rec.downloadLimitUnitLabel, STYLE_TEXT, sst))
                sb.append(stringCell("J", r, rec.uploadLimitUnitLabel, STYLE_TEXT, sst))
                sb.append(numberCell("K", r, dlB, STYLE_INT))
                sb.append(decimalCell("L", r, dlMb, STYLE_DEC))
                sb.append(stringCell("M", r, formatDataBytes(dlB), STYLE_TEXT, sst))
                sb.append(numberCell("N", r, ulB, STYLE_INT))
                sb.append(decimalCell("O", r, ulMb, STYLE_DEC))
                sb.append(stringCell("P", r, formatDataBytes(ulB), STYLE_TEXT, sst))
                sb.append(numberCell("Q", r, totB, STYLE_INT))
                sb.append(decimalCell("R", r, totMb, STYLE_DEC))
                sb.append(decimalCell("S", r, totGb, STYLE_DEC))
                sb.append(stringCell("T", r, rec.formattedDataBreakdown(), STYLE_TEXT, sst))
                sb.append(decimalCell("U", r, avgDlKBs, STYLE_DEC))
                sb.append(stringCell("V", r, rec.formattedAvgDownloadRate(), STYLE_TEXT, sst))
                sb.append(decimalCell("W", r, avgUlKBs, STYLE_DEC))
                sb.append(stringCell("X", r, rec.formattedAvgUploadRate(), STYLE_TEXT, sst))
                sb.append(stringCell("Y", r, rec.status.arabicLabel, STYLE_TEXT, sst))
                sb.append(stringCell("Z", r, exceedLabel, STYLE_TEXT, sst))
                sb.append(decimalCell("AA", r, exceedPct, STYLE_DEC))
                sb.append(decimalCell("AB", r, exceedSec, STYLE_DEC))
                sb.append("</row>")
                currentRow += 1
            }

            val lastDataRow = currentRow - 1
            val r = currentRow
            val totalDurSec = summary.totalDurationMillis / 1_000L
            val overallAvgDlBps = if (summary.totalDurationMillis > 0L) {
                Math.round((summary.totalDownloadBytes.toDouble() * 8_000.0) / summary.totalDurationMillis.toDouble())
            } else {
                0L
            }
            val overallAvgUlBps = if (summary.totalDurationMillis > 0L) {
                Math.round((summary.totalUploadBytes.toDouble() * 8_000.0) / summary.totalDurationMillis.toDouble())
            } else {
                0L
            }
            val totalExceedSec = totalExceedMillis.toDouble() / 1_000.0

            sb.append("<row r=\"").append(r).append("\" ht=\"24\" customHeight=\"1\">")
            sb.append(stringCell("A", r, "الإجمالي", STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("B", r, records.size.toString() + " فترة مسجلة", STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("C", r, periodStartStr, STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("D", r, periodEndStr, STYLE_TOTAL_TEXT, sst))
            sb.append(formulaIntCell("E", r, "SUM(E" + firstDataRow + ":E" + lastDataRow + ")", totalDurSec, STYLE_TOTAL_INT))
            sb.append(stringCell("F", r, SpeedPlanClassifier.formatDuration(summary.totalDurationMillis), STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("G", r, "جميع الخيارات", STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("H", r, "إجمالي كل الفترات", STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("I", r, "—", STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("J", r, "—", STYLE_TOTAL_TEXT, sst))
            sb.append(formulaIntCell("K", r, "SUM(K" + firstDataRow + ":K" + lastDataRow + ")", summary.totalDownloadBytes, STYLE_TOTAL_INT))
            sb.append(formulaDecCell("L", r, "SUM(L" + firstDataRow + ":L" + lastDataRow + ")", summary.totalDownloadBytes.toDouble() / (1024.0 * 1024.0), STYLE_TOTAL_DEC))
            sb.append(stringCell("M", r, formatDataBytes(summary.totalDownloadBytes), STYLE_TOTAL_TEXT, sst))
            sb.append(formulaIntCell("N", r, "SUM(N" + firstDataRow + ":N" + lastDataRow + ")", summary.totalUploadBytes, STYLE_TOTAL_INT))
            sb.append(formulaDecCell("O", r, "SUM(O" + firstDataRow + ":O" + lastDataRow + ")", summary.totalUploadBytes.toDouble() / (1024.0 * 1024.0), STYLE_TOTAL_DEC))
            sb.append(stringCell("P", r, formatDataBytes(summary.totalUploadBytes), STYLE_TOTAL_TEXT, sst))
            sb.append(formulaIntCell("Q", r, "SUM(Q" + firstDataRow + ":Q" + lastDataRow + ")", summary.totalBytes, STYLE_TOTAL_INT))
            sb.append(formulaDecCell("R", r, "SUM(R" + firstDataRow + ":R" + lastDataRow + ")", summary.totalBytes.toDouble() / (1024.0 * 1024.0), STYLE_TOTAL_DEC))
            sb.append(formulaDecCell("S", r, "SUM(S" + firstDataRow + ":S" + lastDataRow + ")", summary.totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0), STYLE_TOTAL_DEC))
            sb.append(stringCell("T", r, SpeedPlanClassifier.formatBytesAllUnits(summary.totalBytes), STYLE_TOTAL_TEXT, sst))
            sb.append(decimalCell("U", r, (overallAvgDlBps.toDouble() / 8.0) / 1000.0, STYLE_TOTAL_DEC))
            sb.append(stringCell("V", r, SpeedPlanClassifier.formatRateBps(overallAvgDlBps), STYLE_TOTAL_TEXT, sst))
            sb.append(decimalCell("W", r, (overallAvgUlBps.toDouble() / 8.0) / 1000.0, STYLE_TOTAL_DEC))
            sb.append(stringCell("X", r, SpeedPlanClassifier.formatRateBps(overallAvgUlBps), STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("Y", r, "مكتمل: " + summary.completedCount, STYLE_TOTAL_TEXT, sst))
            sb.append(stringCell("Z", r, "تجاوز في " + summary.exceededCount + " فترة", STYLE_TOTAL_TEXT, sst))
            sb.append(decimalCell("AA", r, maxExceedPct, STYLE_TOTAL_DEC))
            sb.append(decimalCell("AB", r, totalExceedSec, STYLE_TOTAL_DEC))
            sb.append("</row>")
        }

        sb.append("</sheetData>")
        sb.append("<mergeCells count=\"3\">")
        sb.append("<mergeCell ref=\"A1:AB1\"/>")
        sb.append("<mergeCell ref=\"A2:AB2\"/>")
        sb.append("<mergeCell ref=\"A3:AB3\"/>")
        sb.append("</mergeCells>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun buildSummarySheetXml(
        summary: SpeedHistoryReportSummary,
        generatedAtMillis: Long,
        sst: SharedStringTable,
    ): String {
        val generatedStr = SpeedPlanClassifier.formatTimestamp(generatedAtMillis)
        val periodStartStr = if (summary.periodStartMillis != null) {
            SpeedPlanClassifier.formatTimestamp(summary.periodStartMillis)
        } else {
            "غير متاح (لا توجد سجلات)"
        }
        val periodEndStr = if (summary.periodEndMillis != null) {
            SpeedPlanClassifier.formatTimestamp(summary.periodEndMillis)
        } else {
            "غير متاح (لا توجد سجلات)"
        }
        val totalDurSec = summary.totalDurationMillis / 1_000L

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\"/></sheetViews>")
        sb.append("<cols>")
        val widths = intArrayOf(28, 34, 18, 22, 22, 22, 24, 22, 24, 22, 30, 18)
        for (idx in 0 until widths.size) {
            val c = idx + 1
            val w = widths[idx]
            sb.append("<col min=\"").append(c).append("\" max=\"").append(c).append("\" width=\"").append(w).append("\" customWidth=\"1\"/>")
        }
        sb.append("</cols>")
        sb.append("<sheetData>")

        sb.append("<row r=\"1\" ht=\"28\" customHeight=\"1\">")
        sb.append(stringCell("A", 1, "ملخص استهلاك السرعات وحركة بيانات VPN — SpeedVPN Android", STYLE_TITLE, sst))
        for (c in 2..12) sb.append(emptyStyledCell(colName(c), 1, STYLE_TITLE))
        sb.append("</row>")

        sb.append("<row r=\"2\" ht=\"22\" customHeight=\"1\">")
        sb.append(
            stringCell(
                "A",
                2,
                "تاريخ إنشاء التقرير: " + generatedStr + " | بداية الفترة: " + periodStartStr + " | نهاية الفترة: " + periodEndStr,
                STYLE_META,
                sst,
            ),
        )
        for (c in 2..12) sb.append(emptyStyledCell(colName(c), 2, STYLE_META))
        sb.append("</row>")

        sb.append("<row r=\"4\" ht=\"25\" customHeight=\"1\">")
        sb.append(stringCell("A", 4, "المؤشر الإحصائي العام", STYLE_HEADER, sst))
        sb.append(stringCell("B", 4, "القيمة الرقمية", STYLE_HEADER, sst))
        sb.append(stringCell("C", 4, "الوحدة", STYLE_HEADER, sst))
        sb.append(stringCell("D", 4, "القيمة المنسقة / التفصيل", STYLE_HEADER, sst))
        sb.append("</row>")

        val kpiLabels = arrayOf(
            "إجمالي بيانات التنزيل",
            "إجمالي بيانات الرفع",
            "إجمالي البيانات الكلي (تنزيل + رفع)",
            "إجمالي مدة الاستخدام الفعلية",
            "عدد الجلسات المستقلة (VPN Sessions)",
            "إجمالي عدد فترات السرعة المسجلة",
            "عدد مرات تغيير خطة السرعة أثناء الاتصال",
            "الفترات المكتملة بصورة طبيعية",
            "الفترات المستمرة حاليًا",
            "الفترات المنتهية بصورة غير طبيعية",
            "الفترات التي سُجّل فيها تجاوز محتمل للحد",
        )
        val kpiValues = longArrayOf(
            summary.totalDownloadBytes,
            summary.totalUploadBytes,
            summary.totalBytes,
            totalDurSec,
            summary.distinctVpnSessionsCount.toLong(),
            summary.totalRecordsCount.toLong(),
            summary.planChangesCount.toLong(),
            summary.completedCount.toLong(),
            summary.ongoingCount.toLong(),
            summary.abnormalCount.toLong(),
            summary.exceededCount.toLong(),
        )
        val kpiUnits = arrayOf(
            "بايت (Bytes)",
            "بايت (Bytes)",
            "بايت (Bytes)",
            "ثانية (Seconds)",
            "جلسة اتصال",
            "فترة (Segment)",
            "تغيير خطة",
            "فترة",
            "فترة",
            "فترة",
            "فترة",
        )
        val exceededText = if (summary.exceededCount == 0) {
            "0 (لم يُسجّل تجاوز)"
        } else {
            summary.exceededCount.toString() + " فترة"
        }
        val kpiFormatted = arrayOf(
            SpeedPlanClassifier.formatBytesAllUnits(summary.totalDownloadBytes),
            SpeedPlanClassifier.formatBytesAllUnits(summary.totalUploadBytes),
            SpeedPlanClassifier.formatBytesAllUnits(summary.totalBytes),
            SpeedPlanClassifier.formatDuration(summary.totalDurationMillis),
            summary.distinctVpnSessionsCount.toString() + " جلسة",
            summary.totalRecordsCount.toString() + " فترة",
            summary.planChangesCount.toString() + " مرة",
            summary.completedCount.toString() + " مكتمل",
            summary.ongoingCount.toString() + " مستمر",
            summary.abnormalCount.toString() + " غير طبيعي",
            exceededText,
        )

        var rowIdx = 5
        for (kIdx in 0 until kpiLabels.size) {
            val highlight = kIdx < 4
            val tStyle = if (highlight) STYLE_TOTAL_TEXT else STYLE_TEXT
            val nStyle = if (highlight) STYLE_TOTAL_INT else STYLE_INT
            sb.append("<row r=\"").append(rowIdx).append("\" ht=\"21\" customHeight=\"1\">")
            sb.append(stringCell("A", rowIdx, kpiLabels[kIdx], tStyle, sst))
            sb.append(numberCell("B", rowIdx, kpiValues[kIdx], nStyle))
            sb.append(stringCell("C", rowIdx, kpiUnits[kIdx], tStyle, sst))
            sb.append(stringCell("D", rowIdx, kpiFormatted[kIdx], tStyle, sst))
            sb.append("</row>")
            rowIdx += 1
        }

        rowIdx += 1
        val breakdownHeaderRow = rowIdx
        val breakdownHeaders = arrayOf(
            "فئة خيار السرعة",
            "وصف الخطة / الحدود المطبقة",
            "عدد الفترات",
            "إجمالي المدة (ثانية)",
            "إجمالي المدة (منسق)",
            "بيانات التنزيل (بايت)",
            "بيانات التنزيل (منسق)",
            "بيانات الرفع (بايت)",
            "بيانات الرفع (منسق)",
            "إجمالي البيانات (بايت)",
            "إجمالي البيانات (KB / MB / GB)",
            "نسبة الاستهلاك (%)",
        )
        sb.append("<row r=\"").append(breakdownHeaderRow).append("\" ht=\"25\" customHeight=\"1\">")
        for (idx in 0 until breakdownHeaders.size) {
            sb.append(stringCell(colName(idx + 1), breakdownHeaderRow, breakdownHeaders[idx], STYLE_HEADER, sst))
        }
        sb.append("</row>")
        rowIdx += 1

        val firstBreakdownRow = rowIdx
        for (bIdx in 0 until summary.byCategory.size) {
            val item = summary.byCategory.get(bIdx)
            val durSec = item.totalDurationMillis / 1_000L
            sb.append("<row r=\"").append(rowIdx).append("\" ht=\"20\" customHeight=\"1\">")
            sb.append(stringCell("A", rowIdx, item.category.arabicLabel, STYLE_TEXT, sst))
            sb.append(stringCell("B", rowIdx, item.planOptionLabel, STYLE_TEXT, sst))
            sb.append(numberCell("C", rowIdx, item.segmentsCount.toLong(), STYLE_INT))
            sb.append(numberCell("D", rowIdx, durSec, STYLE_INT))
            sb.append(stringCell("E", rowIdx, SpeedPlanClassifier.formatDuration(item.totalDurationMillis), STYLE_TEXT, sst))
            sb.append(numberCell("F", rowIdx, item.downloadBytes, STYLE_INT))
            sb.append(stringCell("G", rowIdx, formatDataBytes(item.downloadBytes), STYLE_TEXT, sst))
            sb.append(numberCell("H", rowIdx, item.uploadBytes, STYLE_INT))
            sb.append(stringCell("I", rowIdx, formatDataBytes(item.uploadBytes), STYLE_TEXT, sst))
            sb.append(numberCell("J", rowIdx, item.totalBytes, STYLE_INT))
            sb.append(stringCell("K", rowIdx, SpeedPlanClassifier.formatBytesAllUnits(item.totalBytes), STYLE_TEXT, sst))
            sb.append(decimalCell("L", rowIdx, item.sharePercent, STYLE_DEC))
            sb.append("</row>")
            rowIdx += 1
        }
        val lastBreakdownRow = rowIdx - 1

        sb.append("<row r=\"").append(rowIdx).append("\" ht=\"24\" customHeight=\"1\">")
        sb.append(stringCell("A", rowIdx, "الإجمالي الكلي حسب خيار السرعة", STYLE_TOTAL_TEXT, sst))
        sb.append(stringCell("B", rowIdx, "جميع الخطط", STYLE_TOTAL_TEXT, sst))
        sb.append(formulaIntCell("C", rowIdx, "SUM(C" + firstBreakdownRow + ":C" + lastBreakdownRow + ")", summary.totalRecordsCount.toLong(), STYLE_TOTAL_INT))
        sb.append(formulaIntCell("D", rowIdx, "SUM(D" + firstBreakdownRow + ":D" + lastBreakdownRow + ")", totalDurSec, STYLE_TOTAL_INT))
        sb.append(stringCell("E", rowIdx, SpeedPlanClassifier.formatDuration(summary.totalDurationMillis), STYLE_TOTAL_TEXT, sst))
        sb.append(formulaIntCell("F", rowIdx, "SUM(F" + firstBreakdownRow + ":F" + lastBreakdownRow + ")", summary.totalDownloadBytes, STYLE_TOTAL_INT))
        sb.append(stringCell("G", rowIdx, formatDataBytes(summary.totalDownloadBytes), STYLE_TOTAL_TEXT, sst))
        sb.append(formulaIntCell("H", rowIdx, "SUM(H" + firstBreakdownRow + ":H" + lastBreakdownRow + ")", summary.totalUploadBytes, STYLE_TOTAL_INT))
        sb.append(stringCell("I", rowIdx, formatDataBytes(summary.totalUploadBytes), STYLE_TOTAL_TEXT, sst))
        sb.append(formulaIntCell("J", rowIdx, "SUM(J" + firstBreakdownRow + ":J" + lastBreakdownRow + ")", summary.totalBytes, STYLE_TOTAL_INT))
        sb.append(stringCell("K", rowIdx, SpeedPlanClassifier.formatBytesAllUnits(summary.totalBytes), STYLE_TOTAL_TEXT, sst))
        sb.append(decimalCell("L", rowIdx, if (summary.totalBytes > 0L) 100.0 else 0.0, STYLE_TOTAL_DEC))
        sb.append("</row>")

        sb.append("</sheetData>")
        sb.append("<mergeCells count=\"2\">")
        sb.append("<mergeCell ref=\"A1:L1\"/>")
        sb.append("<mergeCell ref=\"A2:L2\"/>")
        sb.append("</mergeCells>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun buildContentTypesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
            <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
        </Types>
    """.trimIndent()

    private fun buildRootRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildWorkbookXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <bookViews>
                <workbookView activeTab="0"/>
            </bookViews>
            <sheets>
                <sheet name="سجل السرعات" sheetId="1" r:id="rId1"/>
                <sheet name="الملخص" sheetId="2" r:id="rId2"/>
            </sheets>
        </workbook>
    """.trimIndent()

    private fun buildWorkbookRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
            <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildStylesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <numFmts count="2">
                <numFmt numFmtId="164" formatCode="#,##0"/>
                <numFmt numFmtId="165" formatCode="#,##0.00"/>
            </numFmts>
            <fonts count="4">
                <font><sz val="11"/><color rgb="FF102A33"/><name val="Arial"/></font>
                <font><b/><sz val="13"/><color rgb="FFFFFFFF"/><name val="Arial"/></font>
                <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Arial"/></font>
                <font><b/><sz val="11"/><color rgb="FF0B5369"/><name val="Arial"/></font>
            </fonts>
            <fills count="6">
                <fill><patternFill patternType="none"/></fill>
                <fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FF0B5369"/><bgColor indexed="64"/></patternFill></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FFE6F2F5"/><bgColor indexed="64"/></patternFill></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FF146C82"/><bgColor indexed="64"/></patternFill></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FFD9EDF2"/><bgColor indexed="64"/></patternFill></fill>
            </fills>
            <borders count="2">
                <border><left/><right/><top/><bottom/><diagonal/></border>
                <border>
                    <left style="thin"><color rgb="FFB8CFD6"/></left>
                    <right style="thin"><color rgb="FFB8CFD6"/></right>
                    <top style="thin"><color rgb="FFB8CFD6"/></top>
                    <bottom style="thin"><color rgb="FFB8CFD6"/></bottom>
                    <diagonal/>
                </border>
            </borders>
            <cellStyleXfs count="1">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
            </cellStyleXfs>
            <cellXfs count="10">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="center" vertical="center" readingOrder="2"/>
                </xf>
                <xf numFmtId="0" fontId="3" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="right" vertical="center" readingOrder="2"/>
                </xf>
                <xf numFmtId="0" fontId="2" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="center" vertical="center" wrapText="1" readingOrder="2"/>
                </xf>
                <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="right" vertical="center" readingOrder="2"/>
                </xf>
                <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="right" vertical="center"/>
                </xf>
                <xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="right" vertical="center"/>
                </xf>
                <xf numFmtId="0" fontId="3" fillId="5" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="right" vertical="center" readingOrder="2"/>
                </xf>
                <xf numFmtId="164" fontId="3" fillId="5" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="right" vertical="center"/>
                </xf>
                <xf numFmtId="165" fontId="3" fillId="5" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
                    <alignment horizontal="right" vertical="center"/>
                </xf>
            </cellXfs>
            <cellStyles count="1">
                <cellStyle name="Normal" xfId="0" builtinId="0"/>
            </cellStyles>
        </styleSheet>
    """.trimIndent()

    private fun colName(colIndex1Based: Int): String {
        var n = colIndex1Based
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun stringCell(col: String, row: Int, value: String, style: Int, sst: SharedStringTable): String {
        val idx = sst.indexOf(value)
        return "<c r=\"" + col + row + "\" s=\"" + style + "\" t=\"s\"><v>" + idx + "</v></c>"
    }

    private fun emptyStyledCell(col: String, row: Int, style: Int): String {
        return "<c r=\"" + col + row + "\" s=\"" + style + "\"/>"
    }

    private fun numberCell(col: String, row: Int, value: Long, style: Int): String {
        return "<c r=\"" + col + row + "\" s=\"" + style + "\"><v>" + value + "</v></c>"
    }

    private fun decimalCell(col: String, row: Int, value: Double, style: Int): String {
        val safe = if (java.lang.Double.isFinite(value)) value else 0.0
        val formatted = String.format(Locale.US, "%.4f", safe)
        return "<c r=\"" + col + row + "\" s=\"" + style + "\"><v>" + formatted + "</v></c>"
    }

    private fun formulaIntCell(col: String, row: Int, formula: String, cached: Long, style: Int): String {
        return "<c r=\"" + col + row + "\" s=\"" + style + "\"><f>" + formula + "</f><v>" + cached + "</v></c>"
    }

    private fun formulaDecCell(col: String, row: Int, formula: String, cached: Double, style: Int): String {
        val safe = if (java.lang.Double.isFinite(cached)) cached else 0.0
        val formatted = String.format(Locale.US, "%.4f", safe)
        return "<c r=\"" + col + row + "\" s=\"" + style + "\"><f>" + formula + "</f><v>" + formatted + "</v></c>"
    }

    internal class SharedStringTable {
        private val indexMap = LinkedHashMap<String, Int>()
        private var totalCount = 0

        fun indexOf(text: String): Int {
            totalCount += 1
            val existing = indexMap.get(text)
            if (existing != null) return existing
            val next = indexMap.size
            indexMap.put(text, next)
            return next
        }

        fun toXml(): String {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sb.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"").append(totalCount).append("\" uniqueCount=\"").append(indexMap.size).append("\">")
            for (str in indexMap.keys) {
                val preserve = str.startsWith(" ") || str.endsWith(" ") || str.contains("\n")
                if (preserve) {
                    sb.append("<si><t xml:space=\"preserve\">").append(escapeXml(str)).append("</t></si>")
                } else {
                    sb.append("<si><t>").append(escapeXml(str)).append("</t></si>")
                }
            }
            sb.append("</sst>")
            return sb.toString()
        }

        private fun escapeXml(input: String): String {
            val out = StringBuilder(input.length + 16)
            for (i in 0 until input.length) {
                val ch = input[i]
                if (ch == '&') out.append("&amp;")
                else if (ch == '<') out.append("&lt;")
                else if (ch == '>') out.append("&gt;")
                else if (ch == '"') out.append("&quot;")
                else if (ch == '\'') out.append("&apos;")
                else if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') {
                    out.append(ch)
                }
            }
            return out.toString()
        }
    }
}
