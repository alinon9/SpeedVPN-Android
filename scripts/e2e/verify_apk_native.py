#!/usr/bin/env python3
"""Verify APK identity and prove each packaged HEV library came from this build."""
from __future__ import annotations

import argparse
import hashlib
import zipfile
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
LIBRARY = "libhev-socks5-tunnel.so"


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_built_library(root: Path, variant: str, abi: str) -> Path:
    """Find the current Gradle packaged-native output, preferring stripped libs."""
    if not root.is_dir():
        raise SystemExit(f"Native build root does not exist: {root}")

    direct = root / abi / LIBRARY
    if direct.is_file():
        return direct

    candidates = []
    for path in root.rglob(LIBRARY):
        if path.parent.name != abi:
            continue
        parts = [part.lower() for part in path.parts]
        if variant.lower() not in parts:
            continue
        candidates.append(path)

    if not candidates:
        raise SystemExit(
            f"No current-build {LIBRARY} found for ABI={abi}, variant={variant} under {root}"
        )

    # APK entries are normally copied from stripped_native_libs. If that output
    # is absent in this AGP version, fall back to merged_native_libs and still
    # require an exact byte-for-byte hash match against the APK entry.
    stripped = [p for p in candidates if "stripped_native_libs" in [x.lower() for x in p.parts]]
    preferred = stripped or [
        p for p in candidates
        if "merged_native_libs" in [x.lower() for x in p.parts]
    ] or candidates
    preferred.sort(key=lambda p: (len(p.parts), str(p)))
    return preferred[0]


def parse_fingerprint(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise SystemExit(f"Expected build fingerprint does not exist: {path}")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compare packaged APK native-library hashes with the current build or its fingerprint."
    )
    parser.add_argument("apk", type=Path)
    parser.add_argument("--variant", choices=("debug", "release"), required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument(
        "--native-build-root",
        type=Path,
        help="Gradle intermediates root containing stripped_native_libs or merged_native_libs",
    )
    source.add_argument(
        "--expected-fingerprint",
        type=Path,
        help="Fingerprint emitted by the build job for a downloaded E2E APK",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    if not args.apk.is_file() or args.apk.stat().st_size == 0:
        raise SystemExit(f"APK missing or empty: {args.apk}")

    apk_digest = sha256_file(args.apk)
    with zipfile.ZipFile(args.apk) as archive:
        names = set(archive.namelist())
        packaged: dict[str, str] = {}
        for abi in ABIS:
            member = f"lib/{abi}/{LIBRARY}"
            if member not in names:
                raise SystemExit(f"APK missing native library: {member}")
            packaged[abi] = sha256_bytes(archive.read(member))

    fingerprint_lines = [f"variant={args.variant}", f"apk_sha256={apk_digest}"]
    for abi in ABIS:
        packaged_hash = packaged[abi]
        if args.native_build_root:
            built_file = find_built_library(args.native_build_root, args.variant, abi)
            built_hash = sha256_file(built_file)
            if packaged_hash != built_hash:
                raise SystemExit(
                    f"NATIVE_FINGERPRINT_MISMATCH variant={args.variant} abi={abi} "
                    f"apk={packaged_hash} build={built_hash} source={built_file}"
                )
            fingerprint_lines.append(f"{abi}_native_sha256={packaged_hash}")
            fingerprint_lines.append(f"{abi}_native_source={built_file.as_posix()}")
            print(f"NATIVE_FINGERPRINT_MATCH variant={args.variant} abi={abi} sha256={packaged_hash}")
        else:
            expected = parse_fingerprint(args.expected_fingerprint)
            if expected.get("variant") != args.variant:
                raise SystemExit(
                    f"FINGERPRINT_VARIANT_MISMATCH expected={expected.get('variant')} actual={args.variant}"
                )
            if expected.get("apk_sha256") != apk_digest:
                raise SystemExit(
                    f"APK_FINGERPRINT_MISMATCH expected={expected.get('apk_sha256')} actual={apk_digest}"
                )
            expected_hash = expected.get(f"{abi}_native_sha256")
            if not expected_hash:
                raise SystemExit(f"Expected fingerprint missing {abi}_native_sha256")
            if packaged_hash != expected_hash:
                raise SystemExit(
                    f"NATIVE_FINGERPRINT_MISMATCH variant={args.variant} abi={abi} "
                    f"artifact={packaged_hash} expected={expected_hash}"
                )
            fingerprint_lines.append(f"{abi}_native_sha256={packaged_hash}")
            print(f"NATIVE_ARTIFACT_MATCH variant={args.variant} abi={abi} sha256={packaged_hash}")

    print(f"variant={args.variant}")
    print(f"apk_sha256={apk_digest}")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text("\n".join(fingerprint_lines) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
