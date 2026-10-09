import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_metrics import (
    STATUS_MISMATCH,
    STATUS_PASS,
    STATUS_UNJUDGEABLE,
    STATUS_UNLIMITED,
    check_metric,
)


def values(direction, baseline, plan, vpn, verdict):
    return {
        f"verify_{direction}_baseline_value": baseline,
        f"verify_{direction}_plan_value": plan,
        f"verify_{direction}_vpn_value": vpn,
        f"verify_{direction}_verdict": verdict,
    }


class VerifyMetricClassificationTests(unittest.TestCase):
    def test_low_baseline_and_rate_mismatch_is_environment_limited(self):
        result = check_metric(
            "upload",
            values("upload", "85.68 Mbps", "72.00 Mbps", "50.11 Mbps", STATUS_MISMATCH),
            72_000,
        )
        self.assertTrue(result[4].startswith("ENV_LIMITED|"), result[4])
        self.assertIn("1.2x verification floor", result[4])

    def test_performance_mismatch_with_sufficient_baseline_still_fails(self):
        result = check_metric(
            "download",
            values("download", "121.27 Mbps", "72.00 Mbps", "22.62 Mbps", STATUS_MISMATCH),
            72_000,
        )
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("outside ±20%", result[4])

    def test_wrong_plan_is_not_hidden_by_low_baseline(self):
        result = check_metric(
            "upload",
            values("upload", "85.68 Mbps", "40.00 Mbps", "50.11 Mbps", STATUS_MISMATCH),
            72_000,
        )
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("differs from target", result[4])

    def test_missing_vpn_measurement_remains_failure(self):
        result = check_metric(
            "upload",
            values("upload", "85.68 Mbps", "72.00 Mbps", "", STATUS_UNJUDGEABLE),
            72_000,
        )
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("invalid/missing VPN measured rate", result[4])

    def test_finite_plan_with_valid_measurement_passes(self):
        result = check_metric(
            "download",
            values("download", "121.27 Mbps", "72.00 Mbps", "70.00 Mbps", STATUS_PASS),
            72_000,
        )
        self.assertEqual("PASS", result[4])

    def test_unlimited_requires_behavioral_verdict_and_independent_rate_check(self):
        valid_values = values("upload", "66.24 Mbps", "بدون حد", "77.56 Mbps", STATUS_UNLIMITED)
        self.assertEqual("PASS_UNLIMITED", check_metric("upload", valid_values, 0)[4])
        mismatch_values = values("upload", "119.76 Mbps", "بدون حد", "77.56 Mbps", STATUS_MISMATCH)
        self.assertTrue(check_metric("upload", mismatch_values, 0)[4].startswith("FAIL|"))

    def test_unlimited_drop_cannot_pass_even_if_ui_verdict_says_ok(self):
        # Mirrors the Sweep #41 anomaly: UI text alone must not hide a large drop.
        values_from_sweep = values("upload", "64.80 Mbps", "بدون حد", "40.44 Mbps", STATUS_UNLIMITED)
        result = check_metric("upload", values_from_sweep, 0)
        self.assertTrue(result[4].startswith("FAIL|"), result[4])
        self.assertIn("retains less than 80% of baseline", result[4])


if __name__ == "__main__":
    unittest.main()
