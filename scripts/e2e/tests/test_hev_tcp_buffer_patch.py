from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from patch_hev_tcp_buffers import PatchError, check_lwip_options_content, patch_lwip_options_content


BASELINE = """#define PBUF_POOL_SIZE                  32
#define TCP_MSS                         8191
#define TCP_WND                         (8 * TCP_MSS)
#define TCP_SND_BUF                     (8 * TCP_MSS)
"""


class HevTcpBufferPatchTests(unittest.TestCase):
    def test_patch_expands_windows_enables_scaling_and_resizes_pool(self) -> None:
        patched, changed = patch_lwip_options_content(BASELINE)
        self.assertTrue(changed)
        values = check_lwip_options_content(patched)
        self.assertEqual("8191", values["TCP_MSS"])
        self.assertEqual("(16 * TCP_MSS)", values["TCP_WND"])
        self.assertEqual("(16 * TCP_MSS)", values["TCP_SND_BUF"])
        self.assertEqual("(4 * TCP_MSS)", values["TCP_SNDLOWAT"])
        self.assertEqual("128", values["PBUF_POOL_SIZE"])
        self.assertEqual("1", values["LWIP_WND_SCALE"])
        self.assertEqual("1", values["TCP_RCV_SCALE"])

    def test_patch_is_idempotent(self) -> None:
        patched, first_changed = patch_lwip_options_content(BASELINE)
        patched_again, second_changed = patch_lwip_options_content(patched)
        self.assertTrue(first_changed)
        self.assertFalse(second_changed)
        self.assertEqual(patched, patched_again)

    def test_unexpected_native_values_fail_closed(self) -> None:
        broken = BASELINE.replace("(8 * TCP_MSS)", "(4 * TCP_MSS)", 1)
        with self.assertRaises(PatchError):
            patch_lwip_options_content(broken)

    def test_duplicate_native_macros_fail_closed(self) -> None:
        broken = BASELINE + "#define TCP_WND (8 * TCP_MSS)\n"
        with self.assertRaises(PatchError):
            patch_lwip_options_content(broken)

    def test_existing_unsafe_send_low_water_mark_is_rejected(self) -> None:
        broken = BASELINE + "#define TCP_SNDLOWAT (8 * TCP_MSS)\n"
        with self.assertRaises(PatchError):
            patch_lwip_options_content(broken)

    def test_verification_rejects_unpatched_config(self) -> None:
        with self.assertRaises(PatchError):
            check_lwip_options_content(BASELINE)


if __name__ == "__main__":
    unittest.main()
