#!/usr/bin/env python3
"""Regression tests for the Part A Speed Test and Verify Speed audit fixes."""

from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[3]


class SpeedTestAndVerifySpeedAuditFixesTest(unittest.TestCase):
    def test_speed_limit_store_stages_live_limits_and_prevents_activity_recreation_clobber(self) -> None:
        store_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedLimitStore.kt").read_text(encoding="utf-8")
        main_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/MainActivity.kt").read_text(encoding="utf-8")
        overlay_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedOverlayService.kt").read_text(encoding="utf-8")

        self.assertIn("fun stageDownload(context: Context, kbps: Long?)", store_kt)
        self.assertIn("fun stageUpload(context: Context, kbps: Long?)", store_kt)
        self.assertIn("stagedLimits?.let { return@synchronized it }", store_kt)
        self.assertIn(
            "private fun loadLocalLimits() = SpeedLimitStore.restoreToLimiterIfUninitialized(this)",
            main_kt,
        )
        self.assertIn("SpeedLimitStore.stageDownload(this, kbps)", main_kt)
        self.assertIn("SpeedLimitStore.stageUpload(this, kbps)", main_kt)
        self.assertIn("SpeedLimiter.setDownloadKbps(kbps) { SpeedLimitStore.saveDownload(this, kbps) }", overlay_kt)
        self.assertIn("SpeedLimiter.setUploadKbps(kbps) { SpeedLimitStore.saveUpload(this, kbps) }", overlay_kt)

    def test_speed_test_engine_preserves_both_phase_errors_and_passes_verification_profile_to_warmup(self) -> None:
        engine_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedTestEngine.kt").read_text(encoding="utf-8")

        self.assertIn("internal fun combinePhaseErrors(downloadError: String?, uploadError: String?): String?", engine_kt)
        self.assertIn("error = combinePhaseErrors(downloadError, uploadError),", engine_kt)
        self.assertIn("internal fun warmupBytesFor(expectedKbps: Long?, verificationProfile: Boolean = false): Long", engine_kt)
        self.assertNotIn("requestSizesFor(expectedKbps).firstOrNull()?.div(4L)", engine_kt)
        self.assertEqual(engine_kt.count("requestSizesFor(expectedKbps, verificationProfile).firstOrNull()?.div(4L)"), 2)

    def test_speed_verification_engine_waits_for_native_tunnel_stop_before_baseline(self) -> None:
        verify_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVerificationEngine.kt").read_text(encoding="utf-8")

        self.assertIn("val disconnectedForBaseline = if (!isTunnelStoppedForRestore()) {", verify_kt)
        self.assertIn("stopForVerificationRestore(context)", verify_kt)

    def test_speed_verification_policy_preserves_thresholds_and_reports_exact_headroom_multiplier(self) -> None:
        policy_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVerificationPolicy.kt").read_text(encoding="utf-8")

        self.assertIn("const val TOLERANCE = 0.20", policy_kt)
        self.assertIn("const val BASELINE_HEADROOM_MULTIPLIER = 1.20", policy_kt)
        self.assertIn("private val LOW_RATE_HEADROOM_TARGETS_KBPS = setOf(600L, 800L)", policy_kt)
        self.assertIn('val requiredLabel = if (headroomMultiplier <= 1.0) "1.0" else "1.2"', policy_kt)
        self.assertIn("سرعة الشبكة الأصلية يجب أن تبلغ $requiredLabel مرة من الخطة على الأقل.", policy_kt)

    def test_main_activity_preserves_speed_screen_state_across_tabs_and_Snapshots_plan_before_delay(self) -> None:
        main_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/MainActivity.kt").read_text(encoding="utf-8")

        self.assertIn("private val speedTestBusyState = mutableStateOf(false)", main_kt)
        self.assertIn("private val verifyBusyState = mutableStateOf(false)", main_kt)
        self.assertIn("bottomBar = { BottomBar(tab, enabled = !speedActionBusy, onTab = { tab = it }) }", main_kt)
        self.assertIn("val selectedDownloadKbps = s.downloadLimitKbps", main_kt)
        self.assertIn("val selectedUploadKbps = s.uploadLimitKbps", main_kt)
        self.assertIn("planDownloadKbps = selectedDownloadKbps,", main_kt)
        self.assertIn("planUploadKbps = selectedUploadKbps,", main_kt)
        self.assertIn(
            'SectionLabel("نتيجة فحص السرعة", "قياس سرعة الشبكة الفعلية خارج خنق VPN (لاختبار الخطة عبر النفق استخدم تحقق من السرعة)")',
            main_kt,
        )


    def test_argon_final_audit_fixes_are_present(self) -> None:
        verify_py = (REPO_ROOT / "scripts/e2e/verify_metrics.py").read_text(encoding="utf-8")
        limiter_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedLimiter.kt").read_text(encoding="utf-8")
        main_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/MainActivity.kt").read_text(encoding="utf-8")
        vpn_kt = (REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVpnService.kt").read_text(encoding="utf-8")

        self.assertIn("LOW_RATE_HEADROOM_TARGETS_KBPS = {600, 800}", verify_py)
        self.assertIn("def headroom_multiplier(expected_kbps: int) -> float:", verify_py)
        self.assertIn("if (newRate == this.bytesPerSec && newRate > 0L) {", limiter_kt)
        self.assertIn('val dl = down.toDoubleOrNull()?.let { SpeedLimiter.toKbps(it, "Mbps") }?.takeIf { it > 0L }', main_kt)
        self.assertIn('val ul = up.toDoubleOrNull()?.let { SpeedLimiter.toKbps(it, "Mbps") }?.takeIf { it > 0L }', main_kt)
        self.assertIn("initialBlockedUids.isNotEmpty() && (!BuildConfig.DEBUG || !hookDisabled)", vpn_kt)


if __name__ == "__main__":
    unittest.main()
