#!/usr/bin/env python3
"""Patch and verify HEV's pthread lifecycle so failed joins never discard ownership."""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path


class PatchError(ValueError):
    """Raised when the pinned JNI source differs from the reviewed baseline."""


START_JOIN_OLD = """    if (thread_joinable) {
        pthread_join (work_thread, NULL);
        thread_joinable = 0;
    }"""
START_JOIN_PATCHED = """    if (thread_joinable) {
        res = pthread_join (work_thread, NULL);
        if (res != 0)
            goto exit;
        thread_joinable = 0;
    }"""

STOP_JOIN_OLD = """    res = pthread_join (work_thread, NULL);

    thread_joinable = 0;
    atomic_store_explicit (&is_running, 0, memory_order_release);"""
STOP_JOIN_PATCHED = """    res = pthread_join (work_thread, NULL);
    if (res != 0)
        goto exit;

    thread_joinable = 0;
    atomic_store_explicit (&is_running, 0, memory_order_release);"""


def _replace_state(content: str, old: str, patched: str, label: str) -> tuple[str, bool]:
    old_count = content.count(old)
    patched_count = content.count(patched)
    if old_count == 1 and patched_count == 0:
        return content.replace(old, patched, 1), True
    if old_count == 0 and patched_count == 1:
        return content, False
    raise PatchError(
        f"{label}: expected exactly one baseline or patched block "
        f"(baseline={old_count}, patched={patched_count})"
    )


def check_native_lifecycle_content(content: str) -> None:
    for old, patched, label in (
        (START_JOIN_OLD, START_JOIN_PATCHED, "native_start_service previous-worker join"),
        (STOP_JOIN_OLD, STOP_JOIN_PATCHED, "native_stop_service worker join"),
    ):
        if content.count(old) != 0 or content.count(patched) != 1:
            raise PatchError(f"{label}: checked join-on-success block is not applied exactly once")


def patch_native_lifecycle_content(content: str) -> tuple[str, bool]:
    output = content
    changed = False
    output, start_changed = _replace_state(
        output, START_JOIN_OLD, START_JOIN_PATCHED, "native_start_service previous-worker join"
    )
    changed |= start_changed
    output, stop_changed = _replace_state(
        output, STOP_JOIN_OLD, STOP_JOIN_PATCHED, "native_stop_service worker join"
    )
    changed |= stop_changed
    check_native_lifecycle_content(output)
    return output, changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="path to the pinned HEV src/hev-jni.c")
    parser.add_argument("--check", action="store_true", help="verify without writing")
    args = parser.parse_args()

    try:
        if not args.path.is_file():
            raise PatchError(f"native JNI source does not exist: {args.path}")
        original = args.path.read_text(encoding="utf-8")
        if args.check:
            check_native_lifecycle_content(original)
            changed = False
        else:
            patched, changed = patch_native_lifecycle_content(original)
            # Validate before writing; a drifted file is never partially transformed.
            check_native_lifecycle_content(patched)
            if changed:
                args.path.write_text(patched, encoding="utf-8", newline="")
            check_native_lifecycle_content(args.path.read_text(encoding="utf-8"))

        digest = hashlib.sha256(args.path.read_bytes()).hexdigest()
        print(
            "HEV_NATIVE_LIFECYCLE "
            f"changed={str(changed).lower()} "
            "start-join-failure=retained stop-join-failure=retained "
            f"sha256={digest}"
        )
        return 0
    except (OSError, PatchError) as error:
        print(f"HEV_NATIVE_LIFECYCLE_ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
