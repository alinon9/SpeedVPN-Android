#!/usr/bin/env python3
"""Drive the isolated UID-detection POC on a headless Android emulator.

This script changes no application behavior. It only operates the emulator UI,
launches network-using apps, and writes diagnostic results for GitHub Actions.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = Path(os.environ.get("UID_POC_ARTIFACTS", str(ROOT / "artifacts")))
TAG = "UidDetectionPOC"
APPS = [
    ("Chrome", "com.android.chrome"),
    ("YouTube", "com.google.android.youtube"),
    ("Google Play Store", "com.android.vending"),
]
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args: str, check: bool = True, timeout: int = 30) -> str:
    cmd = ["adb", *args]
    print("$ " + " ".join(cmd), flush=True)
    proc = subprocess.run(cmd, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout)
    output = proc.stdout or ""
    if output:
        print(output[-2500:], flush=True)
    if check and proc.returncode != 0:
        raise RuntimeError(f"Command failed ({proc.returncode}): {' '.join(cmd)}")
    return output.strip()


def ui_nodes() -> list[ET.Element]:
    adb("shell", "uiautomator", "dump", "/sdcard/uid-poc-window.xml",
        check=False, timeout=35)
    raw = adb("shell", "cat", "/sdcard/uid-poc-window.xml",
              check=False, timeout=15)
    start = raw.find("<?xml")
    if start < 0:
        return []
    try:
        return list(ET.fromstring(raw[start:]).iter("node"))
    except ET.ParseError:
        return []


def node_text(node: ET.Element) -> str:
    return (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()


def find_candidate(candidates: Iterable[str]) -> tuple[str, str] | None:
    wanted = list(candidates)
    nodes = ui_nodes()
    for candidate in wanted:
        for node in nodes:
            actual = node_text(node)
            if actual == candidate:
                match = BOUNDS_RE.search(node.attrib.get("bounds", ""))
                if match:
                    x1, y1, x2, y2 = map(int, match.groups())
                    return candidate, f"{(x1 + x2) // 2} {(y1 + y2) // 2}"
    # Some Android builds expose a longer accessibility label for system dialog buttons.
    for candidate in wanted:
        for node in nodes:
            actual = node_text(node)
            if actual and candidate.lower() in actual.lower():
                match = BOUNDS_RE.search(node.attrib.get("bounds", ""))
                if match:
                    x1, y1, x2, y2 = map(int, match.groups())
                    return actual, f"{(x1 + x2) // 2} {(y1 + y2) // 2}"
    return None


def wait_for_text(candidates: list[str], timeout: int, description: str) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = find_candidate(candidates)
        if found:
            print(f"UI found {description}: {found[0]}", flush=True)
            return found[0]
        time.sleep(1.5)
    raise RuntimeError(f"Timed out waiting for UI {description}; expected one of: {candidates}")


def tap_text(candidates: list[str], timeout: int, description: str) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = find_candidate(candidates)
        if found:
            label, coords = found
            x, y = coords.split()
            adb("shell", "input", "tap", x, y)
            print(f"Tapped UI {description}: {label} at {coords}", flush=True)
            time.sleep(1.5)
            return label
        time.sleep(1.5)
    raise RuntimeError(f"Timed out trying to tap UI {description}; expected one of: {candidates}")


def package_installed(package_name: str) -> bool:
    return "package:" in adb("shell", "pm", "path", package_name,
                             check=False, timeout=15)


def read_poc_log() -> str:
    return adb("logcat", "-d", "-v", "threadtime", "-s", f"{TAG}:I",
               check=False, timeout=30)


def wait_for_poc_start(timeout: int = 90) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        current = read_poc_log()
        if "START Android API=" in current:
            print("POC callback started.", flush=True)
            return current
        time.sleep(3)
    raise RuntimeError(f"Did not observe '{TAG}: START Android API=' after VPN start.")


def launch_app(label: str, package_name: str) -> None:
    if not package_installed(package_name):
        raise RuntimeError(f"Required test app is absent from emulator: {package_name} ({label})")
    if package_name == "com.android.chrome":
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", "https://example.com/?uid_detection_poc=chrome",
            "-p", package_name, check=False, timeout=25)
    else:
        adb("shell", "monkey", "-p", package_name, "1", check=False, timeout=25)
    print(f"Generated traffic from {label} ({package_name}); waiting for packets.",
          flush=True)
    time.sleep(12)


def main() -> int:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    report_path = ARTIFACTS / "uid-poc-summary.json"
    summary_path = ARTIFACTS / "uid-poc-summary.txt"
    api = int(adb("shell", "getprop", "ro.build.version.sdk"))
    if api < 30:
        raise RuntimeError(f"Android API {api} detected; this POC runner requires API 30+.")

    print(f"Android emulator API: {api}", flush=True)
    for label, package_name in APPS:
        if not package_installed(package_name):
            raise RuntimeError(
                f"Required app missing from Google Play emulator image: {label} ({package_name})"
            )

    adb("shell", "pm", "grant", "com.speedvpn.app",
        "android.permission.POST_NOTIFICATIONS", check=False, timeout=15)
    adb("shell", "am", "force-stop", "com.speedvpn.app", check=False)
    adb("logcat", "-c", check=False)
    adb("shell", "am", "start", "-n", "com.speedvpn.app/.MainActivity")

    first = wait_for_text(["منح إذن VPN", "بدء الاتصال", "Start VPN", "Connect VPN"],
                          90, "SpeedVPN home action")
    if first in {"منح إذن VPN", "Start VPN", "Connect VPN"}:
        tap_text([first], 20, "request VPN permission")
        tap_text(["أوافق والمتابعة", "Agree and continue", "Continue"],
                 30, "SpeedVPN VPN disclosure")
        # Android's VpnService consent dialog is owned by the OS, not SpeedVPN.
        tap_text(["OK", "Allow", "موافق", "السماح", "ALLOW"],
                 35, "Android VPN consent")
        time.sleep(3)

    home_state = wait_for_text(
        ["بدء الاتصال", "إيقاف الاتصال", "قيد التشغيل…", "Connected", "Disconnect"],
        45, "SpeedVPN connection action",
    )
    if home_state in {"بدء الاتصال", "Connect", "Start VPN"}:
        tap_text([home_state], 20, "start SpeedVPN")
    elif home_state in {"منح إذن VPN", "Grant VPN permission"}:
        raise RuntimeError("VPN permission was not granted by the emulator UI.")

    wait_for_poc_start()
    for label, package_name in APPS:
        launch_app(label, package_name)

    time.sleep(6)
    tagged = read_poc_log()
    (ARTIFACTS / "logcat-uid-detection.txt").write_text(tagged + "\n", encoding="utf-8")
    full_log = adb("logcat", "-d", "-v", "threadtime", check=False, timeout=30)
    (ARTIFACTS / "logcat-full.txt").write_text(full_log + "\n", encoding="utf-8")

    results = re.findall(r"RESULT verdict=([A-Z_]+).*?\buid=(-?\d+)", tagged)
    valid = [(verdict, int(uid)) for verdict, uid in results
             if int(uid) >= 0 and verdict in {"MATCH_TARGET", "UID_FOUND_OTHER_OR_UNLISTED"}]
    matches = [(verdict, uid) for verdict, uid in valid if verdict == "MATCH_TARGET"]
    expected_lines = [line.strip() for line in tagged.splitlines() if "EXPECTED package=" in line]
    result_lines = [line.strip() for line in tagged.splitlines() if "RESULT verdict=" in line]
    summary = {
        "android_api": api,
        "apps_launched": [{"label": label, "package": pkg} for label, pkg in APPS],
        "poc_started": "START Android API=" in tagged,
        "expected_package_lines": expected_lines,
        "result_count": len(results),
        "valid_uid_result_count": len(valid),
        "target_match_count": len(matches),
        "result_verdicts": [verdict for verdict, _ in results],
        "success": bool(valid),
        "interpretation": (
            "getConnectionOwnerUid returned at least one valid UID."
            if valid else
            "No valid UID returned; inspect SecurityException, INVALID_UID, and argument errors."
        ),
    }
    report_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n",
                            encoding="utf-8")
    summary_text = [
        f"Android API: {api}",
        "Apps launched: " + ", ".join(f"{label} ({pkg})" for label, pkg in APPS),
        f"getConnectionOwnerUid valid UID results: {len(valid)}",
        f"Target package matches: {len(matches)}",
        "Verdicts: " + (", ".join(summary["result_verdicts"]) or "NONE"),
        summary["interpretation"],
        "",
        "Expected UID lines:",
        *(expected_lines or ["(none)"]),
        "",
        "Result lines:",
        *(result_lines or ["(none)"]),
    ]
    summary_path.write_text("\n".join(summary_text) + "\n", encoding="utf-8")
    print("\n" + "\n".join(summary_text), flush=True)

    # Fail the job if we built/launched the POC but the API produced no valid UID.
    # The always() artifact upload still preserves logs for diagnosis.
    return 0 if valid else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"UID POC RUNNER ERROR: {exc}", file=sys.stderr, flush=True)
        sys.exit(1)
