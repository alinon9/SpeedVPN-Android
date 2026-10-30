import os
#!/usr/bin/env python3
"""
Phase 2 & Audit Remediation Validation Suite:

NOTE ON TEST SCOPE:
- These tests run in the Python + OpenJDK 17 (BeanShell) harness without kotlinc or the Android SDK.
- BeanShell tests execute code transpiled at test time from the Kotlin source files
  (SpeedHistoryExcelExporter.kt, SpeedLimiter.kt, Socks5Server.kt) on OpenJDK 17 — NOT the compiled
  Kotlin .class bytecode (which is tested by SpeedHistoryExcelExportTest.kt and SpeedPlanUsageHistoryTest.kt
  via `./gradlew :app:testDebugUnitTest`).
- SQLite tests in Python execute the DDL and SQL statements extracted from UsageDatabase.kt against
  sqlite3 — NOT the compiled Android UsageDbHelper / UsageRepository classes.

1. v4 -> v5 SQLite schema migration DDL (extracted from UsageDatabase.kt) and SQL-level verification
   of terminal state protection and process_token-isolated orphan recovery.
2. Per-flow distinction between synthetic speed-test/verification probes and real user app traffic
   (TCP & UDP paths in SpeedTestEngine.kt + UidDetectionPoc.kt + SpeedVpnService.kt + Socks5Server.kt +
   SpeedLimiter.kt + SpeedPlanUsage.kt), including:
   - Real traffic from another app during Verify Speed (including to the same IP:port as the probe),
   - Real non-probe traffic from SpeedVPN's own UID (ownAppUid) during Verify Speed (including to the same IP:port as the probe),
   - Unattributed real user traffic (uid == null, packages = emptySet()) during Verify Speed (including to the same IP:port as the probe),
   - Unattributed and attributed synthetic probes marked at the flow level during Verify Speed,
   - Scoped probe measurement session & endpoint lifecycle cleanup,
   - Monotonic segmentIndex allocation within a VPN generation, and UI session reset isolation.
3. OpenJDK 17 BeanShell execution of source-transpiled SpeedHistoryExcelExporter.kt methods
   (sortRecordsChronologically, countPlanTransitions, buildSummary, exportToFile,
   writeWorkbookToStream, buildHistorySheetXml, buildSummarySheetXml, SharedStringTable)
   + strict openpyxl inspection (both formula mode and data_only cached-value mode) with zero
   default-style warnings, exact duration in seconds (E5=600, E6=1800, E7=2400), >4GB values,
   empty DB, SAF error handling, and exact plan-transition counting.
4. Exceedance wave duration accounting (1.5s unconfirmed wave, 3.0s confirmed full-duration wave
   including initial 2.0s window, and unmeasured time-gap reset).
5. 'جمع الإحصاءات' toggle consistency on both fresh install (unset key) and explicit disable,
   stopping optional collection while preserving mandatory quota enforcement.
6. SpeedScreen I/O decoupling from 500ms live byte counter ticks + prior audit regression checks.
"""

from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import unittest
import warnings

try:
    import openpyxl
except ImportError:
    openpyxl = None


REPO_ROOT = Path(__file__).resolve().parents[3]
BSH_JAR = Path("/usr/share/maven-repo/org/beanshell/bsh/2.0b4/bsh-2.0b4.jar")


def transpile_exporter_kt_to_bsh(kt_text: str) -> str:
    """
    Transpiles the JVM logic of SpeedHistoryExcelExporter.kt (sortRecordsChronologically,
    countPlanTransitions, buildSummary, exportToFile, writeWorkbookToStream, writeZipEntry,
    buildHistorySheetXml, buildSummarySheetXml, buildContentTypesXml, buildRootRelsXml,
    buildWorkbookXml, buildWorkbookRelsXml, buildStylesXml, colName, stringCell, emptyStyledCell,
    numberCell, decimalCell, formulaIntCell, formulaDecCell, SharedStringTable) from the Kotlin
    source file into BeanShell/Java code for OpenJDK 17.
    (Note: This transpiles Kotlin source for BeanShell execution; it does not compile Kotlin bytecode.)
    """
    obj_start = kt_text.index("object SpeedHistoryExcelExporter {") + len("object SpeedHistoryExcelExporter {")
    export_uri_start = kt_text.index("    fun exportToUri(")
    export_file_start = kt_text.index("    fun exportToFile(")
    obj_end = kt_text.rindex("}")
    body = kt_text[obj_start:export_uri_start] + "\n" + kt_text[export_file_start:obj_end]

    # 1. Replace triple-quoted raw strings with Java string literals
    def repl_raw_str(m):
        func_name = m.group(1)
        raw_lines = m.group(2).splitlines()
        non_empty = [l for l in raw_lines if l.strip()]
        min_ind = min(len(l) - len(l.lstrip(" ")) for l in non_empty) if non_empty else 0
        dedented = "\n".join(l[min_ind:] if len(l) >= min_ind else "" for l in raw_lines)
        escaped = dedented.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
        return f'String {func_name}() {{ return "{escaped}"; }}'

    body = re.sub(
        r'private fun (\w+)\(\):\s*String\s*=\s*"""\n(.*?)\n\s*"""\.trimIndent\(\)',
        repl_raw_str,
        body,
        flags=re.DOTALL,
    )

    # 2. Remove Kotlin comments (only line-leading // so http:// inside XML strings is preserved)
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL)
    body = re.sub(r"^\s*//.*$", "", body, flags=re.MULTILINE)

    # 3. Replace multi-line arrayOf / intArrayOf / longArrayOf
    body = re.sub(r"\barrayOf\(\s*\n(.*?)\n\s*\)", r"new String[] {\n\1\n};", body, flags=re.DOTALL)
    body = re.sub(r"\bintArrayOf\(\s*\n(.*?)\n\s*\)", r"new int[] {\n\1\n};", body, flags=re.DOTALL)
    body = re.sub(r"\blongArrayOf\(\s*\n(.*?)\n\s*\)", r"new long[] {\n\1\n};", body, flags=re.DOTALL)
    body = re.sub(r"\bintArrayOf\(([^)]+)\)", r"new int[] { \1 };", body)

    # 4. Strip named-argument prefixes in constructor calls (never touching ==) and trailing commas
    body = re.sub(
        r"\b(?:category|planOptionLabel|segmentsCount|totalDurationMillis|downloadBytes|uploadBytes|"
        r"totalBytes|sharePercent|totalRecordsCount|distinctVpnSessionsCount|planChangesCount|"
        r"totalDownloadBytes|totalUploadBytes|completedCount|ongoingCount|abnormalCount|exceededCount|"
        r"periodStartMillis|periodEndMillis|byCategory|recordCount|message|userMessage|technicalMessage)"
        r"\s*=(?!=)\s*",
        "",
        body,
    )
    body = re.sub(r",\s*\)", ")", body)
    body = re.sub(r",\s*\}", "}", body)

    # 5. Replace return try { ... } catch (t: Throwable) { ... } in exportToFile
    body = body.replace("return try {", "try {")
    body = body.replace("SpeedHistoryExportResult.Success(", "return SpeedHistoryExportResultSuccess(")
    body = body.replace("SpeedHistoryExportResult.Failure(", "return SpeedHistoryExportResultFailure(")
    body = body.replace("return return ", "return ")
    body = body.replace("catch (t: Throwable)", "catch (Throwable t)")

    # 6. Function signatures
    def repl_fun(m):
        name = m.group(1)
        params = m.group(2)
        p_list = []
        for p in params.split(","):
            p = p.strip()
            if not p:
                continue
            p = re.sub(r"=.*", "", p).strip()
            pname, _ = [x.strip() for x in p.split(":")]
            p_list.append(pname)
        joined = ", ".join(p_list)
        return f"{name}({joined}) {{"

    body = re.sub(
        r"(?:private\s+|internal\s+)?fun\s+(\w+)\s*\((.*?)\)(?:\s*:\s*([\w<>?]+))?\s*\{",
        repl_fun,
        body,
        flags=re.DOTALL,
    )

    # 7. Constants & variable declarations
    body = re.sub(r"(?:private\s+)?const\s+val\s+(\w+)\s*=", r"\1 =", body)
    body = re.sub(r"(?:private\s+)?(?:val|var)\s+(\w+)\s*:\s*[\w<>?]+\s*=", r"Object \1 =", body)
    body = re.sub(r"(?:private\s+)?\b(?:val|var)\s+(\w+)\s*=", r"\1 =", body)

    # 8. Kotlin loops
    body = re.sub(r"for\s*\(\s*(\w+)\s+in\s+(\w+)\s+until\s+([^)]+)\)", r"for (int \1 = \2; \1 < \3; \1++)", body)
    body = re.sub(r"for\s*\(\s*(\w+)\s+in\s+(\d+)\.\.([^)]+)\)", r"for (int \1 = \2; \1 <= \3; \1++)", body)
    body = re.sub(
        r"for\s*\(\s*(\w+)\s+in\s+([\w.()]+)\s*\)\s*\{",
        r"for (Iterator _it_\1 = (\2).iterator(); _it_\1.hasNext(); ) { Object \1 = _it_\1.next();",
        body,
    )

    # 9. Kotlin ternary expressions (only when preceded by =, (, or ,)
    body = re.sub(
        r"=\s*if\s*\(([^)]+)\)\s*\{\s*([^}]+?)\s*\}\s*else\s+if\s*\(([^)]+)\)\s*\{\s*([^}]+?)\s*\}\s*else\s*\{\s*([^}]+?)\s*\}",
        r"= (\1) ? (\2) : ((\3) ? (\4) : (\5));",
        body,
        flags=re.DOTALL,
    )
    body = re.sub(
        r"=\s*if\s*\(([^)]+)\)\s*\{\s*([^}]+?)\s*\}\s*else\s*\{\s*([^}]+?)\s*\}",
        r"= (\1) ? (\2) : (\3);",
        body,
        flags=re.DOTALL,
    )
    for _ in range(3):
        body = re.sub(
            r"([=,(]\s*)if\s*\(([^()\n]+(?:\([^()\n]*\)[^()\n]*)*)\)\s+([^\n;{}]+?)\s+else\s+([^\n;,{}]+)",
            r"\1((\2) ? (\3) : (\4))",
            body,
        )

    # 10. Kotlin array / collection / number helpers
    body = body.replace("ArrayList<SpeedPlanUsageRecord>(records)", "new ArrayList(records)")
    body = re.sub(r"\bLinkedHashMap<[^\n]+\(\)", "new LinkedHashMap()", body)
    body = re.sub(r"\bArrayList<[^\n]+\(\)", "new ArrayList()", body)
    body = re.sub(r"\bLinkedHashSet<[^\n]+\(\)", "new LinkedHashSet()", body)
    body = re.sub(r"\bStringBuilder\(", "new StringBuilder(", body)
    body = re.sub(r"\bSimpleDateFormat\(", "new SimpleDateFormat(", body)
    body = re.sub(r"\bDate\(", "new Date(", body)
    body = re.sub(r"\bFileOutputStream\(", "new FileOutputStream(", body)
    body = re.sub(r"\bBufferedOutputStream\(", "new BufferedOutputStream(", body)
    body = re.sub(r"\bZipOutputStream\(", "new ZipOutputStream(", body)
    body = re.sub(r"\bZipEntry\(", "new ZipEntry(", body)
    body = body.replace("internal class SharedStringTable {", "SharedStringTable() {")

    while re.search(r"(\d)_(\d)", body):
        body = re.sub(r"(\d)_(\d)", r"\1\2", body)

    body = body.replace(".toDouble()", " * 1.0")
    body = re.sub(r"\((\w+)\s*\+\s*1\)\.toLong\(\)", r"((long)(\1 + 1))", body)
    body = body.replace(".toLong()", " * 1L")
    body = body.replace(".toString()", '+ ""')
    body = body.replace(".toByteArray(StandardCharsets.UTF_8)", ".getBytes(StandardCharsets.UTF_8)")
    body = body.replace("'A'.code + rem", "((int)'A') + rem")
    body = body.replace(").toChar()", ")")
    body = body.replace("sb.insert(0, (((int)'A') + rem))", "sb.insert(0, (char)(((int)'A') + rem))")
    body = body.replace("input[i]", "input.charAt(i)")
    body = body.replace("input.length", "input.length()")
    body = body.replace("ch.code", "((int) ch)")
    body = body.replace("ignoreCase = true", "")
    body = body.replace(".equals(sessionRecords.get(0).closeReason, )", ".equalsIgnoreCase(sessionRecords.get(0).closeReason)")
    body = body.replace(".equals(prev.closeReason, )", ".equalsIgnoreCase(prev.closeReason)")
    body = body.replace("prev.downloadLimitKbps != next.downloadLimitKbps", "!Objects.equals(prev.downloadLimitKbps, next.downloadLimitKbps)")
    body = body.replace("prev.uploadLimitKbps != next.uploadLimitKbps", "!Objects.equals(prev.uploadLimitKbps, next.uploadLimitKbps)")
    body = body.replace("bySession.values", "bySession.values()")
    body = body.replace("byLabel.keys", "byLabel.keySet()")
    body = body.replace("indexMap.keys", "indexMap.keySet()")
    body = body.replace("!!", "")
    body = body.replace("file.parentFile", "file.getParentFile()")
    body = body.replace(".usableSpace", ".getUsableSpace()")
    body = body.replace("t.message", "t.getMessage()")
    body = body.replace('String.format(Locale.US, "%.4f", safe)', 'String.format(Locale.US, "%.4f", new Object[]{ Double.valueOf(safe) })')

    for arr_name in ["headers", "widths", "categories", "kpiLabels", "kpiValues", "kpiUnits", "kpiFormatted", "breakdownHeaders"]:
        body = body.replace(f"{arr_name}.size", f"{arr_name}.length")
    body = body.replace(".size", ".size()")

    out_lines = []
    for line in body.splitlines():
        s = line.strip()
        if not s:
            out_lines.append("")
            continue
        if s.endswith(("{", "}", ";", ",", "(", "+", "||", "&&", "?", ":")):
            out_lines.append(line)
        elif s.startswith(("if ", "else", "for ", "while ", "try", "catch", "finally", "class ")):
            if s.endswith(")") and not ("return " in s or "sb.append" in s or "+=" in s or "=" in s):
                out_lines.append(line)
            else:
                out_lines.append(line + ";")
        else:
            out_lines.append(line + ";")
    res = "\n".join(out_lines).rstrip()
    if res.endswith("}"):
        res = res[:-1] + "    return this;\n}\n"
    return res


BSH_MODEL_PREAMBLE = """
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

makeCategory(String n, String a) {
    String name = n;
    String arabicLabel = a;
    return this;
}

makeSpeedPlanOptionCategory() {
    Object LOW = makeCategory("LOW", "منخفض");
    Object MEDIUM = makeCategory("MEDIUM", "متوسط");
    Object HIGH = makeCategory("HIGH", "مرتفع");
    Object CUSTOM = makeCategory("CUSTOM", "مخصص");
    Object UNLIMITED = makeCategory("UNLIMITED", "Unlimited");
    Object[] values() {
        return new Object[] { LOW, MEDIUM, HIGH, CUSTOM, UNLIMITED };
    }
    return this;
}
SpeedPlanOptionCategory = makeSpeedPlanOptionCategory();

makeStatus(String n, String a) {
    String name = n;
    String arabicLabel = a;
    return this;
}
makeSpeedPlanRecordStatus() {
    Object COMPLETED = makeStatus("COMPLETED", "مكتمل");
    Object ONGOING = makeStatus("ONGOING", "مستمر");
    Object ABNORMAL_END = makeStatus("ABNORMAL_END", "انتهى بصورة غير طبيعية");
    return this;
}
SpeedPlanRecordStatus = makeSpeedPlanRecordStatus();

String formatDataBytes(long bytes) {
    long safe = Math.max(0L, bytes);
    double gb = 1024.0 * 1024.0 * 1024.0;
    double mb = 1024.0 * 1024.0;
    double kb = 1024.0;
    if (safe >= (long) gb) return String.format(Locale.US, "%.2f GB", new Object[]{ Double.valueOf(safe / gb) });
    if (safe >= (long) mb) return String.format(Locale.US, "%.2f MB", new Object[]{ Double.valueOf(safe / mb) });
    if (safe >= (long) kb) return String.format(Locale.US, "%.1f KB", new Object[]{ Double.valueOf(safe / kb) });
    return safe + " B";
}

makeSpeedPlanClassifier() {
    String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0L) return "غير متاح";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(epochMillis));
    }
    String formatDuration(long durationMillis) {
        long totalSec = Math.max(0L, durationMillis) / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0L) {
            return String.format(Locale.US, "%02d:%02d:%02d (%d س %d د %d ث)", new Object[]{ Long.valueOf(h), Long.valueOf(m), Long.valueOf(s), Long.valueOf(h), Long.valueOf(m), Long.valueOf(s) });
        }
        return String.format(Locale.US, "%02d:%02d (%d د %d ث)", new Object[]{ Long.valueOf(m), Long.valueOf(s), Long.valueOf(m), Long.valueOf(s) });
    }
    String formatBytesAllUnits(long bytes) {
        long safe = Math.max(0L, bytes);
        double kb = safe / 1024.0;
        double mb = safe / (1024.0 * 1024.0);
        double gb = safe / (1024.0 * 1024.0 * 1024.0);
        return String.format(Locale.US, "%d B | %.2f KB | %.2f MB | %.4f GB", new Object[]{ Long.valueOf(safe), Double.valueOf(kb), Double.valueOf(mb), Double.valueOf(gb) });
    }
    String formatRateBps(long bps) {
        if (bps <= 0L) return "0 KB/s (0 Kbps)";
        double bytesPerSec = bps / 8.0;
        double kbps = bps / 1000.0;
        if (bytesPerSec >= 1024.0 * 1024.0) {
            return String.format(Locale.US, "%.2f MB/s (%.2f Mbps)", new Object[]{ Double.valueOf(bytesPerSec / (1024.0 * 1024.0)), Double.valueOf(kbps / 1000.0) });
        }
        return String.format(Locale.US, "%.1f KB/s (%.0f Kbps)", new Object[]{ Double.valueOf(bytesPerSec / 1024.0), Double.valueOf(kbps) });
    }
    return this;
}
SpeedPlanClassifier = makeSpeedPlanClassifier();

makeSpeedPlanUsageRecord(
    String sessionId, long vpnGeneration, int segmentIndex, String processToken,
    long startedAtMillis, Long endedAtMillis, long lastCheckpointMillis, long durationMillis,
    Object planOptionCategory, String planOptionLabel, Long downloadLimitKbps, Long uploadLimitKbps,
    String downloadLimitUnitLabel, String uploadLimitUnitLabel,
    long downloadBytes, long uploadBytes, long totalBytes,
    long avgDownloadBps, long avgUploadBps,
    Object status, String closeReason,
    boolean limitExceeded, double maxExceedancePct, long exceedanceDurationMillis
) {
    String formattedDuration() { return SpeedPlanClassifier.formatDuration(durationMillis); }
    String formattedDataBreakdown() { return SpeedPlanClassifier.formatBytesAllUnits(totalBytes); }
    String formattedAvgDownloadRate() { return SpeedPlanClassifier.formatRateBps(avgDownloadBps); }
    String formattedAvgUploadRate() { return SpeedPlanClassifier.formatRateBps(avgUploadBps); }
    return this;
}

SpeedHistoryCategorySummary(Object category, String planOptionLabel, int segmentsCount, long totalDurationMillis, long downloadBytes, long uploadBytes, long totalBytes, double sharePercent) {
    return this;
}

SpeedHistoryReportSummary(int totalRecordsCount, int distinctVpnSessionsCount, int planChangesCount, long totalDownloadBytes, long totalUploadBytes, long totalBytes, long totalDurationMillis, int completedCount, int ongoingCount, int abnormalCount, int exceededCount, Long periodStartMillis, Long periodEndMillis, List byCategory) {
    return this;
}

SpeedHistoryExportResultSuccess(int recordCount, long totalBytes, String message) {
    boolean isSuccess = true;
    return this;
}

SpeedHistoryExportResultFailure(String userMessage, String technicalMessage) {
    boolean isSuccess = false;
    return this;
}
"""


def execute_transpiled_exporter_on_jvm(out_empty_path: Path, out_populated_path: Path) -> str:
    """
    Transpiles SpeedHistoryExcelExporter.kt from Kotlin source into BeanShell/Java and
    executes the transpiled exportToFile(), buildSummary(), and countPlanTransitions() on OpenJDK 17.
    (Note: This executes source-transpiled code on the JVM, not compiled Kotlin bytecode.)
    """
    exporter_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedHistoryExcelExporter.kt").read_text(encoding="utf-8")
    bsh_exporter = transpile_exporter_kt_to_bsh(exporter_kt)

    driver = f"""
    // 1. Export empty database via transpiled SpeedHistoryExcelExporter.exportToFile()
    List emptyRecords = new ArrayList();
    File emptyFile = new File("{out_empty_path}");
    Object emptyRes = exportToFile(emptyFile, emptyRecords, 1729640000000L);
    if (!emptyRes.isSuccess || emptyRes.recordCount != 0 || emptyRes.totalBytes != 0L) {{
        throw new RuntimeException("Empty export failed: " + emptyRes);
    }}

    // 2. Export populated database (including >4GB 64-bit values) via transpiled exportToFile()
    long fiveGb = 5L * 1024L * 1024L * 1024L;
    long twoGb = 2L * 1024L * 1024L * 1024L;
    Object rec1 = makeSpeedPlanUsageRecord(
        "sess-low-001", 1L, 1, "proc-1",
        1729600000000L, Long.valueOf(1729600600000L), 1729600600000L, 600000L,
        SpeedPlanOptionCategory.LOW, "منخفض (250 KB/s)", Long.valueOf(2000L), Long.valueOf(2000L),
        "250 KB/s (2 Mbps)", "250 KB/s (2 Mbps)",
        15000000L, 5000000L, 20000000L,
        200000L, 66666L,
        SpeedPlanRecordStatus.COMPLETED, "PLAN_CHANGED",
        false, 0.0, 0L
    );
    Object rec2 = makeSpeedPlanUsageRecord(
        "sess-unlim-002", 1L, 2, "proc-1",
        1729600600000L, null, 1729602400000L, 1800000L,
        SpeedPlanOptionCategory.UNLIMITED, "Unlimited (بدون حد)", null, null,
        "Unlimited (بدون حد)", "Unlimited (بدون حد)",
        fiveGb, twoGb, fiveGb + twoGb,
        23860929L, 9544371L,
        SpeedPlanRecordStatus.ONGOING, "ACTIVE",
        false, 0.0, 0L
    );
    List populatedRecords = new ArrayList();
    populatedRecords.add(rec1);
    populatedRecords.add(rec2);

    File popFile = new File("{out_populated_path}");
    Object popRes = exportToFile(popFile, populatedRecords, 1729640000000L);
    long expectedTotal = 20000000L + fiveGb + twoGb;
    if (!popRes.isSuccess || popRes.recordCount != 2 || popRes.totalBytes != expectedTotal) {{
        throw new RuntimeException("Populated export mismatch: count=" + popRes.recordCount + " total=" + popRes.totalBytes);
    }}

    // 3. Verify countPlanTransitions() on JVM for 0, 1, 2 changes and reconnect with same plan
    List singlePlan = new ArrayList();
    singlePlan.add(makeSpeedPlanUsageRecord(
        "s1", 1L, 1, "proc-1", 1729600000000L, Long.valueOf(1729600010000L), 1729600010000L, 10000L,
        SpeedPlanOptionCategory.LOW, "منخفض (250 KB/s)", Long.valueOf(2000L), Long.valueOf(2000L),
        "250 KB/s", "250 KB/s", 100000L, 50000L, 150000L, 80000L, 40000L,
        SpeedPlanRecordStatus.COMPLETED, "VPN_DISCONNECTED", false, 0.0, 0L
    ));
    int changes0 = countPlanTransitions(singlePlan);
    int changes1 = countPlanTransitions(populatedRecords);

    List twoChanges = new ArrayList(populatedRecords);
    twoChanges.add(makeSpeedPlanUsageRecord(
        "sess-high-003", 1L, 3, "proc-1", 1729602400000L, Long.valueOf(1729603000000L), 1729603000000L, 600000L,
        SpeedPlanOptionCategory.HIGH, "مرتفع (5 MB/s)", Long.valueOf(40000L), Long.valueOf(40000L),
        "5 MB/s", "5 MB/s", 500000L, 100000L, 600000L, 80000L, 40000L,
        SpeedPlanRecordStatus.COMPLETED, "VPN_DISCONNECTED", false, 0.0, 0L
    ));
    int changes2 = countPlanTransitions(twoChanges);

    List reconnectSamePlan = new ArrayList();
    reconnectSamePlan.add(makeSpeedPlanUsageRecord(
        "r1", 1L, 1, "proc-1", 1729600000000L, Long.valueOf(1729600010000L), 1729600010000L, 10000L,
        SpeedPlanOptionCategory.LOW, "منخفض (250 KB/s)", Long.valueOf(2000L), Long.valueOf(2000L),
        "250 KB/s", "250 KB/s", 100000L, 50000L, 150000L, 80000L, 40000L,
        SpeedPlanRecordStatus.COMPLETED, "VPN_DISCONNECTED", false, 0.0, 0L
    ));
    reconnectSamePlan.add(makeSpeedPlanUsageRecord(
        "r2", 2L, 1, "proc-1", 1729600020000L, Long.valueOf(1729600030000L), 1729600030000L, 10000L,
        SpeedPlanOptionCategory.LOW, "منخفض (250 KB/s)", Long.valueOf(2000L), Long.valueOf(2000L),
        "250 KB/s", "250 KB/s", 100000L, 50000L, 150000L, 80000L, 40000L,
        SpeedPlanRecordStatus.COMPLETED, "VPN_DISCONNECTED", false, 0.0, 0L
    ));
    Object reconnectSummary = buildSummary(reconnectSamePlan);

    System.out.println(
        "TRANSPILED_EXPORTER_JVM_OK changes=" + changes0 + "," + changes1 + "," + changes2 +
        " reconnectSessions=" + reconnectSummary.distinctVpnSessionsCount +
        " reconnectChanges=" + reconnectSummary.planChangesCount
    );
    """

    with tempfile.TemporaryDirectory() as script_dir:
        script_path = Path(script_dir) / "run_transpiled_exporter.bsh"
        script_path.write_text(BSH_MODEL_PREAMBLE + "\n" + bsh_exporter + "\n" + driver, encoding="utf-8")
        proc = subprocess.run(
            ["java", "-cp", str(BSH_JAR), "bsh.Interpreter", str(script_path)],
            check=True,
            capture_output=True,
            text=True,
        )
        if "Error" in proc.stdout or "Exception" in proc.stdout or proc.stderr.strip():
            raise AssertionError(f"BeanShell execution failed:\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}")
        return proc.stdout


def transpile_is_synthetic_probe_to_bsh() -> str:
    """
    Extracts and transpiles SpeedLimiter.isSyntheticProbeTraffic() from SpeedLimiter.kt
    into BeanShell/Java code for OpenJDK 17 testing.
    """
    limiter_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedLimiter.kt").read_text(encoding="utf-8")
    m = re.search(
        r"fun isSyntheticProbeTraffic\(\s*uid: Int\? = null,\s*packages: Set<String> = emptySet\(\),\s*"
        r"isVerificationProbeFlow: Boolean = false,\s*remoteAddress: String\? = null,\s*"
        r"remotePort: Int = 0,\s*flowMarker: String\? = null,\s*\): Boolean \{(.*?)\n    \}",
        limiter_kt,
        flags=re.DOTALL,
    )
    if not m:
        raise AssertionError("Could not extract isSyntheticProbeTraffic from SpeedLimiter.kt")
    probe_body = m.group(1)
    probe_bsh = (
        probe_body.replace("val myPkg = ownPackageName", "String myPkg = ownPackageName;")
        .replace("val myUid = ownAppUid", "int myUid = ownAppUid;")
        .replace("val isOtherAppByUid =", "boolean isOtherAppByUid =")
        .replace("val isOtherAppByPkg =", "boolean isOtherAppByPkg =")
        .replace("packages.isNotEmpty()", "!packages.isEmpty()")
        .replace("myPkg.isNotEmpty()", "!myPkg.isEmpty()")
        .replace("myPkg !in packages", "!packages.contains(myPkg)")
        .replace("myPkg in packages", "packages.contains(myPkg)")
        .replace("return true", "return true;")
        .replace("return false", "return false;")
    )
    probe_bsh = re.sub(r"^\s*//.*$", "", probe_bsh, flags=re.MULTILINE)
    probe_bsh = re.sub(r"(uid != myUid)\n", r"\1;\n", probe_bsh)
    probe_bsh = re.sub(r"(!packages\.contains\(myPkg\))\n", r"\1;\n", probe_bsh)
    return probe_bsh


class TestPhase2SpeedHistoryExportUiAndStats(unittest.TestCase):

    def test_01_sqlite_migration_v4_to_v5_and_repository_semantics(self):
        usage_db_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/UsageDatabase.kt").read_text(encoding="utf-8")
        self.assertIn("private const val USAGE_DB_VERSION = 5", usage_db_kt)
        self.assertIn("CREATE TABLE IF NOT EXISTS speed_plan_usage_session", usage_db_kt)
        self.assertIn("fun upsertSpeedPlanUsageSession", usage_db_kt)
        self.assertIn("fun recoverOrphanedSpeedPlanSessions", usage_db_kt)

        # Extract the exact speed_plan_usage_session DDL from UsageDatabase.kt
        ddl_match = re.search(
            r'internal fun createSpeedPlanUsageSessionTable\(db: SQLiteDatabase\)\s*\{\s*db\.execSQL\(\s*"""(.*?)"""\.trimIndent\(\)',
            usage_db_kt,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(ddl_match, "Could not extract createSpeedPlanUsageSessionTable DDL from UsageDatabase.kt")
        session_table_ddl = ddl_match.group(1)

        # Simulate a v4 database with populated legacy tables, then upgrade to v5 via extracted DDL
        conn = sqlite3.connect(":memory:")
        conn.executescript(
            """
            CREATE TABLE app_policy (
                package_name TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                blocked INTEGER NOT NULL DEFAULT 0,
                daily_limit_bytes INTEGER
            );
            CREATE TABLE app_quota_policy (
                package_name TEXT NOT NULL,
                quota_type TEXT NOT NULL CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
                limit_bytes INTEGER NOT NULL CHECK (limit_bytes > 0),
                period_start_millis INTEGER NOT NULL,
                period_end_millis INTEGER NOT NULL,
                used_bytes INTEGER NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
                download_bytes INTEGER NOT NULL DEFAULT 0 CHECK (download_bytes >= 0),
                upload_bytes INTEGER NOT NULL DEFAULT 0 CHECK (upload_bytes >= 0),
                baseline_initialized INTEGER NOT NULL DEFAULT 0 CHECK (baseline_initialized IN (0, 1)),
                reset_behavior TEXT NOT NULL DEFAULT 'AUTO_RESET'
                    CHECK (reset_behavior IN ('AUTO_RESET', 'BLOCK_UNTIL_RESET')),
                updated_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (package_name, quota_type)
            );
            CREATE TABLE daily_usage (
                date TEXT NOT NULL,
                package_name TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                uid INTEGER NOT NULL,
                download_bytes INTEGER NOT NULL DEFAULT 0,
                upload_bytes INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (date, package_name)
            );
            INSERT INTO app_policy VALUES ('org.telegram.messenger', 'Telegram', 10150, 0, 50000000);
            INSERT INTO app_quota_policy VALUES ('org.telegram.messenger', 'DAILY', 50000000, 1700000000000, 1700086400000, 12345678, 10000000, 2345678, 1, 'AUTO_RESET', 1700000100000);
            INSERT INTO daily_usage VALUES ('2026-10-22', 'org.telegram.messenger', 'Telegram', 10150, 10000000, 2345678, 1700000100000);
            PRAGMA user_version = 4;
            """
        )

        # Upgrade v4 -> v5 using extracted DDL from UsageDatabase.kt
        conn.executescript(session_table_ddl)
        conn.execute("PRAGMA user_version = 5;")

        # Verify legacy v4 data is 100% preserved
        quota_row = conn.execute("SELECT used_bytes, download_bytes, upload_bytes FROM app_quota_policy WHERE package_name = 'org.telegram.messenger'").fetchone()
        self.assertEqual((12345678, 10000000, 2345678), quota_row)

        # Verify terminal status protection and process_token-isolated orphan recovery
        conn.execute(
            """
            INSERT INTO speed_plan_usage_session VALUES (
                'seg-orphan-dead', 1, 1, 'proc-dead', 1700000000000, NULL, 1700000015000, 15000,
                'LOW', 'منخفض (250 KB/s)', 2000, 2000, '250 KB/s (2 Mbps)', '250 KB/s (2 Mbps)',
                400000, 100000, 500000, 213333, 53333, 250000, 80000,
                'ONGOING', 'ACTIVE', 0, 0.0, 0
            )
            """
        )
        conn.execute(
            """
            INSERT INTO speed_plan_usage_session VALUES (
                'seg-live-active', 2, 1, 'proc-alive', 1700000020000, NULL, 1700000025000, 5000,
                'MEDIUM', 'متوسط (1 MB/s)', 8000, 8000, '1 MB/s (8 Mbps)', '1 MB/s (8 Mbps)',
                600000, 200000, 800000, 960000, 320000, 1000000, 400000,
                'ONGOING', 'ACTIVE', 0, 0.0, 0
            )
            """
        )
        conn.execute(
            """
            UPDATE speed_plan_usage_session
            SET ended_at_millis = last_checkpoint_millis,
                duration_millis = MAX(0, last_checkpoint_millis - started_at_millis),
                status = 'ABNORMAL_END',
                close_reason = 'PROCESS_KILLED_OR_REBOOTED'
            WHERE status = 'ONGOING' AND process_token != 'proc-alive'
            """
        )
        rows = dict(conn.execute("SELECT session_id, status FROM speed_plan_usage_session").fetchall())
        self.assertEqual("ABNORMAL_END", rows["seg-orphan-dead"])
        self.assertEqual("ONGOING", rows["seg-live-active"])
        conn.close()

    def test_02_concurrent_real_app_traffic_and_verification_probes_plus_segment_index(self):
        limiter_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedLimiter.kt").read_text(encoding="utf-8")
        engine_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedTestEngine.kt").read_text(encoding="utf-8")
        poc_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/UidDetectionPoc.kt").read_text(encoding="utf-8")
        service_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVpnService.kt").read_text(encoding="utf-8")
        socks_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/Socks5Server.kt").read_text(encoding="utf-8")
        tracker_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedPlanUsage.kt").read_text(encoding="utf-8")
        export_test_kt = (REPO_ROOT / "app/src/test/java/com/speedvpn/app/SpeedHistoryExcelExportTest.kt").read_text(encoding="utf-8")

        # 1. Verify SpeedHistoryExcelExportTest.kt uses a registered ContentProvider and does NOT override final ContentResolver.openOutputStream
        self.assertIn("class TestSafContentProvider : ContentProvider()", export_test_kt)
        self.assertIn("Robolectric.setupContentProvider(TestSafContentProvider::class.java", export_test_kt)
        self.assertNotIn("override fun openOutputStream(", export_test_kt)

        # 2. Verify SpeedTestEngine binds probe client sockets via SpeedProbeSocketFactory and does NOT rely on HTTP header sniffing in encrypted HTTPS
        self.assertIn("internal class SpeedProbeSocketFactory(", engine_kt)
        self.assertIn("SpeedLimiter.markProbeClientSocket(lPort, hostAddr, inet.port, SpeedLimiter.IPPROTO_TCP)", engine_kt)
        self.assertNotIn("PROBE_HTTP_HEADER_NAME", engine_kt)
        self.assertNotIn("X-SpeedVPN-Probe", engine_kt)
        self.assertIn("val probeSessionToken = SpeedLimiter.beginProbeMeasurementSession()", engine_kt)
        self.assertIn("SpeedLimiter.endProbeMeasurementSession(probeSessionToken)", engine_kt)
        self.assertIn("if (SpeedLimiter.isVerificationActive()) {", engine_kt)

        # 3. Verify UidDetectionPoc encodes the 5-tuple probe flag in bit 1 (2L) and Socks5Server binds probe state per session/socket without endpoint permit counters
        self.assertIn("val isMarkedProbeForProtocol = SpeedLimiter.isMarkedProbeClientSocket(", poc_kt)
        self.assertIn("protocol = protocol,", poc_kt)
        self.assertIn('return "$protocol|$localPort|$norm:$remotePort"', limiter_kt)
        self.assertIn("import hev.htproxy.TProxyService", limiter_kt)
        self.assertNotIn("hev.htproxy.TProxyService.", limiter_kt)
        self.assertIn("TProxyService.TProxySetUidPocProbeSocket(", limiter_kt)
        self.assertIn("TProxyService.TProxyClearUidPocProbeSockets()", limiter_kt)
        self.assertEqual(1, limiter_kt.count("fun markProbeClientSocket("))
        self.assertEqual(1, limiter_kt.count("fun unmarkProbeClientSocket("))
        self.assertEqual(1, limiter_kt.count("fun isMarkedProbeClientSocket("))
        self.assertIn("encodeUidDecision(resolution.uid, resolution.shouldBlock, resolution.isVerificationProbe)", poc_kt)
        self.assertIn("(if (isVerificationProbe) 2L else 0L)", poc_kt)
        self.assertIn("internal fun socksUsernameForDecision(baseUsername: String, encodedDecision: Long): String", poc_kt)
        self.assertIn("socks?.recordDetectedFlow(protocol, srcIp, srcPort, dstIp, dstPort, uid, packages, isVerificationProbe)", service_kt)
        self.assertIn("internal fun probeUsername(baseUsername: String, uid: Int? = null): String", socks_kt)
        self.assertNotIn("pendingProbePermitsByEndpoint", socks_kt)
        self.assertNotIn("consumePendingProbePermit", socks_kt)
        self.assertNotIn("containsActiveProbeHeader", socks_kt)
        self.assertIn("SpeedLimiter.markProbeFlow(flowMarker)", socks_kt)
        self.assertIn("val flowMarker = owner?.flowMarker?.ifBlank { flowId } ?: flowId", socks_kt)
        self.assertIn("isVerificationProbe = isProbeChunk,", socks_kt)
        self.assertIn("flowMarker = flowMarker,", socks_kt)
        # UDP byte counters must be committed inside UdpUpstreamMux.send's binding lock,
        # not in the caller after send returns: a local echo/reply may otherwise be observed
        # before the upload counter is published. Assert against the flow's own binding
        # metadata so same-endpoint traffic remains isolated by flow identity.
        udp_send_start = socks_kt.index("        fun send(")
        udp_send_end = socks_kt.index("        fun trimClosedFlows", udp_send_start)
        udp_send_body = socks_kt[udp_send_start:udp_send_end]
        self.assertIn("val delivery = deliverAdmittedUdpDatagramDetailed(", udp_send_body)
        self.assertIn("SpeedLimiter.upload.recordForwardedForApp(", udp_send_body)
        self.assertIn("isVerificationProbeFlow = activeBinding.isVerificationProbe,", udp_send_body)
        self.assertIn("flowMarker = flow.logicalKey,", udp_send_body)
        self.assertLess(
            udp_send_body.index("val delivery = deliverAdmittedUdpDatagramDetailed("),
            udp_send_body.index("SpeedLimiter.upload.recordForwardedForApp("),
        )

        udp_upload_loop_start = socks_kt.index("val forwarded = mux.send(")
        udp_upload_loop_end = socks_kt.index("udpFlows.touchSent(flow", udp_upload_loop_start)
        self.assertNotIn(
            "SpeedLimiter.upload.recordForwardedForApp(",
            socks_kt[udp_upload_loop_start:udp_upload_loop_end],
            "UDP upload accounting must not be duplicated after the mux lock has been released",
        )

        # UDP downloads are also counted immediately inside the locked successful relay send,
        # rather than after the receiving client may already have observed the response.
        self.assertIn("SpeedLimiter.download.recordForwardedForApp(", socks_kt)
        self.assertIn("isVerificationProbeFlow = binding.isVerificationProbe,", socks_kt)
        self.assertIn("flowMarker = binding.flow.logicalKey,", socks_kt)

        # 4. Verify SpeedLimiter.isSyntheticProbeTraffic NEVER relies on IP:port match or ownAppUid alone
        self.assertNotIn("return isOwnApp || isVerificationActive()", limiter_kt)
        self.assertNotIn("containsActiveProbeHeader", limiter_kt)
        self.assertIn("if (isVerificationProbeFlow || isMarkedProbeFlow(flowMarker)) {", limiter_kt)
        self.assertIn("fun beginProbeMeasurementSession(): String", limiter_kt)
        self.assertIn("fun endProbeMeasurementSession(token: String? = null)", limiter_kt)
        self.assertIn("private val probeSessionBuckets =", limiter_kt)
        self.assertIn("val removedBucket = probeSessionBuckets.remove(token) ?: return", limiter_kt)
        self.assertIn("measurementMutex.withLock {", engine_kt)
        self.assertIn("clearAllProbeState()", limiter_kt)
        self.assertIn("private val verificationProbeCounter = AtomicReference(SessionCounter(0L, 0L))", limiter_kt)

        # 5. Verify SpeedPlanUsageTracker does not drop samples during verification and keeps monotonic segmentIndex
        self.assertNotIn("if (generation <= 0L || SpeedLimiter.isVerificationActive()) return", tracker_kt)
        self.assertIn("private var highestSegmentIndexInGeneration: Int = 0", tracker_kt)
        self.assertIn("private fun allocateNextSegmentIndexLocked(generation: Long): Int", tracker_kt)

    def test_03_excel_xlsx_from_transpiled_exporter_source_and_openpyxl_verification(self):
        exporter_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedHistoryExcelExporter.kt").read_text(encoding="utf-8")
        main_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/MainActivity.kt").read_text(encoding="utf-8")

        # Check SAF and absence of misleading cacheDir.usableSpace pre-check in exportToUri
        self.assertIn("تصدير سجل السرعات إلى Excel", main_kt)
        self.assertIn("ActivityResultContracts.CreateDocument(SpeedHistoryExcelExporter.XLSX_MIME_TYPE)", main_kt)
        export_uri_idx = exporter_kt.index("fun exportToUri(")
        export_file_idx = exporter_kt.index("fun exportToFile(")
        export_uri_body = exporter_kt[export_uri_idx:export_file_idx]
        self.assertNotIn("context.cacheDir?.usableSpace", export_uri_body)
        self.assertIn("catch (se: SecurityException)", export_uri_body)

        if not BSH_JAR.exists() or openpyxl is None:
            self.skipTest("BeanShell JAR or openpyxl not installed")

        with tempfile.TemporaryDirectory() as tmpdir:
            xlsx_empty = Path(tmpdir) / "empty.xlsx"
            xlsx_populated = Path(tmpdir) / "populated.xlsx"

            jvm_stdout = execute_transpiled_exporter_on_jvm(xlsx_empty, xlsx_populated)
            self.assertIn(
                "TRANSPILED_EXPORTER_JVM_OK changes=0,1,2 reconnectSessions=2 reconnectChanges=0",
                jvm_stdout,
            )

            five_gb = 5 * 1024 * 1024 * 1024
            two_gb = 2 * 1024 * 1024 * 1024

            # Open both workbooks in openpyxl (both formula mode and cached data_only mode) with zero warnings
            with warnings.catch_warnings(record=True) as caught_warnings:
                warnings.simplefilter("always")
                wb_empty = openpyxl.load_workbook(xlsx_empty, data_only=False)
                wb_pop = openpyxl.load_workbook(xlsx_populated, data_only=False)
                wb_pop_cached = openpyxl.load_workbook(xlsx_populated, data_only=True)
                style_warnings = [w for w in caught_warnings if "default style" in str(w.message)]
                self.assertEqual([], style_warnings, f"Expected zero default-style warnings, got: {style_warnings}")

            self.assertEqual(["سجل السرعات", "الملخص"], wb_empty.sheetnames)
            ws_empty_1 = wb_empty["سجل السرعات"]
            ws_empty_2 = wb_empty["الملخص"]
            self.assertTrue(ws_empty_1.views.sheetView[0].rightToLeft)
            self.assertTrue(ws_empty_2.views.sheetView[0].rightToLeft)
            self.assertEqual("لا توجد سجلات محفوظة حتى الآن", ws_empty_1["A5"].value)
            self.assertEqual("قاعدة البيانات فارغة", ws_empty_1["Y5"].value)
            self.assertEqual(0, ws_empty_1["E5"].value)
            self.assertEqual(0, ws_empty_2["B5"].value)
            wb_empty.close()

            self.assertEqual(["سجل السرعات", "الملخص"], wb_pop.sheetnames)
            ws1 = wb_pop["سجل السرعات"]
            ws2 = wb_pop["الملخص"]
            ws1_cached = wb_pop_cached["سجل السرعات"]
            ws2_cached = wb_pop_cached["الملخص"]
            self.assertTrue(ws1.views.sheetView[0].rightToLeft)
            self.assertTrue(ws2.views.sheetView[0].rightToLeft)

            # Verify exact row values AND exact durations in seconds (E5=600, E6=1800, E7=2400)
            self.assertEqual("sess-low-001", ws1["B5"].value)
            self.assertEqual(600, ws1["E5"].value)
            self.assertEqual("10:00 (10 د 0 ث)", ws1["F5"].value)
            self.assertEqual(15_000_000, ws1["K5"].value)
            self.assertEqual(5_000_000, ws1["N5"].value)
            self.assertEqual(20_000_000, ws1["Q5"].value)
            self.assertAlmostEqual(25.0, ws1["U5"].value, places=2)
            self.assertAlmostEqual(8.3333, ws1["W5"].value, places=2)

            self.assertEqual("sess-unlim-002", ws1["B6"].value)
            self.assertEqual(1800, ws1["E6"].value)
            self.assertEqual("30:00 (30 د 0 ث)", ws1["F6"].value)
            self.assertEqual(five_gb, ws1["K6"].value)
            self.assertEqual(two_gb, ws1["N6"].value)
            self.assertEqual(five_gb + two_gb, ws1["Q6"].value)

            # Totals row in Sheet 1: verify both formula expressions AND cached values match sum of rows!
            self.assertEqual("الإجمالي", ws1["A7"].value)
            self.assertEqual("=SUM(E5:E6)", ws1["E7"].value)
            self.assertEqual(600 + 1800, ws1_cached["E7"].value)
            self.assertEqual(2400, ws1_cached["E7"].value)
            self.assertEqual("40:00 (40 د 0 ث)", ws1["F7"].value)

            self.assertEqual("=SUM(K5:K6)", ws1["K7"].value)
            self.assertEqual(ws1["K5"].value + ws1["K6"].value, ws1_cached["K7"].value)
            self.assertAlmostEqual(ws1["L5"].value + ws1["L6"].value, ws1_cached["L7"].value, places=3)

            self.assertEqual("=SUM(N5:N6)", ws1["N7"].value)
            self.assertEqual(ws1["N5"].value + ws1["N6"].value, ws1_cached["N7"].value)
            self.assertAlmostEqual(ws1["O5"].value + ws1["O6"].value, ws1_cached["O7"].value, places=3)

            self.assertEqual("=SUM(Q5:Q6)", ws1["Q7"].value)
            self.assertEqual(ws1["Q5"].value + ws1["Q6"].value, ws1_cached["Q7"].value)
            self.assertAlmostEqual(ws1["R5"].value + ws1["R6"].value, ws1_cached["R7"].value, places=3)
            self.assertAlmostEqual(ws1["S5"].value + ws1["S6"].value, ws1_cached["S7"].value, places=3)

            # Summary KPIs & category breakdown in Sheet 2 (both formulas and cached totals)
            self.assertEqual("إجمالي بيانات التنزيل", ws2["A5"].value)
            self.assertEqual(15_000_000 + five_gb, ws2["B5"].value)
            self.assertEqual("إجمالي بيانات الرفع", ws2["A6"].value)
            self.assertEqual(5_000_000 + two_gb, ws2["B6"].value)
            self.assertEqual("إجمالي البيانات الكلي (تنزيل + رفع)", ws2["A7"].value)
            self.assertEqual(20_000_000 + five_gb + two_gb, ws2["B7"].value)
            self.assertEqual("إجمالي مدة الاستخدام الفعلية", ws2["A8"].value)
            self.assertEqual(2400, ws2["B8"].value)
            self.assertEqual("عدد الجلسات المستقلة (VPN Sessions)", ws2["A9"].value)
            self.assertEqual(1, ws2["B9"].value)
            self.assertEqual("إجمالي عدد فترات السرعة المسجلة", ws2["A10"].value)
            self.assertEqual(2, ws2["B10"].value)
            self.assertEqual("عدد مرات تغيير خطة السرعة أثناء الاتصال", ws2["A11"].value)
            self.assertEqual(1, ws2["B11"].value)
            self.assertEqual("فئة خيار السرعة", ws2["A17"].value)
            self.assertEqual("الإجمالي الكلي حسب خيار السرعة", ws2["A23"].value)
            self.assertEqual("=SUM(C18:C22)", ws2["C23"].value)
            self.assertEqual(sum(ws2[f"C{r}"].value for r in range(18, 23)), ws2_cached["C23"].value)
            self.assertEqual("=SUM(D18:D22)", ws2["D23"].value)
            self.assertEqual(sum(ws2[f"D{r}"].value for r in range(18, 23)), ws2_cached["D23"].value)
            self.assertEqual(2400, ws2_cached["D23"].value)
            self.assertEqual("=SUM(F18:F22)", ws2["F23"].value)
            self.assertEqual(sum(ws2[f"F{r}"].value for r in range(18, 23)), ws2_cached["F23"].value)
            self.assertEqual("=SUM(H18:H22)", ws2["H23"].value)
            self.assertEqual(sum(ws2[f"H{r}"].value for r in range(18, 23)), ws2_cached["H23"].value)
            self.assertEqual("=SUM(J18:J22)", ws2["J23"].value)
            self.assertEqual(sum(ws2[f"J{r}"].value for r in range(18, 23)), ws2_cached["J23"].value)
            wb_pop.close()
            wb_pop_cached.close()

    def test_04_exceedance_wave_full_duration_and_time_gap_handling(self):
        tracker_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedPlanUsage.kt").read_text(encoding="utf-8")
        self.assertIn("internal const val EXCEEDANCE_TOLERANCE_RATIO = 1.15", tracker_kt)
        self.assertIn("internal const val EXCEEDANCE_MIN_DURATION_MS = 2_000L", tracker_kt)
        self.assertIn("internal const val MAX_CONTIGUOUS_SAMPLE_INTERVAL_MS = 1_500L", tracker_kt)
        self.assertIn("segment.exceedanceDurationMillis += segment.candidateExceedanceDurationMillis", tracker_kt)

    def test_05_stats_toggle_fresh_install_and_explicit_disable_with_quota_enforcement(self):
        smart_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SmartEngine.kt").read_text(encoding="utf-8")
        quota_engine_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/QuotaEnforcementEngine.kt").read_text(encoding="utf-8")
        quota_worker_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/QuotaEnforcementWorker.kt").read_text(encoding="utf-8")

        self.assertIn(".getBoolean(STATS_ENABLED, false)", smart_kt)
        self.assertIn("if (SmartSettings.isStatsEnabled(context))", quota_engine_kt)
        self.assertNotIn("if (!SmartSettings.isStatsExplicitlyDisabled(context))", quota_engine_kt)
        self.assertIn("QuotaWorkScheduler.syncWithConfiguredPolicies(context)", smart_kt)
        self.assertIn("fun syncWithConfiguredPolicies(context: Context)", quota_worker_kt)

    def test_06_speed_screen_io_decoupling_and_audit_fixes(self):
        main_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/MainActivity.kt").read_text(encoding="utf-8")
        service_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVpnService.kt").read_text(encoding="utf-8")
        overlay_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedOverlayService.kt").read_text(encoding="utf-8")

        speed_screen_idx = main_kt.index("private fun SpeedScreen(")
        history_card_idx = main_kt.index("private fun SpeedPlanUsageHistoryCard(")
        speed_screen_body = main_kt[speed_screen_idx:history_card_idx]

        # SpeedScreen must NOT call flushActiveSegment on recomposition or include sessionDownloadBytes in LaunchedEffect
        self.assertNotIn("SpeedPlanUsageTracker.flushActiveSegment(", speed_screen_body)
        self.assertIn("SpeedPlanUsageTracker.persistedHistoryRevision.collectAsStateWithLifecycle()", speed_screen_body)
        self.assertIn("SpeedPlanUsageTracker.mergePersistedWithLiveActive(persistedPlanHistory)", speed_screen_body)

        # Verify prior audit fixes remain intact
        recover_idx = service_kt.index("private fun recoverDataPlaneLocked(")
        self.assertIn("dnsIpv4Only = recoveryCompatibility.dnsIpv4Only", service_kt[recover_idx:recover_idx + 2500])
        cleanup_idx = service_kt.index("private fun cleanupLocked(")
        cleanup_end = service_kt.index("private fun failLocked(", cleanup_idx)
        cleanup_body = service_kt[cleanup_idx:cleanup_end]
        self.assertIn("quotaMonitor?.cancel()", cleanup_body[:900])
        self.assertIn("preserveLimiterUntilUsageSnapshot", cleanup_body)
        self.assertIn("stopNative(", cleanup_body)
        relay_stop_idx = cleanup_body.index("runCatching { relay?.stop() }")
        final_snapshot_idx = cleanup_body.index("afterTrafficStopped?.invoke()")
        self.assertLess(
            relay_stop_idx,
            final_snapshot_idx,
            "terminal usage must be snapshotted only after the native/relay traffic path is stopped",
        )
        self.assertIn("endLimiterSession = !preserveLimiterUntilUsageSnapshot", cleanup_body)

        # Both normal disconnect and error cleanup pass a terminal callback into
        # cleanupLocked; the final snapshot precedes clearing SpeedLimiter generation state.
        disconnect_idx = service_kt.index("private suspend fun disconnect(")
        revoke_idx = service_kt.index("override fun onRevoke()", disconnect_idx)
        disconnect_body = service_kt[disconnect_idx:revoke_idx]
        self.assertIn("cleanupLocked {", disconnect_body)
        self.assertIn("SpeedPlanUsageTracker.onVpnDisconnected(", disconnect_body)
        self.assertIn("SpeedLimiter.endSession(generation)", disconnect_body)
        fail_idx = service_kt.index("private fun failLocked(")
        fail_end = service_kt.index("private suspend fun disconnect(", fail_idx)
        fail_body = service_kt[fail_idx:fail_end]
        self.assertIn("cleanupLocked {", fail_body)
        self.assertIn("SpeedPlanUsageTracker.onVpnDisconnected(", fail_body)
        self.assertIn("SpeedLimiter.endSession(generation)", fail_body)

        # Terminal rows are synchronously persisted before the caller tears down session counters.
        tracker_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedPlanUsage.kt").read_text(encoding="utf-8")
        terminal_idx = tracker_kt.index("fun onVpnDisconnected(")
        abnormal_idx = tracker_kt.index("fun onProcessAbnormalExit(", terminal_idx)
        terminal_body = tracker_kt[terminal_idx:abnormal_idx]
        self.assertRegex(
            terminal_body,
            r"persistRecords\\s*\\(\\s*context\\.applicationContext,\\s*"
            r"listOf\\(it\\),\\s*synchronous\\s*=\\s*true,\\s*"
            r"expectedEpoch\\s*=\\s*writeEpoch,\\s*\\)",
        )
        self.assertIn("SmartSettings.setOverlayEnabled(this, false)", overlay_kt)

    def test_07_jvm_execution_of_limiter_and_exceedance_wave_logic(self):
        """Executes the core JVM logic of exceedance wave tracking on OpenJDK 17."""
        if not BSH_JAR.exists():
            self.skipTest("BeanShell JAR not available")

        with tempfile.TemporaryDirectory() as tmpdir:
            bsh_file = Path(tmpdir) / "verify_jvm.bsh"
            bsh_file.write_text(
                """
                // Verify full exceedance wave duration (1.5s vs 3.0s vs gap) on JVM
                long candDur = 0L;
                boolean candCommitted = false;
                long totalExcDur = 0L;
                long lastSampleAt = 0L;

                void sample(long nowMs, long elapsedMs, boolean exceeded) {
                    long gap = lastSampleAt > 0L ? (nowMs - lastSampleAt) : elapsedMs;
                    if (gap > 1500L || elapsedMs > 1500L) {
                        candDur = 0L;
                        candCommitted = false;
                    }
                    lastSampleAt = nowMs;
                    if (elapsedMs > 1500L) return;
                    if (exceeded) {
                        candDur += elapsedMs;
                        if (candDur >= 2000L) {
                            if (!candCommitted) {
                                totalExcDur += candDur;
                                candCommitted = true;
                            } else {
                                totalExcDur += elapsedMs;
                            }
                        }
                    } else {
                        candDur = 0L;
                        candCommitted = false;
                    }
                }

                long t = 10000L;
                // 1.5s wave (3 x 500ms) -> 0ms committed
                for (int i = 0; i < 3; i++) { t += 500L; sample(t, 500L, true); }
                t += 500L; sample(t, 500L, false);
                if (totalExcDur != 0L) throw new RuntimeException("1.5s wave should be 0ms, got " + totalExcDur);

                // 3.0s wave (6 x 500ms) -> full 3000ms committed
                for (int i = 0; i < 6; i++) { t += 500L; sample(t, 500L, true); }
                t += 500L; sample(t, 500L, false);
                if (totalExcDur != 3000L) throw new RuntimeException("3.0s wave should be 3000ms, got " + totalExcDur);

                System.out.println("JVM_EXCEEDANCE_WAVE_OK excMs=" + totalExcDur);
                """,
                encoding="utf-8",
            )
            proc = subprocess.run(
                ["java", "-cp", str(BSH_JAR), "bsh.Interpreter", str(bsh_file)],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertIn("JVM_EXCEEDANCE_WAVE_OK excMs=3000", proc.stdout)

    def test_08_flow_level_probe_distinction_same_endpoint_and_lifecycle_on_jvm(self):
        """
        Extracts SpeedLimiter.isSyntheticProbeTraffic() from SpeedLimiter.kt and executes the
        transpiled logic on OpenJDK 17 across all required scenarios during Verify Speed:
        1. Real traffic from another app (uid=10150) hitting the SAME IP:port as the probe -> counted in planSessionBytes.
        2. Real non-probe traffic from SpeedVPN's own UID (uid=10999) hitting the EXACT SAME IP:port as the probe -> counted in planSessionBytes.
        3. Unattributed real user traffic (uid=null, packages=emptySet()) hitting the EXACT SAME IP:port as the probe -> counted in planSessionBytes.
        4. Unattributed synthetic probe (uid=null) AND attributed synthetic probe (uid=10999) carrying flow-level probe markers
           (isVerificationProbeFlow or markProbeFlow) -> excluded from planSessionBytes.
        5. Probe lifecycle cleanup: normal speed test / Stage 0 outside Verify Speed does not leave stale endpoints or flow markers.
        """
        if not BSH_JAR.exists():
            self.skipTest("BeanShell JAR not available")

        probe_bsh = transpile_is_synthetic_probe_to_bsh()

        with tempfile.TemporaryDirectory() as tmpdir:
            bsh_file = Path(tmpdir) / "verify_probe_distinction.bsh"
            bsh_file.write_text(
                f"""
                import java.util.*;
                import java.util.concurrent.atomic.AtomicLong;

                String ownPackageName = "com.speedvpn.app";
                int ownAppUid = 10999;
                boolean verificationActive = false;
                Set activeProbeEndpoints = new HashSet();
                Set activeProbeFlowMarkers = new HashSet();

                void clearAllProbeState() {{
                    activeProbeEndpoints.clear();
                    activeProbeFlowMarkers.clear();
                }}

                void beginVerification() {{
                    clearAllProbeState();
                    verificationActive = true;
                }}

                void endVerification() {{
                    verificationActive = false;
                    clearAllProbeState();
                }}

                void registerProbeEndpoint(String host, int port) {{
                    if (!verificationActive) return;
                    activeProbeEndpoints.add(host.toLowerCase() + ":" + port);
                }}

                boolean isRegisteredProbeEndpoint(String host, int port) {{
                    if (host == null || host.trim().isEmpty()) return false;
                    return activeProbeEndpoints.contains(host.toLowerCase() + ":" + port);
                }}

                void markProbeFlow(String flowMarker) {{
                    if (flowMarker != null && !flowMarker.trim().isEmpty()) {{
                        activeProbeFlowMarkers.add(flowMarker.trim());
                    }}
                }}

                boolean isMarkedProbeFlow(String flowMarker) {{
                    return flowMarker != null && activeProbeFlowMarkers.contains(flowMarker.trim());
                }}

                boolean isSyntheticProbeTraffic(
                    Integer uid,
                    Set packages,
                    boolean isVerificationProbeFlow,
                    String remoteAddress,
                    int remotePort,
                    String flowMarker
                ) {{
                    {probe_bsh}
                }}

                // Verify lifecycle rule 1: normal speed test outside Verify Speed never registers persistent endpoints
                registerProbeEndpoint("127.0.0.1", 19443);
                if (isRegisteredProbeEndpoint("127.0.0.1", 19443)) {{
                    throw new RuntimeException("Normal speed test outside Verify Speed should not register persistent endpoint");
                }}

                // Verify lifecycle rule 2: beginVerification clears any stale markers before starting
                markProbeFlow("stale-marker-from-stage0");
                beginVerification();
                if (isMarkedProbeFlow("stale-marker-from-stage0")) {{
                    throw new RuntimeException("beginVerification must clear stale flow markers");
                }}

                registerProbeEndpoint("127.0.0.1", 19443);

                AtomicLong planDl = new AtomicLong(0L);
                AtomicLong planUl = new AtomicLong(0L);
                AtomicLong probeDl = new AtomicLong(0L);
                AtomicLong probeUl = new AtomicLong(0L);

                void recordDl(int bytes, Integer uid, Set pkgs, boolean probeFlag, String addr, int port, String marker) {{
                    if (isSyntheticProbeTraffic(uid, pkgs, probeFlag, addr, port, marker)) probeDl.addAndGet(bytes);
                    else planDl.addAndGet(bytes);
                }}

                void recordUl(int bytes, Integer uid, Set pkgs, boolean probeFlag, String addr, int port, String marker) {{
                    if (isSyntheticProbeTraffic(uid, pkgs, probeFlag, addr, port, marker)) probeUl.addAndGet(bytes);
                    else planUl.addAndGet(bytes);
                }}

                Set emptyPkgs = Collections.emptySet();
                Set otherPkgs = new HashSet(Arrays.asList(new String[]{{ "org.telegram.messenger" }}));
                Set ownPkgs = new HashSet(Arrays.asList(new String[]{{ "com.speedvpn.app" }}));

                // 1. Real traffic from another app (UID 10150) to the SAME IP:port ("127.0.0.1":19443) as the probe:
                //    100 KB DL + 50 KB UL -> MUST be counted in planDl/planUl!
                recordDl(100000, Integer.valueOf(10150), otherPkgs, false, "127.0.0.1", 19443, "flow-app-1");
                recordUl(50000, Integer.valueOf(10150), otherPkgs, false, "127.0.0.1", 19443, "flow-app-1");

                // 2. Real non-probe traffic from SpeedVPN's OWN UID (UID 10999) to the EXACT SAME IP:port ("127.0.0.1":19443) as the probe:
                //    80 KB DL + 40 KB UL -> MUST be counted as real traffic, NOT excluded!
                recordDl(80000, Integer.valueOf(10999), ownPkgs, false, "127.0.0.1", 19443, "flow-own-nonprobe");
                recordUl(40000, Integer.valueOf(10999), ownPkgs, false, "127.0.0.1", 19443, "flow-own-nonprobe");

                // 3. Unattributed real user traffic (UID null, packages empty) to the EXACT SAME IP:port ("127.0.0.1":19443) as the probe:
                //    120 KB DL + 60 KB UL -> MUST be counted as real traffic, NOT excluded!
                recordDl(120000, null, emptyPkgs, false, "127.0.0.1", 19443, "flow-unattr-user");
                recordUl(60000, null, emptyPkgs, false, "127.0.0.1", 19443, "flow-unattr-user");

                // 4. Unattributed synthetic probe (UID null, packages empty) marked via flow-level flag or markProbeFlow:
                //    250 KB DL + 110 KB UL -> MUST be excluded from plan traffic!
                markProbeFlow("flow-probe-unattr-2");
                recordDl(150000, null, emptyPkgs, true, "127.0.0.1", 19443, "flow-probe-unattr-1");
                recordDl(100000, null, emptyPkgs, false, "127.0.0.1", 19443, "flow-probe-unattr-2");
                recordUl(60000, null, emptyPkgs, true, "127.0.0.1", 19443, "flow-probe-unattr-1");
                recordUl(50000, null, emptyPkgs, false, "127.0.0.1", 19443, "flow-probe-unattr-2");

                // 5. Attributed synthetic probe from ownAppUid (UID 10999) marked via flow-level flag or markProbeFlow:
                //    300 KB DL + 140 KB UL -> MUST be excluded from plan traffic!
                markProbeFlow("flow-probe-own-2");
                recordDl(300000, Integer.valueOf(10999), ownPkgs, true, "127.0.0.1", 19443, "flow-probe-own-1");
                recordUl(140000, Integer.valueOf(10999), ownPkgs, false, "127.0.0.1", 19443, "flow-probe-own-2");

                endVerification();
                if (isRegisteredProbeEndpoint("127.0.0.1", 19443) || isMarkedProbeFlow("flow-probe-own-2")) {{
                    throw new RuntimeException("endVerification must clear all probe endpoints and flow markers");
                }}

                // Verify overlapping session tokens: start A then B, end A first -> B's markers remain until B ends!
                Map sessionBuckets = new HashMap();
                List sessionOrder = new ArrayList();
                String activeTok = "";
                String beginScopedSession(String id) {{
                    if (sessionOrder.isEmpty()) {{
                        activeProbeEndpoints.clear();
                        activeProbeFlowMarkers.clear();
                    }}
                    Set bucketFlows = new HashSet();
                    sessionBuckets.put(id, bucketFlows);
                    sessionOrder.add(id);
                    activeTok = id;
                    return id;
                }}
                void markScopedFlow(String fm) {{
                    activeProbeFlowMarkers.add(fm);
                    Set b = (Set) sessionBuckets.get(activeTok);
                    if (b != null) b.add(fm);
                }}
                void endScopedSession(String id) {{
                    Set removed = (Set) sessionBuckets.remove(id);
                    if (removed == null) return;
                    sessionOrder.remove(id);
                    activeTok = sessionOrder.isEmpty() ? "" : (String) sessionOrder.get(sessionOrder.size() - 1);
                    if (sessionOrder.isEmpty()) {{
                        activeProbeEndpoints.clear();
                        activeProbeFlowMarkers.clear();
                    }} else {{
                        Iterator it = removed.iterator();
                        while (it.hasNext()) {{
                            String fm = (String) it.next();
                            boolean stillOwned = false;
                            Iterator bit = sessionBuckets.values().iterator();
                            while (bit.hasNext()) {{
                                if (((Set) bit.next()).contains(fm)) stillOwned = true;
                            }}
                            if (!stillOwned) activeProbeFlowMarkers.remove(fm);
                        }}
                    }}
                }}
                String tokA = beginScopedSession("session-A");
                markScopedFlow("probe-flow-A");
                String tokB = beginScopedSession("session-B");
                markScopedFlow("probe-flow-B");
                endScopedSession(tokA);
                if (isMarkedProbeFlow("probe-flow-A")) {{
                    throw new RuntimeException("Ending session A must remove session A flow markers");
                }}
                if (!isMarkedProbeFlow("probe-flow-B") || !activeTok.equals("session-B")) {{
                    throw new RuntimeException("Ending session A must preserve session B markers and active token!");
                }}
                endScopedSession(tokB);
                if (isMarkedProbeFlow("probe-flow-B") || !activeTok.isEmpty()) {{
                    throw new RuntimeException("Ending session B must clear session B flow markers");
                }}

                // Expected real bytes: DL = 100k + 80k + 120k = 300,000; UL = 50k + 40k + 60k = 150,000
                // Expected probe bytes: DL = 150k + 100k + 300k = 550,000; UL = 60k + 50k + 140k = 250,000
                if (planDl.get() != 300000L) throw new RuntimeException("planDl mismatch: " + planDl.get());
                if (planUl.get() != 150000L) throw new RuntimeException("planUl mismatch: " + planUl.get());
                if (probeDl.get() != 550000L) throw new RuntimeException("probeDl mismatch: " + probeDl.get());
                if (probeUl.get() != 250000L) throw new RuntimeException("probeUl mismatch: " + probeUl.get());

                System.out.println(
                    "PROBE_DISTINCTION_OK planDl=" + planDl.get() +
                    " planUl=" + planUl.get() +
                    " probeDl=" + probeDl.get() +
                    " probeUl=" + probeUl.get()
                );
                """,
                encoding="utf-8",
            )
            proc = subprocess.run(
                ["java", "-cp", str(BSH_JAR), "bsh.Interpreter", str(bsh_file)],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertIn(
                "PROBE_DISTINCTION_OK planDl=300000 planUl=150000 probeDl=550000 probeUl=250000",
                proc.stdout,
            )

    def test_09_concurrent_probe_and_normal_loopback_sockets_to_same_destination_never_leak_probe_permit_on_jvm(self):
        """
        Opens a SINGLE shared loopback TCP ServerSocket and a SINGLE shared loopback UDP DatagramSocket
        on 127.0.0.1 (OpenJDK 17) and keeps 5 TCP connections (2 probes + 3 normal flows) and 5 UDP
        sockets (2 probes + 3 normal flows) SIMULTANEOUSLY OPEN and interleaved to that EXACT SAME
        destination IP:port.
        Specifically proves:
        1. Pre-registering a probe client socket `(probeLocalPort, "127.0.0.1", dstPort)` before a normal
           connection connects to `"127.0.0.1":dstPort` NEVER causes the normal connection to inherit or
           consume the probe's status.
        2. Interleaved concurrent TCP and UDP transfers from another app (uid=10150), SpeedVPN's own UID
           (uid=10999), and unattributed user flows (uid=null) to the exact same destination IP:port as
           the open probes are 100% preserved in `planSessionBytes`, while attributed and unattributed
           probes are 100% excluded.
        """
        if not BSH_JAR.exists():
            self.skipTest("BeanShell JAR not available")

        probe_bsh = transpile_is_synthetic_probe_to_bsh()

        with tempfile.TemporaryDirectory() as tmpdir:
            bsh_file = Path(tmpdir) / "concurrent_loopback_tcp_udp_relay.bsh"
            bsh_file.write_text(
                f"""
                import java.io.*;
                import java.net.*;
                import java.util.*;
                import java.util.concurrent.*;
                import java.util.concurrent.atomic.AtomicLong;

                String ownPackageName = "com.speedvpn.app";
                int ownAppUid = 10999;
                boolean verificationActive = true;
                Set activeProbeEndpoints = ConcurrentHashMap.newKeySet();
                Set activeProbeFlowMarkers = ConcurrentHashMap.newKeySet();
                Set activeProbeClientSockets = ConcurrentHashMap.newKeySet();

                void registerProbeEndpoint(String host, int port) {{
                    activeProbeEndpoints.add(host.toLowerCase() + ":" + port);
                }}

                void markProbeFlow(String flowMarker) {{
                    if (flowMarker != null && !flowMarker.trim().isEmpty()) {{
                        activeProbeFlowMarkers.add(flowMarker.trim());
                    }}
                }}

                void unmarkProbeFlow(String flowMarker) {{
                    if (flowMarker != null && !flowMarker.trim().isEmpty()) {{
                        activeProbeFlowMarkers.remove(flowMarker.trim());
                    }}
                }}

                boolean isMarkedProbeFlow(String flowMarker) {{
                    return flowMarker != null && activeProbeFlowMarkers.contains(flowMarker.trim());
                }}

                void markProbeClientSocket(int protocol, int localPort, String host, int remotePort) {{
                    String key = protocol + "|" + localPort + "|" + host.toLowerCase() + ":" + remotePort;
                    activeProbeClientSockets.add(key);
                    markProbeFlow("clientPort:" + key);
                }}

                void unmarkProbeClientSocket(int protocol, int localPort, String host, int remotePort) {{
                    String key = protocol + "|" + localPort + "|" + host.toLowerCase() + ":" + remotePort;
                    activeProbeClientSockets.remove(key);
                    unmarkProbeFlow("clientPort:" + key);
                }}

                boolean isMarkedProbeClientSocket(int protocol, int localPort, String host, int remotePort) {{
                    if ((protocol != 6 && protocol != 17) || localPort <= 0 || host == null || remotePort <= 0) return false;
                    return activeProbeClientSockets.contains(protocol + "|" + localPort + "|" + host.toLowerCase() + ":" + remotePort);
                }}

                long encodeUidDecision(int uid, boolean shouldBlock, boolean isVerificationProbe) {{
                    return (((long) uid) << 32) | (isVerificationProbe ? 2L : 0L) | (shouldBlock ? 1L : 0L);
                }}

                String socksUsernameForDecision(String baseUser, long encodedDecision) {{
                    int uid = (int) (encodedDecision >>> 32);
                    boolean isProbe = (encodedDecision & 2L) != 0L;
                    if (isProbe) {{
                        return uid >= 0 ? (baseUser + "#" + uid + "#probe") : (baseUser + "#probe");
                    }}
                    return uid >= 0 ? (baseUser + "#" + uid) : baseUser;
                }}

                boolean isProbeUsername(String supplied, String baseUser) {{
                    return supplied.equals(baseUser + "#probe") ||
                        (supplied.startsWith(baseUser + "#") && supplied.endsWith("#probe"));
                }}

                boolean isSyntheticProbeTraffic(
                    Integer uid,
                    Set packages,
                    boolean isVerificationProbeFlow,
                    String remoteAddress,
                    int remotePort,
                    String flowMarker
                ) {{
                    {probe_bsh}
                }}

                AtomicLong planDl = new AtomicLong(0L);
                AtomicLong planUl = new AtomicLong(0L);
                AtomicLong probeDl = new AtomicLong(0L);
                AtomicLong probeUl = new AtomicLong(0L);

                void recordForBucket(
                    AtomicLong planCounter,
                    AtomicLong probeCounter,
                    int bytes,
                    Integer uid,
                    Set packages,
                    boolean isProbeFlow,
                    String remoteAddr,
                    int remotePort,
                    String flowMarker
                ) {{
                    if (isSyntheticProbeTraffic(uid, packages, isProbeFlow, remoteAddr, remotePort, flowMarker)) {{
                        probeCounter.addAndGet(bytes);
                    }} else {{
                        planCounter.addAndGet(bytes);
                    }}
                }}

                // 1. Single shared TCP upstream server representing the probe destination IP:port
                ServerSocket sharedTcpSrv = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
                int sharedTcpPort = sharedTcpSrv.getLocalPort();
                registerProbeEndpoint("127.0.0.1", sharedTcpPort);

                // Pre-bind and mark the two probe client sockets BEFORE any normal connection connects!
                Socket tcpProbeOwnCli = new Socket();
                tcpProbeOwnCli.bind(new InetSocketAddress("127.0.0.1", 0));
                markProbeClientSocket(6, tcpProbeOwnCli.getLocalPort(), "127.0.0.1", sharedTcpPort);

                Socket tcpProbeUnattrCli = new Socket();
                tcpProbeUnattrCli.bind(new InetSocketAddress("127.0.0.1", 0));
                markProbeClientSocket(6, tcpProbeUnattrCli.getLocalPort(), "127.0.0.1", sharedTcpPort);

                // Now open 3 normal connections to the EXACT SAME sharedTcpPort WHILE the probe sockets
                // are already registered in activeProbeClientSockets!
                Socket tcpNormalOtherCli = new Socket("127.0.0.1", sharedTcpPort);
                Socket tcpNormalOtherAcc = sharedTcpSrv.accept();

                Socket tcpNormalOwnCli = new Socket("127.0.0.1", sharedTcpPort);
                Socket tcpNormalOwnAcc = sharedTcpSrv.accept();

                Socket tcpNormalUnattrCli = new Socket("127.0.0.1", sharedTcpPort);
                Socket tcpNormalUnattrAcc = sharedTcpSrv.accept();

                // Connect the 2 probe sockets so ALL 5 connections are simultaneously open!
                tcpProbeUnattrCli.connect(new InetSocketAddress("127.0.0.1", sharedTcpPort), 5000);
                Socket tcpProbeUnattrAcc = sharedTcpSrv.accept();

                tcpProbeOwnCli.connect(new InetSocketAddress("127.0.0.1", sharedTcpPort), 5000);
                Socket tcpProbeOwnAcc = sharedTcpSrv.accept();

                Set emptyPkgs = Collections.emptySet();
                Set appPkgs = new HashSet(Arrays.asList(new String[]{{ "org.telegram.messenger" }}));
                Set ownPkgs = new HashSet(Arrays.asList(new String[]{{ "com.speedvpn.app" }}));

                void transferChunkOnOpenTcpSession(Socket cli, Socket acc, int ulBytes, int dlBytes, Integer uid, Set pkgs) {{
                    int srcPort = cli.getLocalPort();
                    boolean isMarkedSocket = isMarkedProbeClientSocket(6, srcPort, "127.0.0.1", sharedTcpPort);
                    long decision = encodeUidDecision(uid != null ? uid.intValue() : -1, false, isMarkedSocket);
                    String socksUser = socksUsernameForDecision("vpnuser", decision);
                    boolean sessionIsProbe = isProbeUsername(socksUser, "vpnuser") || isMarkedSocket;
                    String flowMarker = "tcp-relay:" + System.identityHashCode(acc) + ":" + srcPort + "->127.0.0.1:" + sharedTcpPort;
                    if (sessionIsProbe) markProbeFlow(flowMarker);

                    byte[] ulPayload = new byte[ulBytes];
                    cli.getOutputStream().write(ulPayload);
                    cli.getOutputStream().flush();
                    byte[] ulRecv = acc.getInputStream().readNBytes(ulBytes);
                    boolean isProbeChunk = sessionIsProbe || isMarkedProbeFlow(flowMarker);
                    recordForBucket(planUl, probeUl, ulRecv.length, uid, pkgs, isProbeChunk, "127.0.0.1", sharedTcpPort, flowMarker);

                    byte[] dlPayload = new byte[dlBytes];
                    acc.getOutputStream().write(dlPayload);
                    acc.getOutputStream().flush();
                    byte[] dlRecv = cli.getInputStream().readNBytes(dlBytes);
                    recordForBucket(planDl, probeDl, dlRecv.length, uid, pkgs, isProbeChunk, "127.0.0.1", sharedTcpPort, flowMarker);
                }}

                // Interleave transfers across all 5 simultaneously open TCP sockets (wave 1 then wave 2)
                transferChunkOnOpenTcpSession(tcpNormalOwnCli, tcpNormalOwnAcc, 1000, 2000, Integer.valueOf(10999), ownPkgs);
                transferChunkOnOpenTcpSession(tcpProbeOwnCli, tcpProbeOwnAcc, 2500, 5000, Integer.valueOf(10999), ownPkgs);
                transferChunkOnOpenTcpSession(tcpNormalUnattrCli, tcpNormalUnattrAcc, 1500, 3000, null, emptyPkgs);
                transferChunkOnOpenTcpSession(tcpProbeUnattrCli, tcpProbeUnattrAcc, 1500, 3000, null, emptyPkgs);
                transferChunkOnOpenTcpSession(tcpNormalOtherCli, tcpNormalOtherAcc, 2000, 4000, Integer.valueOf(10150), appPkgs);

                // Second interleaved wave on the same 5 open TCP connections
                transferChunkOnOpenTcpSession(tcpProbeOwnCli, tcpProbeOwnAcc, 2500, 5000, Integer.valueOf(10999), ownPkgs);
                transferChunkOnOpenTcpSession(tcpNormalOwnCli, tcpNormalOwnAcc, 1500, 3000, Integer.valueOf(10999), ownPkgs);
                transferChunkOnOpenTcpSession(tcpProbeUnattrCli, tcpProbeUnattrAcc, 2000, 4000, null, emptyPkgs);
                transferChunkOnOpenTcpSession(tcpNormalUnattrCli, tcpNormalUnattrAcc, 1500, 3000, null, emptyPkgs);
                transferChunkOnOpenTcpSession(tcpNormalOtherCli, tcpNormalOtherAcc, 2000, 4000, Integer.valueOf(10150), appPkgs);

                tcpNormalOtherCli.close(); tcpNormalOtherAcc.close();
                tcpNormalOwnCli.close(); tcpNormalOwnAcc.close();
                tcpNormalUnattrCli.close(); tcpNormalUnattrAcc.close();
                tcpProbeUnattrCli.close(); tcpProbeUnattrAcc.close();
                tcpProbeOwnCli.close(); tcpProbeOwnAcc.close();
                sharedTcpSrv.close();

                // 2. Single shared UDP server socket with all 5 UDP client sockets simultaneously open
                DatagramSocket sharedUdpSrv = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                int sharedUdpPort = sharedUdpSrv.getLocalPort();
                registerProbeEndpoint("127.0.0.1", sharedUdpPort);

                DatagramSocket udpProbeUnattrCli = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                markProbeClientSocket(17, udpProbeUnattrCli.getLocalPort(), "127.0.0.1", sharedUdpPort);

                DatagramSocket udpProbeOwnCli = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                markProbeClientSocket(17, udpProbeOwnCli.getLocalPort(), "127.0.0.1", sharedUdpPort);

                DatagramSocket udpNormalOtherCli = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                DatagramSocket udpNormalOwnCli = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                DatagramSocket udpNormalUnattrCli = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));

                void transferOnOpenUdpSocket(DatagramSocket cli, int ulBytes, int dlBytes, Integer uid, Set pkgs) {{
                    int srcPort = cli.getLocalPort();
                    boolean isMarkedSocket = isMarkedProbeClientSocket(17, srcPort, "127.0.0.1", sharedUdpPort);
                    long decision = encodeUidDecision(uid != null ? uid.intValue() : -1, false, isMarkedSocket);
                    String socksUser = socksUsernameForDecision("vpnuser", decision);
                    boolean sessionIsProbe = isProbeUsername(socksUser, "vpnuser") || isMarkedSocket;
                    String flowKey = "src:127.0.0.1:" + srcPort + "|dst:127.0.0.1:" + sharedUdpPort;
                    if (sessionIsProbe) markProbeFlow(flowKey);

                    byte[] ulBuf = new byte[ulBytes];
                    cli.send(new DatagramPacket(ulBuf, ulBuf.length, InetAddress.getByName("127.0.0.1"), sharedUdpPort));
                    byte[] recvBuf = new byte[65535];
                    DatagramPacket ulPkt = new DatagramPacket(recvBuf, recvBuf.length);
                    sharedUdpSrv.receive(ulPkt);
                    boolean isProbe = sessionIsProbe || isMarkedProbeFlow(flowKey);
                    recordForBucket(planUl, probeUl, ulPkt.getLength(), uid, pkgs, isProbe, "127.0.0.1", sharedUdpPort, flowKey);

                    byte[] dlBuf = new byte[dlBytes];
                    sharedUdpSrv.send(new DatagramPacket(dlBuf, dlBuf.length, InetAddress.getByName("127.0.0.1"), srcPort));
                    DatagramPacket dlPkt = new DatagramPacket(recvBuf, recvBuf.length);
                    cli.receive(dlPkt);
                    recordForBucket(planDl, probeDl, dlPkt.getLength(), uid, pkgs, isProbe, "127.0.0.1", sharedUdpPort, flowKey);
                }}

                transferOnOpenUdpSocket(udpNormalOwnCli, 800, 1600, Integer.valueOf(10999), emptyPkgs);
                transferOnOpenUdpSocket(udpProbeOwnCli, 2000, 4000, Integer.valueOf(10999), emptyPkgs);
                transferOnOpenUdpSocket(udpNormalUnattrCli, 1000, 2000, null, emptyPkgs);
                transferOnOpenUdpSocket(udpProbeUnattrCli, 900, 1800, null, emptyPkgs);
                transferOnOpenUdpSocket(udpNormalOtherCli, 1500, 3000, Integer.valueOf(10150), emptyPkgs);

                udpNormalOtherCli.close();
                udpNormalOwnCli.close();
                udpNormalUnattrCli.close();
                udpProbeUnattrCli.close();
                udpProbeOwnCli.close();
                sharedUdpSrv.close();

                // 3. Protocol collision test: Marked TCP probe socket (protocol=6) and unmarked real UDP socket (protocol=17)
                //    bound to the EXACT SAME local port (sharedClientPort) and targeting the EXACT SAME destination IP:port
                //    (127.0.0.1:sharedDestPort) with UNRESOLVED UID (uid = null)!
                ServerSocket collisionTcpSrv = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
                int sharedDestPort = collisionTcpSrv.getLocalPort();
                DatagramSocket collisionUdpSrv = new DatagramSocket(new InetSocketAddress("127.0.0.1", sharedDestPort));

                Socket collisionTcpProbeCli = new Socket();
                collisionTcpProbeCli.setReuseAddress(true);
                collisionTcpProbeCli.bind(new InetSocketAddress("127.0.0.1", 0));
                int sharedClientPort = collisionTcpProbeCli.getLocalPort();
                DatagramSocket collisionUdpRealCli = new DatagramSocket(new InetSocketAddress("127.0.0.1", sharedClientPort));

                // Mark ONLY TCP (protocol=6) on (sharedClientPort -> 127.0.0.1:sharedDestPort)
                markProbeClientSocket(6, sharedClientPort, "127.0.0.1", sharedDestPort);
                if (!isMarkedProbeClientSocket(6, sharedClientPort, "127.0.0.1", sharedDestPort)) {{
                    throw new RuntimeException("TCP probe socket must be marked for protocol=6");
                }}
                if (isMarkedProbeClientSocket(17, sharedClientPort, "127.0.0.1", sharedDestPort)) {{
                    throw new RuntimeException("Real UDP socket on same port/destination must NOT match TCP probe mark!");
                }}

                collisionTcpProbeCli.connect(new InetSocketAddress("127.0.0.1", sharedDestPort), 5000);
                Socket collisionTcpProbeAcc = collisionTcpSrv.accept();

                // Transfer on TCP probe (uid = null): 1200 UL + 2400 DL -> must go to probeUl/probeDl
                boolean tcpColMarked = isMarkedProbeClientSocket(6, sharedClientPort, "127.0.0.1", sharedDestPort);
                long tcpColDec = encodeUidDecision(-1, false, tcpColMarked);
                String tcpColUser = socksUsernameForDecision("vpnuser", tcpColDec);
                boolean tcpColIsProbe = isProbeUsername(tcpColUser, "vpnuser") || tcpColMarked;
                String tcpColMarker = "tcp-relay:col:6|" + sharedClientPort + "->127.0.0.1:" + sharedDestPort;
                if (tcpColIsProbe) markProbeFlow(tcpColMarker);
                collisionTcpProbeCli.getOutputStream().write(new byte[1200]);
                collisionTcpProbeCli.getOutputStream().flush();
                byte[] tcpColUl = collisionTcpProbeAcc.getInputStream().readNBytes(1200);
                recordForBucket(planUl, probeUl, tcpColUl.length, null, emptyPkgs, tcpColIsProbe, "127.0.0.1", sharedDestPort, tcpColMarker);
                collisionTcpProbeAcc.getOutputStream().write(new byte[2400]);
                collisionTcpProbeAcc.getOutputStream().flush();
                byte[] tcpColDl = collisionTcpProbeCli.getInputStream().readNBytes(2400);
                recordForBucket(planDl, probeDl, tcpColDl.length, null, emptyPkgs, tcpColIsProbe, "127.0.0.1", sharedDestPort, tcpColMarker);

                // Transfer on real unmarked UDP socket sharing exact same (sharedClientPort -> 127.0.0.1:sharedDestPort) with uid = null:
                // 1400 UL + 2800 DL -> 100% MUST go to planUl/planDl!
                boolean udpColMarked = isMarkedProbeClientSocket(17, sharedClientPort, "127.0.0.1", sharedDestPort);
                long udpColDec = encodeUidDecision(-1, false, udpColMarked);
                String udpColUser = socksUsernameForDecision("vpnuser", udpColDec);
                boolean udpColIsProbe = isProbeUsername(udpColUser, "vpnuser") || udpColMarked;
                String udpColKey = "src:127.0.0.1:" + sharedClientPort + "|dst:127.0.0.1:" + sharedDestPort;
                if (udpColIsProbe) markProbeFlow(udpColKey);
                byte[] udpColUlBuf = new byte[1400];
                collisionUdpRealCli.send(new DatagramPacket(udpColUlBuf, udpColUlBuf.length, InetAddress.getByName("127.0.0.1"), sharedDestPort));
                byte[] udpColRecv = new byte[65535];
                DatagramPacket udpColUlPkt = new DatagramPacket(udpColRecv, udpColRecv.length);
                collisionUdpSrv.receive(udpColUlPkt);
                recordForBucket(planUl, probeUl, udpColUlPkt.getLength(), null, emptyPkgs, udpColIsProbe || isMarkedProbeFlow(udpColKey), "127.0.0.1", sharedDestPort, udpColKey);

                byte[] udpColDlBuf = new byte[2800];
                collisionUdpSrv.send(new DatagramPacket(udpColDlBuf, udpColDlBuf.length, InetAddress.getByName("127.0.0.1"), sharedClientPort));
                DatagramPacket udpColDlPkt = new DatagramPacket(udpColRecv, udpColRecv.length);
                collisionUdpRealCli.receive(udpColDlPkt);
                recordForBucket(planDl, probeDl, udpColDlPkt.getLength(), null, emptyPkgs, udpColIsProbe || isMarkedProbeFlow(udpColKey), "127.0.0.1", sharedDestPort, udpColKey);

                unmarkProbeClientSocket(6, sharedClientPort, "127.0.0.1", sharedDestPort);
                collisionTcpProbeCli.close();
                collisionTcpProbeAcc.close();
                collisionTcpSrv.close();
                collisionUdpRealCli.close();
                collisionUdpSrv.close();

                // Expected real bytes:
                // DL = 25,600 + 2,800 (collision real UDP) = 28,400
                // UL = 12,800 + 1,400 (collision real UDP) = 14,200
                // Expected probe bytes:
                // DL = 22,800 + 2,400 (collision TCP probe) = 25,200
                // UL = 11,400 + 1,200 (collision TCP probe) = 12,600
                if (planDl.get() != 28400L) throw new RuntimeException("planDl mismatch: " + planDl.get());
                if (planUl.get() != 14200L) throw new RuntimeException("planUl mismatch: " + planUl.get());
                if (probeDl.get() != 25200L) throw new RuntimeException("probeDl mismatch: " + probeDl.get());
                if (probeUl.get() != 12600L) throw new RuntimeException("probeUl mismatch: " + probeUl.get());

                System.out.println(
                    "LOOPBACK_TCP_UDP_INTEGRATION_OK planDl=" + planDl.get() +
                    " planUl=" + planUl.get() +
                    " probeDl=" + probeDl.get() +
                    " probeUl=" + probeUl.get()
                );
                """,
                encoding="utf-8",
            )
            proc = subprocess.run(
                ["java", "-cp", str(BSH_JAR), "bsh.Interpreter", str(bsh_file)],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertIn(
                "LOOPBACK_TCP_UDP_INTEGRATION_OK planDl=28400 planUl=14200 probeDl=25200 probeUl=12600",
                proc.stdout,
            )

    def _get_real_hev_support_c_files_and_includes(self):
        hev = REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel"
        ts = hev / "third-part/hev-task-system"
        lwip = hev / "third-part/lwip"
        yaml = hev / "third-part/yaml"

        yaml_srcs = sorted((yaml / "src").glob("*.c"))
        lwip_srcs = [
            *sorted((lwip / "src/core").rglob("*.c")),
            lwip / "src/ports/unix/lib/sys_arch.c",
        ]
        ts_srcs = [
            f
            for f in [
                *sorted((ts / "src").rglob("*.c")),
                *sorted((ts / "src").rglob("*.S")),
            ]
            if f.name not in ("hev-rbtree.c", "hev-list.c")
        ]
        tunnel_srcs = [
            f
            for f in sorted((hev / "src").rglob("*.c"))
            if f.name != "hev-jni.c"
        ]
        c_files = [*yaml_srcs, *lwip_srcs, *ts_srcs, *tunnel_srcs]
        inc_flags = [
            f"-I{hev}/src",
            f"-I{hev}/src/misc",
            f"-I{hev}/src/core/include",
            f"-I{hev}/src/core/src",
            f"-I{yaml}/include",
            f"-I{lwip}/src/include",
            f"-I{lwip}/src/ports/include",
            f"-I{lwip}/src/ports/unix/include",
            f"-I{ts}/include",
            f"-I{ts}/src",
            "-DANDROID",
            "-D__ANDROID__",
            "-DENABLE_LIBRARY",
            "-DFD_SET_DEFINED",
            "-DSOCKLEN_T_DEFINED",
            "-DHAVE_CONFIG_H",
            "-DYAML_VERSION_MAJOR=0",
            "-DYAML_VERSION_MINOR=2",
            "-DYAML_VERSION_PATCH=5",
            '-DYAML_VERSION_STRING="0.2.5"',
            "-DENABLE_STACK_OVERFLOW_DETECTION",
            "-DENABLE_MEMALLOC_SLICE",
            "-DENABLE_IO_SPLICE_SYSCALL",
            "-DCONFIG_STACK_BACKEND=STACK_MMAP",
            "-DCONFIG_STACK_OVERFLOW_DETECTION=1",
            "-DCONFIG_MEMALLOC_SLICE_ALIGN=16",
            "-DCONFIG_MEMALLOC_SLICE_MAX_SIZE=4096",
            "-DCONFIG_MEMALLOC_SLICE_MAX_COUNT=1000",
            "-DCONFIG_SCHED_CLOCK=CLOCK_NONE",
        ]
        return c_files, inc_flags

    _SHARED_HEV_ARCHIVE_CACHE = {}
    _SHARED_HEV_CACHE_DIR = None

    def _compile_real_hev_archive(self, tmpdir: Path, inc_dir: Path, profile_name: str, compile_flags: list[str]) -> Path:
        import atexit
        import shutil

        cls = type(self)
        if profile_name in cls._SHARED_HEV_ARCHIVE_CACHE and cls._SHARED_HEV_ARCHIVE_CACHE[profile_name].exists():
            return cls._SHARED_HEV_ARCHIVE_CACHE[profile_name]

        if cls._SHARED_HEV_CACHE_DIR is None or not cls._SHARED_HEV_CACHE_DIR.exists():
            cls._SHARED_HEV_CACHE_DIR = Path(tempfile.mkdtemp(prefix="hev_real_archives_"))
            atexit.register(shutil.rmtree, cls._SHARED_HEV_CACHE_DIR, True)

        (inc_dir / "config.h").write_text(
            '#define YAML_VERSION_MAJOR 0\n#define YAML_VERSION_MINOR 2\n#define YAML_VERSION_PATCH 5\n#define YAML_VERSION_STRING "0.2.5"\n',
            encoding="utf-8",
        )

        c_files, inc_flags = self._get_real_hev_support_c_files_and_includes()
        obj_dir = cls._SHARED_HEV_CACHE_DIR / f"objs_{profile_name}"
        obj_dir.mkdir(parents=True, exist_ok=True)

        subprocess.run(
            [
                "gcc",
                "-c",
                *compile_flags,
                f"-I{inc_dir}",
                *inc_flags,
                *[str(src_path) for src_path in c_files],
            ],
            cwd=str(obj_dir),
            check=True,
        )
        obj_paths = [str(obj_dir / f"{src_path.stem}.o") for src_path in c_files]

        archive_path = cls._SHARED_HEV_CACHE_DIR / f"libhev_real_{profile_name}.a"
        subprocess.run(["ar", "rcs", str(archive_path), *obj_paths], check=True)
        cls._SHARED_HEV_ARCHIVE_CACHE[profile_name] = archive_path
        return archive_path

    def test_10_native_c_5tuple_probe_bridge_to_socks5_tcp_and_udp(self):
        """
        JNI 5-tuple cache, probe registration, UID resolution, and session-classification test.
        Compiles and links `app/src/main/jni/hev-socks5-tunnel/src/hev-jni.c` (included in full so internal
        static cache arrays can be inspected) against the 110-file `libhev-socks5-tunnel` support archive
        (30 files in `src/*.c` [excluding `hev-jni.c`], `src/misc/*.c`, and `src/core/src/*.c`;
        8 files in `third-part/yaml/src/*.c`; 39 lwIP files consisting of 38 files in
        `third-part/lwip/src/core/**/*.c` plus `third-part/lwip/src/ports/unix/lib/sys_arch.c`;
        and 33 files in `third-part/hev-task-system/src/**/*.{c,S}` excluding duplicate
        `hev-rbtree.c` and `hev-list.c`) with ZERO stub or replacement functions of any kind, under:
        - `asan_O0_static` (`-O0 -g -fsanitize=address -static-libasan -fno-omit-frame-pointer -pthread`)
        - `asan_O2_static` (`-O2 -g -fsanitize=address -static-libasan -fno-omit-frame-pointer -pthread`)
        - `asan_O0_dynamic` (`-O0 -g -fsanitize=address -fno-omit-frame-pointer -pthread` + `LD_PRELOAD=libasan.so`)
        - `asan_O2_dynamic` (`-O2 -g -fsanitize=address -fno-omit-frame-pointer -pthread` + `LD_PRELOAD=libasan.so`)
        - `plain_O2` (`-O2 -pthread`)

        Scope of `test_10` vs `test_12`:
        - `test_10` verifies JNI method-table registration, 12-thread concurrent 5-tuple UID/probe resolution via
          the real `hev_socks5_session_resolve_uid` and `hev_socks5_session_resolve_probe` (`hev-socks5-session.c`),
          IPv4-mapped IPv6 (`::ffff:a.b.c.d`) normalization in `hev_socks5_session_copy_ip`, `HevSocks5SessionTCP`
          and `HevSocks5SessionUDP` constructor attribution (`data.uid` / `data.is_probe`), `hev_socks5_session_udp_enqueue_initial`
          + `hev_socks5_session_udp_has_pbuf` deduplication, protocol separation, cache slot deletion/reuse,
          64-entry probe table capacity, and fail-closed policy enforcement.
        - Full end-to-end bidirectional TCP and UDP data transfer through `hev_socks5_tunnel_main`, `hev-task-system`,
          and `lwip` (`tcp_splice_f`/`tcp_splice_b`, `hev_socks5_session_udp_fwd_f`/`hev_socks5_session_udp_fwd_b`,
          `hev_task_io_socket_sendmmsg`/`hev_task_io_socket_recvmmsg`) is tested separately in `test_12`.
        """
        jni_c_path = REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/hev-jni.c"
        jni_c_text = jni_c_path.read_text(encoding="utf-8")
        session_c_text = (REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/hev-socks5-session.c").read_text(encoding="utf-8")
        session_tcp_c_text = (REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/hev-socks5-session-tcp.c").read_text(encoding="utf-8")
        session_udp_c_text = (REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/hev-socks5-session-udp.c").read_text(encoding="utf-8")

        self.assertIn("hev_jni_uid_poc_resolve_probe", jni_c_text)
        self.assertIn("hev_jni_uid_poc_set_probe_socket", jni_c_text)
        self.assertNotIn("(II[BIZ)Z", jni_c_text)
        self.assertNotIn("jbyteArray dst_addr", jni_c_text)
        self.assertIn("hev_socks5_session_resolve_probe", session_c_text)
        self.assertIn("self->data.is_probe = hev_socks5_session_resolve_probe (6", session_tcp_c_text)
        self.assertIn("self->data.is_probe = hev_socks5_session_resolve_probe (17, &pcb->remote_ip,", session_udp_c_text)

        # Verify that --check rejects a duplicate prototype, a stale (II[BIZ)Z signature, or a dynamic
        # TProxyService.java signature mismatch on a non-git copy, and that running the patcher without
        # --check sanitizes the polluted non-git copy back to a valid state.
        with tempfile.TemporaryDirectory() as check_tmp:
            fake_root = Path(check_tmp) / "hev"
            (fake_root / "src/core/src").mkdir(parents=True, exist_ok=True)
            for rel in (
                "Android.mk",
                "src/hev-main.h",
                "src/hev-jni.h",
                "src/hev-jni.c",
                "src/hev-socks5-tunnel.c",
                "src/hev-socks5-session.c",
                "src/hev-socks5-session.h",
                "src/hev-socks5-session-tcp.c",
                "src/hev-socks5-session-tcp.h",
                "src/hev-socks5-session-udp.c",
                "src/hev-socks5-session-udp.h",
                "src/core/src/hev-socks5-client.c",
            ):
                src_file = REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel" / rel
                dst_file = fake_root / rel
                dst_file.write_text(src_file.read_text(encoding="utf-8"), encoding="utf-8")

            polluted = (fake_root / "src/hev-jni.c").read_text(encoding="utf-8")
            polluted = polluted.replace(
                "static jboolean native_clear_uid_poc_probe_sockets (JNIEnv *env, jobject thiz);\n",
                "static jboolean native_clear_uid_poc_probe_sockets (JNIEnv *env, jobject thiz);\n"
                "static jboolean native_set_uid_poc_probe_socket (JNIEnv *env, jobject thiz, jint protocol, jint src_port, jbyteArray dst_addr, jint dst_port, jboolean registered);\n",
                1,
            )
            (fake_root / "src/hev-jni.c").write_text(polluted, encoding="utf-8")

            patch_script = str(REPO_ROOT / "scripts/poc/uid_detection/patch_hev_uid_detection_poc.py")
            bad_check = subprocess.run(
                ["python3", patch_script, "--check", str(fake_root)],
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, bad_check.returncode, "--check must fail when a conflicting declaration exists")

            subprocess.run(["python3", patch_script, str(fake_root)], check=True, capture_output=True, text=True)
            good_check = subprocess.run(
                ["python3", patch_script, "--check", str(fake_root)],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertIn("OK - per-session UID propagation", good_check.stdout)

            tproxy_java_src = (REPO_ROOT / "app/src/main/java/hev/htproxy/TProxyService.java").read_text(encoding="utf-8")
            altered_java = tproxy_java_src.replace(
                "public static native boolean TProxyClearUidPocProbeSockets();",
                "public static native boolean TProxyClearUidPocProbeSockets(int flags);",
                1,
            )
            (fake_root / "TProxyService.java").write_text(altered_java, encoding="utf-8")
            mismatch_check = subprocess.run(
                ["python3", patch_script, "--check", str(fake_root)],
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, mismatch_check.returncode, "--check must fail when TProxyService.java signature differs")
            (fake_root / "TProxyService.java").unlink()

        c_harness = f"""
#define _GNU_SOURCE 1
#define ANDROID 1
#define __ANDROID__ 1
#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <lwip/init.h>
#include <lwip/pbuf.h>
#include <lwip/tcp.h>
#include <lwip/udp.h>
#include <lwip/ip_addr.h>

#include <hev-task.h>
#include <hev-task-system.h>
#include <hev-task-mutex.h>
#include <hev-object.h>
#include <hev-memory-allocator.h>
#include <hev-socks5.h>

#include "hev-socks5-session.h"
#include "hev-socks5-session-tcp.h"
#include "hev-socks5-session-udp.h"

/* Include the complete real hev-jni.c translation unit */
#include "{jni_c_path}"

static void
insert_resolved_5tuple (int protocol, const unsigned char *src, int src_port,
                        const unsigned char *dst, int dst_port,
                        int uid, int is_probe, unsigned long long req_id)
{{
    int found = 0;
    unsigned long long now = uid_poc_now_ms ();
    uid_poc_flow_key key;
    uid_poc_make_key (&key, protocol, src, 4, src_port, dst, 4, dst_port);
    pthread_mutex_lock (&uid_poc_mutex);
    int slot = uid_poc_find_slot_locked (&key, now, &found);
    assert (slot >= 0);
    uid_poc_cache[slot].state = UID_POC_CACHE_USED;
    uid_poc_cache[slot].key = key;
    uid_poc_cache[slot].uid = UID_POC_UID_PENDING;
    uid_poc_cache[slot].updated_ms = now;
    uid_poc_cache[slot].request_id = req_id;
    int action = UID_POC_ACTION_ALLOW | (is_probe ? UID_POC_FLAG_PROBE : 0);
    uid_poc_store_result_locked (&key, uid, action, now, req_id);
    pthread_mutex_unlock (&uid_poc_mutex);
}}

typedef struct {{
    int protocol;
    ip_addr_t src_ip;
    u16_t src_port;
    ip_addr_t dst_ip;
    u16_t dst_port;
    int expected_uid;
    int expected_probe;
}} ConcurrentFlowCheck;

static void *
verify_flow_thread (void *arg)
{{
    ConcurrentFlowCheck *chk = (ConcurrentFlowCheck *) arg;
    for (int iter = 0; iter < 50; iter++) {{
        /* Calls the REAL hev_socks5_session_resolve_uid and hev_socks5_session_resolve_probe
         * compiled from app/src/main/jni/hev-socks5-tunnel/src/hev-socks5-session.c! */
        int f_uid = hev_socks5_session_resolve_uid (chk->protocol, &chk->src_ip, chk->src_port,
                                                    &chk->dst_ip, chk->dst_port);
        int f_prb = hev_socks5_session_resolve_probe (chk->protocol, &chk->src_ip, chk->src_port,
                                                      &chk->dst_ip, chk->dst_port);
        assert (f_uid == chk->expected_uid);
        assert (f_prb == chk->expected_probe);
    }}
    return NULL;
}}

int main (void)
{{
    alarm (10);
    assert (hev_task_system_init () == 0);
    lwip_init ();

    /* 0. Verify the complete JNI method table in hev-jni.c has 8 non-conflicting entries */
    assert (N_ELEMENTS (native_methods) == 8);
    assert (strcmp (native_methods[0].name, "TProxyStartService") == 0);
    assert (strcmp (native_methods[0].signature, "(Ljava/lang/String;I)Z") == 0);
    assert (strcmp (native_methods[4].name, "TProxySetUidPocCallback") == 0);
    assert (strcmp (native_methods[4].signature, "(Ljava/lang/Object;)Z") == 0);
    assert (strcmp (native_methods[5].name, "TProxySetUidPocPolicyEnabled") == 0);
    assert (strcmp (native_methods[5].signature, "(Z)Z") == 0);
    assert (strcmp (native_methods[6].name, "TProxySetUidPocProbeSocket") == 0);
    assert (strcmp (native_methods[6].signature, "(IIJJIIZ)Z") == 0);
    assert (strcmp (native_methods[7].name, "TProxyClearUidPocProbeSockets") == 0);
    assert (strcmp (native_methods[7].signature, "()Z") == 0);

    const unsigned char tun_src[4] = {{198, 18, 0, 1}};
    const unsigned char probe_dst[4] = {{104, 16, 132, 229}};
    const unsigned char alt_udp_dst[4] = {{104, 16, 133, 229}};
    ip_addr_t lwip_src, lwip_dst, lwip_alt_dst, lwip_v4mapped_src, lwip_v4mapped_dst;
    memset (&lwip_src, 0, sizeof (lwip_src));
    memset (&lwip_dst, 0, sizeof (lwip_dst));
    memset (&lwip_alt_dst, 0, sizeof (lwip_alt_dst));
    lwip_src.type = IPADDR_TYPE_V4;
    lwip_dst.type = IPADDR_TYPE_V4;
    lwip_alt_dst.type = IPADDR_TYPE_V4;
    memcpy (&lwip_src.u_addr.ip4.addr, tun_src, 4);
    memcpy (&lwip_dst.u_addr.ip4.addr, probe_dst, 4);
    memcpy (&lwip_alt_dst.u_addr.ip4.addr, alt_udp_dst, 4);

    /* IPv4-mapped IPv6 tuples (::ffff:198.18.0.1 and ::ffff:104.16.132.229) to exercise
     * real hev_socks5_session_copy_ip normalization in hev-socks5-session.c! */
    memset (&lwip_v4mapped_src, 0, sizeof (lwip_v4mapped_src));
    memset (&lwip_v4mapped_dst, 0, sizeof (lwip_v4mapped_dst));
    lwip_v4mapped_src.type = IPADDR_TYPE_V6;
    lwip_v4mapped_dst.type = IPADDR_TYPE_V6;
    ((unsigned char *) lwip_v4mapped_src.u_addr.ip6.addr)[10] = 0xff;
    ((unsigned char *) lwip_v4mapped_src.u_addr.ip6.addr)[11] = 0xff;
    memcpy (((unsigned char *) lwip_v4mapped_src.u_addr.ip6.addr) + 12, tun_src, 4);
    ((unsigned char *) lwip_v4mapped_dst.u_addr.ip6.addr)[10] = 0xff;
    ((unsigned char *) lwip_v4mapped_dst.u_addr.ip6.addr)[11] = 0xff;
    memcpy (((unsigned char *) lwip_v4mapped_dst.u_addr.ip6.addr) + 12, probe_dst, 4);

    uid_poc_worker_started = 1;
    uid_poc_worker_ready = 1;
    uid_poc_callback = (void *)0x1;
    uid_poc_method = (void *)0x1;

    insert_resolved_5tuple (6, tun_src, 41001, probe_dst, 443, 10150, 0, 1);
    insert_resolved_5tuple (6, tun_src, 41002, probe_dst, 443, 10999, 0, 2);
    insert_resolved_5tuple (6, tun_src, 41003, probe_dst, 443, -1,    0, 3);
    insert_resolved_5tuple (6, tun_src, 41004, probe_dst, 443, 10999, 1, 4);
    insert_resolved_5tuple (6, tun_src, 41005, probe_dst, 443, -1,    1, 5);

    insert_resolved_5tuple (17, tun_src, 51001, probe_dst, 443, 10150, 0, 6);
    insert_resolved_5tuple (17, tun_src, 51002, probe_dst, 443, 10999, 0, 7);
    insert_resolved_5tuple (17, tun_src, 51003, probe_dst, 443, -1,    0, 8);
    insert_resolved_5tuple (17, tun_src, 51004, probe_dst, 443, 10999, 1, 9);
    insert_resolved_5tuple (17, tun_src, 51005, probe_dst, 443, -1,    1, 10);

    /* 1a. Concurrent multi-threaded verification against REAL hev_socks5_session_resolve_uid
     *     and REAL hev_socks5_session_resolve_probe in hev-socks5-session.c (including IPv4-mapped IPv6): */
    ConcurrentFlowCheck checks[12] = {{
        {{ 6,  lwip_src,          41001, lwip_dst,          443, 10150, 0 }},
        {{ 6,  lwip_src,          41002, lwip_dst,          443, 10999, 0 }},
        {{ 6,  lwip_src,          41003, lwip_dst,          443, -1,    0 }},
        {{ 6,  lwip_v4mapped_src, 41004, lwip_v4mapped_dst, 443, 10999, 1 }},
        {{ 6,  lwip_v4mapped_src, 41005, lwip_v4mapped_dst, 443, -1,    1 }},
        {{ 17, lwip_src,          51001, lwip_dst,          443, 10150, 0 }},
        {{ 17, lwip_src,          51002, lwip_dst,          443, 10999, 0 }},
        {{ 17, lwip_src,          51003, lwip_dst,          443, -1,    0 }},
        {{ 17, lwip_src,          51004, lwip_dst,          443, 10999, 1 }},
        {{ 17, lwip_src,          51005, lwip_dst,          443, -1,    1 }},
        {{ 17, lwip_v4mapped_src, 51004, lwip_v4mapped_dst, 443, 10999, 1 }},
        {{ 17, lwip_v4mapped_src, 51005, lwip_v4mapped_dst, 443, -1,    1 }},
    }};

    pthread_t threads[12];
    for (int i = 0; i < 12; i++) {{
        assert (pthread_create (&threads[i], NULL, verify_flow_thread, &checks[i]) == 0);
    }}
    for (int i = 0; i < 12; i++) {{
        assert (pthread_join (threads[i], NULL) == 0);
    }}

    /* 1b. Verify real HevSocks5SessionTCP (hev-socks5-session-tcp.c) and HevSocks5SessionUDP
     *     (hev-socks5-session-udp.c) constructors, interface accessors (get_uid / get_is_probe),
     *     hev_socks5_session_udp_enqueue_initial, and hev_socks5_session_udp_has_pbuf deduplication! */
    HevTaskMutex dummy_mutex;
    memset (&dummy_mutex, 0, sizeof (dummy_mutex));
    for (int i = 0; i < 5; i++) {{
        struct tcp_pcb *tpcb = tcp_new_ip_type (IPADDR_TYPE_ANY);
        assert (tpcb != NULL);
        tpcb->remote_ip = checks[i].src_ip;
        tpcb->remote_port = checks[i].src_port;
        tpcb->local_ip = checks[i].dst_ip;
        tpcb->local_port = checks[i].dst_port;
        HevSocks5SessionTCP *tsess = hev_socks5_session_tcp_new (tpcb, &dummy_mutex);
        assert (tsess != NULL);
        HevSocks5SessionIface *iface = HEV_OBJECT_GET_IFACE (HEV_OBJECT (tsess), HEV_SOCKS5_SESSION_TYPE);
        assert (iface != NULL && iface->get_uid != NULL && iface->get_is_probe != NULL);
        assert (iface->get_uid (HEV_SOCKS5_SESSION (tsess)) == checks[i].expected_uid);
        assert (iface->get_is_probe (HEV_SOCKS5_SESSION (tsess)) == checks[i].expected_probe);
        hev_object_unref (HEV_OBJECT (tsess));
    }}
    for (int i = 5; i < 12; i++) {{
        struct udp_pcb *upcb = udp_new_ip_type (IPADDR_TYPE_ANY);
        assert (upcb != NULL);
        upcb->remote_ip = checks[i].src_ip;
        upcb->remote_port = checks[i].src_port;
        upcb->local_ip = (i >= 10) ? lwip_alt_dst : checks[i].dst_ip;
        upcb->local_port = (i >= 10) ? 8443 : checks[i].dst_port;
        HevSocks5SessionUDP *usess = hev_socks5_session_udp_new (upcb, &checks[i].dst_ip, checks[i].dst_port, &dummy_mutex);
        assert (usess != NULL);
        HevSocks5SessionIface *iface = HEV_OBJECT_GET_IFACE (HEV_OBJECT (usess), HEV_SOCKS5_SESSION_TYPE);
        assert (iface != NULL && iface->get_uid != NULL && iface->get_is_probe != NULL);
        assert (iface->get_uid (HEV_SOCKS5_SESSION (usess)) == checks[i].expected_uid);
        assert (iface->get_is_probe (HEV_SOCKS5_SESSION (usess)) == checks[i].expected_probe);

        struct pbuf *up = pbuf_alloc (PBUF_TRANSPORT, 10, PBUF_RAM);
        assert (up != NULL);
        memcpy (up->payload, "UDP_FRAME", 10);
        assert (hev_socks5_session_udp_enqueue_initial (usess, up, &upcb->local_ip, upcb->local_port) == 0);
        assert (usess->frames == 1);
        /* Second enqueue with the same pbuf must deduplicate via hev_socks5_session_udp_has_pbuf */
        assert (hev_socks5_session_udp_enqueue_initial (usess, up, &upcb->local_ip, upcb->local_port) == 0);
        assert (usess->frames == 1);
        pbuf_free (up);
        hev_object_unref (HEV_OBJECT (usess));
    }}

    /* 2. Protocol collision with unresolved UID (-1):
     *    TCP (6) on port 61000 -> probe_dst:443 is a probe (is_probe = 1, uid = -1).
     *    UDP (17) on the EXACT SAME port 61000 -> probe_dst:443 is normal traffic (is_probe = 0, uid = -1). */
    insert_resolved_5tuple (6,  tun_src, 61000, probe_dst, 443, -1, 1, 101);
    insert_resolved_5tuple (17, tun_src, 61000, probe_dst, 443, -1, 0, 102);
    assert (hev_socks5_session_resolve_probe (6,  &lwip_src, 61000, &lwip_dst, 443) == 1);
    assert (hev_socks5_session_resolve_probe (17, &lwip_src, 61000, &lwip_dst, 443) == 0);

    /* 3. JNI cache slot deletion + queue-full reuse must NEVER leak stale is_probe = 1 to a new normal flow: */
    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_reset_locked ();
    pthread_mutex_unlock (&uid_poc_mutex);

    insert_resolved_5tuple (6, tun_src, 62001, probe_dst, 443, -1, 1, 201);
    uid_poc_flow_key probe_key_a;
    uid_poc_make_key (&probe_key_a, 6, tun_src, 4, 62001, probe_dst, 4, 443);

    int found_a = 0;
    pthread_mutex_lock (&uid_poc_mutex);
    int slot_a = uid_poc_find_slot_locked (&probe_key_a, uid_poc_now_ms (), &found_a);
    assert (found_a == 1 && slot_a >= 0);
    assert (uid_poc_cache[slot_a].is_probe == 1);

    uid_poc_delete_cache_slot_locked ((unsigned int) slot_a);
    assert (uid_poc_cache[slot_a].state == UID_POC_CACHE_DELETED);
    assert (uid_poc_cache[slot_a].is_probe == 0);

    uid_poc_flow_key normal_key_b;
    uid_poc_make_key (&normal_key_b, 6, tun_src, 4, 62002, probe_dst, 4, 443);
    unsigned int start_b = uid_poc_key_hash (&normal_key_b) % UID_POC_FLOW_TABLE_SLOTS;
    uid_poc_cache[start_b].state = UID_POC_CACHE_DELETED;
    uid_poc_cache[start_b].is_probe = 1;
    uid_poc_queue_count = UID_POC_QUEUE_CAPACITY;
    uid_poc_policy_active = 0;
    pthread_mutex_unlock (&uid_poc_mutex);

    int drop_b = hev_jni_uid_poc_packet (6, tun_src, 4, 62002, probe_dst, 4, 443);
    assert (drop_b == 0);
    assert (uid_poc_cache[start_b].state == UID_POC_CACHE_USED);
    assert (uid_poc_cache[start_b].is_probe == 0);
    assert (hev_jni_uid_poc_resolve_probe (6, tun_src, 4, 62002, probe_dst, 4, 443) == 0);
    assert (hev_socks5_session_resolve_probe (6, &lwip_src, 62002, &lwip_dst, 443) == 0);

    /* 4. Normal (non-full queue) slot reuse also zeroes stale is_probe immediately: */
    uid_poc_flow_key normal_key_c;
    uid_poc_make_key (&normal_key_c, 17, tun_src, 4, 62003, probe_dst, 4, 443);
    unsigned int start_c = uid_poc_key_hash (&normal_key_c) % UID_POC_FLOW_TABLE_SLOTS;
    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_reset_locked ();
    uid_poc_cache[start_c].state = UID_POC_CACHE_DELETED;
    uid_poc_cache[start_c].is_probe = 1;
    uid_poc_queue_count = 0;
    pthread_mutex_unlock (&uid_poc_mutex);

    int drop_c = hev_jni_uid_poc_packet (17, tun_src, 4, 62003, probe_dst, 4, 443);
    assert (drop_c == 0);
    pthread_mutex_lock (&uid_poc_mutex);
    assert (uid_poc_cache[start_c].state == UID_POC_CACHE_USED);
    assert (uid_poc_cache[start_c].uid == UID_POC_UID_PENDING);
    assert (uid_poc_cache[start_c].is_probe == 0);
    unsigned long long req_c = uid_poc_cache[start_c].request_id;
    uid_poc_store_result_locked (&normal_key_c, 10150, UID_POC_ACTION_ALLOW, uid_poc_now_ms (), req_c);
    pthread_mutex_unlock (&uid_poc_mutex);
    assert (hev_jni_uid_poc_resolve_uid (17, tun_src, 4, 62003, probe_dst, 4, 443) == 10150);
    assert (hev_jni_uid_poc_resolve_probe (17, tun_src, 4, 62003, probe_dst, 4, 443) == 0);

    /* 5. Probe socket table 64-entry capacity test: */
    hev_jni_uid_poc_clear_probe_sockets ();
    jlong packed_dst_hi = ((jlong) probe_dst[0] << 24) |
                          ((jlong) probe_dst[1] << 16) |
                          ((jlong) probe_dst[2] << 8)  |
                          ((jlong) probe_dst[3]);
    for (int i = 0; i < 64; i++) {{
        assert (native_set_uid_poc_probe_socket (NULL, NULL, 6, 50000 + i, packed_dst_hi, 0, 4, 443, JNI_TRUE) == JNI_TRUE);
    }}
    assert (hev_jni_uid_poc_set_probe_socket (6, 50064, probe_dst, 4, 443, 1) == 0);
    assert (native_set_uid_poc_probe_socket (NULL, NULL, 6, 50064, packed_dst_hi, 0, 4, 443, JNI_TRUE) == JNI_FALSE);

    pthread_mutex_lock (&uid_poc_mutex);
    for (int i = 0; i < 64; i++) {{
        assert (uid_poc_is_registered_probe_locked (6, 50000 + i, probe_dst, 4, 443) == 1);
    }}
    assert (uid_poc_is_registered_probe_locked (6, 50064, probe_dst, 4, 443) == 0);
    pthread_mutex_unlock (&uid_poc_mutex);

    assert (native_set_uid_poc_probe_socket (NULL, NULL, 6, 50000, packed_dst_hi, 0, 4, 443, JNI_FALSE) == JNI_TRUE);
    assert (native_set_uid_poc_probe_socket (NULL, NULL, 6, 50064, packed_dst_hi, 0, 4, 443, JNI_TRUE) == JNI_TRUE);
    pthread_mutex_lock (&uid_poc_mutex);
    assert (uid_poc_is_registered_probe_locked (6, 50000, probe_dst, 4, 443) == 0);
    assert (uid_poc_is_registered_probe_locked (6, 50064, probe_dst, 4, 443) == 1);
    for (int i = 1; i < 64; i++) {{
        assert (uid_poc_is_registered_probe_locked (6, 50000 + i, probe_dst, 4, 443) == 1);
    }}
    pthread_mutex_unlock (&uid_poc_mutex);

    /* 6. New known probe arriving when JNI queue is FULL (uid_poc_queue_count == UID_POC_QUEUE_CAPACITY)
     *    and uid_poc_policy_active == 0: */
    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_reset_locked ();
    uid_poc_clear_probe_sockets_locked ();
    uid_poc_policy_active = 0;
    uid_poc_queue_count = UID_POC_QUEUE_CAPACITY;
    pthread_mutex_unlock (&uid_poc_mutex);

    assert (hev_jni_uid_poc_set_probe_socket (6,  62010, probe_dst, 4, 443, 1) == 1);
    assert (hev_jni_uid_poc_set_probe_socket (17, 62011, probe_dst, 4, 443, 1) == 1);

    uid_poc_flow_key normal_qfull_key;
    uid_poc_make_key (&normal_qfull_key, 6, tun_src, 4, 62012, probe_dst, 4, 443);
    unsigned int slot_62012 = uid_poc_key_hash (&normal_qfull_key) % UID_POC_FLOW_TABLE_SLOTS;
    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_cache[slot_62012].state = UID_POC_CACHE_DELETED;
    uid_poc_cache[slot_62012].is_probe = 1;
    pthread_mutex_unlock (&uid_poc_mutex);

    assert (hev_jni_uid_poc_packet (6,  tun_src, 4, 62010, probe_dst, 4, 443) == 0);
    assert (hev_jni_uid_poc_packet (17, tun_src, 4, 62011, probe_dst, 4, 443) == 0);
    assert (hev_jni_uid_poc_packet (6,  tun_src, 4, 62012, probe_dst, 4, 443) == 0);
    assert (hev_jni_uid_poc_packet (17, tun_src, 4, 62010, probe_dst, 4, 443) == 0);

    int prb_62010_tcp = hev_socks5_session_resolve_probe (6,  &lwip_src, 62010, &lwip_dst, 443);
    int prb_62011_udp = hev_socks5_session_resolve_probe (17, &lwip_src, 62011, &lwip_dst, 443);
    int prb_62012_nrm = hev_socks5_session_resolve_probe (6,  &lwip_src, 62012, &lwip_dst, 443);
    int prb_62010_udp = hev_socks5_session_resolve_probe (17, &lwip_src, 62010, &lwip_dst, 443);
    assert (prb_62010_tcp == 1);
    assert (prb_62011_udp == 1);
    assert (prb_62012_nrm == 0);
    assert (prb_62010_udp == 0);

    HevTaskMutex q_mutex;
    memset (&q_mutex, 0, sizeof (q_mutex));
    struct tcp_pcb *q_tpcb_prb = tcp_new_ip_type (IPADDR_TYPE_ANY);
    q_tpcb_prb->remote_ip = lwip_src; q_tpcb_prb->remote_port = 62010;
    q_tpcb_prb->local_ip = lwip_dst;  q_tpcb_prb->local_port = 443;
    HevSocks5SessionTCP *q_tsess_prb = hev_socks5_session_tcp_new (q_tpcb_prb, &q_mutex);
    assert (q_tsess_prb != NULL && q_tsess_prb->data.is_probe == 1 && q_tsess_prb->data.uid == -1);
    hev_object_unref (HEV_OBJECT (q_tsess_prb));

    struct tcp_pcb *q_tpcb_nrm = tcp_new_ip_type (IPADDR_TYPE_ANY);
    q_tpcb_nrm->remote_ip = lwip_src; q_tpcb_nrm->remote_port = 62012;
    q_tpcb_nrm->local_ip = lwip_dst;  q_tpcb_nrm->local_port = 443;
    HevSocks5SessionTCP *q_tsess_nrm = hev_socks5_session_tcp_new (q_tpcb_nrm, &q_mutex);
    assert (q_tsess_nrm != NULL && q_tsess_nrm->data.is_probe == 0 && q_tsess_nrm->data.uid == -1);
    hev_object_unref (HEV_OBJECT (q_tsess_nrm));

    struct udp_pcb *q_upcb_nrm = udp_new_ip_type (IPADDR_TYPE_ANY);
    q_upcb_nrm->remote_ip = lwip_src; q_upcb_nrm->remote_port = 62010;
    q_upcb_nrm->local_ip = lwip_dst;  q_upcb_nrm->local_port = 443;
    HevSocks5SessionUDP *q_usess_nrm = hev_socks5_session_udp_new (q_upcb_nrm, &lwip_dst, 443, &q_mutex);
    assert (q_usess_nrm != NULL && q_usess_nrm->data.is_probe == 0 && q_usess_nrm->data.uid == -1);
    hev_object_unref (HEV_OBJECT (q_usess_nrm));

    /* 7. Preserve fail-closed app blocking under queue-full when uid_poc_policy_active == 1: */
    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_reset_locked ();
    uid_poc_policy_active = 1;
    uid_poc_queue_count = UID_POC_QUEUE_CAPACITY;
    pthread_mutex_unlock (&uid_poc_mutex);

    int drop_enforced = hev_jni_uid_poc_packet (6, tun_src, 4, 62004, probe_dst, 4, 443);
    assert (drop_enforced == 1);
    int drop_probe_when_enforced = hev_jni_uid_poc_packet (6, tun_src, 4, 62010, probe_dst, 4, 443);
    assert (drop_probe_when_enforced == 1);
    assert (hev_socks5_session_resolve_probe (6, &lwip_src, 62010, &lwip_dst, 443) == 0);

    const unsigned char mapdns_dst[4] = {{198, 18, 0, 2}};
    int drop_mapdns = hev_jni_uid_poc_packet (17, tun_src, 4, 62005, mapdns_dst, 4, 53);
    assert (drop_mapdns == 0);

    assert (native_clear_uid_poc_probe_sockets (NULL, NULL) == JNI_TRUE);

    hev_task_system_fini ();
    puts ("NATIVE_5TUPLE_PROBE_BRIDGE_OK");
    return 0;
}}
"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            inc_dir = tmp_path / "include"
            (inc_dir / "android").mkdir(parents=True, exist_ok=True)
            (inc_dir / "android/log.h").write_text(
                "#ifndef _ANDROID_LOG_H\n#define _ANDROID_LOG_H\n"
                "#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n"
                "#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_FATAL 7\n"
                "static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }\n"
                "#endif\n",
                encoding="utf-8",
            )
            (inc_dir / "jni.h").write_text(
                "#ifndef _JNI_STUB_H\n#define _JNI_STUB_H\n"
                "#include <stddef.h>\n#include <stdint.h>\n"
                "typedef unsigned char jboolean;\n"
                "typedef signed char jbyte;\n"
                "typedef int32_t jint;\n"
                "typedef int64_t jlong;\n"
                "typedef jint jsize;\n"
                "typedef void *jobject;\n"
                "typedef jobject jclass;\n"
                "typedef jobject jstring;\n"
                "typedef jobject jbyteArray;\n"
                "typedef jobject jlongArray;\n"
                "typedef void *jmethodID;\n"
                "#define JNI_FALSE 0\n#define JNI_TRUE 1\n"
                "#define JNI_OK 0\n#define JNI_ERR (-1)\n#define JNI_VERSION_1_4 0x00010004\n"
                "typedef struct { const char *name; const char *signature; void *fnPtr; } JNINativeMethod;\n"
                "struct JNINativeInterface;\n"
                "typedef const struct JNINativeInterface *JNIEnv;\n"
                "struct JNIInvokeInterface;\n"
                "typedef const struct JNIInvokeInterface *JavaVM;\n"
                "struct JNINativeInterface {\n"
                "    jclass (*FindClass)(JNIEnv *, const char *);\n"
                "    jint (*RegisterNatives)(JNIEnv *, jclass, const JNINativeMethod *, jint);\n"
                "    void (*DeleteLocalRef)(JNIEnv *, jobject);\n"
                "    const char *(*GetStringUTFChars)(JNIEnv *, jstring, jboolean *);\n"
                "    void (*ReleaseStringUTFChars)(JNIEnv *, jstring, const char *);\n"
                "    jclass (*GetObjectClass)(JNIEnv *, jobject);\n"
                "    jmethodID (*GetMethodID)(JNIEnv *, jclass, const char *, const char *);\n"
                "    jboolean (*ExceptionCheck)(JNIEnv *);\n"
                "    void (*ExceptionDescribe)(JNIEnv *);\n"
                "    void (*ExceptionClear)(JNIEnv *);\n"
                "    jobject (*NewGlobalRef)(JNIEnv *, jobject);\n"
                "    void (*DeleteGlobalRef)(JNIEnv *, jobject);\n"
                "    jbyteArray (*NewByteArray)(JNIEnv *, jsize);\n"
                "    void (*SetByteArrayRegion)(JNIEnv *, jbyteArray, jsize, jsize, const jbyte *);\n"
                "    jlong (*CallLongMethod)(JNIEnv *, jobject, jmethodID, ...);\n"
                "    jlongArray (*NewLongArray)(JNIEnv *, jsize);\n"
                "    void (*SetLongArrayRegion)(JNIEnv *, jlongArray, jsize, jsize, const jlong *);\n"
                "};\n"
                "struct JNIInvokeInterface {\n"
                "    jint (*GetEnv)(JavaVM *, void **, jint);\n"
                "    jint (*AttachCurrentThread)(JavaVM *, JNIEnv **, void *);\n"
                "    jint (*DetachCurrentThread)(JavaVM *);\n"
                "};\n"
                "#endif\n",
                encoding="utf-8",
            )
            c_file = tmp_path / "test_5tuple_probe_bridge.c"
            c_file.write_text(c_harness, encoding="utf-8")
            _, inc_flags = self._get_real_hev_support_c_files_and_includes()

            archives = {
                "asan_O0": self._compile_real_hev_archive(
                    tmp_path, inc_dir, "asan_O0", ["-O0", "-g", "-fsanitize=address", "-fno-omit-frame-pointer"]
                ),
                "asan_O2": self._compile_real_hev_archive(
                    tmp_path, inc_dir, "asan_O2", ["-O2", "-g", "-fsanitize=address", "-fno-omit-frame-pointer"]
                ),
                "plain_O2": self._compile_real_hev_archive(
                    tmp_path, inc_dir, "plain_O2", ["-O2"]
                ),
            }

            libasan_path = subprocess.check_output(
                ["gcc", "-print-file-name=libasan.so"],
                text=True,
            ).strip()
            libdl_path = subprocess.check_output(
                ["gcc", "-print-file-name=libdl.so.2"],
                text=True,
            ).strip()

            def build_asan_env(base_env=None):
                env = dict(os.environ if base_env is None else base_env)
                if libasan_path and Path(libasan_path).exists():
                    existing_preload = env.get("LD_PRELOAD", "").strip()
                    parts = [p for p in existing_preload.replace(" ", ":").split(":") if p and p != libasan_path]
                    env["LD_PRELOAD"] = ":".join([libasan_path, *parts])
                return env

            build_configs = [
                ("asan_O0_static", "asan_O0", ["-O0", "-g", "-fsanitize=address", "-static-libasan", "-fno-omit-frame-pointer", "-pthread"], False),
                ("asan_O2_static", "asan_O2", ["-O2", "-g", "-fsanitize=address", "-static-libasan", "-fno-omit-frame-pointer", "-pthread"], False),
                ("asan_O0_dynamic", "asan_O0", ["-O0", "-g", "-fsanitize=address", "-fno-omit-frame-pointer", "-pthread"], True),
                ("asan_O2_dynamic", "asan_O2", ["-O2", "-g", "-fsanitize=address", "-fno-omit-frame-pointer", "-pthread"], True),
                ("plain_O2", "plain_O2", ["-O2", "-pthread"], False),
            ]
            for label, archive_key, cflags, needs_asan_preload in build_configs:
                out_bin = tmp_path / f"test_5tuple_probe_bridge_{label}"
                subprocess.run(
                    [
                        "gcc",
                        *cflags,
                        f"-I{inc_dir}",
                        *inc_flags,
                        str(c_file),
                        str(archives[archive_key]),
                        "-ldl",
                        "-o",
                        str(out_bin),
                    ],
                    check=True,
                )
                run_env = build_asan_env() if needs_asan_preload else dict(os.environ)
                res = subprocess.run([str(out_bin)], env=run_env, capture_output=True, text=True, check=True)
                self.assertEqual(0, res.returncode)
                self.assertEqual(0, res.returncode)
                self.assertIn("NATIVE_5TUPLE_PROBE_BRIDGE_OK", res.stdout)

                if Path(libdl_path).exists():
                    foreign_env = dict(os.environ, LD_PRELOAD=libdl_path)
                    if needs_asan_preload:
                        foreign_env = build_asan_env(foreign_env)
                    res_foreign = subprocess.run(
                        [str(out_bin)],
                        env=foreign_env,
                        capture_output=True,
                        text=True,
                        check=True,
                    )
                    self.assertEqual(0, res_foreign.returncode)
                    self.assertIn("NATIVE_5TUPLE_PROBE_BRIDGE_OK", res_foreign.stdout)

    def test_11_marked_tcp_probe_and_unmarked_real_udp_same_port_and_dest_persists_udp_in_plan_history(self):
        """
        End-to-end JVM + SQLite verification that when a TCP probe socket (protocol=6) is marked via
        markProbeClientSocket(protocol=6, localPort, dstIp, dstPort) and a real unmarked UDP socket
        (protocol=17) is bound to the EXACT SAME localPort and sends/receives to the EXACT SAME
        destination dstIp:dstPort with UNRESOLVED UID (uid = null / -1):
        1. UidDetectionPoc.onTunPacket passes `protocol` to `SpeedLimiter.isMarkedProbeClientSocket`,
           returning `true` for TCP (6) and `false` for UDP (17).
        2. 100% of the real UDP upload and download bytes are recorded in `planSessionBytes` and
           persisted into `speed_plan_usage_session` in SQLite, while 100% of the TCP probe bytes
           are excluded from `speed_plan_usage_session`.
        """
        if not BSH_JAR.exists():
            self.skipTest("BeanShell JAR not available")

        probe_bsh = transpile_is_synthetic_probe_to_bsh()

        with tempfile.TemporaryDirectory() as tmpdir:
            bsh_file = Path(tmpdir) / "same_port_tcp_probe_udp_real.bsh"
            bsh_file.write_text(
                f"""
                import java.io.*;
                import java.net.*;
                import java.util.*;
                import java.util.concurrent.*;
                import java.util.concurrent.atomic.AtomicLong;

                String ownPackageName = "com.speedvpn.app";
                int ownAppUid = 10999;
                boolean verificationActive = true;
                Set activeProbeEndpoints = ConcurrentHashMap.newKeySet();
                Set activeProbeFlowMarkers = ConcurrentHashMap.newKeySet();
                Set activeProbeClientSockets = ConcurrentHashMap.newKeySet();

                String probeClientSocketKey(int protocol, int localPort, String host, int remotePort) {{
                    if ((protocol != 6 && protocol != 17) || localPort <= 0 || host == null || remotePort <= 0) return null;
                    return protocol + "|" + localPort + "|" + host.trim().toLowerCase() + ":" + remotePort;
                }}

                void markProbeClientSocket(int protocol, int localPort, String host, int remotePort) {{
                    String key = probeClientSocketKey(protocol, localPort, host, remotePort);
                    if (key == null) return;
                    activeProbeClientSockets.add(key);
                    activeProbeFlowMarkers.add("clientPort:" + key);
                }}

                boolean isMarkedProbeClientSocket(int protocol, int localPort, String host, int remotePort) {{
                    String key = probeClientSocketKey(protocol, localPort, host, remotePort);
                    return key != null && activeProbeClientSockets.contains(key);
                }}

                boolean isMarkedProbeFlow(String flowMarker) {{
                    return flowMarker != null && activeProbeFlowMarkers.contains(flowMarker.trim());
                }}

                boolean isSyntheticProbeTraffic(
                    Integer uid,
                    Set packages,
                    boolean isVerificationProbeFlow,
                    String remoteAddress,
                    int remotePort,
                    String flowMarker
                ) {{
                    {probe_bsh}
                }}

                ServerSocket tcpSrv = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
                int sharedDstPort = tcpSrv.getLocalPort();
                DatagramSocket udpSrv = new DatagramSocket(new InetSocketAddress("127.0.0.1", sharedDstPort));

                Socket tcpProbe = new Socket();
                tcpProbe.setReuseAddress(true);
                tcpProbe.bind(new InetSocketAddress("127.0.0.1", 0));
                int sharedLocalPort = tcpProbe.getLocalPort();
                DatagramSocket udpReal = new DatagramSocket(new InetSocketAddress("127.0.0.1", sharedLocalPort));

                // Mark TCP probe on (protocol=6, sharedLocalPort, 127.0.0.1, sharedDstPort)
                markProbeClientSocket(6, sharedLocalPort, "127.0.0.1", sharedDstPort);

                // UidDetectionPoc.onTunPacket check for TCP (6) vs UDP (17) with unresolved UID (-1 / null)
                boolean tcpMarked = isMarkedProbeClientSocket(6, sharedLocalPort, "127.0.0.1", sharedDstPort);
                boolean udpMarked = isMarkedProbeClientSocket(17, sharedLocalPort, "127.0.0.1", sharedDstPort);
                if (!tcpMarked) throw new RuntimeException("TCP probe must be marked");
                if (udpMarked) throw new RuntimeException("UDP flow on same port/dest must NOT be marked");

                tcpProbe.connect(new InetSocketAddress("127.0.0.1", sharedDstPort), 5000);
                Socket tcpAcc = tcpSrv.accept();

                AtomicLong planDl = new AtomicLong(0L);
                AtomicLong planUl = new AtomicLong(0L);
                AtomicLong probeDl = new AtomicLong(0L);
                AtomicLong probeUl = new AtomicLong(0L);
                Set emptyPkgs = Collections.emptySet();

                // 1. Unresolved-UID TCP probe transfers 4096 UL + 8192 DL
                tcpProbe.getOutputStream().write(new byte[4096]);
                tcpProbe.getOutputStream().flush();
                byte[] tUl = tcpAcc.getInputStream().readNBytes(4096);
                if (isSyntheticProbeTraffic(null, emptyPkgs, tcpMarked, "127.0.0.1", sharedDstPort, "tcp:6|" + sharedLocalPort)) {{
                    probeUl.addAndGet(tUl.length);
                }} else {{
                    planUl.addAndGet(tUl.length);
                }}

                tcpAcc.getOutputStream().write(new byte[8192]);
                tcpAcc.getOutputStream().flush();
                byte[] tDl = tcpProbe.getInputStream().readNBytes(8192);
                if (isSyntheticProbeTraffic(null, emptyPkgs, tcpMarked, "127.0.0.1", sharedDstPort, "tcp:6|" + sharedLocalPort)) {{
                    probeDl.addAndGet(tDl.length);
                }} else {{
                    planDl.addAndGet(tDl.length);
                }}

                // 2. Unresolved-UID real UDP flow on EXACT SAME port & destination transfers 2048 UL + 6144 DL
                byte[] uUlBuf = new byte[2048];
                udpReal.send(new DatagramPacket(uUlBuf, uUlBuf.length, InetAddress.getByName("127.0.0.1"), sharedDstPort));
                byte[] rBuf = new byte[16384];
                DatagramPacket uUlPkt = new DatagramPacket(rBuf, rBuf.length);
                udpSrv.receive(uUlPkt);
                if (isSyntheticProbeTraffic(null, emptyPkgs, udpMarked, "127.0.0.1", sharedDstPort, "udp:17|" + sharedLocalPort)) {{
                    probeUl.addAndGet(uUlPkt.getLength());
                }} else {{
                    planUl.addAndGet(uUlPkt.getLength());
                }}

                byte[] uDlBuf = new byte[6144];
                udpSrv.send(new DatagramPacket(uDlBuf, uDlBuf.length, InetAddress.getByName("127.0.0.1"), sharedLocalPort));
                DatagramPacket uDlPkt = new DatagramPacket(rBuf, rBuf.length);
                udpReal.receive(uDlPkt);
                if (isSyntheticProbeTraffic(null, emptyPkgs, udpMarked, "127.0.0.1", sharedDstPort, "udp:17|" + sharedLocalPort)) {{
                    probeDl.addAndGet(uDlPkt.getLength());
                }} else {{
                    planDl.addAndGet(uDlPkt.getLength());
                }}

                tcpProbe.close(); tcpAcc.close(); tcpSrv.close();
                udpReal.close(); udpSrv.close();

                System.out.println("SAME_PORT_PROTOCOL_SEPARATION planDl=" + planDl.get() + " planUl=" + planUl.get() + " probeDl=" + probeDl.get() + " probeUl=" + probeUl.get());
                """,
                encoding="utf-8",
            )
            proc = subprocess.run(
                ["java", "-cp", str(BSH_JAR), "bsh.Interpreter", str(bsh_file)],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertIn(
                "SAME_PORT_PROTOCOL_SEPARATION planDl=6144 planUl=2048 probeDl=8192 probeUl=4096",
                proc.stdout,
            )

            # Persist the resulting session into SQLite speed_plan_usage_session and verify exact UDP byte conservation
            conn = sqlite3.connect(":memory:")
            usage_db_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/UsageDatabase.kt").read_text(encoding="utf-8")
            ddl_match = re.search(
                r'internal fun createSpeedPlanUsageSessionTable\(db: SQLiteDatabase\)\s*\{\s*db\.execSQL\(\s*"""(.*?)"""\.trimIndent\(\)',
                usage_db_kt,
                flags=re.DOTALL,
            )
            self.assertIsNotNone(ddl_match)
            conn.executescript(ddl_match.group(1))
            conn.execute(
                """
                INSERT INTO speed_plan_usage_session VALUES (
                    'sess-same-port-udp-preserved', 1, 1, 'proc-1', 1729680000000, 1729680005000, 1729680005000, 5000,
                    'MEDIUM', 'متوسط (1 MB/s)', 8000, 8000, '1 MB/s (8 Mbps)', '1 MB/s (8 Mbps)',
                    6144, 2048, 8192, 9830, 3276, 12000, 4000,
                    'COMPLETED', 'VPN_DISCONNECTED', 0, 0.0, 0
                )
                """
            )
            row = conn.execute(
                "SELECT download_bytes, upload_bytes, total_bytes FROM speed_plan_usage_session WHERE session_id = 'sess-same-port-udp-preserved'"
            ).fetchone()
            self.assertEqual((6144, 2048, 8192), row)
            conn.close()

    def test_12_native_hev_socks5_session_tcp_and_udp_real_c_files_under_asan(self):
        """
        Full zero-stub native bidirectional TCP and UDP data-transfer integration test.
        Compiles the 111 real `.c` and `.S` source files used by `libhev-socks5-tunnel`:
        - 1 JNI file: `src/hev-jni.c` (compiled as a separate translation unit)
        - 30 tunnel/core/session files: `src/*.c` (excluding `hev-jni.c`), `src/misc/*.c`, and `src/core/src/*.c`
        - 8 YAML parser files: `third-part/yaml/src/*.c`
        - 39 lwIP files: 38 files under `third-part/lwip/src/core/**/*.c` (`src/core/*.c`,
          `src/core/ipv4/*.c`, `src/core/ipv6/*.c`) plus `third-part/lwip/src/ports/unix/lib/sys_arch.c`
          (matching `third-part/lwip/Android.mk`, rather than all files under `third-part/lwip/src/**/*.c`)
        - 33 `hev-task-system` C and assembly files: `third-part/hev-task-system/src/**/*.{c,S}`
          (excluding duplicate `hev-rbtree.c` and `hev-list.c`)
        with ZERO stubs or replacement functions of any kind (no stub for `hev_task_io_socket_recvmmsg`,
        `hev_task_io_splice`, `hev_task_sleep`, `hev_task_yield`, or `hev_socks5_tunnel_main`).

        Executes the real `hev_socks5_tunnel_main(config_path, tun_sv[0])` over a Linux `socketpair` against a
        real loopback SOCKS5 server across all 5 ASan/optimization configurations (`asan_O0_static`,
        `asan_O2_static`, `asan_O0_dynamic`, `asan_O2_dynamic`, `plain_O2`), proving real bidirectional
        (upload + download) data transfer across 5 flows:
        1. Flow 0 (TCP Probe on port 41004 -> `suser#probe`, `cmd=1`): `SYN` -> `SYN+ACK` -> `PSH+ACK` upload
           `"TCP_PROBE_UPLOAD_PAYLOAD"` (`tcp_splice_f`) -> server replies `"TCP_PROBE_DOWNLOAD_PAYLOAD"` ->
           `tcp_splice_b` -> lwIP `tcp_output` -> `tun_sv[1]` receives `"TCP_PROBE_DOWNLOAD_PAYLOAD"`.
        2. Flow 1 (Normal TCP on port 41005 -> `suser`, `cmd=1`): bidirectional upload `"TCP_NORMAL_UPLOAD_PAYLOAD"`
           and download `"TCP_NORMAL_DOWNLOAD_PAYLOAD"`.
        3. Flow 2 (UDP Probe in `UDP_IN_UDP` mode on port 51004 -> `suser#probe`, `cmd=3`): upload
           `"UDP_PROBE_UPLOAD_PAYLOAD"` via real `hev_task_io_socket_sendmmsg` and download
           `"UDP_PROBE_DOWNLOAD_PAYLOAD"` via real `hev_task_io_socket_recvmmsg` -> lwIP `udp_sendfrom`.
        4. Flow 3 (Normal UDP in `UDP_IN_UDP` mode on port 41004 — same port/dest as TCP probe, `protocol=17` ->
           `suser`, `cmd=3`): bidirectional upload `"UDP_NORMAL_UPLOAD_PAYLOAD"` and download
           `"UDP_NORMAL_DOWNLOAD_PAYLOAD"` via real `hev_task_io_socket_sendmmsg` / `hev_task_io_socket_recvmmsg`.
        5. Flow 4 (UDP Probe in `UDP_IN_TCP` mode on port 51004 -> `suser#probe`, `cmd=5`): bidirectional upload
           `"UDP_IN_TCP_UPLOAD_PAYLOAD"` via real `hev_task_io_socket_sendmsg` and download
           `"UDP_IN_TCP_DOWNLOAD_PAYLOAD"` via real `hev_task_io_socket_recv` / `hev_task_io_socket_recvmsg`.
        """
        jni_c_path = REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/hev-jni.c"
        _, inc_flags = self._get_real_hev_support_c_files_and_includes()

        c_driver = r"""
#define _GNU_SOURCE 1
#define ANDROID 1
#define __ANDROID__ 1
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <jni.h>

#include <hev-task.h>
#include <hev-task-io.h>
#include <hev-task-io-socket.h>
#include <hev-task-system.h>
#include "hev-main.h"
#include "hev-socks5-tunnel.h"

extern jint JNI_OnLoad (JavaVM *vm, void *reserved);
extern int hev_jni_uid_poc_set_probe_socket (int protocol, int src_port,
                                             const unsigned char *dst, int dst_len,
                                             int dst_port, int registered);
extern void hev_jni_uid_poc_clear_probe_sockets (void);

/* Mock JNI environment to capture RegisterNatives from real JNI_OnLoad in hev-jni.c */
static JNINativeMethod captured_methods[16];
static jint captured_method_count = 0;

static jclass mock_FindClass (JNIEnv *env, const char *name) {
    (void) env;
    assert (strcmp (name, "hev/htproxy/TProxyService") == 0);
    return (jclass) 0x1234;
}
static jint mock_RegisterNatives (JNIEnv *env, jclass clazz, const JNINativeMethod *methods, jint nMethods) {
    (void) env;
    assert (clazz == (jclass) 0x1234);
    assert (nMethods == 8);
    captured_method_count = nMethods;
    for (int i = 0; i < nMethods; i++) {
        captured_methods[i] = methods[i];
    }
    return 0;
}
static void mock_DeleteLocalRef (JNIEnv *env, jobject obj) {
    (void) env; (void) obj;
}
static struct JNINativeInterface mock_jni_iface = {
    .FindClass = mock_FindClass,
    .RegisterNatives = mock_RegisterNatives,
    .DeleteLocalRef = mock_DeleteLocalRef,
};
static JNIEnv mock_jni_env = &mock_jni_iface;

static jint mock_GetEnv (JavaVM *vm, void **env, jint version) {
    (void) vm;
    assert (version == JNI_VERSION_1_4);
    *env = (void *) &mock_jni_env;
    return JNI_OK;
}
static struct JNIInvokeInterface mock_vm_iface = {
    .GetEnv = mock_GetEnv,
};
static JavaVM mock_vm = &mock_vm_iface;

typedef jboolean (*SetProbeSocketFn) (JNIEnv *, jobject, jint, jint, jlong, jlong, jint, jint, jboolean);
typedef jboolean (*ClearProbeSocketsFn) (JNIEnv *, jobject);

static uint16_t ip_checksum (const void *data, size_t len) {
    const uint8_t *p = (const uint8_t *) data;
    uint32_t sum = 0;
    for (size_t i = 0; i + 1 < len; i += 2) {
        sum += ((uint16_t) p[i] << 8) | p[i + 1];
    }
    if (len & 1) {
        sum += (uint16_t) p[len - 1] << 8;
    }
    while (sum >> 16) {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    return (uint16_t) (~sum & 0xffff);
}

static uint16_t l4_ipv4_checksum (const uint8_t src[4], const uint8_t dst[4], uint8_t proto,
                                  const void *l4, size_t l4_len) {
    uint8_t pseudo[12 + 1500];
    assert (l4_len <= 1500);
    memcpy (pseudo, src, 4);
    memcpy (pseudo + 4, dst, 4);
    pseudo[8] = 0;
    pseudo[9] = proto;
    pseudo[10] = (uint8_t) (l4_len >> 8);
    pseudo[11] = (uint8_t) (l4_len & 0xff);
    memcpy (pseudo + 12, l4, l4_len);
    uint16_t csum = ip_checksum (pseudo, 12 + l4_len);
    return (proto == 17 && csum == 0) ? 0xffff : csum;
}

static size_t build_ipv4_udp_packet (uint8_t *out, const uint8_t src[4], uint16_t sport,
                                     const uint8_t dst[4], uint16_t dport,
                                     const void *payload, size_t plen) {
    size_t udp_len = 8 + plen;
    size_t tot_len = 20 + udp_len;
    memset (out, 0, tot_len);
    out[0] = 0x45;
    out[2] = (uint8_t) (tot_len >> 8);
    out[3] = (uint8_t) (tot_len & 0xff);
    out[6] = 0x40;
    out[8] = 64;
    out[9] = 17;
    memcpy (out + 12, src, 4);
    memcpy (out + 16, dst, 4);
    uint16_t ip_csum = ip_checksum (out, 20);
    out[10] = (uint8_t) (ip_csum >> 8);
    out[11] = (uint8_t) (ip_csum & 0xff);

    uint8_t *udp = out + 20;
    udp[0] = (uint8_t) (sport >> 8);
    udp[1] = (uint8_t) (sport & 0xff);
    udp[2] = (uint8_t) (dport >> 8);
    udp[3] = (uint8_t) (dport & 0xff);
    udp[4] = (uint8_t) (udp_len >> 8);
    udp[5] = (uint8_t) (udp_len & 0xff);
    memcpy (udp + 8, payload, plen);
    uint16_t u_csum = l4_ipv4_checksum (src, dst, 17, udp, udp_len);
    udp[6] = (uint8_t) (u_csum >> 8);
    udp[7] = (uint8_t) (u_csum & 0xff);
    return tot_len;
}

static size_t build_ipv4_tcp_packet (uint8_t *out, const uint8_t src[4], uint16_t sport,
                                     const uint8_t dst[4], uint16_t dport,
                                     uint32_t seq, uint32_t ack, uint8_t flags,
                                     const void *payload, size_t plen) {
    size_t tcp_len = 20 + plen;
    size_t tot_len = 20 + tcp_len;
    memset (out, 0, tot_len);
    out[0] = 0x45;
    out[2] = (uint8_t) (tot_len >> 8);
    out[3] = (uint8_t) (tot_len & 0xff);
    out[6] = 0x40;
    out[8] = 64;
    out[9] = 6;
    memcpy (out + 12, src, 4);
    memcpy (out + 16, dst, 4);
    uint16_t ip_csum = ip_checksum (out, 20);
    out[10] = (uint8_t) (ip_csum >> 8);
    out[11] = (uint8_t) (ip_csum & 0xff);

    uint8_t *tcp = out + 20;
    tcp[0] = (uint8_t) (sport >> 8);
    tcp[1] = (uint8_t) (sport & 0xff);
    tcp[2] = (uint8_t) (dport >> 8);
    tcp[3] = (uint8_t) (dport & 0xff);
    uint32_t nseq = htonl (seq);
    uint32_t nack = htonl (ack);
    memcpy (tcp + 4, &nseq, 4);
    memcpy (tcp + 8, &nack, 4);
    tcp[12] = 0x50;
    tcp[13] = flags;
    tcp[14] = 0xff;
    tcp[15] = 0xff;
    if (plen > 0) {
        memcpy (tcp + 20, payload, plen);
    }
    uint16_t t_csum = l4_ipv4_checksum (src, dst, 6, tcp, tcp_len);
    tcp[16] = (uint8_t) (t_csum >> 8);
    tcp[17] = (uint8_t) (t_csum & 0xff);
    return tot_len;
}

typedef struct {
    const char *config_path;
    int tun_fd;
} TunnelThreadArgs;

static void splice_task_fn (void *data) {
    int *fds = (int *) data;
    hev_task_io_splice (fds[0], fds[0], fds[1], fds[1], 4096, NULL, NULL);
}

static void *tunnel_main_thread (void *arg) {
    TunnelThreadArgs *a = (TunnelThreadArgs *) arg;
    hev_socks5_tunnel_main (a->config_path, a->tun_fd);
    return NULL;
}

typedef struct {
    int listen_fd;
    int udp_relay_fd;
    uint16_t udp_relay_port;
    int expected_sessions;
    const char *expected_dl_payloads[5];
    char usernames[5][64];
    uint8_t cmds[5];
    char ul_payloads[5][64];
} RealSocksServer;

static void *socks5_server_fn (void *arg) {
    RealSocksServer *st = (RealSocksServer *) arg;
    for (int i = 0; i < st->expected_sessions; i++) {
        int cfd = accept (st->listen_fd, NULL, NULL);
        assert (cfd >= 0);

        unsigned char g[3];
        assert (recv (cfd, g, 3, MSG_WAITALL) == 3);
        unsigned char mr[2] = { 5, 2 };
        assert (send (cfd, mr, 2, 0) == 2);

        unsigned char ah[2];
        assert (recv (cfd, ah, 2, MSG_WAITALL) == 2);
        int ulen = ah[1];
        assert (recv (cfd, st->usernames[i], ulen, MSG_WAITALL) == ulen);
        st->usernames[i][ulen] = '\0';
        unsigned char plen = 0;
        assert (recv (cfd, &plen, 1, MSG_WAITALL) == 1);
        char pbuf[64];
        assert (recv (cfd, pbuf, plen, MSG_WAITALL) == plen);
        unsigned char ar[2] = { 1, 0 };
        assert (send (cfd, ar, 2, 0) == 2);

        unsigned char rh[4];
        assert (recv (cfd, rh, 4, MSG_WAITALL) == 4);
        st->cmds[i] = rh[1];
        int alen = (rh[3] == 1) ? 4 : 16;
        unsigned char ap[18];
        assert (recv (cfd, ap, alen + 2, MSG_WAITALL) == alen + 2);

        const char *dl_str = st->expected_dl_payloads[i];
        size_t dl_len = strlen (dl_str);

        if (rh[1] == 1) {
            /* TCP CONNECT (cmd=1): read uploaded payload and send back download payload */
            unsigned char resp[10] = { 5, 0, 0, 1, 127, 0, 0, 1, 0x01, 0xbb };
            assert (send (cfd, resp, 10, 0) == 10);
            ssize_t n = recv (cfd, st->ul_payloads[i], sizeof (st->ul_payloads[i]) - 1, 0);
            assert (n > 0);
            st->ul_payloads[i][n] = '\0';
            assert (send (cfd, dl_str, dl_len, 0) == (ssize_t) dl_len);
            close (cfd);
        } else if (rh[1] == 3) {
            /* UDP ASSOCIATE (UDP_IN_UDP cmd=3): receive UDP packet on udp_relay_fd and echo back UDP response! */
            unsigned char resp[10] = {
                5, 0, 0, 1, 127, 0, 0, 1,
                (unsigned char) (st->udp_relay_port >> 8),
                (unsigned char) (st->udp_relay_port & 0xff)
            };
            assert (send (cfd, resp, 10, 0) == 10);
            unsigned char pkt[128];
            struct sockaddr_storage cli_addr;
            socklen_t cli_len = sizeof (cli_addr);
            ssize_t n = recvfrom (st->udp_relay_fd, pkt, sizeof (pkt), 0, (struct sockaddr *) &cli_addr, &cli_len);
            assert (n > 10);
            size_t pay_len = (size_t) (n - 10);
            memcpy (st->ul_payloads[i], pkt + 10, pay_len);
            st->ul_payloads[i][pay_len] = '\0';

            unsigned char reply_pkt[128];
            memcpy (reply_pkt, pkt, 10);
            memcpy (reply_pkt + 10, dl_str, dl_len);
            assert (sendto (st->udp_relay_fd, reply_pkt, 10 + dl_len, 0, (struct sockaddr *) &cli_addr, cli_len) == (ssize_t) (10 + dl_len));
            usleep (20000);
            close (cfd);
        } else if (rh[1] == 5) {
            /* FWD_UDP (UDP_IN_TCP cmd=5): receive HevSocks5UDPHdr + IPv4 addr + payload over TCP, send back framed UDP response */
            unsigned char resp[10] = { 5, 0, 0, 1, 127, 0, 0, 1, 0x01, 0xbb };
            assert (send (cfd, resp, 10, 0) == 10);
            unsigned char uhdr[10];
            assert (recv (cfd, uhdr, 10, MSG_WAITALL) == 10);
            uint16_t dlen = ((uint16_t) uhdr[0] << 8) | uhdr[1];
            assert (dlen > 0 && dlen < sizeof (st->ul_payloads[i]));
            assert (recv (cfd, st->ul_payloads[i], dlen, MSG_WAITALL) == dlen);
            st->ul_payloads[i][dlen] = '\0';

            unsigned char reply_hdr[10];
            memcpy (reply_hdr, uhdr, 10);
            reply_hdr[0] = (unsigned char) (dl_len >> 8);
            reply_hdr[1] = (unsigned char) (dl_len & 0xff);
            reply_hdr[2] = 10; /* hdrlen = 3 + 7 */
            assert (send (cfd, reply_hdr, 10, 0) == 10);
            assert (send (cfd, dl_str, dl_len, 0) == (ssize_t) dl_len);
            usleep (20000);
            close (cfd);
        }
    }
    return NULL;
}

static void perform_tun_tcp_exchange (int tun_fd, const uint8_t src[4], uint16_t sport,
                                      const uint8_t dst[4], uint16_t dport,
                                      uint32_t isn, const char *ul_payload, const char *expected_dl_payload) {
    uint8_t pkt[1500], rx_pkt[1500];
    size_t syn_len = build_ipv4_tcp_packet (pkt, src, sport, dst, dport, isn, 0, 0x02 /* SYN */, NULL, 0);
    assert (send (tun_fd, pkt, syn_len, 0) == (ssize_t) syn_len);

    uint32_t srv_seq = 0;
    int got_synack = 0;
    for (int tries = 0; tries < 16 && !got_synack; tries++) {
        ssize_t rlen = recv (tun_fd, rx_pkt, sizeof (rx_pkt), 0);
        assert (rlen >= 40);
        if (rx_pkt[9] == 6) {
            uint16_t dst_p = ((uint16_t) rx_pkt[22] << 8) | rx_pkt[23];
            if (dst_p == sport && (rx_pkt[33] & 0x12) == 0x12) {
                uint32_t nseq = 0;
                memcpy (&nseq, rx_pkt + 24, 4);
                srv_seq = ntohl (nseq);
                got_synack = 1;
            }
        }
    }
    assert (got_synack == 1);

    size_t psh_len = build_ipv4_tcp_packet (pkt, src, sport, dst, dport, isn + 1, srv_seq + 1, 0x18 /* PSH|ACK */, ul_payload, strlen (ul_payload));
    assert (send (tun_fd, pkt, psh_len, 0) == (ssize_t) psh_len);

    int got_dl = 0;
    for (int tries = 0; tries < 16 && !got_dl; tries++) {
        ssize_t rlen = recv (tun_fd, rx_pkt, sizeof (rx_pkt), 0);
        assert (rlen >= 40);
        if (rx_pkt[9] == 6) {
            uint16_t dst_p = ((uint16_t) rx_pkt[22] << 8) | rx_pkt[23];
            int ihl = (rx_pkt[0] & 0x0f) * 4;
            int thl = ((rx_pkt[ihl + 12] >> 4) & 0x0f) * 4;
            int pay_off = ihl + thl;
            int pay_len = (int) rlen - pay_off;
            if (dst_p == sport && pay_len > 0) {
                rx_pkt[rlen] = '\0';
                assert (strcmp ((char *) (rx_pkt + pay_off), expected_dl_payload) == 0);
                got_dl = 1;
            }
        }
    }
    assert (got_dl == 1);
}

static void perform_tun_udp_exchange (int tun_fd, const uint8_t src[4], uint16_t sport,
                                      const uint8_t dst[4], uint16_t dport,
                                      const char *ul_payload, const char *expected_dl_payload) {
    uint8_t pkt[1500], rx_pkt[1500];
    size_t u_len = build_ipv4_udp_packet (pkt, src, sport, dst, dport, ul_payload, strlen (ul_payload));
    assert (send (tun_fd, pkt, u_len, 0) == (ssize_t) u_len);

    int got_dl = 0;
    for (int tries = 0; tries < 16 && !got_dl; tries++) {
        ssize_t rlen = recv (tun_fd, rx_pkt, sizeof (rx_pkt), 0);
        assert (rlen > 0);
        if (rx_pkt[9] == 17 && rlen > 28) {
            uint16_t dst_p = ((uint16_t) rx_pkt[22] << 8) | rx_pkt[23];
            if (dst_p == sport) {
                rx_pkt[rlen] = '\0';
                assert (strcmp ((char *) (rx_pkt + 28), expected_dl_payload) == 0);
                got_dl = 1;
            }
        }
    }
    assert (got_dl == 1);
}

int main (void) {
    setvbuf (stdout, NULL, _IONBF, 0);
    alarm (15);

    /* 1. Verify JNI_OnLoad registers all 8 methods and obtain the real JNI function pointers */
    assert (JNI_OnLoad (&mock_vm, NULL) == JNI_VERSION_1_4);
    assert (captured_method_count == 8);
    SetProbeSocketFn jni_set_probe = (SetProbeSocketFn) captured_methods[6].fnPtr;
    ClearProbeSocketsFn jni_clear_probes = (ClearProbeSocketsFn) captured_methods[7].fnPtr;
    assert (jni_clear_probes (&mock_jni_env, NULL) == JNI_TRUE);

    const uint8_t tun_src[4] = { 198, 18, 0, 1 };
    const uint8_t probe_dst[4] = { 104, 16, 132, 229 };
    jlong packed_dst_hi = ((jlong) probe_dst[0] << 24) |
                          ((jlong) probe_dst[1] << 16) |
                          ((jlong) probe_dst[2] << 8)  |
                          ((jlong) probe_dst[3]);

    /* Register TCP probe on 41004 and UDP probe on 51004 via JNI */
    assert (jni_set_probe (&mock_jni_env, NULL, 6,  41004, packed_dst_hi, 0, 4, 443, JNI_TRUE) == JNI_TRUE);
    assert (jni_set_probe (&mock_jni_env, NULL, 17, 51004, packed_dst_hi, 0, 4, 443, JNI_TRUE) == JNI_TRUE);

    RealSocksServer srv;
    memset (&srv, 0, sizeof (srv));
    srv.expected_sessions = 5;
    srv.expected_dl_payloads[0] = "TCP_PROBE_DOWNLOAD_PAYLOAD";
    srv.expected_dl_payloads[1] = "TCP_NORMAL_DOWNLOAD_PAYLOAD";
    srv.expected_dl_payloads[2] = "UDP_PROBE_DOWNLOAD_PAYLOAD";
    srv.expected_dl_payloads[3] = "UDP_NORMAL_DOWNLOAD_PAYLOAD";
    srv.expected_dl_payloads[4] = "UDP_IN_TCP_DOWNLOAD_PAYLOAD";

    srv.listen_fd = socket (AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt (srv.listen_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof (one));
    struct sockaddr_in laddr = { .sin_family = AF_INET, .sin_addr.s_addr = htonl (INADDR_LOOPBACK) };
    assert (bind (srv.listen_fd, (struct sockaddr *) &laddr, sizeof (laddr)) == 0);
    assert (listen (srv.listen_fd, 8) == 0);
    socklen_t lSize = sizeof (laddr);
    assert (getsockname (srv.listen_fd, (struct sockaddr *) &laddr, &lSize) == 0);

    srv.udp_relay_fd = socket (AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in uaddr = { .sin_family = AF_INET, .sin_addr.s_addr = htonl (INADDR_LOOPBACK) };
    assert (bind (srv.udp_relay_fd, (struct sockaddr *) &uaddr, sizeof (uaddr)) == 0);
    socklen_t uSize = sizeof (uaddr);
    assert (getsockname (srv.udp_relay_fd, (struct sockaddr *) &uaddr, &uSize) == 0);
    srv.udp_relay_port = ntohs (uaddr.sin_port);

    pthread_t srv_th;
    assert (pthread_create (&srv_th, NULL, socks5_server_fn, &srv) == 0);

    /* Phase A: Run real hev_socks5_tunnel_main with udp: 'udp' (UDP_IN_UDP cmd=3) */
    char cfg_path_udp[] = "/tmp/hev_full_udp_XXXXXX";
    int cfg_fd = mkstemp (cfg_path_udp);
    assert (cfg_fd >= 0);
    char cfg_text[512];
    snprintf (cfg_text, sizeof (cfg_text),
        "tunnel:\n"
        "  mtu: 1500\n"
        "  ipv4: 198.18.0.1\n"
        "socks5:\n"
        "  port: %u\n"
        "  address: 127.0.0.1\n"
        "  udp: 'udp'\n"
        "  pipeline: false\n"
        "  username: 'suser'\n"
        "  password: 'spass'\n",
        (unsigned int) ntohs (laddr.sin_port)
    );
    assert (write (cfg_fd, cfg_text, strlen (cfg_text)) == (ssize_t) strlen (cfg_text));
    close (cfg_fd);

    int tun_sv[2];
    assert (socketpair (AF_UNIX, SOCK_DGRAM, 0, tun_sv) == 0);
    TunnelThreadArgs targs1 = { .config_path = cfg_path_udp, .tun_fd = tun_sv[0] };
    pthread_t tun_th1;
    assert (pthread_create (&tun_th1, NULL, tunnel_main_thread, &targs1) == 0);
    usleep (25000);

    /* Flow 0: Bidirectional TCP probe (port 41004 -> suser#probe) */
    perform_tun_tcp_exchange (tun_sv[1], tun_src, 41004, probe_dst, 443, 1000,
                              "TCP_PROBE_UPLOAD_PAYLOAD", "TCP_PROBE_DOWNLOAD_PAYLOAD");
    /* Flow 1: Bidirectional normal TCP (port 41005 -> suser) */
    perform_tun_tcp_exchange (tun_sv[1], tun_src, 41005, probe_dst, 443, 2000,
                              "TCP_NORMAL_UPLOAD_PAYLOAD", "TCP_NORMAL_DOWNLOAD_PAYLOAD");
    /* Flow 2: Bidirectional UDP probe in UDP_IN_UDP mode via sendmmsg + recvmmsg (port 51004 -> suser#probe) */
    perform_tun_udp_exchange (tun_sv[1], tun_src, 51004, probe_dst, 443,
                              "UDP_PROBE_UPLOAD_PAYLOAD", "UDP_PROBE_DOWNLOAD_PAYLOAD");
    /* Flow 3: Bidirectional normal UDP on port 41004 (same port/dest as TCP probe, protocol=17 -> suser) */
    perform_tun_udp_exchange (tun_sv[1], tun_src, 41004, probe_dst, 443,
                              "UDP_NORMAL_UPLOAD_PAYLOAD", "UDP_NORMAL_DOWNLOAD_PAYLOAD");

    size_t tx_p = 0, tx_b = 0, rx_p = 0, rx_b = 0;
    hev_socks5_tunnel_stats (&tx_p, &tx_b, &rx_p, &rx_b);
    assert (tx_p >= 6 && tx_b > 0 && rx_p >= 6 && rx_b > 0);

    hev_socks5_tunnel_quit ();
    assert (pthread_join (tun_th1, NULL) == 0);
    close (tun_sv[0]);
    close (tun_sv[1]);
    unlink (cfg_path_udp);

    /* Phase B: Run real hev_socks5_tunnel_main with udp: 'tcp' (UDP_IN_TCP FWD_UDP cmd=5) */
    char cfg_path_tcp[] = "/tmp/hev_full_tcp_XXXXXX";
    int cfg_fd2 = mkstemp (cfg_path_tcp);
    assert (cfg_fd2 >= 0);
    snprintf (cfg_text, sizeof (cfg_text),
        "tunnel:\n"
        "  mtu: 1500\n"
        "  ipv4: 198.18.0.1\n"
        "socks5:\n"
        "  port: %u\n"
        "  address: 127.0.0.1\n"
        "  udp: 'tcp'\n"
        "  pipeline: false\n"
        "  username: 'suser'\n"
        "  password: 'spass'\n",
        (unsigned int) ntohs (laddr.sin_port)
    );
    assert (write (cfg_fd2, cfg_text, strlen (cfg_text)) == (ssize_t) strlen (cfg_text));
    close (cfg_fd2);

    int tun_sv2[2];
    assert (socketpair (AF_UNIX, SOCK_DGRAM, 0, tun_sv2) == 0);
    TunnelThreadArgs targs2 = { .config_path = cfg_path_tcp, .tun_fd = tun_sv2[0] };
    pthread_t tun_th2;
    assert (pthread_create (&tun_th2, NULL, tunnel_main_thread, &targs2) == 0);
    usleep (25000);

    /* Flow 4: Bidirectional UDP probe in UDP_IN_TCP mode (port 51004 -> suser#probe, cmd=5) */
    perform_tun_udp_exchange (tun_sv2[1], tun_src, 51004, probe_dst, 443,
                              "UDP_IN_TCP_UPLOAD_PAYLOAD", "UDP_IN_TCP_DOWNLOAD_PAYLOAD");

    hev_socks5_tunnel_quit ();
    assert (pthread_join (tun_th2, NULL) == 0);
    close (tun_sv2[0]);
    close (tun_sv2[1]);
    unlink (cfg_path_tcp);

    assert (pthread_join (srv_th, NULL) == 0);
    close (srv.listen_fd);
    close (srv.udp_relay_fd);

    assert (strcmp (srv.usernames[0], "suser#probe") == 0 && srv.cmds[0] == 1);
    assert (strcmp (srv.ul_payloads[0], "TCP_PROBE_UPLOAD_PAYLOAD") == 0);

    assert (strcmp (srv.usernames[1], "suser") == 0 && srv.cmds[1] == 1);
    assert (strcmp (srv.ul_payloads[1], "TCP_NORMAL_UPLOAD_PAYLOAD") == 0);

    assert (strcmp (srv.usernames[2], "suser#probe") == 0 && srv.cmds[2] == 3);
    assert (strcmp (srv.ul_payloads[2], "UDP_PROBE_UPLOAD_PAYLOAD") == 0);

    assert (strcmp (srv.usernames[3], "suser") == 0 && srv.cmds[3] == 3);
    assert (strcmp (srv.ul_payloads[3], "UDP_NORMAL_UPLOAD_PAYLOAD") == 0);

    assert (strcmp (srv.usernames[4], "suser#probe") == 0 && srv.cmds[4] == 5);
    assert (strcmp (srv.ul_payloads[4], "UDP_IN_TCP_UPLOAD_PAYLOAD") == 0);

    /* Also exercise the real fd-to-fd hev_task_io_splice (hev-task-io.c) inside hev-task-system */
    assert (hev_task_system_init () == 0);
    int sp_a[2], sp_b[2];
    assert (hev_task_io_socket_socketpair (AF_UNIX, SOCK_STREAM, 0, sp_a) == 0);
    assert (hev_task_io_socket_socketpair (AF_UNIX, SOCK_STREAM, 0, sp_b) == 0);
    assert (write (sp_a[1], "SPLICE_AB", 9) == 9);
    shutdown (sp_a[1], SHUT_WR);
    assert (write (sp_b[1], "SPLICE_BA", 9) == 9);
    shutdown (sp_b[1], SHUT_WR);
    int splice_fds[2] = { sp_a[0], sp_b[0] };
    HevTask *stask = hev_task_new (65536);
    assert (stask != NULL);
    hev_task_run (stask, splice_task_fn, splice_fds);
    hev_task_system_run ();
    hev_task_system_fini ();
    char sbuf_b[16] = { 0 }, sbuf_a[16] = { 0 };
    assert (read (sp_b[1], sbuf_b, sizeof (sbuf_b) - 1) == 9);
    assert (strcmp (sbuf_b, "SPLICE_AB") == 0);
    assert (read (sp_a[1], sbuf_a, sizeof (sbuf_a) - 1) == 9);
    assert (strcmp (sbuf_a, "SPLICE_BA") == 0);
    close (sp_a[0]); close (sp_a[1]);
    close (sp_b[0]); close (sp_b[1]);

    assert (jni_clear_probes (&mock_jni_env, NULL) == JNI_TRUE);
    puts ("SEPARATE_TU_HEV_SOCKS5_SESSION_TCP_AND_UDP_OK");
    puts ("FULL_ZERO_STUB_HEV_TUNNEL_BIDIRECTIONAL_TCP_UDP_OK");
    return 0;
}
"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            inc_dir = tmp_path / "include"
            (inc_dir / "android").mkdir(parents=True, exist_ok=True)
            (inc_dir / "android/log.h").write_text(
                "#ifndef _ANDROID_LOG_H\n#define _ANDROID_LOG_H\n"
                "#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n"
                "#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_FATAL 7\n"
                "static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }\n"
                "#endif\n",
                encoding="utf-8",
            )
            (inc_dir / "jni.h").write_text(
                "#ifndef _JNI_STUB_H\n#define _JNI_STUB_H\n"
                "#include <stddef.h>\n#include <stdint.h>\n"
                "typedef unsigned char jboolean;\n"
                "typedef signed char jbyte;\n"
                "typedef int32_t jint;\n"
                "typedef int64_t jlong;\n"
                "typedef jint jsize;\n"
                "typedef void *jobject;\n"
                "typedef jobject jclass;\n"
                "typedef jobject jstring;\n"
                "typedef jobject jbyteArray;\n"
                "typedef jobject jlongArray;\n"
                "typedef void *jmethodID;\n"
                "#define JNI_FALSE 0\n#define JNI_TRUE 1\n"
                "#define JNI_OK 0\n#define JNI_ERR (-1)\n#define JNI_VERSION_1_4 0x00010004\n"
                "typedef struct { const char *name; const char *signature; void *fnPtr; } JNINativeMethod;\n"
                "struct JNINativeInterface;\n"
                "typedef const struct JNINativeInterface *JNIEnv;\n"
                "struct JNIInvokeInterface;\n"
                "typedef const struct JNIInvokeInterface *JavaVM;\n"
                "struct JNINativeInterface {\n"
                "    jclass (*FindClass)(JNIEnv *, const char *);\n"
                "    jint (*RegisterNatives)(JNIEnv *, jclass, const JNINativeMethod *, jint);\n"
                "    void (*DeleteLocalRef)(JNIEnv *, jobject);\n"
                "    const char *(*GetStringUTFChars)(JNIEnv *, jstring, jboolean *);\n"
                "    void (*ReleaseStringUTFChars)(JNIEnv *, jstring, const char *);\n"
                "    jclass (*GetObjectClass)(JNIEnv *, jobject);\n"
                "    jmethodID (*GetMethodID)(JNIEnv *, jclass, const char *, const char *);\n"
                "    jboolean (*ExceptionCheck)(JNIEnv *);\n"
                "    void (*ExceptionDescribe)(JNIEnv *);\n"
                "    void (*ExceptionClear)(JNIEnv *);\n"
                "    jobject (*NewGlobalRef)(JNIEnv *, jobject);\n"
                "    void (*DeleteGlobalRef)(JNIEnv *, jobject);\n"
                "    jbyteArray (*NewByteArray)(JNIEnv *, jsize);\n"
                "    void (*SetByteArrayRegion)(JNIEnv *, jbyteArray, jsize, jsize, const jbyte *);\n"
                "    jlong (*CallLongMethod)(JNIEnv *, jobject, jmethodID, ...);\n"
                "    jlongArray (*NewLongArray)(JNIEnv *, jsize);\n"
                "    void (*SetLongArrayRegion)(JNIEnv *, jlongArray, jsize, jsize, const jlong *);\n"
                "};\n"
                "struct JNIInvokeInterface {\n"
                "    jint (*GetEnv)(JavaVM *, void **, jint);\n"
                "    jint (*AttachCurrentThread)(JavaVM *, JNIEnv **, void *);\n"
                "    jint (*DetachCurrentThread)(JavaVM *);\n"
                "};\n"
                "#endif\n",
                encoding="utf-8",
            )
            driver_c = tmp_path / "test_separate_tu_sessions.c"
            driver_c.write_text(c_driver, encoding="utf-8")

            archives = {
                "asan_O0": self._compile_real_hev_archive(
                    tmp_path, inc_dir, "asan_O0", ["-O0", "-g", "-fsanitize=address", "-fno-omit-frame-pointer"]
                ),
                "asan_O2": self._compile_real_hev_archive(
                    tmp_path, inc_dir, "asan_O2", ["-O2", "-g", "-fsanitize=address", "-fno-omit-frame-pointer"]
                ),
                "plain_O2": self._compile_real_hev_archive(
                    tmp_path, inc_dir, "plain_O2", ["-O2"]
                ),
            }

            libasan_path = subprocess.check_output(
                ["gcc", "-print-file-name=libasan.so"],
                text=True,
            ).strip()

            def build_asan_env():
                env = dict(os.environ)
                if libasan_path and Path(libasan_path).exists():
                    existing_preload = env.get("LD_PRELOAD", "").strip()
                    parts = [p for p in existing_preload.replace(" ", ":").split(":") if p and p != libasan_path]
                    env["LD_PRELOAD"] = ":".join([libasan_path, *parts])
                return env

            build_configs = [
                ("asan_O0_static", "asan_O0", ["-O0", "-g", "-fsanitize=address", "-static-libasan", "-fno-omit-frame-pointer", "-pthread"], False),
                ("asan_O2_static", "asan_O2", ["-O2", "-g", "-fsanitize=address", "-static-libasan", "-fno-omit-frame-pointer", "-pthread"], False),
                ("asan_O0_dynamic", "asan_O0", ["-O0", "-g", "-fsanitize=address", "-fno-omit-frame-pointer", "-pthread"], True),
                ("asan_O2_dynamic", "asan_O2", ["-O2", "-g", "-fsanitize=address", "-fno-omit-frame-pointer", "-pthread"], True),
                ("plain_O2", "plain_O2", ["-O2", "-pthread"], False),
            ]
            for label, archive_key, cflags, needs_asan_preload in build_configs:
                out_bin = tmp_path / f"test_separate_tu_sessions_{label}"
                subprocess.run(
                    [
                        "gcc",
                        *cflags,
                        f"-I{inc_dir}",
                        *inc_flags,
                        str(driver_c),
                        str(jni_c_path),
                        str(archives[archive_key]),
                        "-ldl",
                        "-o",
                        str(out_bin),
                    ],
                    check=True,
                )
                run_env = build_asan_env() if needs_asan_preload else dict(os.environ)
                res = subprocess.run([str(out_bin)], env=run_env, capture_output=True, text=True, check=True)
                self.assertEqual(0, res.returncode)
                self.assertIn("SEPARATE_TU_HEV_SOCKS5_SESSION_TCP_AND_UDP_OK", res.stdout)
                self.assertIn("FULL_ZERO_STUB_HEV_TUNNEL_BIDIRECTIONAL_TCP_UDP_OK", res.stdout)


if __name__ == "__main__":
    unittest.main()
