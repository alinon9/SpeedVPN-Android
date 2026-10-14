#!/usr/bin/env bash
# Collect Android emulator Download-path diagnostics. Observation only: no app-source edits.
set -Eeuo pipefail

DURATION=600
SERIAL=""
PACKAGE="${PACKAGE:-com.speedvpn.app}"
OUT_DIR="${OUT_DIR:-artifacts/download-path}"
SAMPLE_INTERVAL="${SAMPLE_INTERVAL:-2}"
PING_HOST="${PING_HOST:-1.1.1.1}"
TEST_CMD=()

usage() {
  echo "Usage: $0 [--duration SECONDS] [--serial DEVICE] -- <test-runner-command>"
  echo "Example: PACKAGE=com.speedvpn.app $0 --duration 600 --serial emulator-5554 -- bash scripts/e2e/run-download-unlimited.sh"
  exit 2
}
while (($#)); do
  case "$1" in
    --duration) DURATION="${2:?missing seconds}"; shift 2 ;;
    --serial) SERIAL="${2:?missing device serial}"; shift 2 ;;
    --) shift; TEST_CMD=("$@"); break ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done
(("${#TEST_CMD[@]}" > 0)) || usage
command -v adb >/dev/null || { echo "ERROR: adb not found" >&2; exit 2; }
command -v timeout >/dev/null || { echo "ERROR: timeout not found" >&2; exit 2; }

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=( -s "$SERIAL" )
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "ERROR: Android device unavailable" >&2; exit 2; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${OUT_DIR%/}/$STAMP"
mkdir -p "$OUT_DIR"
export SPEEDVPN_MEASURE_RUN_DIR="$OUT_DIR"
echo "OUT_DIR=$OUT_DIR"
echo "DEVICE=$("${ADB[@]}" get-serialno)"
echo "PACKAGE=$PACKAGE"
echo "TEST_COMMAND=${TEST_CMD[*]}"
echo "Observation-only diagnostic; no application source or speed plans are modified."

"${ADB[@]}" shell date > "$OUT_DIR/device-date.txt" 2>&1 || true
"${ADB[@]}" shell getprop > "$OUT_DIR/getprop.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys connectivity > "$OUT_DIR/connectivity-before.txt" 2>&1 || true
"${ADB[@]}" shell cat /proc/net/tcp /proc/net/tcp6 > "$OUT_DIR/proc-net-tcp-before.txt" 2>&1 || true
"${ADB[@]}" shell 'command -v ss >/dev/null 2>&1 && ss -tinm || echo "ss unavailable or restricted"' > "$OUT_DIR/ss-before.txt" 2>&1 || true
"${ADB[@]}" shell 'cat /proc/meminfo; echo; cat /proc/loadavg' > "$OUT_DIR/system-memory-before.txt" 2>&1 || true
"${ADB[@]}" shell 'cat /sys/kernel/debug/lwip/stats 2>/dev/null || cat /sys/kernel/debug/lwip_stats 2>/dev/null || echo "No exposed lwIP debug stats path"' > "$OUT_DIR/lwip-stats-before.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys cpuinfo > "$OUT_DIR/cpuinfo-before.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/meminfo-before.txt" 2>&1 || true

"${ADB[@]}" logcat -c >/dev/null 2>&1 || true
"${ADB[@]}" logcat -v threadtime > "$OUT_DIR/logcat-full.txt" 2>&1 &
LOGCAT_PID=$!
SAMPLER_PID=""
TCPDUMP_PID=""
stop_tcpdump() {
  set +e
  if [[ -n "${TCPDUMP_PID:-}" ]]; then
    # This runner owns this dedicated capture; signal tcpdump itself, then reap sudo.
    sudo pkill -INT -x tcpdump >/dev/null 2>&1 || true
    wait "$TCPDUMP_PID" 2>/dev/null || true
    TCPDUMP_PID=""
  fi
}
cleanup() {
  set +e
  stop_tcpdump
  [[ -n "${SAMPLER_PID:-}" ]] && kill "$SAMPLER_PID" 2>/dev/null
  [[ -n "${LOGCAT_PID:-}" ]] && kill "$LOGCAT_PID" 2>/dev/null
  [[ -n "${LOGCAT_PID:-}" ]] && wait "$LOGCAT_PID" 2>/dev/null
}
trap cleanup EXIT INT TERM

# Capture TCP SYN/SYN-ACK packets only: no HTTP bodies, TLS records, or credentials.
# This measures upstream TCP handshake RTT at the runner/NAT boundary; it is not
# Android guest TCP_INFO, but it is an actual RTT for TCP connections used by the relay.
CAPTURE_FILE="$OUT_DIR/tcp-syn-capture.txt"
if command -v tcpdump >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
  : > "$CAPTURE_FILE"
  sudo tcpdump -i any -nn -tttt -vvv -l -s 128 \
    'tcp port 443 and (tcp[tcpflags] & tcp-syn != 0)' \
    > "$CAPTURE_FILE" 2>&1 &
  TCPDUMP_PID=$!
  sleep 1
  if ! kill -0 "$TCPDUMP_PID" 2>/dev/null; then
    echo "WARNING: tcpdump exited before measurement began." >> "$CAPTURE_FILE"
    TCPDUMP_PID=""
  else
    echo "TCP_SYN_CAPTURE_STARTED=1" | tee "$OUT_DIR/tcp-capture-status.txt"
  fi
else
  echo "tcpdump unavailable or sudo unavailable; no TCP RTT measurement captured." | tee "$CAPTURE_FILE" "$OUT_DIR/tcp-capture-status.txt"
fi

(
  while :; do
    TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    {
      echo "===== $TS ====="
      echo "--- /proc/net/tcp + tcp6 ---"
      "${ADB[@]}" shell cat /proc/net/tcp /proc/net/tcp6 2>&1
      echo "--- ss -tinm (tcp_info/socket memory, if permitted) ---"
      "${ADB[@]}" shell 'command -v ss >/dev/null 2>&1 && ss -tinm || echo "ss unavailable or restricted"' 2>&1
      echo "--- CPU ---"
      "${ADB[@]}" shell dumpsys cpuinfo 2>&1
      echo "--- RAM/load ---"
      "${ADB[@]}" shell 'grep -E "MemTotal|MemFree|MemAvailable|Buffers|Cached|SwapTotal|SwapFree" /proc/meminfo; cat /proc/loadavg' 2>&1
      echo "--- app memory ---"
      "${ADB[@]}" shell dumpsys meminfo "$PACKAGE" 2>&1
      echo "--- lwIP/debugfs counters (best effort) ---"
      "${ADB[@]}" shell 'cat /sys/kernel/debug/lwip/stats 2>/dev/null || cat /sys/kernel/debug/lwip_stats 2>/dev/null || echo "No exposed lwIP stats"' 2>&1
    } >> "$OUT_DIR/samples.txt"
    if command -v ping >/dev/null 2>&1; then
      ping -n -c 1 -W 1 "$PING_HOST" 2>&1 | sed "s/^/[$TS] /" >> "$OUT_DIR/ping.txt" || true
    fi
    sleep "$SAMPLE_INTERVAL"
  done
) &
SAMPLER_PID=$!

set +e
timeout --signal=INT --kill-after=10 "$DURATION" "${TEST_CMD[@]}" 2>&1 | tee "$OUT_DIR/test-command.log"
TEST_STATUS=${PIPESTATUS[0]}
set -e
echo "$TEST_STATUS" > "$OUT_DIR/test-exit-code.txt"
stop_tcpdump
echo "TCP_SYN_CAPTURE_STOPPED=1" >> "$OUT_DIR/tcp-capture-status.txt"

"${ADB[@]}" shell cat /proc/net/tcp /proc/net/tcp6 > "$OUT_DIR/proc-net-tcp-after.txt" 2>&1 || true
"${ADB[@]}" shell 'command -v ss >/dev/null 2>&1 && ss -tinm || echo "ss unavailable or restricted"' > "$OUT_DIR/ss-after.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys connectivity > "$OUT_DIR/connectivity-after.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys cpuinfo > "$OUT_DIR/cpuinfo-after.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/meminfo-after.txt" 2>&1 || true
"${ADB[@]}" shell 'cat /sys/kernel/debug/lwip/stats 2>/dev/null || cat /sys/kernel/debug/lwip_stats 2>/dev/null || echo "No exposed lwIP debug stats path"' > "$OUT_DIR/lwip-stats-after.txt" 2>&1 || true

kill "$SAMPLER_PID" 2>/dev/null || true
SAMPLER_PID=""
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true
LOGCAT_PID=""
python3 scripts/e2e/analyze_tcp_handshake_rtt.py \
  --capture "$CAPTURE_FILE" --logcat "$OUT_DIR/logcat-full.txt" \
  > "$OUT_DIR/tcp-rtt-summary.txt" 2>&1 || true
trap - EXIT INT TERM

grep -Ein 'ERR_MEM|mem err|tcp_sndbuf|snd_buf|rcv_buf|chunk|socks5|download|upload|rtt|round.trip|broken pipe|connection reset|Phase complete direction=download|Phase start direction=upload|Unlimited|unlimited' \
  "$OUT_DIR/logcat-full.txt" > "$OUT_DIR/relevant-log-lines.txt" || true

cat > "$OUT_DIR/README.txt" <<EOF
Download-path diagnostic bundle
UTC run: $STAMP
Device: $("${ADB[@]}" get-serialno 2>/dev/null || echo unknown)
Package: $PACKAGE
Test exit code: $TEST_STATUS
Command: ${TEST_CMD[*]}

Files:
- samples.txt: repeated /proc/net/tcp, ss -tinm, CPU, RAM/load, app memory and debugfs snapshots.
- logcat-full.txt / relevant-log-lines.txt: full logs and filtered SOCKS5, chunk, ERR_MEM, Download/Upload and RTT-like lines.
- proc-net-tcp-*.txt / ss-*.txt: before/after socket snapshots.
- tcp-syn-capture.txt / tcp-rtt-summary.txt: SYN-to-SYN/ACK RTT for TCP/443 remote IPs seen in SOCKS relay flow IDs; only packet metadata is captured.
- ping.txt: host-runner ICMP diagnostic; it is not used as a substitute for TCP RTT.
- runner-status.txt, phase-monitor.log and *download*capture*.txt are emitted by the UI runner.
- test-command.log and test-exit-code.txt: runner output and return code.

Limitations:
- /proc/net/tcp alone does not expose TCP send/receive buffers or RTT.
- TCP handshake RTT is measured at the Linux runner's capture interface, through the emulator NAT boundary, not from Android guest TCP_INFO. A missing/empty capture is reported explicitly.
- ss may be absent or restricted; tcp_info availability varies by emulator image.
- lwIP ERR_MEM counters require the build/device to expose the probed debugfs paths.
- Current log output determines whether SOCKS5 chunk sizes are visible; absent log lines do not prove absent chunks.
EOF

echo "Measurement bundle: $OUT_DIR"
echo "Test exit code: $TEST_STATUS"
echo "Upload the entire artifacts/download-path directory when returning results."
exit "$TEST_STATUS"

# Local use: connect an Android device/emulator with adb, then run:
# PACKAGE=com.speedvpn.app bash scripts/e2e/measure_download_path.sh --duration 600 --serial <adb-serial> -- bash scripts/e2e/run-download-unlimited.sh
#
# GitHub Actions use: the measure-download-path.yml workflow provisions the emulator,
# builds/installs the APK, calls this script, and uploads artifacts/download-path/.
# Expected output includes socket snapshots, SYN-only TCP handshake RTT, ping samples, CPU/RAM, full logcat,
# ERR_MEM/chunk/SOCKS5 matches when emitted, and runner status. Missing ss/debugfs
# or absent chunk logs are limitations, not proof that the target data does not exist.
# Send the uploaded Actions artifact ZIP back here for diagnosis.
