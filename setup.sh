#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"
DIR="app/src/main/jni/hev-socks5-tunnel"
TAG="2.18.0"
COMMIT="d9dca26c7ad0e494492244f0309e80ee583e739e"
REPO="https://github.com/heiher/hev-socks5-tunnel"
LWIP_COMMIT="e22c9d2873cd5a8aade9deb2e518b75dab05170e"
LWIP_DIR="$DIR/third-part/lwip"
LWIP_PATCH="$ROOT_DIR/patches/hev-lwip-window-scaling.patch"

if [ ! -d "$DIR/.git" ]; then
  rm -rf "$DIR"
  git clone --branch "$TAG" --depth 1 --recursive "$REPO" "$DIR"
else
  git -C "$DIR" fetch --tags --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
fi

git -C "$DIR" checkout --detach "$COMMIT"
git -C "$DIR" submodule update --init --recursive
HEAD="$(git -C "$DIR" rev-parse HEAD)"
case "$HEAD" in
  "$COMMIT"*) ;;
  *) echo "ERROR - hev-socks5-tunnel HEAD=$HEAD, expected $COMMIT" >&2; exit 1 ;;
esac

test "$(git -C "$LWIP_DIR" rev-parse HEAD)" = "$LWIP_COMMIT" || {
  echo "ERROR - lwIP HEAD=$(git -C "$LWIP_DIR" rev-parse HEAD), expected $LWIP_COMMIT" >&2
  exit 1
}
test -s "$LWIP_PATCH"
if git -C "$LWIP_DIR" apply --reverse --check "$LWIP_PATCH" >/dev/null 2>&1; then
  echo "OK - lwIP TCP window patch already applied"
else
  git -C "$LWIP_DIR" apply --check "$LWIP_PATCH"
  git -C "$LWIP_DIR" apply "$LWIP_PATCH"
  echo "OK - applied lwIP TCP window patch"
fi
LWIP_OPTS="$LWIP_DIR/src/ports/include/lwipopts.h"
grep -Fq '#define PBUF_POOL_SIZE                  64' "$LWIP_OPTS"
grep -Fq '#define TCP_WND                         (32 * TCP_MSS)' "$LWIP_OPTS"
grep -Fq '#define TCP_SND_BUF                     (32 * TCP_MSS)' "$LWIP_OPTS"
grep -Fq '#define TCP_SNDLOWAT                   ((2 * TCP_MSS) + 1)' "$LWIP_OPTS"
grep -Fq '#define LWIP_WND_SCALE                  1' "$LWIP_OPTS"
grep -Fq '#define TCP_RCV_SCALE                   2' "$LWIP_OPTS"
git -C "$LWIP_DIR" diff --check
echo "OK - lwIP receive/send windows are 256 KiB with TCP window scaling"
echo "OK - hev-socks5-tunnel pinned to $TAG ($HEAD)"
