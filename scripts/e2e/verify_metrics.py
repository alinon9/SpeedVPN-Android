#!/usr/bin/env python3
"""Stable Compose-metric collection and strict SpeedVPN speed-limiter CI gates."""
from __future__ import annotations

import csv
import json
import os
import re
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
CSV_FIELDS = [
    "index", "preset", "expected_kbps", "direction", "baseline_raw",
    "plan_raw", "vpn_raw", "accuracy_raw", "verdict", "reason", "classification", "run_id", "recorded_at_utc",
]


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
    rows = []
    failed = False
    run_id = (os.environ.get("GITHUB_RUN_ID") or "local").strip()
    recorded_at_utc = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    for direction in ("download", "upload"):
        baseline, plan, vpn, verdict, result = check_metric(direction, values, expected_kbps)
        classification, sep, reason = result.partition("|")
        failed = failed or classification not in {"PASS", "PASS_UNLIMITED"}
        label = "Download" if direction == "download" else "Upload"
        accuracy = (values.get(f"verify_{direction}_accuracy_value") or "").strip()
        rows.append([index, preset, expected_kbps, label, baseline, plan, vpn, accuracy, verdict, reason if sep else "", classification, run_id, recorded_at_utc])
    with open(output_path, "w", newline="", encoding="utf-8") as handle:
        csv.writer(handle).writerows(rows)
    for row in rows:
        print("RESULT|" + "|".join(map(str, row)))
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
    expected_run_id = (os.environ.get("GITHUB_RUN_ID") or "").strip()
    observed_run_ids: set[str] = set()
    expected_keys = {(i, direction) for i in EXPECTED_PRESETS for direction in ("Download", "Upload")}
    found = {}
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
        print("Usage: verify_metrics.py collect XML JSON | parse-json JSON PRESET EXPECTED_KBPS INDEX OUTPUT_CSV | validate-json JSON EXPECTED_DOWNLOAD_KBPS EXPECTED_UPLOAD_KBPS | validate-sweep CSV", file=sys.stderr)
        return 2
    command = argv[1]
    try:
        if command == "collect" and len(argv) == 4:
            return collect(argv[2], argv[3])
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
