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
        # Runtime TUN MTU starts at 1280 bytes and IPv6 is enabled by default.
        # 1280 - 40-byte IPv6 header - 20-byte TCP header = 1220-byte safe MSS.
        "TCP_MSS": "1220",
        # Keep >300 KiB in flight so the native stack's TCP window does not cap
        # the high presets before the app-level token bucket can be evaluated.
        "TCP_WND": "(256 * TCP_MSS)",
        "TCP_SND_BUF": "(256 * TCP_MSS)",
        "TCP_SNDLOWAT": "(4 * TCP_MSS)",
        "PBUF_POOL_SIZE": "512",
        "LWIP_WND_SCALE": "1",
        "TCP_RCV_SCALE": "3",
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
    # Runtime supports MTU 1280..1500 and IPv6 by default. Use a conservative
    # IPv6-safe MSS for the minimum MTU instead of the old 8191-byte pseudo-MSS.
    changed |= _set_macro(lines, "TCP_MSS", {"8191", "1220"}, "1220")
    # The old 16-MSS receive window and window-scale shift allowed too little
    # bandwidth-delay product for sustained high-rate transfers.
    changed |= _set_macro(
        lines, "TCP_WND",
        {"(8 * TCP_MSS)", "(16 * TCP_MSS)", "(256 * TCP_MSS)"},
        "(256 * TCP_MSS)",
    )
    changed |= _set_macro(
        lines, "TCP_SND_BUF",
        {"(8 * TCP_MSS)", "(16 * TCP_MSS)", "(32 * TCP_MSS)", "(256 * TCP_MSS)"},
        "(256 * TCP_MSS)",
    )
    sndlowat_indexes = _macro_lines(lines, "TCP_SNDLOWAT")
    if not sndlowat_indexes:
        anchor_indexes = _macro_lines(lines, "TCP_SND_BUF")
        if len(anchor_indexes) != 1:
            raise PatchError(f"TCP_SND_BUF: expected exactly one definition, found {len(anchor_indexes)}")
        anchor = anchor_indexes[0]
        newline = "\r\n" if anchor > 0 and lines[anchor - 1].endswith("\r\n") else "\n"
        lines.insert(anchor, f'#define {"TCP_SNDLOWAT":<29} (4 * TCP_MSS){newline}')
        changed = True
    else:
        changed |= _set_macro(lines, "TCP_SNDLOWAT", {"(4 * TCP_MSS)"}, "(4 * TCP_MSS)")
    changed |= _set_macro(lines, "PBUF_POOL_SIZE", {"32", "128", "256", "512"}, "512")

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
            inserted_value = "1" if name == "LWIP_WND_SCALE" else "3"
            lines.insert(index, f"#define {name:<29} {inserted_value}{newline}")
            changed = True
        else:
            if name == "LWIP_WND_SCALE":
                changed |= _set_macro(lines, name, {"0", "1"}, "1")
            else:
                # TCP_WND=312,320 needs a 3-bit receive-window scale:
                # 312,320 >> 3 = 39,040, which fits the 16-bit TCP header field.
                changed |= _set_macro(lines, name, {"0", "1", "2", "3"}, "3")

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
            + f" tcp-buffer-size=312320 changed={str(changed).lower()} sha256={digest}"
        )
        return 0
    except (OSError, PatchError) as error:
        print(f"HEV_TCP_CONFIG_ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
