#!/usr/bin/env python3
from __future__ import annotations

import csv
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_metrics import CSV_FIELDS, EXPECTED_PRESETS, STATUS_MISMATCH, STATUS_PASS, STATUS_UNLIMITED, validate_sweep  # noqa: E402


def rate_label(bps: float) -> str:
    if bps >= 1_000_000:
        return f"{bps / 1_000_000:.2f} Mbps"
    if bps >= 1_000:
        return f"{bps / 1_000:.2f} Kbps"
    return f"{bps:.2f} bps"


def valid_rows() -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for index, (preset, expected_kbps) in EXPECTED_PRESETS.items():
        for direction in ("Download", "Upload"):
            if expected_kbps == 0:
                baseline, plan, vpn, verdict, classification = (
                    "100.00 Mbps", "بدون حد", "90.00 Mbps", STATUS_UNLIMITED, "PASS_UNLIMITED"
                )
                accuracy = "90.0%"
            else:
                target_bps = expected_kbps * 1_000.0
                baseline, plan, vpn, verdict, classification = (
                    rate_label(target_bps * 1.5), rate_label(target_bps),
                    rate_label(target_bps), STATUS_PASS, "PASS"
                )
                accuracy = "100.0%"
            now = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            commit_sha = subprocess.run(
                ["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True, timeout=5,
            ).stdout.strip()
            unit = {"bps": 1e-6, "Kbps": 1e-3, "Mbps": 1.0, "Gbps": 1000.0}
            original_mbps = float(baseline.split()[0]) * unit[baseline.split()[1]]
            vpn_mbps = float(vpn.split()[0]) * unit[vpn.split()[1]]
            rows.append({
                "index": str(index), "preset": preset, "expected_kbps": str(expected_kbps),
                "direction": direction, "baseline_raw": baseline, "plan_raw": plan,
                "vpn_raw": vpn, "accuracy_raw": accuracy, "verdict": verdict, "reason": "",
                "classification": classification, "run_id": "test-run-123", "recorded_at_utc": now,
                "commit_sha": commit_sha, "scenario_id": "speed-limit-sweep-v1",
                "preset_id": f"speed-preset-{index:02d}", "preset_name": preset,
                "measurement_id": f"test-run-123-{index:02d}-{direction.lower()}",
                "original_speed_mbps": f"{original_mbps:.6f}", "vpn_speed_mbps": f"{vpn_mbps:.6f}",
                "status": classification, "failure_reason": "", "started_at": now, "completed_at": now,
            })
    return rows


class SweepValidationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.csv_path = Path(self.temp_dir.name) / "sweep.csv"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_rows(self, rows: list[dict[str, str]]) -> None:
        with self.csv_path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
            writer.writeheader()
            writer.writerows(rows)

    def validate_without_ci_run(self) -> int:
        with patch.dict(os.environ, {"GITHUB_RUN_ID": ""}):
            return validate_sweep(str(self.csv_path))

    def test_exact_38_valid_unique_results_pass(self) -> None:
        rows = valid_rows()
        self.assertEqual(38, len(rows))
        self.write_rows(rows)
        self.assertEqual(0, self.validate_without_ci_run())

    def test_missing_result_fails_coverage(self) -> None:
        self.write_rows(valid_rows()[:-1])
        self.assertEqual(1, self.validate_without_ci_run())

    def test_duplicate_result_fails(self) -> None:
        rows = valid_rows()
        rows.append(rows[0].copy())
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_documented_environment_limited_row_is_an_accepted_final_state(self) -> None:
        rows = valid_rows()
        row = next(r for r in rows if r["index"] == "16" and r["direction"] == "Download")
        target = EXPECTED_PRESETS[16][1] * 1_000.0
        row["baseline_raw"] = rate_label(target * 1.1)
        row["original_speed_mbps"] = f"{target * 1.1 / 1_000_000:.6f}"
        row["accuracy_raw"] = "—"
        row["classification"] = "ENV_LIMITED"
        row["status"] = "ENV_LIMITED"
        row["reason"] = "environment baseline below documented 1.2x headroom floor"
        row["failure_reason"] = row["reason"]
        self.write_rows(rows)
        self.assertEqual(0, self.validate_without_ci_run())

    def test_measured_out_of_range_result_with_sufficient_headroom_still_fails(self) -> None:
        rows = valid_rows()
        row = next(r for r in rows if r["index"] == "18" and r["direction"] == "Upload")
        target_bps = EXPECTED_PRESETS[18][1] * 1_000.0
        row["vpn_raw"] = rate_label(target_bps * 0.6)
        row["vpn_speed_mbps"] = f"{target_bps * 0.6 / 1_000_000:.6f}"
        row["accuracy_raw"] = "60.0%"
        row["verdict"] = STATUS_MISMATCH
        row["classification"] = "FAIL"
        row["status"] = "FAIL"
        row["reason"] = "measured upload is outside strict ±20% acceptance range"
        row["failure_reason"] = row["reason"]
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_wrong_run_id_fails_when_github_context_is_present(self) -> None:
        self.write_rows(valid_rows())
        with patch.dict(os.environ, {"GITHUB_RUN_ID": "current-run-456"}):
            self.assertEqual(1, validate_sweep(str(self.csv_path)))

    def test_mixed_run_ids_are_rejected(self) -> None:
        rows = valid_rows()
        rows[-1]["run_id"] = "other-run"
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_invalid_timestamp_fails(self) -> None:
        rows = valid_rows()
        rows[0]["recorded_at_utc"] = "yesterday"
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_accuracy_mismatch_fails_independent_recalculation(self) -> None:
        rows = valid_rows()
        rows[0]["accuracy_raw"] = "64.0%"
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_missing_accuracy_fails(self) -> None:
        rows = valid_rows()
        rows[0]["accuracy_raw"] = ""
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_unavailable_accuracy_is_required_for_environment_limited_metric(self) -> None:
        rows = valid_rows()
        row = next(r for r in rows if r["index"] == "16" and r["direction"] == "Download")
        target = EXPECTED_PRESETS[16][1] * 1_000.0
        row["baseline_raw"] = rate_label(target * 1.1)
        row["original_speed_mbps"] = f"{target * 1.1 / 1_000_000:.6f}"
        row["accuracy_raw"] = "80.0%"
        row["classification"] = "ENV_LIMITED"
        row["status"] = "ENV_LIMITED"
        row["failure_reason"] = "baseline below 1.2x headroom"
        row["reason"] = row["failure_reason"]
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())
    
    def test_verify_is_never_a_speed_direction(self) -> None:
        rows = valid_rows()
        rows[0]["direction"] = "Verify"
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_nan_speed_is_rejected(self) -> None:
        rows = valid_rows()
        rows[0]["original_speed_mbps"] = "NaN"
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_infinity_speed_is_rejected(self) -> None:
        rows = valid_rows()
        rows[0]["vpn_speed_mbps"] = "Infinity"
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_stale_commit_provenance_is_rejected(self) -> None:
        rows = valid_rows()
        rows[0]["commit_sha"] = "0" * 40
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_duplicate_measurement_id_is_rejected(self) -> None:
        rows = valid_rows()
        rows[1]["measurement_id"] = rows[0]["measurement_id"]
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_missing_speed_value_cannot_pass(self) -> None:
        rows = valid_rows()
        rows[0]["vpn_speed_mbps"] = ""
        self.write_rows(rows)
        self.assertEqual(1, self.validate_without_ci_run())

    def test_stale_measurement_from_before_this_run_is_rejected(self) -> None:
        rows = valid_rows()
        stale = "2020-01-01T00:00:00.000Z"
        for row in rows:
            row["started_at"] = stale
            row["completed_at"] = stale
            row["recorded_at_utc"] = stale
        self.write_rows(rows)
        with patch.dict(os.environ, {
            "GITHUB_RUN_ID": "",
            "SPEEDVPN_SWEEP_STARTED_AT": "2026-10-11T10:00:00.000Z",
        }):
            self.assertEqual(1, validate_sweep(str(self.csv_path)))


if __name__ == "__main__":
    unittest.main()
