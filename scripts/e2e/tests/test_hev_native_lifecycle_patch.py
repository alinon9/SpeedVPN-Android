from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from patch_hev_native_lifecycle import (
    PatchError,
    check_native_lifecycle_content,
    patch_native_lifecycle_content,
)


JNI_BASELINE = """static jboolean
native_start_service (JNIEnv *env, jobject thiz, jstring config_path, jint fd)
{
    int res;
    if (thread_joinable) {
        pthread_join (work_thread, NULL);
        thread_joinable = 0;
    }
    res = pthread_create (&work_thread, NULL, thread_handler, tdata);
    return JNI_TRUE;
}

static jboolean
native_stop_service (JNIEnv *env, jobject thiz)
{
    int res = 0;
    res = pthread_join (work_thread, NULL);

    thread_joinable = 0;
    atomic_store_explicit (&is_running, 0, memory_order_release);
    return res == 0 ? JNI_TRUE : JNI_FALSE;
}
"""


class HevNativeLifecyclePatchTests(unittest.TestCase):
    def test_patch_checks_both_native_join_results(self) -> None:
        patched, changed = patch_native_lifecycle_content(JNI_BASELINE)
        self.assertTrue(changed)
        check_native_lifecycle_content(patched)
        self.assertIn(
            "res = pthread_join (work_thread, NULL);\n"
            "        if (res != 0)\n"
            "            goto exit;\n"
            "        thread_joinable = 0;",
            patched,
        )
        self.assertIn(
            "res = pthread_join (work_thread, NULL);\n"
            "    if (res != 0)\n"
            "        goto exit;\n\n"
            "    thread_joinable = 0;",
            patched,
        )

    def test_patch_is_idempotent(self) -> None:
        patched, first_changed = patch_native_lifecycle_content(JNI_BASELINE)
        patched_again, second_changed = patch_native_lifecycle_content(patched)
        self.assertTrue(first_changed)
        self.assertFalse(second_changed)
        self.assertEqual(patched, patched_again)

    def test_start_keeps_worker_joinable_after_join_failure(self) -> None:
        patched, _ = patch_native_lifecycle_content(JNI_BASELINE)
        start = patched.split("static jboolean\nnative_stop_service", 1)[0]
        self.assertLess(
            start.index("if (res != 0)\n            goto exit;"),
            start.index("thread_joinable = 0;"),
        )

    def test_stop_does_not_clear_worker_state_after_join_failure(self) -> None:
        patched, _ = patch_native_lifecycle_content(JNI_BASELINE)
        stop = patched.split("static jboolean\nnative_stop_service", 1)[1]
        self.assertLess(
            stop.index("if (res != 0)\n        goto exit;"),
            stop.index("thread_joinable = 0;"),
        )
        self.assertLess(
            stop.index("if (res != 0)\n        goto exit;"),
            stop.index("atomic_store_explicit (&is_running, 0, memory_order_release);"),
        )

    def test_unreviewed_native_source_is_rejected(self) -> None:
        changed_source = JNI_BASELINE.replace(
            "pthread_join (work_thread, NULL);",
            "pthread_join (unexpected_thread, NULL);",
            1,
        )
        with self.assertRaises(PatchError):
            patch_native_lifecycle_content(changed_source)

    def test_duplicate_join_anchor_is_rejected(self) -> None:
        changed_source = JNI_BASELINE.replace(
            "    if (thread_joinable) {",
            "    if (thread_joinable) {\n    if (thread_joinable) {",
            1,
        )
        with self.assertRaises(PatchError):
            patch_native_lifecycle_content(changed_source)


if __name__ == "__main__":
    unittest.main()
