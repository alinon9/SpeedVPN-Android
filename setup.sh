#!/usr/bin/env bash
# Run once before opening the project in Android Studio.
set -e
cd "$(dirname "$0")"
DIR=app/src/main/jni/hev-socks5-tunnel
if [ ! -d "$DIR/.git" ]; then
  git clone --recursive https://github.com/heiher/hev-socks5-tunnel "$DIR"
else
  git -C "$DIR" pull && git -C "$DIR" submodule update --init --recursive
fi
echo "OK - now open this folder in Android Studio."
