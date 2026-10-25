#!/usr/bin/env bash
# Note on archive extraction & execution:
# Some archive extractors or cross-platform transfers do not preserve POSIX executable
# mode bits (+x) on shell/Python scripts. Running `bash ./setup.sh` works even before +x
# is set, and the `chmod +x` line below immediately restores executable permissions on
# `setup.sh`, `gradlew`, and the native patch scripts so subsequent `./setup.sh` and
# `./gradlew` invocations work directly.
set -euo pipefail
cd "$(dirname "$0")"
chmod +x setup.sh gradlew scripts/e2e/patch_hev_tcp_buffers.py scripts/poc/uid_detection/patch_hev_uid_detection_poc.py 2>/dev/null || true
DIR="app/src/main/jni/hev-socks5-tunnel"
TAG="2.18.0"
COMMIT="d9dca26c7ad0e494492244f0309e80ee583e739e"
REPO="https://github.com/heiher/hev-socks5-tunnel"

if [ -d "$DIR/.git" ]; then
  HEAD_NOW="$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)"
  case "$HEAD_NOW" in
    "$COMMIT"*) ;;
    *)
      git -C "$DIR" fetch --tags --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
      ;;
  esac
  git -C "$DIR" checkout --detach "$COMMIT"
  # The native patchers edit tracked files in this pinned checkout. Reset it and its
  # submodules before applying patches so repeated setup runs always start pristine.
  git -C "$DIR" reset --hard "$COMMIT"
  git -C "$DIR" submodule update --init --recursive 2>/dev/null || true
  git -C "$DIR" submodule foreach --recursive 'git reset --hard' 2>/dev/null || true
  HEAD="$(git -C "$DIR" rev-parse HEAD)"
  case "$HEAD" in
    "$COMMIT"*) ;;
    *) echo "ERROR - hev-socks5-tunnel HEAD=$HEAD, expected $COMMIT" >&2; exit 1 ;;
  esac
  echo "OK - hev-socks5-tunnel pinned to $TAG ($HEAD)"
elif [ -f "$DIR/src/hev-jni.c" ] && [ -f "$DIR/third-part/lwip/src/ports/include/lwipopts.h" ]; then
  echo "INFO - sanitizing and verifying existing hev-socks5-tunnel tree ($TAG / $COMMIT)"
else
  rm -rf "$DIR"
  git clone --branch "$TAG" --depth 1 --recursive "$REPO" "$DIR"
  git -C "$DIR" checkout --detach "$COMMIT"
  git -C "$DIR" reset --hard "$COMMIT"
  git -C "$DIR" submodule update --init --recursive
  git -C "$DIR" submodule foreach --recursive 'git reset --hard'
  HEAD="$(git -C "$DIR" rev-parse HEAD)"
  case "$HEAD" in
    "$COMMIT"*) ;;
    *) echo "ERROR - hev-socks5-tunnel HEAD=$HEAD, expected $COMMIT" >&2; exit 1 ;;
  esac
  echo "OK - hev-socks5-tunnel pinned to $TAG ($HEAD)"
fi

# Both patchers perform full post-patch verification internally before returning 0
# (and also support `--check` for standalone read-only verification in CI workflows).
python3 scripts/e2e/patch_hev_tcp_buffers.py "$DIR/third-part/lwip/src/ports/include/lwipopts.h"
python3 scripts/poc/uid_detection/patch_hev_uid_detection_poc.py "$DIR"
echo "OK - hev-socks5-tunnel native tree verified and ready ($TAG / $COMMIT)"
