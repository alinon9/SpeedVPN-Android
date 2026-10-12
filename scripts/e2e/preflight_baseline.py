#!/usr/bin/env python3
"""Validate three independent pre-sweep baseline measurements.

The 15% stability gate uses the relative range (max-min)/median. The script
also records sample variance and coefficient of variation for diagnostics.
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
    errors: list[str] = []
    if median < minimum_mbps:
        errors.append(f"median {median:.2f} Mbps is below {minimum_mbps:.2f} Mbps")
    if spread_pct > max_variability_pct:
        errors.append(
            f"measurement spread {spread_pct:.2f}% exceeds {max_variability_pct:.2f}%"
        )
    return {
        "samples_mbps": [round(value, 4) for value in values],
        "median_mbps": round(median, 4),
        "sample_variance_mbps_squared": round(sample_variance, 6),
        "sample_stddev_mbps": round(sample_stddev, 4),
        "spread_percent_of_median": round(spread_pct, 4),
        "coefficient_of_variation_percent": round(cv_pct, 4),
        "minimum_mbps": minimum_mbps,
        "max_variability_percent": max_variability_pct,
        "passed": not errors,
        "errors": errors,
    }


def validate_files(
    json_paths: list[Path],
    download_min_mbps: float = 96.0,
    upload_min_mbps: float = 106.0,
    max_variability_pct: float = 15.0,
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
    parser.add_argument("--upload-min", type=float, default=106.0)
    parser.add_argument("--max-variability", type=float, default=15.0)
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
