import json
import tempfile
import unittest
from pathlib import Path

from preflight_baseline import summarize_series, validate_files


class PreflightBaselineTests(unittest.TestCase):
    def test_stable_series_passes_and_reports_median_variance(self):
        result = summarize_series([110.0, 112.0, 111.0], 96.0, 15.0)
        self.assertTrue(result["passed"])
        self.assertEqual(result["median_mbps"], 111.0)
        self.assertGreater(result["sample_variance_mbps_squared"], 0)
        self.assertLessEqual(result["spread_percent_of_median"], 15.0)

    def test_low_download_baseline_fails(self):
        result = summarize_series([90.0, 94.0, 92.0], 96.0, 15.0)
        self.assertFalse(result["passed"])
        self.assertTrue(any("below 96.00 Mbps" in e for e in result["errors"]))

    def test_low_upload_baseline_fails(self):
        result = summarize_series([105.0, 108.0, 106.0], 106.0, 15.0)
        self.assertFalse(result["passed"])

    def test_high_spread_fails_even_when_median_meets_headroom(self):
        result = summarize_series([100.0, 120.0, 140.0], 96.0, 15.0)
        self.assertFalse(result["passed"])
        self.assertTrue(any("spread" in e for e in result["errors"]))

    def test_three_json_samples_are_aggregated_per_direction(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            data = [
                ({"baseline_preflight_download_value": "110 Mbps", "baseline_preflight_upload_value": "112 Mbps"}),
                ({"baseline_preflight_download_value": "111 Mbps", "baseline_preflight_upload_value": "113 Mbps"}),
                ({"baseline_preflight_download_value": "109 Mbps", "baseline_preflight_upload_value": "111 Mbps"}),
            ]
            for i, payload in enumerate(data):
                path = Path(tmp) / f"round-{i + 1}.json"
                path.write_text(json.dumps(payload), encoding="utf-8")
                paths.append(path)
            result = validate_files(paths)
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["directions"]["download"]["median_mbps"], 110.0)
            self.assertEqual(result["directions"]["upload"]["median_mbps"], 112.0)


if __name__ == "__main__":
    unittest.main()
