#!/usr/bin/env python3
"""Stable Compose-metric collection and strict SpeedVPN speed-limiter CI gates."""
from __future__ import annotations

import csv
import json
import math
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
from pathlib import Path

STATUS_PASS = "✅ السرعة متطابقة"
STATUS_MISMATCH = "❌ السرعة غير متطابقة"
STATUS_UNJUDGEABLE = "⚠️ لا يمكن الحكم"
STATUS_UNLIMITED = "✅ لا يظهر سقف واضح"

VALUE_TAGS = {
    f"verify_{direction}_{field}_value"
    for direction in ("download", "upload")
    for field in ("baseline", "plan", "vpn", "accuracy")
} | {
    f"verify_{direction}_verdict"
    for direction in ("download", "upload")
}
REQUIRED_FIELDS = sorted(VALUE_TAGS)
PREFLIGHT_VALUE_TAGS = {
    "baseline_preflight_download_value",
    "baseline_preflight_upload_value",
}

RATE_RE = re.compile(r"^([0-9]+(?:\.[0-9]+)?)\s?(bps|Kbps|Mbps|Gbps)$")
ACCURACY_RE = re.compile(r"^([0-9]+(?:\.[0-9]+)?)%$")
MULTIPLIERS = {"bps": 1.0, "Kbps": 1_000.0, "Mbps": 1_000_000.0, "Gbps": 1_000_000_000.0}
EXPECTED_PRESETS = {
    1: ("10 KB", 80), 2: ("25 KB", 200), 3: ("50 KB", 400),
    4: ("75 KB", 600), 5: ("100 KB", 800), 6: ("130 KB", 1040),
    7: ("250 KB", 2000), 8: ("500 KB", 4000), 9: ("750 KB", 6000),
    10: ("950 KB", 7600), 11: ("1 MB", 8000), 12: ("2 MB", 16000),
    13: ("3 MB", 24000), 14: ("4 MB", 32000), 15: ("5 MB", 40000),
    16: ("9 MB", 72000), 17: ("10 MB", 80000), 18: ("11 MB", 88000),
    19: ("بدون حد", 0),
}
SCENARIO_ID = "speed-limit-sweep-v1"
CSV_FIELDS = [
    "index", "preset", "expected_kbps", "direction", "baseline_raw",
    "plan_raw", "vpn_raw", "accuracy_raw", "verdict", "reason", "classification",
    "run_id", "recorded_at_utc", "commit_sha", "scenario_id", "preset_id",
    "preset_name", "measurement_id", "original_speed_mbps", "vpn_speed_mbps",
    "status", "failure_reason", "started_at", "completed_at",
]


def current_commit_sha() -> str:
    """Resolve provenance from the checked-out source, not a claimed artifact label."""
    expected = (os.environ.get("SPEEDVPN_EXPECTED_COMMIT_SHA") or "").strip()
    if expected:
        return expected
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True, timeout=5,
        )
        sha = result.stdout.strip()
        if re.fullmatch(r"[0-9a-f]{40}", sha):
            return sha
    except (OSError, subprocess.SubprocessError):
        pass
    return (os.environ.get("GITHUB_SHA") or "").strip()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def rate_mbps(value: str | None) -> str:
    bps = rate_bps(value)
    if bps is None or not math.isfinite(bps) or bps <= 0:
        return ""
    return f"{bps / 1_000_000.0:.6f}"


def structured_result_fields(
    index: int, preset: str, direction: str, run_id: str,
    baseline: str, vpn: str, status: str, failure_reason: str,
    started_at: str, completed_at: str,
) -> dict[str, str]:
    return {
        "commit_sha": current_commit_sha(),
        "scenario_id": SCENARIO_ID,
        "preset_id": f"speed-preset-{index:02d}",
        "preset_name": preset,
        "measurement_id": f"{run_id}-{index:02d}-{direction.lower()}",
        "original_speed_mbps": rate_mbps(baseline),
        "vpn_speed_mbps": rate_mbps(vpn),
        "status": status,
        "failure_reason": failure_reason,
        "started_at": started_at,
        "completed_at": completed_at,
    }


def init_csv(csv_path: str) -> int:
    path = Path(csv_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        csv.DictWriter(handle, fieldnames=CSV_FIELDS).writeheader()
    return 0


def record_failed_pair(
    csv_path: str, index: int, preset: str, expected_kbps: int, status: str, reason: str,
) -> int:
    """Record both expected directions as failed rows; never invent a Verify direction."""
    if status not in {"MEASUREMENT_ERROR", "CONFIGURATION_ERROR"}:
        raise ValueError(f"invalid failure status {status!r}")
    if index not in EXPECTED_PRESETS or EXPECTED_PRESETS[index] != (preset, expected_kbps):
        raise ValueError(f"preset contract mismatch for index={index}, preset={preset!r}")
    run_id = (os.environ.get("GITHUB_RUN_ID") or "local").strip()
    completed_at = utc_now()
    started_at = (os.environ.get("SPEEDVPN_CASE_STARTED_AT") or "").strip() or completed_at
    if not started_at.endswith("Z"):
        raise ValueError("SPEEDVPN_CASE_STARTED_AT must be an ISO-8601 UTC timestamp")
    rows: list[dict[str, str]] = []
    for direction in ("Download", "Upload"):
        row = {
            "index": str(index), "preset": preset, "expected_kbps": str(expected_kbps),
            "direction": direction, "baseline_raw": "", "plan_raw": "", "vpn_raw": "",
            "accuracy_raw": "—", "verdict": STATUS_UNJUDGEABLE, "reason": reason,
            "classification": "FAIL", "run_id": run_id, "recorded_at_utc": completed_at,
        }
        row.update(structured_result_fields(
            index, preset, direction, run_id, "", "", status, reason, started_at, completed_at,
        ))
        rows.append(row)
    with Path(csv_path).open("a", newline="", encoding="utf-8") as handle:
        csv.DictWriter(handle, fieldnames=CSV_FIELDS).writerows(rows)
    for row in rows:
        print(
            f"RESULT|{row['index']}|{row['preset']}|{row['expected_kbps']}|"
            f"{row['direction']}|FAILED|{row['status']}|{row['failure_reason']}"
        )
    return 0


def rate_bps(value: str | None) -> float | None:
    if not value:
        return None
    match = RATE_RE.fullmatch(value.strip())
    return None if not match else float(match.group(1)) * MULTIPLIERS[match.group(2)]


def rate_display_step_bps(value: str | None) -> float | None:
    """Return the resolution of a displayed rate so checks can account for UI rounding."""
    if not value:
        return None
    match = RATE_RE.fullmatch(value.strip())
    if not match:
        return None
    decimals = len(match.group(1).partition(".")[2])
    return MULTIPLIERS[match.group(2)] / (10 ** decimals)


def normalise_resource_id(value: str) -> str:
    return value.rsplit("/", 1)[-1].rsplit(":", 1)[-1]


def expected_accuracy_range(
    baseline_bps: float | None,
    vpn_bps: float | None,
    expected_kbps: int,
    baseline_step_bps: float | None,
    vpn_step_bps: float | None,
) -> tuple[float, float] | None:
    if baseline_bps is None or baseline_bps <= 0 or vpn_bps is None or vpn_bps < 0:
        return None
    baseline_half_step = (baseline_step_bps or 0.0) / 2.0
    vpn_half_step = (vpn_step_bps or 0.0) / 2.0
    baseline_low = max(0.0, baseline_bps - baseline_half_step)
    baseline_high = baseline_bps + baseline_half_step
    vpn_low = max(0.0, vpn_bps - vpn_half_step)
    vpn_high = vpn_bps + vpn_half_step
    if expected_kbps == 0:
        return (
            min(100.0, vpn_low / baseline_high * 100.0),
            min(100.0, vpn_high / max(baseline_low, 1.0) * 100.0),
        )
    target_bps = expected_kbps * 1_000.0
    if baseline_low < target_bps * 1.2:
        return None
    def accuracy(rate: float) -> float:
        return max(0.0, min(100.0, 100.0 - abs(rate - target_bps) / target_bps * 100.0))
    upper = 100.0 if vpn_low <= target_bps <= vpn_high else max(accuracy(vpn_low), accuracy(vpn_high))
    return min(accuracy(vpn_low), accuracy(vpn_high)), upper


def check_accuracy(
    raw: str,
    baseline_bps: float | None,
    vpn_bps: float | None,
    expected_kbps: int,
    baseline_step_bps: float | None = None,
    vpn_step_bps: float | None = None,
) -> str | None:
    expected = expected_accuracy_range(
        baseline_bps, vpn_bps, expected_kbps, baseline_step_bps, vpn_step_bps,
    )
    value = (raw or "").strip()
    if expected is None:
        return None if value == "—" else f"accuracy should be unavailable (—), got {value!r}"
    match = ACCURACY_RE.fullmatch(value)
    if not match:
        return f"invalid/missing accuracy '{value}'"
    actual = float(match.group(1))
    # Rate values are rounded for display (integer Kbps, two decimal Mbps/Gbps),
    # while the app computes accuracy from the underlying Bps values. Accept only
    # the mathematically possible range from those display units plus 0.1% text rounding.
    lower, upper = expected
    if actual < lower - 0.051 or actual > upper + 0.051:
        return f"accuracy {actual:.1f}% disagrees with independently calculated range [{lower:.3f}%, {upper:.3f}%]"
    return None


def collect(xml_path: str, json_path: str) -> int:
    output = Path(json_path)
    try:
        state = json.loads(output.read_text(encoding="utf-8"))
        if not isinstance(state, dict):
            state = {}
    except (OSError, json.JSONDecodeError):
        state = {}
    root = ET.parse(xml_path).getroot()
    for node in root.iter("node"):
        tag = normalise_resource_id(node.attrib.get("resource-id", ""))
        value = (node.attrib.get("text", "") or "").strip()
        if tag in VALUE_TAGS and value:
            state[tag] = value
    output.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    missing = [field for field in REQUIRED_FIELDS if not str(state.get(field, "")).strip()]
    print(f"VERIFY_FIELDS_CAPTURED={len(REQUIRED_FIELDS) - len(missing)}/{len(REQUIRED_FIELDS)}")
    if missing:
        print("VERIFY_FIELDS_MISSING=" + ",".join(missing))
        return 1
    print("VERIFY_FIELDS_CAPTURED_ALL=1")
    return 0


def collect_preflight(xml_path: str, json_path: str) -> int:
    """Accumulate only the real baseline measurements rendered by the debug UI."""
    output = Path(json_path)
    try:
        state = json.loads(output.read_text(encoding="utf-8"))
        if not isinstance(state, dict):
            state = {}
    except (OSError, json.JSONDecodeError):
        state = {}
    root = ET.parse(xml_path).getroot()
    for node in root.iter("node"):
        tag = normalise_resource_id(node.attrib.get("resource-id", ""))
        value = (node.attrib.get("text", "") or "").strip()
        if tag in PREFLIGHT_VALUE_TAGS and value:
            state[tag] = value
    output.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    missing = sorted(PREFLIGHT_VALUE_TAGS - {key for key, value in state.items() if str(value).strip()})
    print(f"PREFLIGHT_FIELDS_CAPTURED={len(PREFLIGHT_VALUE_TAGS) - len(missing)}/2")
    if missing:
        print("PREFLIGHT_FIELDS_MISSING=" + ",".join(missing))
        return 1
    print("PREFLIGHT_FIELDS_CAPTURED_ALL=1")
    return 0


def baseline_headroom_errors(values: dict[str, str], expected_kbps: int) -> list[str]:
    """Independently enforce the 1.2x physical-baseline floor using displayed-rate precision."""
    if expected_kbps <= 0:
        return ["preflight target must be a positive Kbps value"]
    threshold_bps = expected_kbps * 1_000.0 * 1.2
    errors: list[str] = []
    for direction in ("download", "upload"):
        key = f"baseline_preflight_{direction}_value"
        raw = (values.get(key) or "").strip()
        measured_bps = rate_bps(raw)
        display_step_bps = rate_display_step_bps(raw)
        if measured_bps is None or measured_bps <= 0:
            errors.append(f"{direction}: missing or invalid baseline rate {raw!r}")
            continue
        # Use the lower edge of the displayed value, never a rounded-up number,
        # so values near the 1.2x boundary cannot become a false-positive pass.
        conservative_bps = measured_bps - (display_step_bps or 0.0) / 2.0
        if conservative_bps < threshold_bps:
            errors.append(
                f"{direction}: baseline {measured_bps / 1_000_000:.2f} Mbps is below "
                f"the required {threshold_bps / 1_000_000:.2f} Mbps (1.2x target)"
            )
        else:
            print(
                f"PREFLIGHT {direction.upper()}: baseline={measured_bps / 1_000_000:.2f} Mbps; "
                f"minimum={threshold_bps / 1_000_000:.2f} Mbps"
            )
    return errors


def validate_headroom(json_path: str, expected_kbps: int) -> int:
    path = Path(json_path)
    try:
        values = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"PREFLIGHT_FAIL=unable to read baseline JSON: {error}", file=sys.stderr)
        return 1
    if not isinstance(values, dict):
        print("PREFLIGHT_FAIL=baseline JSON must be an object", file=sys.stderr)
        return 1
    errors = baseline_headroom_errors(values, expected_kbps)
    if errors:
        print(f"PREFLIGHT_FAIL={len(errors)} issue(s)", file=sys.stderr)
        for error in errors:
            print(" - " + error, file=sys.stderr)
        return 1
    print("PREFLIGHT_HEADROOM_PASS=2/2")
    return 0


def check_metric(direction: str, values: dict[str, str], expected_kbps: int) -> tuple[str, str, str, str, str]:
    baseline = (values.get(f"verify_{direction}_baseline_value") or "").strip()
    plan = (values.get(f"verify_{direction}_plan_value") or "").strip()
    vpn = (values.get(f"verify_{direction}_vpn_value") or "").strip()
    verdict = (values.get(f"verify_{direction}_verdict") or "").strip()
    accuracy = (values.get(f"verify_{direction}_accuracy_value") or "").strip()
    reasons: list[str] = []
    baseline_bps = rate_bps(baseline)
    vpn_bps = rate_bps(vpn)

    if baseline_bps is None or baseline_bps <= 0:
        reasons.append(f"{direction}: invalid/missing Original network rate '{baseline}'")
    if vpn_bps is None or vpn_bps <= 0:
        reasons.append(f"{direction}: invalid/missing VPN measured rate '{vpn}'")
    baseline_step = rate_display_step_bps(baseline)
    vpn_step = rate_display_step_bps(vpn)
    accuracy_error = check_accuracy(accuracy, baseline_bps, vpn_bps, expected_kbps, baseline_step, vpn_step)
    if accuracy_error:
        reasons.append(f"{direction}: {accuracy_error}")

    if expected_kbps == 0:
        if plan != "بدون حد":
            reasons.append(f"{direction}: Unlimited selected but UI reports plan '{plan}'")
        if verdict != STATUS_UNLIMITED:
            reasons.append(f"{direction}: expected verdict '{STATUS_UNLIMITED}', got '{verdict}'")
        # Independently validate the behavior instead of trusting the UI verdict.
        # The product policy treats an Unlimited path as healthy when at least
        # 80% of its measured physical baseline is retained; faster VPN samples
        # are allowed because consecutive network samples can fluctuate upward.
        if (
            baseline_bps is not None
            and baseline_bps > 0
            and vpn_bps is not None
            and vpn_bps >= 0
            and vpn_bps < baseline_bps * 0.8
        ):
            reasons.append(
                f"{direction}: Unlimited VPN rate {vpn_bps / 1_000_000:.2f} Mbps "
                f"retains less than 80% of baseline {baseline_bps / 1_000_000:.2f} Mbps"
            )
        classification = "PASS_UNLIMITED" if not reasons else "FAIL"
        return baseline, plan, vpn, verdict, classification + ("|" + "; ".join(reasons) if reasons else "")

    target_bps = expected_kbps * 1_000.0
    plan_bps = rate_bps(plan)
    plan_valid = plan_bps is not None and abs(plan_bps - target_bps) <= max(1.0, target_bps * 0.01)
    if not plan_valid:
        reasons.append(f"{direction}: plan '{plan}' differs from target {expected_kbps} Kbps")
    if verdict == STATUS_MISMATCH:
        reasons.append(f"{direction}: app verdict is MISMATCH")
    elif verdict == STATUS_UNJUDGEABLE:
        reasons.append(f"{direction}: app verdict is UNJUDGEABLE")
    elif verdict != STATUS_PASS:
        reasons.append(f"{direction}: unexpected verdict '{verdict}'")
    if vpn_bps is not None and not (target_bps * 0.8 <= vpn_bps <= target_bps * 1.2):
        reasons.append(
            f"{direction}: measured {vpn_bps / 1000:.2f} Kbps outside "
            f"±20% interval [{target_bps * 0.8 / 1000:.2f}, {target_bps * 1.2 / 1000:.2f}] Kbps"
        )

    baseline_limited = (
        baseline_bps is not None
        and 0 < baseline_bps
        and baseline_bps - (baseline_step or 0.0) / 2.0 < target_bps * 1.2
    )
    if baseline_limited:
        reasons.append(
            f"{direction}: environment baseline {baseline_bps / 1000:.2f} Kbps "
            f"is below the 1.2x verification floor for {expected_kbps} Kbps"
        )

    # When the physical baseline misses the documented 1.2x headroom floor,
    # an under/over-target VPN result cannot independently prove an app defect.
    # Keep the strict gate red (ENV_LIMITED is never PASS), but classify it
    # separately from a verified rate-limiter failure. Structural failures such
    # as a wrong saved plan, missing rate, or unknown verdict remain FAIL.
    valid_verdicts = {STATUS_PASS, STATUS_MISMATCH, STATUS_UNJUDGEABLE}
    if (
        baseline_limited
        and plan_valid
        and vpn_bps is not None
        and vpn_bps > 0
        and verdict in valid_verdicts
    ):
        return baseline, plan, vpn, verdict, "ENV_LIMITED|" + "; ".join(reasons)

    if not reasons:
        return baseline, plan, vpn, verdict, "PASS"
    return baseline, plan, vpn, verdict, "FAIL|" + "; ".join(reasons)


def parse_json(json_path: str, preset: str, expected_kbps: int, index: int, output_path: str) -> int:
    values = json.loads(Path(json_path).read_text(encoding="utf-8"))
    rows: list[dict[str, str]] = []
    failed = False
    run_id = (os.environ.get("GITHUB_RUN_ID") or "local").strip()
    recorded_at_utc = utc_now()
    started_at = (os.environ.get("SPEEDVPN_CASE_STARTED_AT") or "").strip() or recorded_at_utc
    if not started_at.endswith("Z"):
        raise ValueError("SPEEDVPN_CASE_STARTED_AT must be an ISO-8601 UTC timestamp")
    if index not in EXPECTED_PRESETS or EXPECTED_PRESETS[index] != (preset, expected_kbps):
        raise ValueError(f"preset contract mismatch for index={index}, preset={preset!r}")
    for direction in ("download", "upload"):
        baseline, plan, vpn, verdict, result = check_metric(direction, values, expected_kbps)
        classification, sep, reason = result.partition("|")
        failed = failed or classification not in {"PASS", "PASS_UNLIMITED"}
        label = "Download" if direction == "download" else "Upload"
        accuracy = (values.get(f"verify_{direction}_accuracy_value") or "").strip()
        if classification in {"PASS", "PASS_UNLIMITED"}:
            status = classification
        elif classification == "ENV_LIMITED":
            status = "ENV_LIMITED"
        elif rate_bps(baseline) is None or rate_bps(vpn) is None:
            status = "MEASUREMENT_ERROR"
        else:
            status = "FAIL"
        row = {
            "index": str(index), "preset": preset, "expected_kbps": str(expected_kbps),
            "direction": label, "baseline_raw": baseline, "plan_raw": plan, "vpn_raw": vpn,
            "accuracy_raw": accuracy, "verdict": verdict, "reason": reason if sep else "",
            "classification": classification, "run_id": run_id, "recorded_at_utc": recorded_at_utc,
        }
        row.update(structured_result_fields(
            index, preset, label, run_id, baseline, vpn, status,
            reason if sep else "", started_at, recorded_at_utc,
        ))
        rows.append(row)
    with open(output_path, "w", newline="", encoding="utf-8") as handle:
        csv.DictWriter(handle, fieldnames=CSV_FIELDS).writerows(rows)
    for row in rows:
        print("RESULT|" + "|".join(str(row.get(field, "")) for field in CSV_FIELDS[:13]))
    print("OVERALL|" + ("FAIL" if failed else "PASS"))
    return 2 if failed else 0


def validate_json(json_path: str, expected_download: int, expected_upload: int) -> int:
    values = json.loads(Path(json_path).read_text(encoding="utf-8"))
    errors = []
    for direction, expected in (("download", expected_download), ("upload", expected_upload)):
        _, plan, vpn, verdict, result = check_metric(direction, values, expected)
        classification, sep, reason = result.partition("|")
        print(f"VERIFY {direction.upper()}: plan={plan}; vpn={vpn}; verdict={verdict}; class={classification}")
        if classification not in {"PASS", "PASS_UNLIMITED"}:
            errors.append(reason or f"{direction}: {classification}")
    if errors:
        for error in errors:
            print("STRICT_GATE_FAIL=" + error, file=sys.stderr)
        return 1
    print("STRICT_GATE_PASS=2/2")
    return 0


def validate_sweep(csv_path: str) -> int:
    path = Path(csv_path)
    if not path.is_file():
        print(f"STRICT_GATE_FAIL=missing results CSV: {csv_path}", file=sys.stderr)
        return 1
    try:
        with path.open(newline="", encoding="utf-8-sig") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames != CSV_FIELDS:
                print(f"STRICT_GATE_FAIL=unexpected CSV header: {reader.fieldnames}", file=sys.stderr)
                return 1
            rows = list(reader)
    except (OSError, csv.Error) as error:
        print(f"STRICT_GATE_FAIL=unable to parse CSV: {error}", file=sys.stderr)
        return 1

    errors = []
    hard_fail_events = int((os.environ.get("SPEEDVPN_SWEEP_HARD_FAILURE_COUNT") or "0").strip() or "0")
    if hard_fail_events:
        errors.append(f"run has {hard_fail_events} additional hard-failure event(s); overall strict gate must fail")
    expected_run_id = (os.environ.get("GITHUB_RUN_ID") or "").strip()
    observed_run_ids: set[str] = set()
    expected_keys = {(i, direction) for i in EXPECTED_PRESETS for direction in ("Download", "Upload")}
    found = {}
    measurement_ids: set[str] = set()
    for line, row in enumerate(rows, start=2):
        row_run_id = (row.get("run_id") or "").strip()
        recorded_at = (row.get("recorded_at_utc") or "").strip()
        if not row_run_id:
            errors.append(f"CSV line {line}: missing run_id")
        else:
            observed_run_ids.add(row_run_id)
            if expected_run_id and row_run_id != expected_run_id:
                errors.append(f"CSV line {line}: run_id {row_run_id!r} does not match current GitHub run {expected_run_id!r}")
        if not recorded_at.endswith("Z"):
            errors.append(f"CSV line {line}: recorded_at_utc must be an ISO-8601 UTC timestamp ending in Z")
        else:
            try:
                parsed_at = datetime.fromisoformat(recorded_at[:-1] + "+00:00")
                if parsed_at.tzinfo is None or parsed_at.utcoffset() is None:
                    errors.append(f"CSV line {line}: recorded_at_utc is missing UTC timezone")
            except ValueError:
                errors.append(f"CSV line {line}: invalid recorded_at_utc {recorded_at!r}")
        try:
            index = int(row["index"])
            expected = int(row["expected_kbps"])
        except (TypeError, ValueError):
            errors.append(f"CSV line {line}: missing/non-numeric index or expected_kbps")
            continue
        if index not in EXPECTED_PRESETS:
            errors.append(f"CSV line {line}: unexpected preset index {index}")
            continue
        preset, expected_value = EXPECTED_PRESETS[index]
        if row["preset"] != preset or expected != expected_value:
            errors.append(f"CSV line {line}: preset/target mismatch; expected {preset} at {expected_value} Kbps")
        key = (index, row["direction"])
        if key not in expected_keys:
            errors.append(f"CSV line {line}: unexpected metric key {key}")
            continue
        if key in found:
            errors.append(f"CSV line {line}: duplicate metric {key}")
            continue
        found[key] = row
        commit_sha = (row.get("commit_sha") or "").strip()
        expected_commit_sha = current_commit_sha()
        if not re.fullmatch(r"[0-9a-f]{40}", commit_sha):
            errors.append(f"CSV line {line}: invalid/missing commit_sha")
        elif expected_commit_sha and commit_sha != expected_commit_sha:
            errors.append(f"CSV line {line}: commit_sha does not match checked-out source")
        if row.get("scenario_id") != SCENARIO_ID:
            errors.append(f"CSV line {line}: invalid scenario_id {row.get('scenario_id')!r}")
        if row.get("preset_id") != f"speed-preset-{index:02d}":
            errors.append(f"CSV line {line}: invalid preset_id {row.get('preset_id')!r}")
        if row.get("preset_name") != preset:
            errors.append(f"CSV line {line}: preset_name does not match planned preset")
        measurement_id = (row.get("measurement_id") or "").strip()
        expected_measurement_id = f"{row_run_id}-{index:02d}-{row['direction'].lower()}"
        if measurement_id != expected_measurement_id:
            errors.append(f"CSV line {line}: measurement_id does not match planned identity")
        if measurement_id in measurement_ids:
            errors.append(f"CSV line {line}: duplicate measurement_id {measurement_id!r}")
        measurement_ids.add(measurement_id)

        started_at_value = None
        completed_at_value = None
        for field_name in ("started_at", "completed_at"):
            stamp = (row.get(field_name) or "").strip()
            try:
                if not stamp.endswith("Z"):
                    raise ValueError("timestamp must end in Z")
                parsed_stamp = datetime.fromisoformat(stamp[:-1] + "+00:00")
                if parsed_stamp.tzinfo is None or parsed_stamp.utcoffset() is None:
                    raise ValueError("timestamp has no timezone")
                if field_name == "started_at":
                    started_at_value = parsed_stamp
                else:
                    completed_at_value = parsed_stamp
            except ValueError as error:
                errors.append(f"CSV line {line}: invalid {field_name}: {error}")
        if started_at_value is not None and completed_at_value is not None and completed_at_value < started_at_value:
            errors.append(f"CSV line {line}: completed_at precedes started_at")
        if row.get("completed_at") != recorded_at:
            errors.append(f"CSV line {line}: completed_at does not match recorded_at_utc")
        if row.get("failure_reason", "") != row.get("reason", ""):
            errors.append(f"CSV line {line}: failure_reason disagrees with reason")

        status = (row.get("status") or "").strip()
        allowed_statuses = {"PASS", "PASS_UNLIMITED", "FAIL", "ENV_LIMITED", "MEASUREMENT_ERROR", "CONFIGURATION_ERROR"}
        if status not in allowed_statuses:
            errors.append(f"CSV line {line}: invalid/missing status {status!r}")
        if status in {"PASS", "PASS_UNLIMITED"} and row.get("failure_reason", "").strip():
            errors.append(f"CSV line {line}: passing measurement has failure_reason")
        if status not in {"PASS", "PASS_UNLIMITED"} and not row.get("failure_reason", "").strip():
            errors.append(f"CSV line {line}: non-passing status lacks failure_reason")
        if status in {"PASS", "PASS_UNLIMITED"} and row.get("classification") != status:
            errors.append(f"CSV line {line}: passing status disagrees with classification")
        if status in {"MEASUREMENT_ERROR", "CONFIGURATION_ERROR"} and row.get("classification") != "FAIL":
            errors.append(f"CSV line {line}: measurement/configuration error must remain FAIL")

        baseline_bps_contract = rate_bps(row.get("baseline_raw"))
        vpn_bps_contract = rate_bps(row.get("vpn_raw"))
        for field_name, raw_bps in (
            ("original_speed_mbps", baseline_bps_contract),
            ("vpn_speed_mbps", vpn_bps_contract),
        ):
            raw_value = (row.get(field_name) or "").strip()
            try:
                numeric = float(raw_value)
                if not math.isfinite(numeric) or numeric <= 0.0:
                    raise ValueError("must be finite and > 0")
                if raw_bps is None or abs(numeric - raw_bps / 1_000_000.0) > 0.00001:
                    raise ValueError("does not match corresponding raw rate")
            except (TypeError, ValueError):
                errors.append(f"CSV line {line}: invalid/inconsistent {field_name} {raw_value!r}")

        direction = "download" if row["direction"] == "Download" else "upload"
        values = {
            f"verify_{direction}_baseline_value": row["baseline_raw"],
            f"verify_{direction}_plan_value": row["plan_raw"],
            f"verify_{direction}_vpn_value": row["vpn_raw"],
            f"verify_{direction}_accuracy_value": row["accuracy_raw"],
            f"verify_{direction}_verdict": row["verdict"],
        }
        _, _, _, _, result = check_metric(direction, values, expected)
        calculated, sep, reason = result.partition("|")
        recorded = row["classification"]
        if calculated not in {"PASS", "PASS_UNLIMITED"}:
            errors.append(f"{key}: strict measurement check failed: {reason or calculated}")
        if recorded not in {"PASS", "PASS_UNLIMITED"}:
            errors.append(f"{key}: recorded classification is {recorded}")
        if recorded != calculated:
            errors.append(f"{key}: recorded class {recorded} disagrees with strict class {calculated}")
        if status == "FAIL" and recorded != "FAIL":
            errors.append(f"{key}: status FAIL disagrees with classification {recorded}")

    if len(observed_run_ids) > 1:
        errors.append(f"provenance: rows contain mixed run IDs: {sorted(observed_run_ids)}")
    missing = expected_keys - set(found)
    if missing:
        errors.append(f"coverage: missing {len(missing)} of 38 metrics: {sorted(missing)[:8]}")
    if len(rows) != 38:
        errors.append(f"coverage: expected exactly 38 metric rows, got {len(rows)}")
    if errors:
        print(f"STRICT_GATE_FAIL: {len(errors)} issue(s)", file=sys.stderr)
        for error in errors[:80]:
            print(" - " + error, file=sys.stderr)
        return 1
    print("STRICT_GATE_PASS=38/38; PASS=38; ENV_LIMITED=0; FAIL=0")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("Usage: verify_metrics.py collect XML JSON | collect-preflight XML JSON | validate-headroom JSON TARGET_KBPS | parse-json JSON PRESET EXPECTED_KBPS INDEX OUTPUT_CSV | validate-json JSON EXPECTED_DOWNLOAD_KBPS EXPECTED_UPLOAD_KBPS | validate-sweep CSV", file=sys.stderr)
        return 2
    command = argv[1]
    try:
        if command == "init-csv" and len(argv) == 3:
            return init_csv(argv[2])
        if command == "record-failure" and len(argv) == 8:
            return record_failed_pair(argv[2], int(argv[3]), argv[4], int(argv[5]), argv[6], argv[7])
        if command == "collect" and len(argv) == 4:
            return collect(argv[2], argv[3])
        if command == "collect-preflight" and len(argv) == 4:
            return collect_preflight(argv[2], argv[3])
        if command == "validate-headroom" and len(argv) == 4:
            return validate_headroom(argv[2], int(argv[3]))
        if command == "parse-json" and len(argv) == 7:
            return parse_json(argv[2], argv[3], int(argv[4]), int(argv[5]), argv[6])
        if command == "validate-json" and len(argv) == 5:
            return validate_json(argv[2], int(argv[3]), int(argv[4]))
        if command == "validate-sweep" and len(argv) == 3:
            return validate_sweep(argv[2])
    except (OSError, json.JSONDecodeError, ET.ParseError, ValueError) as error:
        print(f"STRICT_GATE_FAIL={error}", file=sys.stderr)
        return 1
    print("Invalid arguments", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
