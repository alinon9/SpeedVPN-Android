#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
import re
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from local_speed_test_server import MAX_PAYLOAD_BYTES, SOCKET_RECEIVE_BUFFER_BYTES, make_server


class LocalSpeedTestServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = make_server("127.0.0.1", 0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_listener_and_accepted_sockets_request_large_receive_buffers(self) -> None:
        listener_bytes = self.server.socket.getsockopt(1, 8)  # SOL_SOCKET / SO_RCVBUF on Linux
        self.assertGreater(listener_bytes, 64 * 1024)
        self.assertEqual(4 * 1024 * 1024, SOCKET_RECEIVE_BUFFER_BYTES)

    def test_health_check_reports_effective_accepted_socket_receive_buffer(self) -> None:
        with urlopen(self.base_url + "/healthz", timeout=2) as response:
            self.assertEqual(200, response.status)
            payload = json.loads(response.read())
            self.assertEqual("ok", payload["status"])
            # Linux may clamp SO_RCVBUF according to the runner's kernel policy;
            # the test confirms we expose what was actually negotiated, not just
            # the requested 4 MiB value.
            self.assertGreater(int(payload["socket_receive_buffer_bytes"]), 64 * 1024)

    def test_download_returns_exact_requested_bytes(self) -> None:
        expected = 123_457
        with urlopen(f"{self.base_url}/__down?bytes={expected}&cacheBust=test", timeout=2) as response:
            body = response.read()
            self.assertEqual(200, response.status)
            self.assertEqual(expected, int(response.headers["Content-Length"]))
            self.assertEqual(expected, len(body))

    def test_upload_consumes_exact_body_and_succeeds(self) -> None:
        payload = b"x" * 32_123
        request = Request(
            f"{self.base_url}/__up?bytes={len(payload)}&cacheBust=test",
            data=payload,
            method="POST",
            headers={"Content-Type": "application/octet-stream"},
        )
        with urlopen(request, timeout=2) as response:
            self.assertEqual(200, response.status)
            self.assertEqual(b"", response.read())

    def test_download_logs_actual_transfer_timing(self) -> None:
        expected = 64_321
        with urlopen(f"{self.base_url}/__down?bytes={expected}&cacheBust=timing", timeout=2) as response:
            self.assertEqual(expected, len(response.read()))
        self.assertTrue(self.server.wait_for_transfer_event())
        self.assertRegex(
            "\n".join(self.server.transfer_events),
            re.compile(
                rf"TRANSFER method=GET path=/__down requested_bytes={expected} "
                rf"sent_bytes={expected} elapsed_ms=\d+\.\d{{3}} outcome=complete"
            ),
        )

    def test_upload_logs_actual_transfer_timing(self) -> None:
        payload = b"x" * 65_432
        request = Request(
            f"{self.base_url}/__up?bytes={len(payload)}&cacheBust=timing",
            data=payload,
            method="POST",
            headers={"Content-Type": "application/octet-stream"},
        )
        with urlopen(request, timeout=2) as response:
            self.assertEqual(200, response.status)
            self.assertEqual(b"", response.read())
        self.assertTrue(self.server.wait_for_transfer_event())
        self.assertRegex(
            "\n".join(self.server.transfer_events),
            re.compile(
                rf"TRANSFER method=POST path=/__up requested_bytes={len(payload)} "
                rf"received_bytes={len(payload)} elapsed_ms=\d+\.\d{{3}} outcome=complete"
            ),
        )

    def test_loopback_benchmark_rejects_unrealistic_throughput(self) -> None:
        script = Path(__file__).resolve().parents[1] / "benchmark_local_speed_test_server.py"
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "benchmark.json"
            completed = subprocess.run(
                [
                    sys.executable, str(script),
                    "--base-url", self.base_url,
                    "--rounds", "1",
                    "--streams", "2",
                    "--bytes-per-stream", "200000",
                    "--min-mbps", "1",
                    "--max-mbps", "0.001",
                    "--timeout-seconds", "5",
                    "--output", str(output),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(0, completed.returncode)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("FAIL", report["status"])
            self.assertEqual(0.001, report["maximum_sanity_mbps"])
            self.assertTrue(
                any(
                    item.get("verdict") == "UNREALISTIC"
                    for item in report["results"]
                )
            )

    def test_unknown_route_is_not_misreported_as_success(self) -> None:
        with self.assertRaises(HTTPError) as raised:
            urlopen(self.base_url + "/unknown", timeout=2)
        self.assertEqual(404, raised.exception.code)

    def test_oversized_download_is_rejected(self) -> None:
        with self.assertRaises(HTTPError) as raised:
            urlopen(f"{self.base_url}/__down?bytes={MAX_PAYLOAD_BYTES + 1}", timeout=2)
        self.assertEqual(413, raised.exception.code)

    def test_invalid_upload_length_is_rejected(self) -> None:
        request = Request(
            f"{self.base_url}/__up?bytes=64",
            data=b"x" * 63,
            method="POST",
            headers={"Content-Type": "application/octet-stream"},
        )
        with self.assertRaises(HTTPError) as raised:
            urlopen(request, timeout=2)
        self.assertEqual(400, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
