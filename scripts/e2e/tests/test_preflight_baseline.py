import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from preflight_baseline import summarize_series, validate_files


class PreflightBaselineTests(unittest.TestCase):
    def test_stable_series_passes_and_reports_diagnostics(self):
        result = summarize_series([110.0, 112.0, 111.0], 105.6, 25.0)
        self.assertTrue(result["passed"])
        self.assertEqual(result["median_mbps"], 111.0)
        self.assertGreater(result["sample_variance_mbps_squared"], 0)
        self.assertLessEqual(result["spread_percent_of_median"], 25.0)
        self.assertEqual(result["stability_mode"], "PRIMARY_SPREAD_GATE")

    def test_low_download_baseline_fails(self):
        result = summarize_series([90.0, 94.0, 92.0], 105.6, 25.0)
        self.assertFalse(result["passed"])
        self.assertTrue(any("below 105.60 Mbps" in error for error in result["errors"]))

    def test_upload_below_105_6_mbps_fails(self):
        result = summarize_series([39.99, 59.39, 67.09], 105.6, 25.0)
        self.assertFalse(result["passed"])
        self.assertTrue(any("below 105.60 Mbps" in error for error in result["errors"]))

    def test_attempt_1_catastrophic_spread_remains_rejected(self):
        # Run #6 Attempt 1: high raw spread plus upload below the 105.6 Mbps top-preset floor.
        result = summarize_series([70.69, 121.54, 111.26], 105.6, 25.0)
        self.assertFalse(result["passed"])
        self.assertGreater(result["spread_percent_of_median"], 45.0)
        self.assertTrue(any("bounded single-outlier tolerance" in error for error in result["errors"]))

        upload = summarize_series([39.99, 59.39, 67.09], 105.6, 25.0)
        self.assertFalse(upload["passed"])
        self.assertGreater(upload["spread_percent_of_median"], 45.0)

    def test_attempt_2_runner_profile_is_blocked_by_upload_ceiling(self):
        # Run #6 Attempt 2 measurements, unchanged: download median 197.57,
        # upload median 94.79, download raw spread 29.10%, upload spread 19.41%.
        with tempfile.TemporaryDirectory() as temp:
            samples = [
                {"baseline_preflight_download_value": "147.19 Mbps",
                 "baseline_preflight_upload_value": "82.73 Mbps"},
                {"baseline_preflight_download_value": "204.69 Mbps",
                 "baseline_preflight_upload_value": "101.13 Mbps"},
                {"baseline_preflight_download_value": "197.57 Mbps",
                 "baseline_preflight_upload_value": "94.79 Mbps"},
            ]
            paths = []
            for index, payload in enumerate(samples, start=1):
                path = Path(temp) / f"round-{index}.json"
                path.write_text(json.dumps(payload), encoding="utf-8")
                paths.append(path)

            result = validate_files(paths)
            self.assertEqual(result["status"], "ENV_INSUFFICIENT")
            self.assertEqual(result["directions"]["download"]["median_mbps"], 197.57)
            self.assertEqual(result["directions"]["upload"]["median_mbps"], 94.79)
            self.assertEqual(
                result["directions"]["download"]["stability_mode"],
                "BOUNDED_SINGLE_OUTLIER",
            )
            self.assertAlmostEqual(
                result["directions"]["download"]["spread_percent_of_median"],
                29.104,
                places=2,
            )
            self.assertTrue(result["directions"]["download"]["passed"])
            self.assertIn("download raw spread 29.10%", result["env_note"])
            self.assertIn("105.6 Mbps baseline needed for 11 MB/s upload verification", result["env_note"])
            self.assertIn("105.6 Mbps", result["env_note"])

    def test_default_preflight_requires_105_6_mbps_in_both_directions(self):
        with tempfile.TemporaryDirectory() as temp:
            samples = [
                {"baseline_preflight_download_value": "110.00 Mbps",
                 "baseline_preflight_upload_value": "100.00 Mbps"},
                {"baseline_preflight_download_value": "111.00 Mbps",
                 "baseline_preflight_upload_value": "100.50 Mbps"},
                {"baseline_preflight_download_value": "110.50 Mbps",
                 "baseline_preflight_upload_value": "99.50 Mbps"},
            ]
            paths = []
            for index, payload in enumerate(samples, start=1):
                path = Path(temp) / f"round-{index}.json"
                path.write_text(json.dumps(payload), encoding="utf-8")
                paths.append(path)

            result = validate_files(paths)
            self.assertEqual(result["status"], "ENV_INSUFFICIENT")
            self.assertEqual(result["directions"]["download"]["minimum_mbps"], 105.6)
            self.assertEqual(result["directions"]["upload"]["minimum_mbps"], 105.6)
            self.assertTrue(
                any("below 105.60 Mbps" in error for error in result["directions"]["upload"]["errors"])
            )

    def test_three_json_samples_are_aggregated_per_direction(self):
        with tempfile.TemporaryDirectory() as temp:
            samples = [
                {"baseline_preflight_download_value": "110 Mbps",
                 "baseline_preflight_upload_value": "112 Mbps"},
                {"baseline_preflight_download_value": "111 Mbps",
                 "baseline_preflight_upload_value": "113 Mbps"},
                {"baseline_preflight_download_value": "109 Mbps",
                 "baseline_preflight_upload_value": "111 Mbps"},
            ]
            paths = []
            for index, payload in enumerate(samples, start=1):
                path = Path(temp) / f"round-{index}.json"
                path.write_text(json.dumps(payload), encoding="utf-8")
                paths.append(path)

            result = validate_files(paths)
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["directions"]["download"]["median_mbps"], 110.0)
            self.assertEqual(result["directions"]["upload"]["median_mbps"], 112.0)
            self.assertIn("No upload ceiling", result["env_note"])


if __name__ == "__main__":
    unittest.main()
