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
