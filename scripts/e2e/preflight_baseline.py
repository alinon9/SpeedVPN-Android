#!/usr/bin/env python3
"""Validate three independent pre-sweep baseline measurements.

The primary stability limit is the relative range (max-min)/median <= 25%.
A bounded single-outlier allowance applies only when total spread is <= 30%,
the two samples closest to the median are within 25%, and no sample deviates
from the median by more than 30%. Raw spread and diagnostics remain reported.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
from pathlib import Path
from typing import Any

_RATE_RE = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)\s*(bps|Kbps|Mbps|Gbps)\s*$")
_MULTIPLIERS = {"bps": 1e-6, "Kbps": 1e-3, "Mbps": 1.0, "Gbps": 1e3}


def parse_mbps(value: Any) -> float:
    if not isinstance(value, str):
        raise ValueError(f"rate must be a displayed string, got {value!r}")
    match = _RATE_RE.fullmatch(value)
    if not match:
        raise ValueError(f"invalid displayed rate {value!r}")
    result = float(match.group(1)) * _MULTIPLIERS[match.group(2)]
    if result <= 0 or not (result < float("inf")):
        raise ValueError(f"rate must be positive and finite, got {value!r}")
    return result


def summarize_series(values: list[float], minimum_mbps: float, max_variability_pct: float) -> dict[str, Any]:
    if len(values) != 3:
        raise ValueError(f"exactly 3 samples are required, got {len(values)}")
    median = statistics.median(values)
    sample_variance = statistics.variance(values)
    sample_stddev = statistics.stdev(values)
    spread_pct = ((max(values) - min(values)) / median) * 100.0
    cv_pct = (sample_stddev / median) * 100.0

    # A single transient low/high reading may stretch the raw range slightly
    # beyond 25%. Keep the primary 25% gate, but tolerate that one outlier only
    # when the full spread is <= 30%, the two readings nearest the median form
    # a stable pair within 25%, and every reading remains within 30% of median.
    closest_pair = sorted(values, key=lambda value: abs(value - median))[:2]
    closest_pair_median = statistics.median(closest_pair)
    closest_pair_spread_pct = (
        ((max(closest_pair) - min(closest_pair)) / closest_pair_median) * 100.0
    )
    max_deviation_pct = max(abs(value - median) / median * 100.0 for value in values)
    outlier_limit_pct = max_variability_pct + 5.0
    bounded_single_outlier = (
        spread_pct > max_variability_pct
        and spread_pct <= outlier_limit_pct
        and closest_pair_spread_pct <= max_variability_pct
        and max_deviation_pct <= outlier_limit_pct
    )

    errors: list[str] = []
    if median < minimum_mbps:
        errors.append(f"median {median:.2f} Mbps is below {minimum_mbps:.2f} Mbps")
    if spread_pct > max_variability_pct and not bounded_single_outlier:
        errors.append(
            f"measurement spread {spread_pct:.2f}% exceeds {max_variability_pct:.2f}% "
            f"and does not qualify for bounded single-outlier tolerance "
            f"(maximum raw spread {outlier_limit_pct:.2f}%)"
        )

    stability_mode = "BOUNDED_SINGLE_OUTLIER" if bounded_single_outlier else "PRIMARY_SPREAD_GATE"
    return {
        "samples_mbps": [round(value, 4) for value in values],
        "median_mbps": round(median, 4),
        "sample_variance_mbps_squared": round(sample_variance, 6),
        "sample_stddev_mbps": round(sample_stddev, 4),
        "spread_percent_of_median": round(spread_pct, 4),
        "coefficient_of_variation_percent": round(cv_pct, 4),
        "closest_pair_spread_percent_of_median": round(closest_pair_spread_pct, 4),
        "max_deviation_from_median_percent": round(max_deviation_pct, 4),
        "minimum_mbps": minimum_mbps,
        "max_variability_percent": max_variability_pct,
        "bounded_outlier_max_spread_percent": round(outlier_limit_pct, 4),
        "stability_mode": stability_mode,
        "passed": not errors,
        "errors": errors,
    }

def build_environment_note(result: dict[str, Any]) -> str:
    """Summarize measured CI limits without converting limited checks into PASS."""
    notes: list[str] = []
    directions = result.get("directions", {})

    for direction in ("download", "upload"):
        item = directions.get(direction, {})
        if item.get("stability_mode") == "BOUNDED_SINGLE_OUTLIER":
            notes.append(
                f"{direction} raw spread {item['spread_percent_of_median']:.2f}% exceeded "
                f"{item['max_variability_percent']:.2f}% but passed the bounded single-outlier "
                f"rule (closest-pair spread "
                f"{item['closest_pair_spread_percent_of_median']:.2f}%, maximum deviation "
                f"{item['max_deviation_from_median_percent']:.2f}%, hard cap "
                f"{item['bounded_outlier_max_spread_percent']:.2f}%)."
            )

    upload = directions.get("upload", {})
    median_upload = upload.get("median_mbps")
    if isinstance(median_upload, (int, float)):
        if median_upload < 90.0:
            notes.append(
                f"upload ceiling ~{median_upload:.0f} Mbps is below the 90 Mbps preflight "
                "floor; 9/10/11 MB/s upload checks may be ENV_LIMITED."
            )
        elif median_upload < 96.0:
            notes.append(
                f"upload ceiling ~{median_upload:.0f} Mbps limits 10/11 MB/s upload "
                "verification (10 MB/s needs >=96 Mbps baseline; 11 MB/s needs >=105.6 Mbps)."
            )
        elif median_upload < 105.6:
            notes.append(
                f"upload baseline ~{median_upload:.0f} Mbps leaves 11 MB/s upload "
                "verification ENV_LIMITED (requires >=105.6 Mbps baseline)."
            )

    if result.get("status") != "PASS":
        for direction in ("download", "upload"):
            item = directions.get(direction, {})
            if item.get("errors"):
                notes.append(f"{direction} preflight: {'; '.join(item['errors'])}.")

    return " ".join(notes) if notes else (
        "No upload ceiling identified by preflight; the full sweep remains authoritative."
    )


def validate_files(
    json_paths: list[Path],
    download_min_mbps: float = 96.0,
    upload_min_mbps: float = 90.0,
    max_variability_pct: float = 25.0,
) -> dict[str, Any]:
    if len(json_paths) != 3:
        raise ValueError("exactly three baseline JSON files are required")
    parsed: list[dict[str, Any]] = []
    for path in json_paths:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict):
            raise ValueError(f"{path}: expected a JSON object")
        parsed.append(payload)

    result: dict[str, Any] = {
        "samples": [str(path) for path in json_paths],
        "variability_metric": "(max - min) / median * 100",
        "status": "PASS",
        "directions": {},
    }
    for direction, minimum in (
        ("download", download_min_mbps),
        ("upload", upload_min_mbps),
    ):
        key = f"baseline_preflight_{direction}_value"
        values = [parse_mbps(payload.get(key)) for payload in parsed]
        summary = summarize_series(values, minimum, max_variability_pct)
        result["directions"][direction] = summary
        print(
            f"PREFLIGHT_{direction.upper()} samples={summary['samples_mbps']} Mbps "
            f"median={summary['median_mbps']:.2f} Mbps "
            f"sample_variance={summary['sample_variance_mbps_squared']:.6f} Mbps^2 "
            f"spread={summary['spread_percent_of_median']:.2f}% "
            f"cv={summary['coefficient_of_variation_percent']:.2f}% "
            f"minimum={minimum:.2f} Mbps"
        )
        for error in summary["errors"]:
            print(f"PREFLIGHT_{direction.upper()}_FAIL={error}")
        if not summary["passed"]:
            result["status"] = "ENV_INSUFFICIENT"

    result["env_note"] = build_environment_note(result)
    print(f"ENV_NOTE={result['env_note']}")
    print(f"PREFLIGHT_STATUS={result['status']}")
    if result["status"] == "ENV_INSUFFICIENT":
        print("ENV_INSUFFICIENT — skipped sweep")
    else:
        print("PREFLIGHT_PASS — continuing to speed sweep")

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as output:
            output.write(f"preflight_status={result['status']}\n")

    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("samples", nargs=3, type=Path, help="three per-run JSON measurements")
    parser.add_argument("--download-min", type=float, default=96.0)
    parser.add_argument("--upload-min", type=float, default=90.0)
    parser.add_argument("--max-variability", type=float, default=25.0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    try:
        result = validate_files(
            args.samples,
            download_min_mbps=args.download_min,
            upload_min_mbps=args.upload_min,
            max_variability_pct=args.max_variability,
        )
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"PREFLIGHT_INVALID={error}", file=sys.stderr)
        result = {"status": "ENV_INSUFFICIENT", "error": str(error)}
        github_output = os.environ.get("GITHUB_OUTPUT")
        if github_output:
            with open(github_output, "a", encoding="utf-8") as output:
                output.write("preflight_status=ENV_INSUFFICIENT\n")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    if result["status"] != "PASS":
        print("ENV_INSUFFICIENT — skipped sweep")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
