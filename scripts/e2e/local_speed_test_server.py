#!/usr/bin/env python3
"""Runner-local Cloudflare-compatible fixture for deterministic VPN limiter tests.

Only the CI/debug app is configured to use this endpoint. Release builds continue
using https://speed.cloudflare.com. This server deliberately uses the stdlib only.
"""
from __future__ import annotations

import argparse
import json
import re
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
        diagnostic_id = parse_qs(urlsplit(self.path).query).get("diagId", ["none"])[0]
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", diagnostic_id):
            diagnostic_id = "invalid"
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
        close_reason = "unexpected_eof"
        exception_type = "none"
        response_started = False
        response_sent = False
        status = "not_sent"
        try:
            remaining = content_length
            while remaining:
                chunk = self.rfile.read(min(CHUNK_BYTES, remaining))
                if not chunk:
                    close_reason = "peer_closed_before_body_complete"
                    status = "400"
                    response_started = True
                    self._send_body(400, b"upload body ended before Content-Length")
                    response_sent = True
                    self.close_connection = True
                    return
                remaining -= len(chunk)
                received += len(chunk)

            status = "200"
            response_started = True
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.send_header("Cache-Control", "no-cache, no-store")
            self.end_headers()
            response_sent = True
            completed = True
            close_reason = "normal"
        except (BrokenPipeError, ConnectionResetError) as error:
            exception_type = error.__class__.__name__
            close_reason = "peer_reset_or_broken_pipe"
            self.close_connection = True
            raise
        except TimeoutError as error:
            exception_type = error.__class__.__name__
            close_reason = "timeout"
            self.close_connection = True
            raise
        except Exception as error:
            exception_type = error.__class__.__name__
            close_reason = "server_error"
            self.close_connection = True
            raise
        finally:
            elapsed_ms = (time.monotonic_ns() - started_ns) / 1_000_000
            self._record_transfer_event(
                f"UPLOAD_DIAG method=POST path=/__up diagId={diagnostic_id} serverPort={self.server.server_port} "
                f"peerPort={self.client_address[1]} requestedBytes={size} contentLength={content_length} "
                f"receivedBytes={received} bodyComplete={str(received == content_length).lower()} "
                f"responseStarted={str(response_started).lower()} responseSent={str(response_sent).lower()} "
                f"httpStatus={status} elapsedMs={elapsed_ms:.3f} closeReason={close_reason} exceptionType={exception_type} "
                f"socketClosed={self.connection.fileno() < 0} socketReceiveBufferBytes={self.socket_receive_buffer_bytes}"
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be in 1..65535")
    server = make_server(args.host, args.port)
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
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
