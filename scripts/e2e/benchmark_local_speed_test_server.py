#!/usr/bin/env python3
"""Real, repeated multi-stream throughput preflight for the local E2E HTTP fixture."""
from __future__ import annotations

import argparse
import concurrent.futures
import http.client
import json
import time
from pathlib import Path
from urllib.parse import urlsplit


def rate_mbps(byte_count: int, elapsed_seconds: float) -> float:
    if byte_count <= 0 or elapsed_seconds <= 0:
        raise ValueError("bytes and elapsed time must be positive")
    return byte_count * 8.0 / elapsed_seconds / 1_000_000.0


def _endpoint(base_url: str, path: str, size_bytes: int) -> tuple[str, str, int]:
    parsed = urlsplit(base_url)
    if parsed.scheme != "http" or not parsed.hostname:
        raise ValueError("base URL must be a plain HTTP URL to the local fixture")
    return parsed.hostname, f"{path}?bytes={size_bytes}&cacheBust=fixture-benchmark-{time.time_ns()}", parsed.port or 80


def _download(base_url: str, size_bytes: int, timeout: float) -> int:
    host, path, port = _endpoint(base_url, "/__down", size_bytes)
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        connection.request("GET", path, headers={"Accept-Encoding": "identity", "Cache-Control": "no-cache"})
        response = connection.getresponse()
        if response.status != 200:
            raise RuntimeError(f"GET fixture returned HTTP {response.status}")
        declared_length = int(response.getheader("Content-Length", "-1"))
        if declared_length != size_bytes:
            raise RuntimeError(f"GET Content-Length={declared_length}, expected {size_bytes}")
        received = 0
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            received += len(block)
        if received != size_bytes:
            raise RuntimeError(f"GET received {received} bytes, expected {size_bytes}")
        return received
    finally:
        connection.close()


def _upload(base_url: str, size_bytes: int, timeout: float) -> int:
    host, path, port = _endpoint(base_url, "/__up", size_bytes)
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    chunk = b"x" * min(1024 * 1024, size_bytes)
    try:
        connection.putrequest("POST", path)
        connection.putheader("Content-Type", "application/octet-stream")
        connection.putheader("Content-Length", str(size_bytes))
        connection.putheader("Cache-Control", "no-cache")
        connection.endheaders()
        sent = 0
        while sent < size_bytes:
            count = min(len(chunk), size_bytes - sent)
            connection.send(chunk[:count])
            sent += count
        response = connection.getresponse()
        response.read()
        if response.status != 200:
            raise RuntimeError(f"POST fixture returned HTTP {response.status}")
        # The fixture responds 200 only after it has consumed the full Content-Length.
        return sent
    finally:
        connection.close()


def _measure_round(
    base_url: str,
    direction: str,
    streams: int,
    bytes_per_stream: int,
    timeout: float,
) -> dict[str, object]:
    transfer = _download if direction == "download" else _upload
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=streams) as pool:
        futures = [
            pool.submit(transfer, base_url, bytes_per_stream, timeout)
            for _ in range(streams)
        ]
        per_stream_bytes = [future.result() for future in futures]
    elapsed = time.perf_counter() - started
    total_bytes = sum(per_stream_bytes)
    expected = streams * bytes_per_stream
    if total_bytes != expected:
        raise RuntimeError(f"measured {total_bytes} bytes, expected {expected}")
    return {
        "direction": direction,
        "streams": streams,
        "bytes_per_stream": bytes_per_stream,
        "total_bytes": total_bytes,
        "elapsed_ms": round(elapsed * 1000.0, 3),
        "mbps": rate_mbps(total_bytes, elapsed),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18765")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--streams", type=int, default=6)
    parser.add_argument("--bytes-per-stream", type=int, default=8_000_000)
    parser.add_argument(
        "--min-mbps",
        type=float,
        default=211.2,
        help="Minimum throughput required in each round (Mbps).",
    )
    parser.add_argument(
        "--max-mbps",
        type=float,
        default=None,
        help=(
            "Optional sanity ceiling for loopback fixture measurements. Values above this "
            "ceiling are marked UNREALISTIC and fail the diagnostic instead of being "
            "interpreted as network headroom."
        ),
    )
    parser.add_argument("--timeout-seconds", type=float, default=60.0)
    parser.add_argument("--output", type=Path, default=Path("fixture-throughput-benchmark.json"))
    args = parser.parse_args()
    if args.rounds < 1 or args.streams < 1 or args.bytes_per_stream < 1 or args.min_mbps <= 0:
        parser.error("rounds, streams, bytes-per-stream and min-mbps must be positive")
    if args.max_mbps is not None and args.max_mbps <= args.min_mbps:
        parser.error("--max-mbps must be greater than --min-mbps")

    results: list[dict[str, object]] = []
    failures: list[str] = []
    for round_number in range(1, args.rounds + 1):
        for direction in ("download", "upload"):
            try:
                result = _measure_round(
                    args.base_url, direction, args.streams, args.bytes_per_stream, args.timeout_seconds
                )
                result["round"] = round_number
                result["minimum_required_mbps"] = args.min_mbps
                measured_mbps = float(result["mbps"])
                if args.max_mbps is not None and measured_mbps > args.max_mbps:
                    result["verdict"] = "UNREALISTIC"
                    result["diagnostic"] = (
                        f"Loopback throughput {measured_mbps:.3f} Mbps exceeded the "
                        f"{args.max_mbps:.3f} Mbps sanity ceiling; this is not evidence "
                        "of end-to-end emulator/VPN network capacity."
                    )
                else:
                    result["verdict"] = "PASS" if measured_mbps >= args.min_mbps else "FAIL"
                print(
                    f"FIXTURE_BENCHMARK direction={direction} round={round_number}/{args.rounds} "
                    f"streams={args.streams} bytes={result['total_bytes']} "
                    f"elapsed_ms={result['elapsed_ms']} Mbps={float(result['mbps']):.3f} "
                    f"minimum_mbps={args.min_mbps} verdict={result['verdict']}",
                    flush=True,
                )
                if result["verdict"] == "FAIL":
                    failures.append(
                        f"{direction} round {round_number}: {result['mbps']} Mbps below {args.min_mbps} Mbps"
                    )
                elif result["verdict"] == "UNREALISTIC":
                    failures.append(
                        f"{direction} round {round_number}: " + str(result["diagnostic"])
                    )
                results.append(result)
            except Exception as error:
                message = f"{direction} round {round_number}: {type(error).__name__}: {error}"
                print(f"FIXTURE_BENCHMARK_ERROR {message}", flush=True)
                failures.append(message)
                results.append({
                    "direction": direction,
                    "round": round_number,
                    "verdict": "FAIL",
                    "error": message,
                    "minimum_required_mbps": args.min_mbps,
                })

    document = {
        "status": "FAIL" if failures else "PASS",
        "base_url": args.base_url,
        "rounds": args.rounds,
        "streams": args.streams,
        "bytes_per_stream": args.bytes_per_stream,
        "minimum_required_mbps": args.min_mbps,
        "maximum_sanity_mbps": args.max_mbps,
        "results": results,
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(f"FIXTURE_BENCHMARK_SUMMARY={document['status']} results={len(results)} failures={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
