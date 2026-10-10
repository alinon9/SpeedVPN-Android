#!/usr/bin/env python3
"""Detect a terminal Verify Speed result for the currently selected plan.

This helper only determines whether the UI has finished presenting a result.
It does not classify a measurement or decide whether it passes. Numeric checks,
baseline headroom, thresholds, and PASS/FAIL/ENV_LIMITED remain in verify_metrics.py.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

STATUS_LABELS = {
    "✅ السرعة متطابقة",
    "❌ السرعة غير متطابقة",
    "⚠️ لا يمكن الحكم",
    "✅ لا يظهر سقف واضح",
}
LABELS = {
    "baseline": "🌐 سرعة الإنترنت الأصلية",
    "plan": "🔒 السرعة المحجوزة / المحددة",
    "vpn": "🚀 السرعة الفعلية داخل VPN",
}
TERMINAL_ERRORS = {
    "فشل القياس",
    "تعذر القياس",
    "لم يتم القياس",
    "غير متاح",
    "لا توجد بيانات",
}
RATE_RE = re.compile(r"^([0-9]+(?:\.[0-9]+)?) ?(bps|Kbps|Mbps|Gbps)$")
MULTIPLIERS = {"bps": 1.0, "Kbps": 1000.0, "Mbps": 1_000_000.0, "Gbps": 1_000_000_000.0}


def rate(value: str) -> float | None:
    match = RATE_RE.fullmatch(value)
    return None if match is None else float(match.group(1)) * MULTIPLIERS[match.group(2)]


def value_after(section: list[str], label: str, *, allow_error: bool = False) -> str | None:
    try:
        index = section.index(label)
    except ValueError:
        return None
    boundaries = set(STATUS_LABELS) | set(LABELS.values()) | {
        "Download", "Upload", "الدقة مقارنة بالخيار"
    }
    for value in section[index + 1:index + 7]:
        if value == "بدون حد" or rate(value) is not None:
            return value
        if allow_error and value in TERMINAL_ERRORS:
            return value
        if value in boundaries:
            break
    return None


def has_terminal_metric_row(xml_path: str | Path, expected_kbps: int) -> bool:
    try:
        root = ET.parse(xml_path).getroot()
    except (OSError, ET.ParseError):
        return False
    texts = [(node.attrib.get("text", "") or "").strip() for node in root.iter("node")]
    anchors = [i for i, value in enumerate(texts) if value == LABELS["baseline"]]
    for position, start in enumerate(anchors):
        end = anchors[position + 1] if position + 1 < len(anchors) else len(texts)
        section = texts[start:end]
        if not any(value in STATUS_LABELS for value in section):
            continue
        baseline_raw = value_after(section, LABELS["baseline"], allow_error=True)
        plan_raw = value_after(section, LABELS["plan"], allow_error=True)
        vpn_raw = value_after(section, LABELS["vpn"], allow_error=True)
        if baseline_raw is None or plan_raw is None or vpn_raw is None:
            continue
        if baseline_raw not in TERMINAL_ERRORS and rate(baseline_raw) is None:
            continue

        if expected_kbps == 0:
            plan_matches = plan_raw == "بدون حد"
        else:
            plan_bps = rate(plan_raw)
            expected_bps = expected_kbps * 1000.0
            # Same visible-label rounding allowance as the previous inline detector.
            plan_matches = plan_bps is not None and abs(plan_bps - expected_bps) <= max(1.0, expected_bps * 0.01)
        if not plan_matches:
            continue

        # A numeric sample OR an explicit measurement error is a terminal UI state.
        # This only stops a needless 300-second wait; the strict validator still fails
        # missing/invalid values and never turns an error result into PASS.
        if rate(vpn_raw) is not None or vpn_raw in TERMINAL_ERRORS:
            return True
    return False


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("Usage: metric_result_status.py UI_XML EXPECTED_KBPS", file=sys.stderr)
        return 2
    try:
        expected_kbps = int(argv[2])
    except ValueError:
        print("EXPECTED_KBPS must be an integer", file=sys.stderr)
        return 2
    return 0 if has_terminal_metric_row(argv[1], expected_kbps) else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
