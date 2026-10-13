import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import json
import tempfile

from verify_metrics import (
    ACCEPTED_FINAL_STATES,
    STATUS_MISMATCH,
    STATUS_PASS,
    STATUS_UNJUDGEABLE,
    STATUS_UNLIMITED,
    baseline_headroom_errors,
    collect_preflight,
    check_metric,
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
    def test_accepted_final_states_are_explicit_and_documented(self):
        self.assertEqual(
            {"PASS", "PASS_UNLIMITED", "ENV_LIMITED", "ENV_INSUFFICIENT"},
            ACCEPTED_FINAL_STATES,
        )

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

    def test_finite_plan_with_valid_measurement_passes(self):
        result = check_metric(
            "download",
            values("download", "121.27 Mbps", "72.00 Mbps", "70.00 Mbps", STATUS_PASS, "97.2%"),
            72_000,
        )
        self.assertEqual("PASS", result[4])

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


if __name__ == "__main__":
    unittest.main()
