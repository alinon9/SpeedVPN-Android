import sys
import unittest
import csv
import os
from contextlib import redirect_stdout, redirect_stderr
from datetime import datetime, timedelta, timezone
from io import StringIO
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import json
import tempfile

from verify_metrics import (
    ACCEPTED_FINAL_STATES,
    CSV_FIELDS,
    EXPECTED_PRESETS,
    RELEASE_PASS_STATES,
    SCENARIO_ID,
    STATUS_MISMATCH,
    STATUS_PASS,
    STATUS_UNJUDGEABLE,
    STATUS_UNLIMITED,
    baseline_headroom_errors,
    collect_preflight,
    check_metric,
    parse_json,
    validate_sweep,
)


def values(direction, baseline, plan, vpn, verdict, accuracy="—"):
    return {
        f"verify_{direction}_baseline_value": baseline,
        f"verify_{direction}_plan_value": plan,
        f"verify_{direction}_vpn_value": vpn,
        f"verify_{direction}_accuracy_value": accuracy,
        f"verify_{direction}_verdict": verdict,
    }


def write_sweep_fixture(path: Path, env_limited_metric: tuple[int, str] | None = None) -> None:
    now = datetime.now(timezone.utc)
    completed = now.isoformat(timespec="microseconds").replace("+00:00", "Z")
    started = (now - timedelta(seconds=1)).isoformat(timespec="microseconds").replace("+00:00", "Z")
    run_id = "unit-test-run"
    commit_sha = "a" * 40
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for index, (preset, expected_kbps) in EXPECTED_PRESETS.items():
            for direction in ("Download", "Upload"):
                baseline_mbps = 150.0
                vpn_mbps = expected_kbps / 1000.0 if expected_kbps else 130.0
                verdict = STATUS_PASS if expected_kbps else STATUS_UNLIMITED
                if env_limited_metric == (index, direction):
                    baseline_mbps = 90.0
                if expected_kbps == 0:
                    plan_raw = "بدون حد"
                    vpn_raw = "130.00 Mbps"
                    accuracy_raw = "86.7%"
                    expected_status = "PASS_UNLIMITED"
                else:
                    plan_raw = f"{expected_kbps} Kbps" if expected_kbps < 1000 else f"{expected_kbps / 1000:.2f} Mbps"
                    vpn_raw = plan_raw
                    accuracy_raw = "100.0%"
                    expected_status = "PASS"
                status = expected_status
                classification = expected_status
                reason = ""
                if env_limited_metric == (index, direction):
                    status = "ENV_LIMITED"
                    classification = "ENV_LIMITED"
                    reason = "baseline below the 1.2x headroom requirement"
                writer.writerow({
                    "index": str(index),
                    "preset": preset,
                    "expected_kbps": str(expected_kbps),
                    "direction": direction,
                    "baseline_raw": f"{baseline_mbps:.2f} Mbps",
                    "plan_raw": plan_raw,
                    "vpn_raw": vpn_raw,
                    "accuracy_raw": accuracy_raw,
                    "verdict": verdict,
                    "reason": reason,
                    "classification": classification,
                    "run_id": run_id,
                    "recorded_at_utc": completed,
                    "commit_sha": commit_sha,
                    "scenario_id": SCENARIO_ID,
                    "preset_id": f"speed-preset-{index:02d}",
                    "preset_name": preset,
                    "measurement_id": f"{run_id}-{index:02d}-{direction.lower()}",
                    "original_speed_mbps": str(baseline_mbps),
                    "vpn_speed_mbps": str(vpn_mbps),
                    "status": status,
                    "failure_reason": reason,
                    "started_at": started,
                    "completed_at": completed,
                })


class VerifyMetricClassificationTests(unittest.TestCase):
    def test_accepted_final_states_are_explicit_and_documented(self):
        self.assertEqual(
            {"PASS", "PASS_UNLIMITED", "ENV_LIMITED", "ENV_INSUFFICIENT"},
            ACCEPTED_FINAL_STATES,
        )

    def test_release_pass_states_exclude_environment_diagnostics(self):
        self.assertEqual({"PASS", "PASS_UNLIMITED"}, RELEASE_PASS_STATES)
        self.assertIn("ENV_LIMITED", ACCEPTED_FINAL_STATES)
        self.assertIn("ENV_INSUFFICIENT", ACCEPTED_FINAL_STATES)

    def test_strict_sweep_accepts_38_rows_only_when_every_metric_passes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "all-pass.csv"
            write_sweep_fixture(path)
            output = StringIO()
            error_output = StringIO()
            with patch.dict(os.environ, {
                "SPEEDVPN_EXPECTED_COMMIT_SHA": "a" * 40,
                "GITHUB_RUN_ID": "unit-test-run",
                "SPEEDVPN_SWEEP_STARTED_AT": "2020-01-01T00:00:00Z",
                "SPEEDVPN_SWEEP_HARD_FAILURE_COUNT": "0",
            }):
                with redirect_stdout(output), redirect_stderr(error_output):
                    code = validate_sweep(str(path))
            self.assertEqual(0, code, error_output.getvalue())
            self.assertIn("STRICT_GATE_ACCEPTED=38/38", output.getvalue())
            self.assertIn("ENV_LIMITED=0", output.getvalue())
            self.assertIn("ENV_INSUFFICIENT=0", output.getvalue())

    def test_strict_sweep_rejects_env_limited_row_even_with_38_of_38_coverage(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "env-limited.csv"
            write_sweep_fixture(path, env_limited_metric=(18, "Upload"))
            output = StringIO()
            error_output = StringIO()
            with patch.dict(os.environ, {
                "SPEEDVPN_EXPECTED_COMMIT_SHA": "a" * 40,
                "GITHUB_RUN_ID": "unit-test-run",
                "SPEEDVPN_SWEEP_STARTED_AT": "2020-01-01T00:00:00Z",
                "SPEEDVPN_SWEEP_HARD_FAILURE_COUNT": "0",
            }):
                with redirect_stdout(output), redirect_stderr(error_output):
                    code = validate_sweep(str(path))
            self.assertEqual(1, code)
            self.assertIn("release acceptance requires PASS; got status=ENV_LIMITED, classification=ENV_LIMITED", error_output.getvalue())
            self.assertNotIn("STRICT_GATE_ACCEPTED=38/38", output.getvalue())

    def test_parse_json_reports_environment_limit_without_claiming_pass(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            metrics_path = Path(temp_dir) / "metrics.json"
            results_path = Path(temp_dir) / "results.csv"
            metric_values = {}
            metric_values.update(
                values("download", "100.00 Mbps", "72.00 Mbps", "72.00 Mbps", STATUS_PASS, "100.0%")
            )
            metric_values.update(
                values("upload", "79.20 Mbps", "72.00 Mbps", "72.00 Mbps", STATUS_PASS, "—")
            )
            metrics_path.write_text(json.dumps(metric_values), encoding="utf-8")
            output = StringIO()
            with redirect_stdout(output):
                code = parse_json(str(metrics_path), "9 MB", 72_000, 16, str(results_path))
            self.assertEqual(0, code)
            self.assertIn("OVERALL|ENV_LIMITED", output.getvalue())
            self.assertNotIn("OVERALL|PASS", output.getvalue())
            with results_path.open(newline="", encoding="utf-8") as handle:
                all_rows = list(csv.DictReader(handle))

            # parse_json intentionally writes data rows without a header. DictReader
            # therefore exposes one row after treating the first measurement as
            # the field-name row; assert the actual filtered count requested here.
            self.assertEqual(1, len(all_rows))
            # Both real directions must still be emitted, with the environment
            # limitation visible in the classification output and never overall PASS.
            self.assertEqual(2, output.getvalue().count("RESULT|"))
            self.assertIn("ENV_LIMITED", output.getvalue())

    def test_preflight_xml_collection_requires_both_tagged_real_metrics(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            xml_path = Path(temp_dir) / "ui.xml"
            json_path = Path(temp_dir) / "metrics.json"
            xml_path.write_text(
                '<hierarchy>'
                '<node resource-id="com.speedvpn.app:id/baseline_preflight_download_value" text="110.00 Mbps" />'
                '<node resource-id="com.speedvpn.app:id/baseline_preflight_upload_value"><node text="Upload baseline"/><node text="112.00 Mbps" /></node>'
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


    def test_low_rate_600_and_800_kbps_presets_use_1_0x_headroom_floor_matching_kotlin_policy(self):
        # 600 Kbps (75 KB/s) and 800 Kbps (100 KB/s) use 1.0x headroom in SpeedVerificationPolicy.kt.
        # A baseline between 1.0x and 1.2x (e.g. 650 Kbps for 600 Kbps, or 850 Kbps for 800 Kbps)
        # must pass with numeric accuracy rather than failing accuracy check with expected '—'.
        res_600 = check_metric(
            "download",
            values("download", "650 Kbps", "600 Kbps", "600 Kbps", STATUS_PASS, "100.0%"),
            600,
        )
        self.assertEqual("PASS", res_600[4])

        res_800 = check_metric(
            "upload",
            values("upload", "850 Kbps", "800 Kbps", "792 Kbps", STATUS_PASS, "99.0%"),
            800,
        )
        self.assertEqual("PASS", res_800[4])

        # Below 1.0x for 600 Kbps is still ENV_LIMITED with 1.0x floor message.
        low_600 = check_metric(
            "download",
            values("download", "580 Kbps", "600 Kbps", "550 Kbps", STATUS_UNJUDGEABLE, "—"),
            600,
        )
        self.assertTrue(low_600[4].startswith("ENV_LIMITED|"), low_600[4])
        self.assertIn("1.0x verification floor", low_600[4])


if __name__ == "__main__":
    unittest.main()
