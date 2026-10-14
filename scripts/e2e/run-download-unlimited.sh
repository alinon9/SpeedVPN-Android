#!/usr/bin/env bash
# Download-path CI probe. UI-only harness; does not modify application source.
set -Eeuo pipefail

DEVICE="${ANDROID_SERIAL:-emulator-5554}"
PACKAGE="${PACKAGE:-com.speedvpn.app}"
RUN_DIR="${SPEEDVPN_MEASURE_RUN_DIR:-artifacts/download-path/runner}"
UI_XML="$RUN_DIR/current-ui.xml"
mkdir -p "$RUN_DIR"

fail() {
  echo "DOWNLOAD_PROBE_FAIL: $*" | tee -a "$RUN_DIR/runner-status.txt" >&2
  exit 1
}

dump_ui() {
  local out="$1"
  local remote="/sdcard/download-path-ui.xml"
  for attempt in 1 2 3 4 5; do
    rm -f "$out"
    adb -s "$DEVICE" shell uiautomator dump "$remote" >/dev/null 2>&1 || true
    adb -s "$DEVICE" exec-out cat "$remote" > "$out" 2>/dev/null || true
    if [ -s "$out" ] && python3 -c 'import sys,xml.etree.ElementTree as E; E.parse(sys.argv[1])' "$out" >/dev/null 2>&1; then
      adb -s "$DEVICE" shell rm -f "$remote" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 1
  done
  return 1
}

coord_for() {
  local xml="$1" mode="$2" wanted="$3"
  python3 - "$xml" "$mode" "$wanted" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, mode, wanted = sys.argv[1:]
root = ET.parse(path).getroot()
parents = {c:p for p in root.iter() for c in list(p)}
def point(node):
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not m: return None
    x1,y1,x2,y2=map(int,m.groups())
    if x2<=x1 or y2<=y1: return None
    if node.attrib.get("enabled","true").lower()=="false": return None
    if node.attrib.get("visible-to-user","true").lower()=="false": return None
    p=parents.get(node)
    while p is not None:
        if "ScrollView" in p.attrib.get("class","") or p.attrib.get("scrollable","false").lower()=="true":
            pm=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",p.attrib.get("bounds",""))
            if pm:
                a,b,c,d=map(int,pm.groups())
                x1,y1,x2,y2=max(x1,a),max(y1,b),min(x2,c),min(y2,d)
        p=parents.get(p)
    if x2<=x1 or y2<=y1: return None
    return ((x1+x2)//2,(y1+y2)//2)
matches=[]
for n in root.iter("node"):
    if mode=="id":
        rid=n.attrib.get("resource-id","")
        ok=(rid==wanted or rid.endswith("/"+wanted) or rid.endswith(":id/"+wanted))
    else:
        ok=(n.attrib.get("text","").strip()==wanted or n.attrib.get("content-desc","").strip()==wanted)
    if ok:
        p=point(n)
        if p: matches.append(p)
if not matches: raise SystemExit(1)
print(*matches[0])
PY
}

tap_selector() {
  local mode="$1" wanted="$2" c="" x y
  for attempt in 1 2 3 4 5 6 7 8; do
    dump_ui "$UI_XML" || true
    c="$(coord_for "$UI_XML" "$mode" "$wanted" 2>/dev/null || true)"
    if [ -n "$c" ]; then
      read -r x y <<< "$c"
      adb -s "$DEVICE" shell input tap "$x" "$y"
      sleep 1
      return 0
    fi
    adb -s "$DEVICE" shell input swipe 160 520 160 290 650 >/dev/null 2>&1 || true
    sleep 0.5
  done
  return 1
}

tap_text() { tap_selector text "$1"; }
tap_id() { tap_selector id "$1"; }

ui_has_text() {
  dump_ui "$UI_XML" || return 1
  python3 - "$UI_XML" "$1" <<'PY'
import sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); wanted=sys.argv[2]
raise SystemExit(0 if any(n.attrib.get("text","").strip()==wanted or n.attrib.get("content-desc","").strip()==wanted for n in root.iter("node")) else 1)
PY
}

echo "DOWNLOAD_PROBE_START package=$PACKAGE device=$DEVICE" | tee "$RUN_DIR/runner-status.txt"
adb -s "$DEVICE" get-state | grep -q '^device$' || fail "ADB device is not ready"
adb -s "$DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk | tee "$RUN_DIR/apk-install.log"
adb -s "$DEVICE" shell am start -n "$PACKAGE/.MainActivity" | tee "$RUN_DIR/app-start.log"
sleep 5

# Open the Speed tab and explicitly persist Unlimited in both directions.
tap_text "السرعة" || fail "Could not open Speed tab"
tap_id "speed_preset_download_unlimited" || fail "Could not select Download Unlimited"
sleep 1
limits="$(adb -s "$DEVICE" shell run-as "$PACKAGE" cat shared_prefs/local_limits.xml 2>/dev/null || true)"
printf '%s\n' "$limits" > "$RUN_DIR/limits-after-download-selection.xml"
printf '%s\n' "$limits" | python3 scripts/e2e/preference_matches.py dl 0 none || fail "Download Unlimited (dl=0) did not persist"
tap_id "speed_preset_upload_unlimited" || fail "Could not select Upload Unlimited"
sleep 1
limits="$(adb -s "$DEVICE" shell run-as "$PACKAGE" cat shared_prefs/local_limits.xml 2>/dev/null || true)"
printf '%s\n' "$limits" > "$RUN_DIR/limits-after-unlimited-selection.xml"
printf '%s\n' "$limits" | python3 scripts/e2e/preference_matches.py ul 0 both || fail "Unlimited limits did not persist for both directions"

echo "PLAN_VERIFIED download=Unlimited upload=Unlimited" | tee -a "$RUN_DIR/runner-status.txt"

# Monitor SpeedTestEngine using a directly-owned adb logcat process.
# The old "adb logcat | while read" pipeline could leave its logcat producer alive
# after the reader broke, so EXIT cleanup could wait on a process that never exited.
# A direct PID lets cleanup terminate and reap the monitor deterministically.
MONITOR_LOG="$RUN_DIR/phase-monitor.log"
rm -f "$RUN_DIR/download-phase-captured.txt" "$MONITOR_LOG"
: > "$MONITOR_LOG"
adb -s "$DEVICE" logcat -v brief -s SpeedTestEngine:I > "$MONITOR_LOG" 2>&1 &
MONITOR_PID=$!
cleanup_monitor() {
  set +e
  if [[ -n "${MONITOR_PID:-}" ]]; then
    kill "$MONITOR_PID" 2>/dev/null || true
    wait "$MONITOR_PID" 2>/dev/null || true
    MONITOR_PID=""
  fi
}
trap cleanup_monitor EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
sleep 1

# Verify Speed establishes the app VPN and routes its probe through the limiter.
# On a fresh emulator it first displays SpeedVPN's disclosure and Android's VPN
# approval dialog. Handle those dialogs if present, then retry the Verify button.
tap_text "✅ تحقق من السرعة" || tap_text "تحقق من السرعة" || fail "Could not tap Verify Speed"

sleep 1
if ui_has_text "أوافق والمتابعة"; then
  tap_text "أوافق والمتابعة" || fail "Could not accept SpeedVPN VPN disclosure"
  sleep 2
  if ui_has_text "OK"; then
    tap_text "OK" || fail "Could not accept Android VPN approval"
  elif ui_has_text "Allow"; then
    tap_text "Allow" || fail "Could not accept Android VPN approval"
  elif ui_has_text "السماح"; then
    tap_text "السماح" || fail "Could not accept Android VPN approval"
  else
    fail "Android VPN approval dialog was not recognized after disclosure"
  fi
  sleep 3
  tap_text "✅ تحقق من السرعة" || tap_text "تحقق من السرعة" || fail "Could not restart Verify Speed after VPN approval"
elif ui_has_text "OK" || ui_has_text "Allow" || ui_has_text "السماح"; then
  if ui_has_text "OK"; then tap_text "OK" || true
  elif ui_has_text "Allow"; then tap_text "Allow" || true
  else tap_text "السماح" || true
  fi
  sleep 3
  tap_text "✅ تحقق من السرعة" || tap_text "تحقق من السرعة" || fail "Could not restart Verify Speed after VPN approval"
fi

# Hard cap this runner's measurement wait at 15 minutes, independent of caller
# input. The outer wrapper also enforces its configured timeout.
RUNNER_TIMEOUT_SECONDS="${RUNNER_TIMEOUT_SECONDS:-900}"
if ! [[ "$RUNNER_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  fail "RUNNER_TIMEOUT_SECONDS must be a positive integer"
fi
if (( RUNNER_TIMEOUT_SECONDS > 900 )); then
  echo "RUNNER_TIMEOUT_CLAMPED requested=$RUNNER_TIMEOUT_SECONDS cap=900" | tee -a "$RUN_DIR/runner-status.txt"
  RUNNER_TIMEOUT_SECONDS=900
fi

for ((elapsed=0; elapsed<RUNNER_TIMEOUT_SECONDS; elapsed++)); do
  count="$(grep -cF 'Phase complete direction=download' "$MONITOR_LOG" 2>/dev/null || true)"
  count="${count:-0}"
  if (( count >= 2 )); then
    grep -F 'Phase complete direction=download' "$MONITOR_LOG" | tail -n 1 > "$RUN_DIR/download-phase-captured.txt"
    line="$(cat "$RUN_DIR/download-phase-captured.txt")"
    echo "VPN_DOWNLOAD_PHASE_CAPTURED: $line" | tee -a "$RUN_DIR/runner-status.txt"
    adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    # Explicitly stop and reap adb logcat before printing the completion marker.
    cleanup_monitor
    adb -s "$DEVICE" logcat -d -v threadtime > "$RUN_DIR/logcat-at-download-capture.txt" 2>&1 || true
    echo "DOWNLOAD_DIAGNOSTIC_COMPLETE" | tee -a "$RUN_DIR/runner-status.txt"
    exit 0
  fi
  sleep 1
done

cleanup_monitor
adb -s "$DEVICE" logcat -d -v threadtime > "$RUN_DIR/logcat-runner-timeout.txt" 2>&1 || true
fail "Timed out waiting for baseline + VPN Download phase completion after ${RUNNER_TIMEOUT_SECONDS}s"
