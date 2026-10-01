#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_SHA256="2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"

if [ ! -f "$WRAPPER_JAR" ]; then
  mkdir -p "$(dirname "$WRAPPER_JAR")" || exit 1
  tmp="$WRAPPER_JAR.tmp.$$"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 --retry-delay 2 -o "$tmp" "$WRAPPER_URL" || { rm -f "$tmp"; exit 1; }
  elif command -v wget >/dev/null 2>&1; then
    wget -q --tries=3 -O "$tmp" "$WRAPPER_URL" || { rm -f "$tmp"; exit 1; }
  else
    echo "ERROR: curl or wget is required to bootstrap Gradle Wrapper." >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$tmp" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$tmp" | awk '{print $1}')
  else
    echo "ERROR: SHA-256 utility (sha256sum or shasum) is required." >&2
    rm -f "$tmp"
    exit 1
  fi
  [ "$actual" = "$WRAPPER_SHA256" ] || { echo "ERROR: Gradle wrapper JAR checksum mismatch." >&2; rm -f "$tmp"; exit 1; }
  mv "$tmp" "$WRAPPER_JAR" || exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual=$(sha256sum "$WRAPPER_JAR" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  actual=$(shasum -a 256 "$WRAPPER_JAR" | awk '{print $1}')
else
  echo "ERROR: SHA-256 utility (sha256sum or shasum) is required." >&2
  exit 1
fi
if [ "$actual" != "$WRAPPER_SHA256" ]; then
  echo "ERROR: Gradle wrapper JAR checksum mismatch." >&2
  exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=$(command -v java 2>/dev/null || true)
fi
[ -x "$JAVACMD" ] || { echo "ERROR: Java is required." >&2; exit 1; }

exec "$JAVACMD" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
