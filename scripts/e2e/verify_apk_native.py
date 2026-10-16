#!/usr/bin/env python3
"""Fail-closed APK fingerprint and native-library presence check."""
import argparse
import hashlib
import zipfile
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
LIBRARY = "libhev-socks5-tunnel.so"

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--variant", choices=("debug", "release"), required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.apk.is_file() or args.apk.stat().st_size == 0:
        raise SystemExit(f"APK missing or empty: {args.apk}")
    with zipfile.ZipFile(args.apk) as archive:
        names = set(archive.namelist())
        for abi in ABIS:
            member = f"lib/{abi}/{LIBRARY}"
            if member not in names:
                raise SystemExit(f"APK missing native library: {member}")
            payload_hash = hashlib.sha256(archive.read(member)).hexdigest()
            print(f"{abi}_native_sha256={payload_hash}")
    digest = sha256(args.apk)
    print(f"variant={args.variant}")
    print(f"apk_sha256={digest}")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(f"variant={args.variant}\napk_sha256={digest}\n", encoding="utf-8")

if __name__ == "__main__":
    main()
