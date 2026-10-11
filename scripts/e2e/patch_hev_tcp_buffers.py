#!/usr/bin/env python3
"""Patch and verify the pinned HEV/LwIP TCP windows used by SpeedVPN's TUN path."""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path


class PatchError(ValueError):
    """Raised when the pinned native source no longer matches the reviewed baseline."""


def _macro_lines(lines: list[str], name: str) -> list[int]:
    pattern = re.compile(r"^[ \t]*#[ \t]*define[ \t]+" + re.escape(name) + r"\b")
    return [index for index, line in enumerate(lines) if pattern.match(line)]


def _set_macro(
    lines: list[str],
    name: str,
    allowed_values: set[str],
    desired_value: str,
) -> bool:
    matches = _macro_lines(lines, name)
    if len(matches) != 1:
        raise PatchError(f"{name}: expected exactly one definition, found {len(matches)}")
    index = matches[0]
    line = lines[index]
    match = re.match(
        r"^([ \t]*#[ \t]*define[ \t]+" + re.escape(name) + r"[ \t]+)(.*?)(\r?\n)?$",
        line,
    )
    if match is None:
        raise PatchError(f"{name}: unable to parse definition: {line.rstrip()}")
    current_value = match.group(2).strip()
    if current_value not in allowed_values:
        raise PatchError(
            f"{name}: unexpected value {current_value!r}; expected one of {sorted(allowed_values)}"
        )
    if current_value == desired_value:
        return False
    lines[index] = match.group(1) + desired_value + (match.group(3) or "")
    return True


def _read_macro(lines: list[str], name: str) -> str:
    matches = _macro_lines(lines, name)
    if len(matches) != 1:
        raise PatchError(f"{name}: expected exactly one definition, found {len(matches)}")
    match = re.match(
        r"^[ \t]*#[ \t]*define[ \t]+" + re.escape(name) + r"[ \t]+(.*?)[ \t]*(?:\r?\n)?$",
        lines[matches[0]],
    )
    if match is None:
        raise PatchError(f"{name}: unable to parse definition")
    return match.group(1).strip()


def check_lwip_options_content(content: str) -> dict[str, str]:
    lines = content.splitlines(keepends=True)
    expected = {
        "TCP_MSS": "8191",
        "TCP_WND": "(16 * TCP_MSS)",
        "TCP_SND_BUF": "(16 * TCP_MSS)",
        "PBUF_POOL_SIZE": "128",
        "LWIP_WND_SCALE": "1",
        "TCP_RCV_SCALE": "1",
    }
    actual = {name: _read_macro(lines, name) for name in expected}
    mismatches = [
        f"{name}={actual[name]!r} (expected {expected[name]!r})"
        for name in expected
        if actual[name] != expected[name]
    ]
    if mismatches:
        raise PatchError("native TCP configuration not applied: " + "; ".join(mismatches))
    return actual


def patch_lwip_options_content(content: str) -> tuple[str, bool]:
    lines = content.splitlines(keepends=True)
    changed = False
    changed |= _set_macro(lines, "TCP_MSS", {"8191"}, "8191")
    changed |= _set_macro(lines, "TCP_WND", {"(8 * TCP_MSS)", "(16 * TCP_MSS)"}, "(16 * TCP_MSS)")
    changed |= _set_macro(lines, "TCP_SND_BUF", {"(8 * TCP_MSS)", "(16 * TCP_MSS)"}, "(16 * TCP_MSS)")
    changed |= _set_macro(lines, "PBUF_POOL_SIZE", {"32", "128"}, "128")

    # lwIP's generic defaults disable scaling when these macros are absent.
    # Define them in the port config before TCP_WND so receive-window scaling
    # and the sender-side queue have enough headroom on higher-RTT paths.
    for name in ("LWIP_WND_SCALE", "TCP_RCV_SCALE"):
        indexes = _macro_lines(lines, name)
        if not indexes:
            tcp_wnd_indexes = _macro_lines(lines, "TCP_WND")
            if len(tcp_wnd_indexes) != 1:
                raise PatchError(f"TCP_WND: expected exactly one definition, found {len(tcp_wnd_indexes)}")
            index = tcp_wnd_indexes[0]
            newline = "\r\n" if index > 0 and lines[index - 1].endswith("\r\n") else "\n"
            lines.insert(index, f"#define {name:<29} 1{newline}")
            changed = True
        else:
            changed |= _set_macro(lines, name, {"0", "1"}, "1")

    output = "".join(lines)
    check_lwip_options_content(output)
    return output, changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="path to the pinned HEV/LwIP lwipopts.h")
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the intended native configuration without writing",
    )
    args = parser.parse_args()

    try:
        if not args.path.is_file():
            raise PatchError(f"native options file does not exist: {args.path}")
        original = args.path.read_text(encoding="utf-8")
        if args.check:
            values = check_lwip_options_content(original)
            changed = False
        else:
            patched, changed = patch_lwip_options_content(original)
            if changed:
                args.path.write_text(patched, encoding="utf-8", newline="")
            values = check_lwip_options_content(args.path.read_text(encoding="utf-8"))
        digest = hashlib.sha256(args.path.read_bytes()).hexdigest()
        print(
            "HEV_TCP_CONFIG "
            + " ".join(f"{name}={values[name]}" for name in (
                "TCP_MSS", "TCP_WND", "TCP_SND_BUF", "PBUF_POOL_SIZE",
                "LWIP_WND_SCALE", "TCP_RCV_SCALE",
            ))
            + f" tcp-buffer-size=131056 changed={str(changed).lower()} sha256={digest}"
        )
        return 0
    except (OSError, PatchError) as error:
        print(f"HEV_TCP_CONFIG_ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
