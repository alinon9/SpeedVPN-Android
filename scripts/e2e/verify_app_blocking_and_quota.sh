#!/usr/bin/env bash
set -euo pipefail

ADB=(adb -s emulator-5554)
BLOCKED_APK="${1:?blocked fixture APK path required}"
ALLOWED_APK="${2:?allowed fixture APK path required}"
BLOCKED_PACKAGE="com.speedvpn.fixture.blocked"
ALLOWED_PACKAGE="com.speedvpn.fixture.allowed"
CONTROL_ACTIVITY="com.speedvpn.app/.MainActivity"
RESULTS="app-policy-e2e-results.txt"
: > "$RESULTS"
: > app-policy-e2e-logcat.txt

capture_logs() {
  "${ADB[@]}" logcat -d -v threadtime -t 8000 >> app-policy-e2e-logcat.txt 2>/dev/null || true
}
trap capture_logs EXIT

log() { echo "$*" | tee -a "$RESULTS"; }

wait_for_log() {
  local marker="$1"
  local timeout_seconds="${2:-30}"
  local line=""
  for ((second=0; second<timeout_seconds; second++)); do
    line="$("${ADB[@]}" logcat -d -v brief 2>/dev/null | grep -F "$marker" | tail -n 1 || true)"
    if [[ -n "$line" ]]; then printf '%s\n' "$line"; return 0; fi
    sleep 1
  done
  capture_logs
  log "FAIL: timed out waiting for log marker: $marker"
  tail -n 180 app-policy-e2e-logcat.txt || true
  return 1
}

control() {
  local action="$1"
  local id="$2"
  shift 2
  capture_logs
  # Clearing logcat can fail transiently on emulator logd under network load.
  # Every wait uses a unique E2E id, so stale entries cannot satisfy the assertion.
  "${ADB[@]}" logcat -c >/dev/null 2>&1 || true
  "${ADB[@]}" shell am start -W -n "$CONTROL_ACTIVITY" \
    --es e2e_action "$action" --es e2e_id "$id" --es e2e_package "$BLOCKED_PACKAGE" "$@" >/dev/null
  wait_for_log "E2E_CONTROL id=$id" 40 | tee -a "$RESULTS"
  capture_logs
}

probe() {
  local package="$1"
  local id="$2"
  local bytes="$3"
  local expected="$4"
  local component="$package/com.speedvpn.e2e.ProbeActivity"
  capture_logs
  # Clearing logcat can fail transiently on emulator logd under network load.
  # Every wait uses a unique E2E id, so stale entries cannot satisfy the assertion.
  "${ADB[@]}" logcat -c >/dev/null 2>&1 || true
  "${ADB[@]}" shell am start -W -n "$component" --es probe_id "$id" --ei bytes "$bytes" >/dev/null
  local line
  line="$(wait_for_log "E2E_PROBE id=$id" 75)"
  capture_logs
  log "$line"
  if [[ "$expected" == "PASS" ]]; then
    grep -Fq "result=PASS" <<< "$line" || { log "FAIL: $id expected a complete HTTP transfer from $package"; return 1; }
    grep -Fq "received=$bytes" <<< "$line" || { log "FAIL: $id did not receive the complete expected payload"; return 1; }
  else
    grep -Fq "result=FAIL" <<< "$line" || { log "FAIL: $id expected traffic to be dropped/truncated from $package"; return 1; }
  fi
}

log "Installing two separate-UID HTTP probe apps."
"${ADB[@]}" install -r "$BLOCKED_APK" | tee -a "$RESULTS"
"${ADB[@]}" install -r "$ALLOWED_APK" | tee -a "$RESULTS"

log "Resetting target policy."
control reset_policy reset-before-block

log "Starting the actual VPN tunnel before changing the live app policy."
line="$(control connect connect-before-block-test)"
grep -Fq "result=PASS" <<< "$line"
grep -Fq "vpn=CONNECTED" <<< "$line"

log "Enabling manual block for $BLOCKED_PACKAGE while VPN is connected."
line="$(control manual_block manual-block-on --ez e2e_enabled true)"
grep -Fq "result=PASS" <<< "$line"
grep -Fq "effectiveBlocked=true" <<< "$line"
# The running service applies package changes from its one-second policy monitor.
sleep 2

log "Live block must drop one UID and preserve another UID's network access."
probe "$BLOCKED_PACKAGE" manual-block-drops 4096 FAIL
probe "$ALLOWED_PACKAGE" allowed-during-manual-block 4096 PASS

log "Removing the manual block."
line="$(control manual_block manual-block-off --ez e2e_enabled false)"
grep -Fq "result=PASS" <<< "$line"
# Wait for the native policy transition to be published by SpeedVpnService.
sleep 2
probe "$BLOCKED_PACKAGE" manual-unblock-restores-traffic 4096 PASS

log "Applying an exact 1,000,000-byte daily quota."
line="$(control set_quota set-one-mb-quota --el e2e_bytes 1000000)"
grep -Fq "result=PASS" <<< "$line"
grep -Fq "quota=DAILY:1000000:" <<< "$line"
python3 - "$line" <<'PY'
import re
import sys
line = sys.argv[1]
m = re.search(r"quota=DAILY:1000000:(\d+)", line)
if not m:
    raise SystemExit("E2E quota setup did not return its configured daily policy: " + line)
used = int(m.group(1))
if used >= 1_000_000:
    raise SystemExit(f"quota baseline already exhausted before the relay test (used={used})")
print(f"PASS: quota configured with a valid non-exhausted baseline of {used} bytes")
PY

log "A 2 MB download must be truncated by the relay quota gate."
probe "$BLOCKED_PACKAGE" quota-overrun-is-cut 2000000 FAIL

log "Waiting for the native/relay quota policy transition before reading persisted state."
# The quota callback blocks the target UID immediately; the service monitor then publishes
# the UID-scoped policy snapshot and invalidates pre-transition SOCKS sessions. Wait for that
# single transition before clearing logcat for a status command or probing another UID.
wait_for_log "UID_POLICY_CHANGED blockedPackages=$BLOCKED_PACKAGE" 15 | tee -a "$RESULTS"
sleep 1

log "Checking persisted usage and quota block before restart."
line="$(control status quota-state-before-restart)"
grep -Fq "result=PASS" <<< "$line"
grep -Fq "quotaBlocked=true" <<< "$line"
python3 - "$line" <<'PY'
import re
import sys
line = sys.argv[1]
m = re.search(r"quota=DAILY:1000000:(\d+)", line)
if not m:
    raise SystemExit("E2E quota state missing daily 1 MB policy: " + line)
used = int(m.group(1))
if used < 1_000_000:
    raise SystemExit(f"quota counter never reached 1,000,000 bytes (used={used})")
print(f"PASS: durable quota counter reached {used} bytes and quotaBlocked=true")
PY

# The quota-triggered UID-policy transition runs asynchronously. Let its one-second
# service monitor invalidate stale sessions before testing a different UID, so this
# probe does not race the transition and confuse a stale-session reset with a global block.
sleep 2
log "The unrelated UID must still transfer data after quota policy has settled."
probe "$ALLOWED_PACKAGE" allowed-after-quota 4096 PASS

log "Restarting SpeedVpnService and verifying durable enforcement."
line="$(control restart restart-after-quota)"
grep -Fq "result=PASS" <<< "$line"
grep -Fq "vpn=CONNECTED" <<< "$line"
line="$(control status quota-state-after-restart)"
grep -Fq "result=PASS" <<< "$line"
grep -Fq "quotaBlocked=true" <<< "$line"
grep -Fq "quota=DAILY:1000000:" <<< "$line"

probe "$BLOCKED_PACKAGE" quota-block-survives-service-restart 4096 FAIL
probe "$ALLOWED_PACKAGE" allowed-after-service-restart 4096 PASS

log "APP_BLOCK_AND_QUOTA_E2E=PASS"
capture_logs
