#!/usr/bin/env python3
"""Summarize TCP SYN-to-SYN/ACK RTTs for remote IPs observed in SOCKS relay flow logs.

The capture contains SYN packets only (connection metadata; no application payload).
RTT is measured at the Linux GitHub Actions runner's packet-capture interface,
not from the Android guest's TCP_INFO, so treat it as upstream path RTT at the
emulator NAT boundary rather than a full app-to-server latency.
"""
from __future__ import annotations

import argparse
import datetime as dt
import re
import statistics
from collections import defaultdict
from pathlib import Path

TIMESTAMP_RE = re.compile(r"(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)")
PACKET_RE = re.compile(
    r"(?P<src>(?:\d{1,3}\.){3}\d{1,3})\.(?P<sport>\d+)\s+>\s+"
    r"(?P<dst>(?:\d{1,3}\.){3}\d{1,3})\.(?P<dport>\d+):\s+Flags\s+"
    r"\[(?P<flags>[^\]]+)\]"
)
FLOW_IP_RE = re.compile(r"->((?:\d{1,3}\.){3}\d{1,3}):443\b")
LOG_TIME_RE = re.compile(r"^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})")
PHASE_START_RE = re.compile(r"Phase start direction=download\b")
PHASE_END_RE = re.compile(r"Phase complete direction=download\b")


def log_timestamp(line: str, year: int) -> dt.datetime | None:
    m = LOG_TIME_RE.search(line)
    if not m:
        return None
    month, day, hour, minute, second, millis = map(int, m.groups())
    return dt.datetime(year, month, day, hour, minute, second, millis * 1000)


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, int((len(ordered) - 1) * p + 0.999999)))]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture", required=True)
    parser.add_argument("--logcat", required=True)
    args = parser.parse_args()

    capture_path, logcat_path = Path(args.capture), Path(args.logcat)
    capture = capture_path.read_text(errors="replace") if capture_path.exists() else ""
    logcat = logcat_path.read_text(errors="replace") if logcat_path.exists() else ""
    packet_lines = capture.splitlines()
    first_timestamp = next((TIMESTAMP_RE.search(line) for line in packet_lines if TIMESTAMP_RE.search(line)), None)
    year = int(first_timestamp.group("ts")[:4]) if first_timestamp else dt.datetime.now().year

    target_ips = set(FLOW_IP_RE.findall(logcat))
    phase_starts: list[dt.datetime] = []
    phase_windows: list[tuple[str, dt.datetime, dt.datetime]] = []
    parsed_log = []
    for line in logcat.splitlines():
        timestamp = log_timestamp(line, year)
        if timestamp is not None:
            parsed_log.append((timestamp, line))
            if PHASE_START_RE.search(line):
                phase_starts.append(timestamp)
    phase_ends = [timestamp for timestamp, line in parsed_log if PHASE_END_RE.search(line)]
    for index, start in enumerate(phase_starts):
        end = next((candidate for candidate in phase_ends if candidate > start), None)
        if end is not None:
            phase_windows.append(("baseline" if index == 0 else "vpn", start, end))
            phase_ends.remove(end)

    pending: dict[tuple[str, int, str, int], tuple[dt.datetime, str]] = {}
    matches: list[tuple[dt.datetime, str, str, int, str, int, float]] = []
    current_timestamp: dt.datetime | None = None
    for line in packet_lines:
        time_match = TIMESTAMP_RE.search(line)
        if time_match:
            try:
                current_timestamp = dt.datetime.fromisoformat(time_match.group("ts"))
            except ValueError:
                current_timestamp = None
        match = PACKET_RE.search(line)
        # tcpdump -vvv prints packet timestamps on a header line and the TCP
        # 4-tuple/flags on the following indented line. Carry that timestamp across.
        if not match or current_timestamp is None:
            continue
        timestamp = current_timestamp
        src, dst = match.group("src"), match.group("dst")
        sport, dport = int(match.group("sport")), int(match.group("dport"))
        flags = match.group("flags")
        key = (src, sport, dst, dport)
        if "S" not in flags:
            continue
        if "." not in flags:  # initial SYN (without ACK)
            pending.setdefault(key, (timestamp, line))
        else:  # SYN/ACK; pair it with the original SYN
            original = (dst, dport, src, sport)
            prior = pending.pop(original, None)
            if prior is None:
                continue
            started, _ = prior
            rtt_ms = (timestamp - started).total_seconds() * 1000.0
            # The SYN/ACK direction is remote -> local; retain local -> remote for reporting.
            remote_ip = src
            if remote_ip not in target_ips:
                continue
            phase = "outside"
            for label, start, end in phase_windows:
                if start <= started <= end:
                    phase = label
                    break
            matches.append((started, phase, dst, dport, src, sport, rtt_ms))

    print("TCP SYN-to-SYN/ACK RTT diagnostic (metadata only; no payload captured)")
    print(f"Capture file: {capture_path}")
    print(f"SOCKS flow remote IPv4 targets found in logcat: {', '.join(sorted(target_ips)) or 'none'}")
    print(f"Parsed SYN/SYN-ACK pairs to those targets: {len(matches)}")
    print("Scope: GitHub Actions Linux runner packet capture of upstream TCP/443 handshakes; not Android guest TCP_INFO.")
    for label in ("baseline", "vpn", "outside"):
        values = [item[6] for item in matches if item[1] == label]
        if values:
            print(f"{label}: n={len(values)} median_ms={statistics.median(values):.3f} p90_ms={percentile(values, 0.90):.3f} min_ms={min(values):.3f} max_ms={max(values):.3f}")
        else:
            print(f"{label}: no matched handshakes")
    for timestamp, phase, src, sport, dst, dport, rtt_ms in sorted(matches):
        print(f"{timestamp.isoformat(timespec='microseconds')} phase={phase} {src}:{sport} -> {dst}:{dport} rtt_ms={rtt_ms:.3f}")
    if not capture:
        print("WARNING: capture file is missing or empty; tcpdump may not have started.")
    elif not target_ips:
        print("WARNING: no completed SOCKS flow IDs were found; target-IP correlation unavailable.")
    elif not matches:
        print("WARNING: no SYN/SYN-ACK pairs for SOCKS-observed remote targets; inspect raw capture and NAT behavior.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
