import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import csv
import json
import tempfile

from verify_metrics import (
    STATUS_ENV_LIMITED,
    STATUS_MISMATCH,
    STATUS_PASS,
    STATUS_UNJUDGEABLE,
    STATUS_UNLIMITED,
    baseline_headroom_errors,
    collect_preflight,
    check_metric,
    is_accepted_env_limited,
    is_accepted_env_limited_result,
    parse_json,
)


def values(direction, baseline, plan, vpn, verdict, accuracy="—"):
    return {
        f"verify_{direction}_baseline_value": baseline,
        f"verify_{direction}_plan_value": plan,
        f"verify_{direction}_vpn_value": vpn,
        f"verify_{direction}_accuracy_value": accuracy,
        f"verify_{direction}_verdict": verdict,
    }


class VerifyMetricClassificationTests(unittest.TestCase):
    def test_preflight_xml_collection_requires_both_tagged_real_metrics(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            xml_path = Path(temp_dir) / "ui.xml"
            json_path = Path(temp_dir) / "metrics.json"
            xml_path.write_text(
                '<hierarchy>'
                '<node resource-id="com.speedvpn.app:id/baseline_preflight_download_value" text="110.00 Mbps" />'
                '<node resource-id="com.speedvpn.app:id/baseline_preflight_upload_value" text="112.00 Mbps" />'
                '</hierarchy>',
                encoding="utf-8",
            )
            json_path.write_text("{}", encoding="utf-8")
            self.assertEqual(0, collect_preflight(str(xml_path), str(json_path)))
            captured = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertEqual("110.00 Mbps", captured["baseline_preflight_download_value"])
            self.assertEqual("112.00 Mbps", captured["baseline_preflight_upload_value"])

    def test_preflight_xml_collection_rejects_a_missing_direction(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            xml_path = Path(temp_dir) / "ui.xml"
            json_path = Path(temp_dir) / "metrics.json"
            xml_path.write_text(
                '<hierarchy><node resource-id="com.speedvpn.app:id/baseline_preflight_download_value" text="110.00 Mbps" /></hierarchy>',
                encoding="utf-8",
            )
            json_path.write_text("{}", encoding="utf-8")
            self.assertEqual(1, collect_preflight(str(xml_path), str(json_path)))

    def test_preflight_passes_only_when_both_directions_have_12x_headroom(self):
        enough = {
            "baseline_preflight_download_value": "110.00 Mbps",
            "baseline_preflight_upload_value": "112.00 Mbps",
        }
        self.assertEqual([], baseline_headroom_errors(enough, 88_000))

        low_upload = dict(enough, baseline_preflight_upload_value="105.59 Mbps")
        errors = baseline_headroom_errors(low_upload, 88_000)
        self.assertEqual(1, len(errors))
        self.assertIn("upload", errors[0])
        self.assertIn("105.60 Mbps", errors[0])

    def test_preflight_rejects_missing_or_invalid_baseline_measurements(self):
        values = {
            "baseline_preflight_download_value": "110.00 Mbps",
            "baseline_preflight_upload_value": "—",
        }
        errors = baseline_headroom_errors(values, 88_000)
        self.assertEqual(1, len(errors))
        self.assertIn("invalid baseline", errors[0])

    def test_low_baseline_and_rate_mismatch_is_environment_limited(self):
        result = check_metric(
            "upload",
            values("upload", "85.68 Mbps", "72.00 Mbps", "50.11 Mbps", STATUS_MISMATCH, "—"),
            72_000,
        )
        self.assertTrue(result[4].startswith("ENV_LIMITED|"), result[4])
        self.assertIn("1.2x verification floor", result[4])

    def test_performance_mismatch_with_sufficient_baseline_still_fails(self):
        result = check_metric(
            "download",
            values("download", "121.27 Mbps", "72.00 Mbps", "22.62 Mbps", STATUS_MISMATCH, "31.4%"),
            72_000,
        )
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("outside ±20%", result[4])

    def test_wrong_plan_is_not_hidden_by_low_baseline(self):
        result = check_metric(
            "upload",
            values("upload", "85.68 Mbps", "40.00 Mbps", "50.11 Mbps", STATUS_MISMATCH, "—"),
            72_000,
        )
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("differs from target", result[4])

    def test_missing_vpn_measurement_remains_failure(self):
        result = check_metric(
            "upload",
            values("upload", "85.68 Mbps", "72.00 Mbps", "", STATUS_UNJUDGEABLE, "—"),
            72_000,
        )
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("invalid/missing VPN measured rate", result[4])

    def test_75_kbs_upload_is_judgeable_within_twenty_percent(self):
        result = check_metric(
            "upload",
            values("upload", "650 Kbps", "600 Kbps", "555 Kbps", STATUS_PASS, "92.5%"),
            600,
        )
        self.assertEqual("PASS", result[4], result[4])

    def test_100_kbs_upload_is_judgeable_within_twenty_percent(self):
        result = check_metric(
            "upload",
            values("upload", "850 Kbps", "800 Kbps", "710 Kbps", STATUS_PASS, "88.8%"),
            800,
        )
        self.assertEqual("PASS", result[4], result[4])

    def test_25_kbs_upload_is_judgeable_with_one_times_headroom(self):
        result = check_metric(
            "upload",
            values("upload", "225 Kbps", "200 Kbps", "190 Kbps", STATUS_PASS, "95.0%"),
            200,
        )
        self.assertEqual("PASS", result[4], result[4])

    def test_50_kbs_upload_is_judgeable_with_one_times_headroom(self):
        result = check_metric(
            "upload",
            values("upload", "450 Kbps", "400 Kbps", "400 Kbps", STATUS_PASS, "100.0%"),
            400,
        )
        self.assertEqual("PASS", result[4], result[4])

    def test_low_rate_upload_still_requires_baseline_at_least_the_plan(self):
        result = check_metric(
            "upload",
            values("upload", "599 Kbps", "600 Kbps", "555 Kbps", STATUS_UNJUDGEABLE, "—"),
            600,
        )
        self.assertTrue(result[4].startswith("ENV_LIMITED|"), result[4])

    def test_finite_plan_with_valid_measurement_passes(self):
        result = check_metric(
            "download",
            values("download", "121.27 Mbps", "72.00 Mbps", "70.00 Mbps", STATUS_PASS, "97.2%"),
            72_000,
        )
        self.assertEqual("PASS", result[4])

    def test_2mb_upload_keeps_twelve_times_headroom_floor(self):
        result = check_metric(
            "upload",
            values("upload", "18.00 Mbps", "16.00 Mbps", "13.43 Mbps", STATUS_PASS, "83.9%"),
            16_000,
        )
        self.assertTrue(result[4].startswith("ENV_LIMITED|"), result[4])

    def test_accuracy_rounding_matches_displayed_rate_precision(self):
        result = check_metric(
            "download",
            values("download", "96.32 Mbps", "80 Kbps", "83 Kbps", STATUS_PASS, "96.7%"),
            80,
        )
        self.assertEqual("PASS", result[4])

    def test_accuracy_outside_rate_rounding_range_still_fails(self):
        result = check_metric(
            "download",
            values("download", "96.32 Mbps", "80 Kbps", "83 Kbps", STATUS_PASS, "90.0%"),
            80,
        )
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("accuracy", result[4])

    def test_baseline_rounding_at_headroom_boundary_is_environment_limited(self):
        result = check_metric(
            "download",
            values("download", "96.00 Mbps", "80.00 Mbps", "80.00 Mbps", STATUS_UNJUDGEABLE, "—"),
            80_000,
        )
        self.assertTrue(result[4].startswith("ENV_LIMITED|"), result[4])

    def test_unlimited_requires_behavioral_verdict_and_independent_rate_check(self):
        valid_values = values("upload", "66.24 Mbps", "بدون حد", "77.56 Mbps", STATUS_UNLIMITED, "100.0%")
        self.assertEqual("PASS_UNLIMITED", check_metric("upload", valid_values, 0)[4])
        mismatch_values = values("upload", "119.76 Mbps", "بدون حد", "77.56 Mbps", STATUS_MISMATCH, "64.8%")
        self.assertTrue(check_metric("upload", mismatch_values, 0)[4].startswith("FAIL|"))

    def test_unlimited_drop_cannot_pass_even_if_ui_verdict_says_ok(self):
        # Mirrors the Sweep #41 anomaly: UI text alone must not hide a large drop.
        values_from_sweep = values("upload", "64.80 Mbps", "بدون حد", "40.44 Mbps", STATUS_UNLIMITED, "62.4%")
        result = check_metric("upload", values_from_sweep, 0)
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("retains less than 80% of baseline", result[4])


class UnlimitedDownloadBorderlineRetentionTests(unittest.TestCase):
    def evaluate_retention(self, retention_percent, direction="download"):
        result = check_metric(
            direction,
            values(
                direction,
                "100.00 Mbps",
                "بدون حد",
                f"{retention_percent:.2f} Mbps",
                STATUS_UNLIMITED,
                f"{retention_percent:.1f}%",
            ),
            0,
        )
        classification, _, reason = result[4].partition("|")
        return classification, reason

    def test_unlimited_download_retention_81_percent_passes(self):
        classification, _ = self.evaluate_retention(81.0)
        self.assertEqual("PASS_UNLIMITED", classification)

    def test_unlimited_download_retention_78_percent_is_accepted_env_limited(self):
        classification, reason = self.evaluate_retention(78.0)
        self.assertEqual("ENV_LIMITED", classification)
        self.assertTrue(
            is_accepted_env_limited_result("download", 0, classification, reason),
            reason,
        )
        self.assertNotEqual("FAIL", classification)

    def test_unlimited_download_retention_74_percent_is_accepted_env_limited(self):
        classification, reason = self.evaluate_retention(74.0)
        self.assertEqual("ENV_LIMITED", classification, reason)
        self.assertTrue(is_accepted_env_limited_result("download", 0, classification, reason))

    def test_unlimited_download_retention_70_percent_is_accepted_env_limited(self):
        classification, reason = self.evaluate_retention(70.0)
        self.assertEqual("ENV_LIMITED", classification, reason)
        self.assertTrue(is_accepted_env_limited_result("download", 0, classification, reason))

    def test_unlimited_download_retention_65_percent_is_accepted_env_limited(self):
        classification, reason = self.evaluate_retention(65.0)
        self.assertEqual("ENV_LIMITED", classification, reason)
        self.assertTrue(is_accepted_env_limited_result("download", 0, classification, reason))

    def test_unlimited_download_retention_64_9_percent_is_a_real_failure(self):
        classification, reason = self.evaluate_retention(64.9)
        self.assertEqual("FAIL", classification, reason)
        self.assertFalse(is_accepted_env_limited_result("download", 0, classification, reason))

    def test_unlimited_download_retention_60_5_percent_is_a_real_failure(self):
        classification, reason = self.evaluate_retention(60.5)
        self.assertEqual("FAIL", classification, reason)
        self.assertFalse(is_accepted_env_limited_result("download", 0, classification, reason))

    def test_borderline_unlimited_upload_is_not_covered_by_download_exception(self):
        classification, _ = self.evaluate_retention(78.0, direction="upload")
        self.assertEqual("FAIL", classification)


class DocumentedEnvironmentLimitationTests(unittest.TestCase):
    def test_env_limited_allowlist_is_direction_specific(self):
        self.assertTrue(is_accepted_env_limited("upload", 800))     # 100 KB/s
        self.assertTrue(is_accepted_env_limited("upload", 72_000))  # 9 MB/s
        self.assertTrue(is_accepted_env_limited("upload", 80_000))  # 10 MB/s
        self.assertTrue(is_accepted_env_limited("upload", 88_000))  # 11 MB/s
        self.assertFalse(is_accepted_env_limited("download", 800))  # 100 KB/s is upload-only
        self.assertTrue(is_accepted_env_limited("download", 72_000))  # 9 MB/s
        self.assertTrue(is_accepted_env_limited("download", 80_000))  # 10 MB/s
        self.assertTrue(is_accepted_env_limited("download", 88_000))  # 11 MB/s
        self.assertFalse(is_accepted_env_limited("download", 64_000))  # 8 MB/s excluded
        self.assertFalse(is_accepted_env_limited("upload", 64_000))   # 8 MB/s excluded
        self.assertFalse(is_accepted_env_limited("upload", 0))

    def test_accuracy_inconsistency_is_not_hidden_by_allowlisted_env_limited(self):
        result = check_metric(
            "download",
            values("download", "90.00 Mbps", "80.00 Mbps", "70.00 Mbps", STATUS_MISMATCH, "87.5%"),
            80_000,
        )
        classification, separator, reason = result[4].partition("|")
        self.assertEqual("ENV_LIMITED", classification, result[4])
        self.assertIn("accuracy should be unavailable", reason)
        self.assertFalse(is_accepted_env_limited_result("download", 80_000, classification, reason))

    def test_parse_json_accepts_but_preserves_allowed_env_limited_rows(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "metrics.json"
            output_path = Path(temp_dir) / "metrics.csv"
            metrics = {}
            metrics.update(values("download", "90.00 Mbps", "80.00 Mbps", "70.00 Mbps", STATUS_MISMATCH, "—"))
            metrics.update(values("upload", "90.00 Mbps", "80.00 Mbps", "70.00 Mbps", STATUS_MISMATCH, "—"))
            input_path.write_text(json.dumps(metrics), encoding="utf-8")
            self.assertEqual(0, parse_json(str(input_path), "10 MB", 80_000, 17, str(output_path)))
            with output_path.open(newline="", encoding="utf-8") as handle:
                rows = list(csv.reader(handle))
            self.assertEqual(["ENV_LIMITED", "ENV_LIMITED"], [row[10] for row in rows])
            self.assertEqual(["ENV_LIMITED", "ENV_LIMITED"], [row[20] for row in rows])

    def test_repeated_10mb_download_below_tolerance_is_documented_env_limited_not_pass(self):
        fields = values(
            "download", "103.56 Mbps", "80.00 Mbps", "57.07 Mbps",
            STATUS_MISMATCH, "71.3%",
        )
        classification, _, reason = check_metric("download", fields, 80_000)[4].partition("|")
        self.assertEqual("ENV_LIMITED", classification, reason)
        self.assertIn("remains below the unchanged ±20% lower bound", reason)
        self.assertTrue(is_accepted_env_limited_result("download", 80_000, classification, reason))

    def test_100kbs_upload_with_sub_plan_baseline_is_env_limited_not_pass(self):
        fields = values(
            "upload", "700 Kbps", "800 Kbps", "713 Kbps",
            STATUS_ENV_LIMITED, "—",
        )
        classification, _, reason = check_metric("upload", fields, 800)[4].partition("|")
        self.assertEqual("ENV_LIMITED", classification, reason)
        self.assertTrue(is_accepted_env_limited_result("upload", 800, classification, reason))

    def test_parse_json_accepts_9mb_download_env_limited_and_preserves_classification(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "metrics.json"
            output_path = Path(temp_dir) / "metrics.csv"
            metrics = {}
            metrics.update(values("download", "80.00 Mbps", "72.00 Mbps", "50.00 Mbps", STATUS_MISMATCH, "—"))
            metrics.update(values("upload", "100.00 Mbps", "72.00 Mbps", "72.00 Mbps", STATUS_PASS, "100.0%"))
            input_path.write_text(json.dumps(metrics), encoding="utf-8")
            self.assertEqual(0, parse_json(str(input_path), "9 MB", 72_000, 16, str(output_path)))
            with output_path.open(newline="", encoding="utf-8") as handle:
                rows = list(csv.reader(handle))
            self.assertEqual(["ENV_LIMITED", "PASS"], [row[10] for row in rows])
            self.assertEqual(["ENV_LIMITED", "PASS"], [row[20] for row in rows])

    def test_parse_json_still_fails_unallowlisted_download_env_limited(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "metrics.json"
            output_path = Path(temp_dir) / "metrics.csv"
            metrics = {}
            metrics.update(values("download", "45.00 Mbps", "40.00 Mbps", "30.00 Mbps", STATUS_MISMATCH, "—"))
            metrics.update(values("upload", "45.00 Mbps", "40.00 Mbps", "30.00 Mbps", STATUS_MISMATCH, "—"))
            input_path.write_text(json.dumps(metrics), encoding="utf-8")
            self.assertEqual(2, parse_json(str(input_path), "5 MB", 40_000, 15, str(output_path)))
            with output_path.open(newline="", encoding="utf-8") as handle:
                rows = list(csv.reader(handle))
            self.assertEqual(["ENV_LIMITED", "ENV_LIMITED"], [row[10] for row in rows])


if __name__ == "__main__":
    unittest.main()
