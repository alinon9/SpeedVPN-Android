#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
DIR="app/src/main/jni/hev-socks5-tunnel"
TAG="2.18.0"
COMMIT="d9dca26c7ad0e494492244f0309e80ee583e739e"
REPO="https://github.com/heiher/hev-socks5-tunnel"

if [ ! -d "$DIR/.git" ]; then
  rm -rf "$DIR"
  git clone --branch "$TAG" --depth 1 --recursive "$REPO" "$DIR"
else
  git -C "$DIR" reset --hard
  git -C "$DIR" clean -fdx
  git -C "$DIR" fetch --tags --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
fi

git -C "$DIR" checkout --detach --force "$COMMIT"
git -C "$DIR" submodule update --init --recursive --force
git -C "$DIR" submodule foreach --recursive 'git reset --hard && git clean -fdx'
HEAD="$(git -C "$DIR" rev-parse HEAD)"
case "$HEAD" in
  "$COMMIT"*) ;;
  *) echo "ERROR - hev-socks5-tunnel HEAD=$HEAD, expected $COMMIT" >&2; exit 1 ;;
esac

echo "OK - hev-socks5-tunnel pinned to $TAG ($HEAD)"
python3 scripts/e2e/patch_hev_tcp_buffers.py "$DIR/third-part/lwip/src/ports/include/lwipopts.h"
python3 scripts/e2e/patch_hev_tcp_buffers.py --check "$DIR/third-part/lwip/src/ports/include/lwipopts.h"
python3 scripts/poc/uid_detection/patch_hev_uid_detection_poc.py "$DIR"

JNI_SOURCE="$DIR/src/hev-jni.c"
TUNNEL_SOURCE="$DIR/src/hev-socks5-tunnel.c"
LWIP_HEADER="$DIR/third-part/lwip/src/ports/include/lwipopts.h"
grep -Fq 'SPEEDVPN_UID_POC_BEGIN' "$JNI_SOURCE"
grep -Fq 'SPEEDVPN_UID_POC_BEGIN' "$TUNNEL_SOURCE"
grep -Fq 'UID_WORKER_READY stack=1048576' "$JNI_SOURCE"
python3 scripts/e2e/patch_hev_tcp_buffers.py --check "$LWIP_HEADER"
echo "Native patch inputs SHA-256:"
sha256sum setup.sh scripts/poc/uid_detection/patch_hev_uid_detection_poc.py scripts/e2e/patch_hev_tcp_buffers.py "$JNI_SOURCE" "$TUNNEL_SOURCE" "$LWIP_HEADER"
