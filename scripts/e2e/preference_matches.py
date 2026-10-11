#!/usr/bin/env python3
"""Validate persisted SpeedVPN speed preferences from a SharedPreferences XML stream."""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET


def preferences_match(
    xml_text: str,
    target_key: str,
    expected: int,
    check_download: str = "none",
) -> bool:
    """Return True only when all requested preference values are present and exact."""
    if not target_key or check_download not in {"none", "both"}:
        return False
    try:
        root = ET.fromstring(xml_text)
    except (ET.ParseError, TypeError):
        return False

    values: dict[str, int] = {}
    for node in root.iter("long"):
        name = node.attrib.get("name")
        if not name:
            continue
        try:
            values[name] = int(node.attrib["value"])
        except (KeyError, ValueError):
            # Malformed numeric preferences must never be treated as a match.
            return False

    keys = [target_key]
    if check_download == "both" and "dl" not in keys:
        keys.append("dl")
    return all(values.get(key) == expected for key in keys)


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(
            "Usage: preference_matches.py TARGET_KEY EXPECTED_VALUE none|both < XML",
            file=sys.stderr,
        )
        return 2
    target_key, expected_raw, check_download = argv[1:]
    try:
        expected = int(expected_raw)
    except ValueError:
        print("Invalid expected preference value", file=sys.stderr)
        return 2

    if preferences_match(sys.stdin.read(), target_key, expected, check_download):
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
