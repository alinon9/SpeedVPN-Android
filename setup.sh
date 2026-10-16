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
  git -C "$DIR" fetch --tags --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
fi

git -C "$DIR" checkout --detach "$COMMIT"
git -C "$DIR" submodule update --init --recursive
HEAD="$(git -C "$DIR" rev-parse HEAD)"
case "$HEAD" in
  "$COMMIT"*) ;;
  *) echo "ERROR - hev-socks5-tunnel HEAD=$HEAD, expected $COMMIT" >&2; exit 1 ;;
esac

echo "OK - hev-socks5-tunnel pinned to $TAG ($HEAD)"
TCP_SOURCE="$DIR/src/hev-socks5-session-tcp.c"
LWIP_OPTS="$DIR/third-part/lwip/src/ports/include/lwipopts.h"
test -s "$TCP_SOURCE"
test -s "$LWIP_OPTS"
python3 scripts/e2e/patch_hev_tcp_buffers.py "$LWIP_OPTS" --tcp-source "$TCP_SOURCE"
python3 scripts/e2e/patch_hev_tcp_buffers.py "$LWIP_OPTS" --tcp-source "$TCP_SOURCE" --check

# Production builds must never contain the experimental JNI UID upcall hook.
if grep -R -nE 'SPEEDVPN_UID_POC_BEGIN|native_set_uid_poc_callback|TProxySetUidPocCallback|uid_poc_' "$DIR/src"; then
  echo "ERROR - unsafe UID POC patch residue found in native sources" >&2
  exit 1
fi
echo "OK - native TCP patches verified; UID POC hook absent"
