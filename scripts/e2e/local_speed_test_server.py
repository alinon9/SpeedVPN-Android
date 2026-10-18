#!/usr/bin/env python3
"""Runner-local Cloudflare-compatible fixture for deterministic VPN limiter tests.

Only the CI/debug app is configured to use this endpoint. Release builds continue
using https://speed.cloudflare.com. This server deliberately uses the stdlib only.
"""
from __future__ import annotations

import argparse
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MAX_PAYLOAD_BYTES = 25_000_000
CHUNK_BYTES = 1024 * 1024
SOCKET_RECEIVE_BUFFER_BYTES = 4 * 1024 * 1024


class SpeedTestRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self) -> None:
        super().setup()
        # Apply a larger receive window before reading an upload body. The listener
        # sets the same option before listen(), so TCP window scaling is negotiated
        # with the larger requested capacity from the beginning of the connection.
        self.connection.setsockopt(
            socket.SOL_SOCKET, socket.SO_RCVBUF, SOCKET_RECEIVE_BUFFER_BYTES
        )
        self.socket_receive_buffer_bytes = self.connection.getsockopt(
            socket.SOL_SOCKET, socket.SO_RCVBUF
        )
    server_version = "SpeedVPN-CI-SpeedTest/1.0"
    sys_version = ""

    def _record_transfer_event(self, event: str) -> None:
        # Match this peer port to the upstream SOCKS socket's local port in relay logs.
        peer = f"{self.client_address[0]}:{self.client_address[1]}"
        correlated = f"{event} peer={peer}"
        print(correlated, flush=True)
        recorder = getattr(self.server, "record_transfer_event", None)
        if callable(recorder):
            recorder(correlated)

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
            self._send_body(
                200,
                json.dumps({
                    "status": "ok",
                    "socket_receive_buffer_bytes": self.socket_receive_buffer_bytes,
                }).encode(),
                "application/json",
            )
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

        started_ns = time.monotonic_ns()
        sent = 0
        completed = False
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-cache, no-store")
            self.end_headers()
            block = b"\x00" * CHUNK_BYTES
            remaining = size
            while remaining:
                count = min(remaining, len(block))
                self.wfile.write(block[:count])
                remaining -= count
                sent += count
            self.wfile.flush()
            completed = True
        except (BrokenPipeError, ConnectionResetError):
            # A cancelled sample can close the connection before the payload ends.
            self.close_connection = True
        finally:
            elapsed_ms = (time.monotonic_ns() - started_ns) / 1_000_000
            outcome = "complete" if completed else "aborted"
            self._record_transfer_event(
                f"TRANSFER method=GET path=/__down requested_bytes={size} "
                f"sent_bytes={sent} elapsed_ms={elapsed_ms:.3f} outcome={outcome} "
                f"socket_receive_buffer_bytes={self.socket_receive_buffer_bytes}"
            )
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
            # Consume small, already-bounded request bodies before returning the
            # 400. Otherwise some clients (notably Windows' HTTP stack) reset the
            # connection while the response is being read because the server
            # closed with unread request bytes still in the socket.
            if 0 <= content_length <= MAX_PAYLOAD_BYTES:
                self.rfile.read(content_length)
            self._send_body(400, b"query bytes must match Content-Length")
            self.close_connection = True
            return

        started_ns = time.monotonic_ns()
        received = 0
        completed = False
        try:
            remaining = content_length
            while remaining:
                chunk = self.rfile.read(min(CHUNK_BYTES, remaining))
                if not chunk:
                    self._send_body(400, b"upload body ended before Content-Length")
                    self.close_connection = True
                    return
                remaining -= len(chunk)
                received += len(chunk)

            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.send_header("Cache-Control", "no-cache, no-store")
            self.end_headers()
            completed = True
        finally:
            elapsed_ms = (time.monotonic_ns() - started_ns) / 1_000_000
            outcome = "complete" if completed else "aborted"
            self._record_transfer_event(
                f"TRANSFER method=POST path=/__up requested_bytes={size} "
                f"received_bytes={received} elapsed_ms={elapsed_ms:.3f} outcome={outcome} "
                f"socket_receive_buffer_bytes={self.socket_receive_buffer_bytes}"
            )
    def log_message(self, format_string: str, *args: object) -> None:
        # Never print a malformed request line: it can include binary upload data.
        # Keep enough information to match an HTTP parse error to its TCP peer.
        path = urlsplit(self.path).path
        if path == "/healthz":
            return
        peer = f"{self.client_address[0]}:{self.client_address[1]}"
        if format_string.startswith("code %d, message %s"):
            status = args[0] if args else "unknown"
            print(f"HTTP_PARSE_ERROR peer={peer} status={status}", flush=True)
            return
        if format_string.startswith('"%s" %s %s'):
            method = self.command if self.command in {"GET", "POST"} else "UNKNOWN"
            safe_path = path if path in {"/__up", "/__down"} else "<unknown>"
            status = args[1] if len(args) > 1 else "unknown"
            print(f"HTTP_ACCESS peer={peer} method={method} path={safe_path} status={status}", flush=True)
            return
        print(f"HTTP_DIAGNOSTIC peer={peer} event={format_string.split('%', 1)[0][:80]}", flush=True)


class ReusableThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

    def server_bind(self) -> None:
        # Enlarge the passive socket before listen/accept so the receive window
        # can be negotiated for the local Android-emulator upload path.
        self.socket.setsockopt(
            socket.SOL_SOCKET, socket.SO_RCVBUF, SOCKET_RECEIVE_BUFFER_BYTES
        )
        super().server_bind()

    def __init__(self, server_address: tuple[str, int], handler_class: type[BaseHTTPRequestHandler]) -> None:
        super().__init__(server_address, handler_class)
        self.transfer_events: list[str] = []
        self._transfer_events_lock = threading.Lock()
        self._transfer_event_added = threading.Event()

    def record_transfer_event(self, event: str) -> None:
        with self._transfer_events_lock:
            self.transfer_events.append(event)
        self._transfer_event_added.set()

    def wait_for_transfer_event(self, timeout: float = 1.0) -> bool:
        return self._transfer_event_added.wait(timeout)


def make_server(host: str, port: int) -> ReusableThreadingHTTPServer:
    return ReusableThreadingHTTPServer((host, port), SpeedTestRequestHandler)


def serve_udp_echo(sock: socket.socket, stop_event: threading.Event) -> None:
    """Echo fixture datagrams so the Android E2E app can verify per-UID UDP policy."""
    sock.settimeout(0.5)
    print(f"SpeedVPN UDP echo fixture listening on {sock.getsockname()}", flush=True)
    while not stop_event.is_set():
        try:
            payload, peer = sock.recvfrom(65_507)
        except socket.timeout:
            continue
        except OSError:
            if stop_event.is_set():
                break
            raise
        try:
            sock.sendto(payload, peer)
            print(f"UDP_ECHO peer={peer[0]}:{peer[1]} bytes={len(payload)}", flush=True)
        except OSError as error:
            if not stop_event.is_set():
                print(f"UDP_ECHO_ERROR peer={peer[0]}:{peer[1]} error={error}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--udp-port", type=int, default=18766)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be in 1..65535")
    if not 1 <= args.udp_port <= 65535 or args.udp_port == args.port:
        parser.error("--udp-port must be in 1..65535 and differ from --port")
    server = make_server(args.host, args.port)
    udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    udp_socket.bind((args.host, args.udp_port))
    udp_stop = threading.Event()
    udp_thread = threading.Thread(
        target=serve_udp_echo, args=(udp_socket, udp_stop),
        name="speedvpn-udp-fixture", daemon=True,
    )
    udp_thread.start()
    effective_rcvbuf = server.socket.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)
    print(
        f"SpeedVPN local speed-test fixture listening on {args.host}:{args.port}; "
        f"requested_receive_buffer_bytes={SOCKET_RECEIVE_BUFFER_BYTES} "
        f"effective_listener_receive_buffer_bytes={effective_rcvbuf}",
        flush=True,
    )
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        udp_stop.set()
        udp_socket.close()
        udp_thread.join(timeout=1.0)
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
