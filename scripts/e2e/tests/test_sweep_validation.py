#!/usr/bin/env python3
from __future__ import annotations

import csv
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_metrics import CSV_FIELDS, EXPECTED_PRESETS, STATUS_PASS, STATUS_UNLIMITED, validate_sweep  # noqa: E402


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
            else:
                target_bps = expected_kbps * 1_000.0
                baseline, plan, vpn, verdict, classification = (
                    rate_label(target_bps * 1.5), rate_label(target_bps),
                    rate_label(target_bps), STATUS_PASS, "PASS"
                )
            rows.append({
                "index": str(index), "preset": preset, "expected_kbps": str(expected_kbps),
                "direction": direction, "baseline_raw": baseline, "plan_raw": plan,
                "vpn_raw": vpn, "verdict": verdict, "reason": "",
                "classification": classification, "run_id": "test-run-123",
                "recorded_at_utc": "2026-10-09T12:00:00.000Z",
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

    def test_environment_limited_is_never_counted_as_pass(self) -> None:
        rows = valid_rows()
        row = next(r for r in rows if r["index"] == "16" and r["direction"] == "Download")
        target = EXPECTED_PRESETS[16][1] * 1_000.0
        row["baseline_raw"] = rate_label(target * 1.1)
        row["classification"] = "ENV_LIMITED"
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


if __name__ == "__main__":
    unittest.main()
