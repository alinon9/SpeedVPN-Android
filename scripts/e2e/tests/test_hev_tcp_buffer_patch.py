from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from patch_hev_tcp_buffers import (
    PatchError,
    check_lwip_options_content,
    check_tcp_session_content,
    patch_lwip_options_content,
    patch_tcp_session_content,
)


BASELINE = """#define PBUF_POOL_SIZE                  32
#define TCP_MSS                         8191
#define TCP_WND                         (8 * TCP_MSS)
#define TCP_SND_BUF                     (8 * TCP_MSS)
"""


TCP_SESSION_BASELINE = """static int
tcp_splice_b (HevSocks5SessionTCP *self)
{
    struct iovec iov[2];
    err_t err = ERR_OK;
    int res = 1, iovc;

    iovc = hev_ring_buffer_writing (self->buffer, iov);
    if (iovc) {
        ssize_t s = readv (HEV_SOCKS5 (self)->fd, iov, iovc);
        if (0 >= s) {
            if ((0 > s) && (EAGAIN == errno))
                res = 0;
            else
                res = -1;
        } else {
            hev_ring_buffer_write_finish (self->buffer, s);
        }
    } else {
        res = 0;
    }

    hev_task_mutex_lock (self->mutex);
    if (self->pcb) {
        iovc = hev_ring_buffer_reading (self->buffer, iov);
        if (iovc) {
            ssize_t s = 0;
            int i;
            for (i = 0; i < iovc; i++) {
                void *ptr = iov[i].iov_base;
                size_t len = iov[i].iov_len;
                err |= tcp_write (self->pcb, ptr, len, 0);
                s += len;
            }
            hev_ring_buffer_read_finish (self->buffer, s);
            err |= tcp_output (self->pcb);
            res = 1;
        } else if (res < 0) {
            tcp_shutdown (self->pcb, 0, 1);
        }
    }
    hev_task_mutex_unlock (self->mutex);
    if (!self->pcb || (err != ERR_OK))
        res = -1;

    return res;
}

static err_t
tcp_recv_handler (void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t err)
{
    return ERR_OK;
}
"""

class HevTcpBufferPatchTests(unittest.TestCase):
    def test_patch_expands_windows_enables_scaling_and_resizes_pool(self) -> None:
        patched, changed = patch_lwip_options_content(BASELINE)
        self.assertTrue(changed)
        values = check_lwip_options_content(patched)
        self.assertEqual("1220", values["TCP_MSS"])
        self.assertEqual("(256 * TCP_MSS)", values["TCP_WND"])
        self.assertEqual("65535", values["TCP_SND_BUF"])
        self.assertEqual("(4 * (((TCP_SND_BUF) + (TCP_MSS - 1)) / (TCP_MSS)))", values["TCP_SND_QUEUELEN"])
        self.assertEqual("(4 * TCP_MSS)", values["TCP_SNDLOWAT"])
        self.assertEqual("512", values["PBUF_POOL_SIZE"])
        self.assertEqual("1", values["LWIP_WND_SCALE"])
        self.assertEqual("3", values["TCP_RCV_SCALE"])

    def test_native_tcp_profile_fits_minimum_ipv6_tun_mtu_and_high_rate_window(self) -> None:
        patched, _ = patch_lwip_options_content(BASELINE)
        values = check_lwip_options_content(patched)

        self.assertLessEqual(int(values["TCP_MSS"]), 1280 - 40 - 20)
        self.assertEqual(256 * int(values["TCP_MSS"]), 312_320)
        self.assertEqual("(256 * TCP_MSS)", values["TCP_WND"])
        # lwIP tcp_pcb.snd_buf is u16_t; never overflow its 65,535-byte range.
        self.assertEqual("65535", values["TCP_SND_BUF"])
        self.assertLessEqual(int(values["TCP_SND_BUF"]), 65_535)
        # lwIP advertises TCP_WND >> TCP_RCV_SCALE in the 16-bit TCP header.
        self.assertLessEqual((256 * int(values["TCP_MSS"])) >> int(values["TCP_RCV_SCALE"]), 65_535)
        self.assertGreaterEqual(int(values["PBUF_POOL_SIZE"]), 256)

    def test_native_tcp_splice_retains_unaccepted_bytes_on_lwip_backpressure(self) -> None:
        patched, changed = patch_tcp_session_content(TCP_SESSION_BASELINE)
        self.assertTrue(changed)
        check_tcp_session_content(patched)
        start = patched.index("static int\ntcp_splice_b (HevSocks5SessionTCP *self)\n{")
        end = patched.index("\nstatic err_t\ntcp_recv_handler", start)
        function = patched[start:end]
        self.assertIn("tcp_sndbuf (self->pcb)", function)
        self.assertIn("if (write_err == ERR_MEM)", function)
        self.assertIn("TCP_WRITE_FLAG_COPY", function)
        self.assertIn("hev_ring_buffer_read_finish (self->buffer, queued);", function)
        self.assertNotIn("hev_ring_buffer_read_finish (self->buffer, s);", function)
        self.assertNotIn("s += len;", function)

    def test_native_tcp_splice_patch_is_idempotent(self) -> None:
        patched, changed = patch_tcp_session_content(TCP_SESSION_BASELINE)
        patched_again, changed_again = patch_tcp_session_content(patched)
        self.assertTrue(changed)
        self.assertFalse(changed_again)
        self.assertEqual(patched, patched_again)
        check_tcp_session_content(patched_again)

    def test_native_tcp_splice_patch_rejects_unreviewed_source(self) -> None:
        broken = TCP_SESSION_BASELINE.replace(
            "err |= tcp_write (self->pcb, ptr, len, 0);",
            "err = ERR_OK;",
        )
        with self.assertRaises(PatchError):
            patch_tcp_session_content(broken)

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
