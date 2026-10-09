#!/usr/bin/env python3
"""Runner-local Cloudflare-compatible fixture for deterministic VPN limiter tests.

Only the CI/debug app is configured to use this endpoint. Release builds continue
using https://speed.cloudflare.com. This server deliberately uses the stdlib only.
"""
from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MAX_PAYLOAD_BYTES = 25_000_000
CHUNK_BYTES = 64 * 1024


class SpeedTestRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "SpeedVPN-CI-SpeedTest/1.0"
    sys_version = ""

    def _send_body(self, status: int, body: bytes, content_type: str = "text/plain") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache, no-store")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _requested_size(self) -> int | None:
        values = parse_qs(urlsplit(self.path).query).get("bytes")
        if not values:
            return None
        try:
            size = int(values[0])
        except (TypeError, ValueError):
            return None
        if size <= 0:
            return None
        return size

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path == "/healthz":
            self._send_body(200, json.dumps({"status": "ok"}).encode(), "application/json")
            return
        if path != "/__down":
            self._send_body(404, b"not found")
            return

        size = self._requested_size()
        if size is None:
            self._send_body(400, b"query parameter bytes must be a positive integer")
            return
        if size > MAX_PAYLOAD_BYTES:
            self._send_body(413, b"requested payload exceeds fixture limit")
            return

        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-cache, no-store")
        self.end_headers()
        block = b"\x00" * CHUNK_BYTES
        remaining = size
        try:
            while remaining:
                count = min(remaining, len(block))
                self.wfile.write(block[:count])
                remaining -= count
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            # A cancelled sample can close the connection before the payload ends.
            self.close_connection = True

    def do_POST(self) -> None:
        if urlsplit(self.path).path != "/__up":
            self._send_body(404, b"not found")
            self.close_connection = True
            return

        size = self._requested_size()
        try:
            content_length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            content_length = -1
        if size is None or size > MAX_PAYLOAD_BYTES:
            self._send_body(413 if size is not None else 400, b"invalid upload size")
            self.close_connection = True
            return
        if content_length != size:
            self._send_body(400, b"query bytes must match Content-Length")
            self.close_connection = True
            return

        remaining = content_length
        while remaining:
            chunk = self.rfile.read(min(CHUNK_BYTES, remaining))
            if not chunk:
                self._send_body(400, b"upload body ended before Content-Length")
                self.close_connection = True
                return
            remaining -= len(chunk)

        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-cache, no-store")
        self.end_headers()

    def log_message(self, format_string: str, *args: object) -> None:
        # Request counts are high during the sweep; keep the artifact log concise.
        if urlsplit(self.path).path == "/healthz":
            return
        super().log_message(format_string, *args)


class ReusableThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def make_server(host: str, port: int) -> ReusableThreadingHTTPServer:
    return ReusableThreadingHTTPServer((host, port), SpeedTestRequestHandler)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be in 1..65535")
    server = make_server(args.host, args.port)
    print(f"SpeedVPN local speed-test fixture listening on {args.host}:{args.port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
