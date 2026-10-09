#!/usr/bin/env python3
from __future__ import annotations

import json
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from local_speed_test_server import MAX_PAYLOAD_BYTES, make_server


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

    def test_health_check_returns_json(self) -> None:
        with urlopen(self.base_url + "/healthz", timeout=2) as response:
            self.assertEqual(200, response.status)
            self.assertEqual({"status": "ok"}, json.loads(response.read()))

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
