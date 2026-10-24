package com.speedvpn.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedHistoryExcelExportTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    class TestSafContentProvider : ContentProvider() {
        companion object {
            const val AUTHORITY = "com.speedvpn.test.saf"
            @Volatile
            var targetFile: File? = null
        }

        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            return when (uri.lastPathSegment) {
                "success.xlsx" -> {
                    val dest = targetFile ?: throw FileNotFoundException("No target file configured")
                    ParcelFileDescriptor.open(
                        dest,
                        ParcelFileDescriptor.MODE_WRITE_ONLY or
                            ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    )
                }
                "denied.xlsx" -> throw SecurityException("Permission denial: writing to $uri")
                "missing.xlsx" -> throw FileNotFoundException("Missing document target: $uri")
                else -> throw FileNotFoundException("Unknown test URI: $uri")
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = SpeedHistoryExcelExporter.XLSX_MIME_TYPE

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    @Before
    fun setUp() {
        context.deleteDatabase("speedvpn_usage.db")
        SpeedLimiter.endVerification(null, null)
        SpeedLimiter.endSession(1L)
        SpeedPlanUsageTracker.resetForTests("proc-excel-test")
    }

    @After
    fun tearDown() {
        TestSafContentProvider.targetFile = null
        SpeedLimiter.endVerification(null, null)
        SpeedLimiter.endSession(1L)
        SpeedPlanUsageTracker.resetForTests("proc-excel-cleanup")
        context.deleteDatabase("speedvpn_usage.db")
    }

    @Test
    fun exportEmptyDatabase_createsValidXlsxWithBothSheetsNormalStyleAndArabicEmptyMessage() {
        val tempFile = File.createTempFile("speed_history_empty_", ".xlsx")
        try {
            val result = SpeedHistoryExcelExporter.exportToFile(
                file = tempFile,
                records = emptyList(),
                generatedAtMillis = 1_729_640_000_000L,
            )
            assertTrue(result is SpeedHistoryExportResult.Success)
            val success = result as SpeedHistoryExportResult.Success
            assertEquals(0, success.recordCount)
            assertEquals(0L, success.totalBytes)
            assertTrue(tempFile.exists() && tempFile.length() > 500L)

            ZipFile(tempFile).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(entries.contains("[Content_Types].xml"))
                assertTrue(entries.contains("xl/workbook.xml"))
                assertTrue(entries.contains("xl/styles.xml"))
                assertTrue(entries.contains("xl/sharedStrings.xml"))
                assertTrue(entries.contains("xl/worksheets/sheet1.xml"))
                assertTrue(entries.contains("xl/worksheets/sheet2.xml"))

                val workbookXml = zip.getInputStream(zip.getEntry("xl/workbook.xml"))
                    .readBytes().toString(StandardCharsets.UTF_8)
                assertTrue(workbookXml.contains("سجل السرعات"))
                assertTrue(workbookXml.contains("الملخص"))

                val stylesXml = zip.getInputStream(zip.getEntry("xl/styles.xml"))
                    .readBytes().toString(StandardCharsets.UTF_8)
                assertTrue(
                    "styles.xml must define default Normal cellStyle to avoid spreadsheet warnings",
                    stylesXml.contains("""<cellStyle name="Normal" xfId="0" builtinId="0"/>"""),
                )

                val sharedStringsXml = zip.getInputStream(zip.getEntry("xl/sharedStrings.xml"))
                    .readBytes().toString(StandardCharsets.UTF_8)
                assertTrue(sharedStringsXml.contains("لا توجد سجلات محفوظة حتى الآن"))
                assertTrue(sharedStringsXml.contains("قاعدة البيانات فارغة"))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun exportPopulatedRecords_includesAllRowsExactDurationInSecondsLargeValuesOver4GbAndMatchingFormulaTotals() {
        val tempFile = File.createTempFile("speed_history_populated_", ".xlsx")
        try {
            val rec1 = SpeedPlanUsageRecord(
                sessionId = "sess-low-1",
                vpnGeneration = 1L,
                segmentIndex = 1,
                processToken = "proc-1",
                startedAtMillis = 1_729_600_000_000L,
                endedAtMillis = 1_729_600_600_000L,
                lastCheckpointMillis = 1_729_600_600_000L,
                durationMillis = 600_000L, // 600 seconds (10 minutes)
                planOptionCategory = SpeedPlanOptionCategory.LOW,
                planOptionLabel = "منخفض (250 KB/s)",
                downloadLimitKbps = 2_000L,
                uploadLimitKbps = 2_000L,
                downloadLimitUnitLabel = "250 KB/s (2 Mbps)",
                uploadLimitUnitLabel = "250 KB/s (2 Mbps)",
                downloadBytes = 15_000_000L,
                uploadBytes = 5_000_000L,
                totalBytes = 20_000_000L,
                avgDownloadBps = 200_000L,
                avgUploadBps = 66_666L,
                peakDownloadBps = 256_000L,
                peakUploadBps = 96_000L,
                status = SpeedPlanRecordStatus.COMPLETED,
                closeReason = "PLAN_CHANGED",
                limitExceeded = false,
                maxExceedancePct = 0.0,
                exceedanceDurationMillis = 0L,
            )
            val fiveGb = 5L * 1024L * 1024L * 1024L
            val twoGb = 2L * 1024L * 1024L * 1024L
            val rec2 = SpeedPlanUsageRecord(
                sessionId = "sess-unlimited-2",
                vpnGeneration = 1L,
                segmentIndex = 2,
                processToken = "proc-1",
                startedAtMillis = 1_729_600_600_000L,
                endedAtMillis = null,
                lastCheckpointMillis = 1_729_602_400_000L,
                durationMillis = 1_800_000L, // 1,800 seconds (30 minutes)
                planOptionCategory = SpeedPlanOptionCategory.UNLIMITED,
                planOptionLabel = "Unlimited (بدون حد)",
                downloadLimitKbps = null,
                uploadLimitKbps = null,
                downloadLimitUnitLabel = "Unlimited (بدون حد)",
                uploadLimitUnitLabel = "Unlimited (بدون حد)",
                downloadBytes = fiveGb,
                uploadBytes = twoGb,
                totalBytes = fiveGb + twoGb,
                avgDownloadBps = 23_860_929L,
                avgUploadBps = 9_544_371L,
                peakDownloadBps = 96_000_000L,
                peakUploadBps = 40_000_000L,
                status = SpeedPlanRecordStatus.ONGOING,
                closeReason = "ACTIVE",
                limitExceeded = false,
                maxExceedancePct = 0.0,
                exceedanceDurationMillis = 0L,
            )

            val records = listOf(rec1, rec2)
            val expectedDlBytes = 15_000_000L + fiveGb
            val expectedUlBytes = 5_000_000L + twoGb
            val expectedTotalBytes = 20_000_000L + fiveGb + twoGb
            val summary = SpeedHistoryExcelExporter.buildSummary(records)
            assertEquals(2, summary.totalRecordsCount)
            assertEquals(1, summary.distinctVpnSessionsCount)
            assertEquals(1, summary.planChangesCount)
            assertEquals(2_400_000L, summary.totalDurationMillis)

            val streamOut = ByteArrayOutputStream()
            SpeedHistoryExcelExporter.writeWorkbookToStream(streamOut, records, 1_729_640_000_000L)
            assertTrue(streamOut.size() > 1_000)

            val result = SpeedHistoryExcelExporter.exportToFile(
                file = tempFile,
                records = records,
                generatedAtMillis = 1_729_640_000_000L,
            )
            assertTrue(result is SpeedHistoryExportResult.Success)
            val success = result as SpeedHistoryExportResult.Success
            assertEquals(2, success.recordCount)
            assertEquals(expectedTotalBytes, success.totalBytes)

            ZipFile(tempFile).use { zip ->
                val sheet1Xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                    .readBytes().toString(StandardCharsets.UTF_8)
                val sheet2Xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml"))
                    .readBytes().toString(StandardCharsets.UTF_8)
                val sstXml = zip.getInputStream(zip.getEntry("xl/sharedStrings.xml"))
                    .readBytes().toString(StandardCharsets.UTF_8)

                assertTrue(sheet1Xml.contains("""rightToLeft="1""""))
                assertTrue(sheet2Xml.contains("""rightToLeft="1""""))

                // Verify exact durations in seconds (E5 = 600, E6 = 1800, E7 = SUM(E5:E6) cached 2400)
                assertTrue(sheet1Xml.contains("""<c r="E5" s="5"><v>600</v></c>"""))
                assertTrue(sheet1Xml.contains("""<c r="E6" s="5"><v>1800</v></c>"""))
                assertTrue(sheet1Xml.contains("""<c r="E7" s="8"><f>SUM(E5:E6)</f><v>2400</v></c>"""))

                // Verify exact byte values and cached formula totals in Sheet 1
                assertTrue(sheet1Xml.contains("""<c r="K5" s="5"><v>15000000</v></c>"""))
                assertTrue(sheet1Xml.contains("""<c r="K6" s="5"><v>$fiveGb</v></c>"""))
                assertTrue(sheet1Xml.contains("""<c r="K7" s="8"><f>SUM(K5:K6)</f><v>$expectedDlBytes</v></c>"""))
                assertTrue(sheet1Xml.contains("""<c r="N7" s="8"><f>SUM(N5:N6)</f><v>$expectedUlBytes</v></c>"""))
                assertTrue(sheet1Xml.contains("""<c r="Q7" s="8"><f>SUM(Q5:Q6)</f><v>$expectedTotalBytes</v></c>"""))

                // Verify Sheet 2 KPIs and category breakdown formula totals
                assertTrue(sheet2Xml.contains("""<c r="B8" s="8"><v>2400</v></c>"""))
                assertTrue(sheet2Xml.contains("""<c r="D23" s="8"><f>SUM(D18:D22)</f><v>2400</v></c>"""))
                assertTrue(sheet2Xml.contains("""<c r="J23" s="8"><f>SUM(J18:J22)</f><v>$expectedTotalBytes</v></c>"""))

                assertTrue(sstXml.contains("sess-low-1"))
                assertTrue(sstXml.contains("sess-unlimited-2"))
                assertTrue(sstXml.contains("منخفض (250 KB/s)"))
                assertTrue(sstXml.contains("Unlimited (بدون حد)"))
                assertTrue(sstXml.contains("غير متاح (جلسة مستمرة)"))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun countPlanTransitions_accuratelyCountsZeroOneTwoChangesAndReconnectWithSamePlan() {
        fun seg(
            id: String,
            gen: Long,
            idx: Int,
            dl: Long?,
            ul: Long?,
            reason: String,
        ) = SpeedPlanUsageRecord(
            sessionId = id,
            vpnGeneration = gen,
            segmentIndex = idx,
            processToken = "proc-1",
            startedAtMillis = 1_729_600_000_000L + (gen * 100_000L) + (idx * 10_000L),
            endedAtMillis = 1_729_600_005_000L + (gen * 100_000L) + (idx * 10_000L),
            lastCheckpointMillis = 1_729_600_005_000L + (gen * 100_000L) + (idx * 10_000L),
            durationMillis = 5_000L,
            planOptionCategory = SpeedPlanClassifier.classify(dl, ul).category,
            planOptionLabel = SpeedPlanClassifier.classify(dl, ul).optionLabel,
            downloadLimitKbps = dl,
            uploadLimitKbps = ul,
            downloadLimitUnitLabel = SpeedPlanClassifier.formatLimitLabel(dl),
            uploadLimitUnitLabel = SpeedPlanClassifier.formatLimitLabel(ul),
            downloadBytes = 100_000L,
            uploadBytes = 50_000L,
            totalBytes = 150_000L,
            avgDownloadBps = 160_000L,
            avgUploadBps = 80_000L,
            peakDownloadBps = 200_000L,
            peakUploadBps = 100_000L,
            status = SpeedPlanRecordStatus.COMPLETED,
            closeReason = reason,
            limitExceeded = false,
            maxExceedancePct = 0.0,
            exceedanceDurationMillis = 0L,
        )

        // 1. Single plan without change
        val onePlan = listOf(seg("s1", 1L, 1, 2_000L, 2_000L, "VPN_DISCONNECTED"))
        assertEquals(0, SpeedHistoryExcelExporter.buildSummary(onePlan).planChangesCount)

        // 2. One change then disconnect
        val oneChange = listOf(
            seg("s1", 1L, 1, 2_000L, 2_000L, "PLAN_CHANGED"),
            seg("s2", 1L, 2, 8_000L, 8_000L, "VPN_DISCONNECTED"),
        )
        assertEquals(1, SpeedHistoryExcelExporter.buildSummary(oneChange).planChangesCount)

        // 3. Two changes then disconnect
        val twoChanges = listOf(
            seg("s1", 1L, 1, 2_000L, 2_000L, "PLAN_CHANGED"),
            seg("s2", 1L, 2, 8_000L, 8_000L, "PLAN_CHANGED"),
            seg("s3", 1L, 3, null, null, "VPN_DISCONNECTED"),
        )
        assertEquals(2, SpeedHistoryExcelExporter.buildSummary(twoChanges).planChangesCount)

        // 4. Disconnect and reconnect with the same speed plan
        val reconnectSamePlan = listOf(
            seg("s1", 1L, 1, 2_000L, 2_000L, "VPN_DISCONNECTED"),
            seg("s2", 2L, 1, 2_000L, 2_000L, "VPN_DISCONNECTED"),
        )
        val reconnectSummary = SpeedHistoryExcelExporter.buildSummary(reconnectSamePlan)
        assertEquals(2, reconnectSummary.distinctVpnSessionsCount)
        assertEquals(0, reconnectSummary.planChangesCount)
    }

    @Test
    fun exportToUri_viaRegisteredSafContentProvider_flushesActiveSegmentAndHandlesWriteAndPermissionErrors() {
        Robolectric.setupContentProvider(TestSafContentProvider::class.java, TestSafContentProvider.AUTHORITY)

        val gen = 1L
        val t0 = 1_729_650_000_000L
        SpeedLimiter.beginSession(gen)
        SpeedLimiter.setLimitsKbps(8_000L, 8_000L)
        SpeedPlanUsageTracker.onVpnConnected(context, gen, nowMillis = t0)
        SpeedLimiter.download.recordForwardedForApp(250_000, gen, uid = 10150, packages = setOf("org.telegram.messenger"))
        SpeedLimiter.upload.recordForwardedForApp(50_000, gen, uid = 10150, packages = setOf("org.telegram.messenger"))

        val safDestFile = File.createTempFile("saf_export_dest_", ".xlsx")
        TestSafContentProvider.targetFile = safDestFile
        try {
            // 1. Successful SAF export via registered ContentProvider (content://com.speedvpn.test.saf/success.xlsx)
            val successUri = Uri.parse("content://${TestSafContentProvider.AUTHORITY}/success.xlsx")
            val okResult = SpeedHistoryExcelExporter.exportToUri(
                context = context,
                uri = successUri,
                generatedAtMillis = t0 + 10_000L,
            )
            assertTrue("Expected SAF export success, got: $okResult", okResult is SpeedHistoryExportResult.Success)
            val success = okResult as SpeedHistoryExportResult.Success
            assertEquals(1, success.recordCount)
            assertEquals(300_000L, success.totalBytes)
            assertTrue(safDestFile.exists() && safDestFile.length() > 1_000L)

            ZipFile(safDestFile).use { zip ->
                val sheet1Xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                    .readBytes().toString(StandardCharsets.UTF_8)
                assertTrue(sheet1Xml.contains("""<c r="E5" s="5"><v>10</v></c>"""))
                assertTrue(sheet1Xml.contains("<v>250000</v>"))
                assertTrue(sheet1Xml.contains("<v>50000</v>"))
                assertTrue(sheet1Xml.contains("<v>300000</v>"))
            }

            // 2. SAF SecurityException thrown by ContentProvider.openFile
            val deniedUri = Uri.parse("content://${TestSafContentProvider.AUTHORITY}/denied.xlsx")
            val secResult = SpeedHistoryExcelExporter.exportToUri(
                context = context,
                uri = deniedUri,
                generatedAtMillis = t0 + 11_000L,
            )
            assertTrue(secResult is SpeedHistoryExportResult.Failure)
            val secFailure = secResult as SpeedHistoryExportResult.Failure
            assertTrue(secFailure.userMessage.contains("تم رفض صلاحية الكتابة"))

            // 3. SAF FileNotFoundException thrown by ContentProvider.openFile
            val missingUri = Uri.parse("content://${TestSafContentProvider.AUTHORITY}/missing.xlsx")
            val fnfResult = SpeedHistoryExcelExporter.exportToUri(
                context = context,
                uri = missingUri,
                generatedAtMillis = t0 + 12_000L,
            )
            assertTrue(fnfResult is SpeedHistoryExportResult.Failure)
            val fnfFailure = fnfResult as SpeedHistoryExportResult.Failure
            assertTrue(fnfFailure.userMessage.contains("الملف أو المجلد غير متاح"))

            // 4. Null OutputStream returned when opening destination
            val nullResult = SpeedHistoryExcelExporter.exportToUri(
                context = context,
                uri = Uri.parse("content://${TestSafContentProvider.AUTHORITY}/null.xlsx"),
                generatedAtMillis = t0 + 13_000L,
                openDestinationStream = { _, _ -> null },
            )
            assertTrue(nullResult is SpeedHistoryExportResult.Failure)
            assertEquals("NULL_OUTPUT_STREAM", (nullResult as SpeedHistoryExportResult.Failure).technicalMessage)

            // 5. IOException("ENOSPC: No space left on device") thrown during stream write
            val enospcResult = SpeedHistoryExcelExporter.exportToUri(
                context = context,
                uri = Uri.parse("content://${TestSafContentProvider.AUTHORITY}/enospc.xlsx"),
                generatedAtMillis = t0 + 14_000L,
                openDestinationStream = { _, _ ->
                    object : OutputStream() {
                        override fun write(b: Int) {
                            throw IOException("write failed: ENOSPC (No space left on device)")
                        }

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            throw IOException("write failed: ENOSPC (No space left on device)")
                        }
                    }
                },
            )
            assertTrue(enospcResult is SpeedHistoryExportResult.Failure)
            val enospcFailure = enospcResult as SpeedHistoryExportResult.Failure
            assertTrue(enospcFailure.userMessage.contains("عدم توفر مساحة كافية"))
        } finally {
            safDestFile.delete()
        }
    }
}
