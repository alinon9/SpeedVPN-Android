#!/usr/bin/env bash
set -euo pipefail

DEVICE="emulator-5554"
PACKAGE="com.speedvpn.app"
RUN_DIR="speed-limit-sweep"
mkdir -p "$RUN_DIR"
: > "$RUN_DIR/speed-limit-sweep-results.csv"
: > "$RUN_DIR/sweep-console.log"

echo 'index,preset,expected_kbps,direction,baseline_raw,plan_raw,vpn_raw,verdict,reason,classification' > "$RUN_DIR/speed-limit-sweep-results.csv"

SPEED_CASES=$(cat <<'CASES'
1|10 KB|80
2|25 KB|200
3|50 KB|400
4|75 KB|600
5|100 KB|800
6|130 KB|1040
7|250 KB|2000
8|500 KB|4000
9|750 KB|6000
10|950 KB|7600
11|1 MB|8000
12|2 MB|16000
13|3 MB|24000
14|4 MB|32000
15|5 MB|40000
16|9 MB|72000
17|10 MB|80000
18|11 MB|88000
19|بدون حد|0
CASES
)

dump_ui() {
  local out="$1"
  local remote="/sdcard/speedvpn-sweep-ui.xml"
  for attempt in 1 2 3 4 5; do
    rm -f "$out"
    if adb -s "$DEVICE" shell uiautomator dump "$remote" >/dev/null 2>&1 &&
       adb -s "$DEVICE" exec-out cat "$remote" > "$out" 2>/dev/null &&
       [ -s "$out" ]; then
      adb -s "$DEVICE" shell rm -f "$remote" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 1
  done
  return 1
}

coord_for_text() {
  local xml="$1"
  local wanted="$2"
  local mode="$3"
  python3 - "$xml" "$wanted" "$mode" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, wanted, mode = sys.argv[1:]
root = ET.parse(path).getroot()
matches = []
for node in root.iter("node"):
    if node.attrib.get("text") != wanted:
        continue
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds",""))
    if not m:
        continue
    x1,y1,x2,y2 = map(int,m.groups())
    matches.append(((y1+y2)//2,(x1+x2)//2))
if not matches:
    raise SystemExit(1)
if mode == "top":
    y,x = min(matches)
elif mode == "bottom":
    y,x = max(matches)
else:
    y,x = matches[0]
print(x,y)
PY
}

tap_text() {
  local wanted="$1"
  local mode="any"
  if [ "$#" -ge 2 ]; then mode="$2"; fi
  local xml="$RUN_DIR/current-ui.xml"

  for scroll in 0 1 2 3 4 5 6 7; do
    dump_ui "$xml" || true
    local c=""
    c="$(coord_for_text "$xml" "$wanted" "$mode" 2>/dev/null || true)"
    if [ -n "$c" ]; then
      read -r x y <<< "$c"
      adb -s "$DEVICE" shell input tap "$x" "$y"
      return 0
    fi
    [ "$scroll" -lt 7 ] || break
    adb -s "$DEVICE" shell input swipe 160 500 160 220 650
    sleep 1
  done
  return 1
}

preset_group_title() {
  case "$1" in
    "10 KB"|"25 KB"|"50 KB"|"75 KB"|"100 KB"|"130 KB"|"250 KB"|"500 KB"|"750 KB"|"950 KB")
      echo "منخفض" ;;
    "1 MB"|"2 MB"|"3 MB"|"4 MB"|"5 MB")
      echo "متوسط" ;;
    "9 MB"|"10 MB"|"11 MB")
      echo "مرتفع" ;;
    *)
      return 1 ;;
  esac
}

preset_swipe_y() {
  local xml="$1"
  local mode="$2"
  local group_title="$3"
  python3 - "$xml" "$mode" "$group_title" <<'PY'
import re, sys, xml.etree.ElementTree as ET
mode, group_title = sys.argv[2], sys.argv[3]
groups = {
    "منخفض": {"10 KB","25 KB","50 KB","75 KB","100 KB","130 KB","250 KB","500 KB","750 KB","950 KB"},
    "متوسط": {"1 MB","2 MB","3 MB","4 MB","5 MB"},
    "مرتفع": {"9 MB","10 MB","11 MB"},
}
labels = groups.get(group_title, set())
root=ET.parse(sys.argv[1]).getroot()
ys=[]
for node in root.iter("node"):
    if node.attrib.get("text") not in labels:
        continue
    m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
    if m:
        x1,y1,x2,y2=map(int,m.groups())
        ys.append((y1+y2)//2)
if not ys:
    raise SystemExit(1)
print(min(ys) if mode=="top" else max(ys))
PY
}

preset_swipe_y_near_card() {
  local xml="$1"
  local title="$2"
  local group_title="$3"
  python3 - "$xml" "$title" "$group_title" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path,title,group=sys.argv[1:]
groups = {
    "منخفض": {"10 KB","25 KB","50 KB","75 KB","100 KB","130 KB","250 KB","500 KB","750 KB","950 KB"},
    "متوسط": {"1 MB","2 MB","3 MB","4 MB","5 MB"},
    "مرتفع": {"9 MB","10 MB","11 MB"},
}
labels=groups.get(group,set())
root=ET.parse(path).getroot()
def center(node):
    m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
    if not m: return None
    x1,y1,x2,y2=map(int,m.groups())
    return (x1,y1,x2,y2,(y1+y2)//2,(x1+x2)//2)
titles=[center(n) for n in root.iter("node") if n.attrib.get("text")==title]
buttons=[center(n) for n in root.iter("node") if n.attrib.get("text") in labels]
titles=[b for b in titles if b]
buttons=[b for b in buttons if b]
if not titles or not buttons:
    raise SystemExit(1)
ty=min(titles,key=lambda b:abs(b[4]-500))[4]
near=[b for b in buttons if abs(b[4]-ty)<=100]
if not near:
    raise SystemExit(1)
print(min(b[4] for b in near))
PY
}

direction_title() {
  case "$1" in
    top) echo "سرعة التحميل" ;;
    bottom) echo "سرعة الرفع" ;;
    *) return 1 ;;
  esac
}

coord_for_resource_id() {
  local xml="$1"
  local wanted="$2"
  python3 - "$xml" "$wanted" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, wanted = sys.argv[1:]
root = ET.parse(path).getroot()
for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    if resource_id != wanted and not resource_id.endswith("/" + wanted) and not resource_id.endswith(":id/" + wanted):
        continue
    if node.attrib.get("enabled", "true").lower() == "false":
        continue
    if node.attrib.get("visible-to-user", "true").lower() == "false":
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds",""))
    if not match:
        continue
    x1,y1,x2,y2 = map(int, match.groups())
    x,y = (x1+x2)//2,(y1+y2)//2
    # Do not tap a clipped off-screen center even if the Compose node remains in the XML tree.
    if x < 0 or y < 0:
        continue
    print(x, y)
    raise SystemExit(0)
raise SystemExit(1)
PY
}

coord_for_content_desc() {
  local xml="$1"
  local wanted="$2"
  python3 - "$xml" "$wanted" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, wanted = sys.argv[1:]
root = ET.parse(path).getroot()
for node in root.iter("node"):
    if node.attrib.get("content-desc") != wanted:
        continue
    if node.attrib.get("enabled", "true").lower() == "false":
        continue
    if node.attrib.get("visible-to-user", "true").lower() == "false":
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds",""))
    if not match:
        continue
    x1,y1,x2,y2 = map(int, match.groups())
    x,y = (x1+x2)//2,(y1+y2)//2
    if x < 0 or y < 0:
        continue
    print(x, y)
    raise SystemExit(0)
raise SystemExit(1)
PY
}

preset_desc_row_y() {
  local xml="$1"
  local prefix="$2"
  local group_title="$3"
  python3 - "$xml" "$prefix" "$group_title" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, prefix, group_title = sys.argv[1:]
groups = {
    "منخفض": {"10 KB","25 KB","50 KB","75 KB","100 KB","130 KB","250 KB","500 KB","750 KB","950 KB"},
    "متوسط": {"1 MB","2 MB","3 MB","4 MB","5 MB"},
    "مرتفع": {"9 MB","10 MB","11 MB"},
}
labels = groups.get(group_title, set())
if not labels:
    raise SystemExit(1)
root = ET.parse(path).getroot()
ys = []
for node in root.iter("node"):
    desc = node.attrib.get("content-desc", "")
    if not desc.startswith(prefix + ": "):
        continue
    if desc[len(prefix) + 2:] not in labels:
        continue
    if node.attrib.get("visible-to-user", "true").lower() == "false":
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if match:
        x1, y1, x2, y2 = map(int, match.groups())
        if x2 > x1 and y2 > y1:
            ys.append((y1 + y2) // 2)
if not ys:
    raise SystemExit(1)
print(sorted(ys)[len(ys) // 2])
PY
}
coord_for_text_near_title() {
  local xml="$1"
  local title="$2"
  local wanted="$3"
  python3 - "$xml" "$title" "$wanted" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path,title,wanted=sys.argv[1:]
root=ET.parse(path).getroot()
def bounds(node):
    m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
    if not m: return None
    x1,y1,x2,y2=map(int,m.groups())
    return (x1,y1,x2,y2,(y1+y2)//2,(x1+x2)//2)
titles=[]
buttons=[]
for n in root.iter("node"):
    b=bounds(n)
    if not b: continue
    if n.attrib.get("text")==title:
        titles.append(b)
    if n.attrib.get("text")==wanted:
        buttons.append(b)
if not titles or not buttons:
    raise SystemExit(1)
ty=min(titles,key=lambda b:abs(b[4]-500))[4]
near=[b for b in buttons if abs(b[4]-ty)<=90]
if not near:
    raise SystemExit(1)
# The rendered card layout places the preset row below the title; allow
# enough vertical tolerance for density/font/layout differences. The
# direction title still prevents matching the duplicate speed label in the
# other card.
b=min(near,key=lambda b:abs(b[4]-ty))
print(b[5],b[4])
PY
}

tap_preset_row() {
  local wanted="$1"
  local mode="$2"
  local expected="${3:?expected Kbps is required}"
  local xml="$RUN_DIR/current-ui.xml"
  local card_title=""
  local direction_tag=""
  card_title="$(direction_title "$mode")" || return 1
  if [ "$mode" = "top" ]; then direction_tag="download"; else direction_tag="upload"; fi

  local unique_desc="$card_title: $wanted"
  local unique_tag="speed_preset_${direction_tag}_${expected}"
  if [ "$expected" -eq 0 ]; then unique_tag="speed_preset_${direction_tag}_unlimited"; fi

  # Stable Compose testTag/resource-id first; accessibility contentDescription second.
  tap_text "$card_title" "$mode" || true
  sleep 1

  for attempt in $(seq 1 24); do
    dump_ui "$xml" || true
    local c=""
    c="$(coord_for_resource_id "$xml" "$unique_tag" 2>/dev/null || true)"
    if [ -z "$c" ]; then c="$(coord_for_content_desc "$xml" "$unique_desc" 2>/dev/null || true)"; fi
    if [ -n "$c" ]; then
      read -r x y <<< "$c"
      adb -s "$DEVICE" shell input tap "$x" "$y"
      sleep 1
      return 0
    fi

    if [ "$wanted" = "بدون حد" ]; then
      # Unlimited is a standalone button below the horizontal preset rows.
      adb -s "$DEVICE" shell input swipe 160 500 160 300 700
    else
      local y=""
      local group_title=""
      group_title="$(preset_group_title "$wanted" 2>/dev/null || true)"
      y="$(preset_desc_row_y "$xml" "$card_title" "$group_title" 2>/dev/null || true)"
      if [ -n "$y" ]; then
        # Scroll only the selected Download/Upload preset row horizontally.
        adb -s "$DEVICE" shell input swipe 300 "$y" 60 "$y" 700
      else
        # Reveal the card inside the main vertical ScrollView.
        adb -s "$DEVICE" shell input swipe 160 500 160 300 700
      fi
    fi
    sleep 1
  done

  echo "UI_ERROR: cannot select '$wanted' using '$unique_tag' / '$unique_desc'."
  return 1
}

read_limits() {
  adb -s "$DEVICE" shell run-as "$PACKAGE" cat shared_prefs/local_limits.xml 2>/dev/null || true
}

preference_matches() {
  local xml="$1"
  local target_key="$2"
  local expected="$3"
  local check_download="$4"
  printf '%s\n' "$xml" | python3 -c '
import sys, xml.etree.ElementTree as ET
key, expected_raw, check_download = sys.argv[1:]
try:
    root = ET.parse(sys.stdin).getroot()
except ET.ParseError:
    raise SystemExit(1)
values = {
    node.attrib.get("name"): int(node.attrib.get("value", "-999999"))
    for node in root.iter("long") if node.attrib.get("name")
}
expected = int(expected_raw)
keys = [key]
if check_download == "both" and "dl" not in keys:
    keys.append("dl")
if any(values.get(item, -999999) != expected for item in keys):
    raise SystemExit(1)
'
}

select_preset_and_wait() {
  local wanted="$1"
  local mode="$2"
  local expected="$3"
  local target_key="$4"
  local check_download="$5"
  local limits=""

  for attempt in 1 2 3; do
    if tap_preset_row "$wanted" "$mode" "$expected"; then
      for poll in $(seq 1 12); do
        limits="$(read_limits)"
        if preference_matches "$limits" "$target_key" "$expected" "$check_download"; then
          echo "PREFERENCE_OK: $wanted ($mode) persisted $target_key=$expected on attempt $attempt."
          return 0
        fi
        sleep 0.25
      done
      echo "PREFERENCE_RETRY: $wanted ($mode) did not persist expected value; attempt=$attempt."
    else
      echo "SELECTION_RETRY: $wanted ($mode) could not be targeted; attempt=$attempt."
    fi
    sleep 0.5
  done
  echo "PREFERENCE_FAIL: $wanted ($mode) never persisted expected $target_key=$expected."
  return 1
}


vpn_tun_present() {
  adb -s "$DEVICE" shell ip -o addr show 2>/dev/null | grep -q "198\.18\.0\.1"
}

cleanup_vpn() {
  adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
  sleep 2
  if vpn_tun_present; then
    echo "VPN cleanup: TUN remained; retrying force-stop."
    adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    sleep 3
  fi
  if vpn_tun_present; then
    return 1
  fi
  return 0
}

parse_verify() {
  local metrics_json="$1"
  local preset="$2"
  local expected="$3"
  local index="$4"
  local out="$5"
  python3 scripts/e2e/verify_metrics.py parse-json "$metrics_json" "$preset" "$expected" "$index" "$out"
}

TOTAL=19
FAIL_COUNT=0
ENV_LIMITED_COUNT=0
PASS_COUNT=0
TESTED_PRESET_COUNT=0
METRIC_RESULT_COUNT=0

echo "===== SPEED LIMIT SWEEP START ====="
echo "19 finite presets + Unlimited."
echo "Real UI selection for Download and Upload, then Verify Speed, record, stop VPN, next preset."
echo

# Keep the case list on FD 3 rather than stdin: adb shell can consume
# stdin and otherwise discard all remaining speed presets after the first one.
while IFS='|' read -r index preset expected <&3; do
  [ -n "$index" ] || continue
  TESTED_PRESET_COUNT=$((TESTED_PRESET_COUNT+1))
  slug="$(printf '%02d' "$index")-$(echo "$preset" | tr ' ' '_' | tr -cd '[:alnum:]_-')"
  echo "===== SWEEP $index/$TOTAL: $preset =====" | tee -a "$RUN_DIR/sweep-console.log"

  adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
  sleep 2
  adb -s "$DEVICE" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
  sleep 4

  if ! tap_text "السرعة"; then
    echo "FAIL $preset: Speed tab not found."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,UI,,,,,Speed tab not found,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    continue
  fi
  sleep 2

  if ! select_preset_and_wait "$preset" top "$expected" dl none; then
    dl_limits="$(read_limits)"
    echo "Download prefs after failed selection: $dl_limits" | tee -a "$RUN_DIR/sweep-console.log"
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Download,,,,,Preset selection/persistence failed,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  dl_limits="$(read_limits)"
  echo "Download prefs: $dl_limits" | tee -a "$RUN_DIR/sweep-console.log"

  adb -s "$DEVICE" shell input swipe 160 570 160 190 850
  sleep 2

  if ! select_preset_and_wait "$preset" bottom "$expected" ul both; then
    ul_limits="$(read_limits)"
    echo "Upload prefs after failed selection: $ul_limits" | tee -a "$RUN_DIR/sweep-console.log"
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Upload,,,,,Preset selection/persistence failed,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  ul_limits="$(read_limits)"
  echo "Upload prefs: $ul_limits" | tee -a "$RUN_DIR/sweep-console.log"

  echo "UI selection verified for $preset." | tee -a "$RUN_DIR/sweep-console.log"

  if ! tap_text "✅ تحقق من السرعة"; then
    echo "FAIL $preset: Verify button not found."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Verify,,,,,Verify button not found,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  if tap_text "أوافق والمتابعة"; then
    echo "Accepted SpeedVPN VPN disclosure."
    sleep 2
    if tap_text "OK"; then
      echo "Accepted Android VPN confirmation."
    elif tap_text "السماح"; then
      echo "Accepted Android VPN permission."
    elif tap_text "Allow"; then
      echo "Accepted Android VPN permission."
    else
      echo "FAIL $preset: Android VPN approval not completed."
      FAIL_COUNT=$((FAIL_COUNT+1))
      echo "$index,$preset,$expected,VPN,,,,,VPN approval failed,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
      cleanup_vpn || true
      continue
    fi

    # VpnService.prepare() launches an external Android permission activity.
    # Returning from it only updates MainActivity.permissionGranted; it does
    # not automatically execute the Verify button's action a second time.
    # Give the ActivityResult callback time to update Compose, then explicitly
    # press Verify again so the actual verification coroutine starts.
    verify_restarted=0
    for retry in 1 2 3 4 5; do
      sleep 1
      if tap_text "✅ تحقق من السرعة"; then
        echo "Re-started Verify Speed after VPN permission (attempt $retry)."
        verify_restarted=1
        break
      fi
    done
    if [ "$verify_restarted" -ne 1 ]; then
      echo "FAIL $preset: Verify button was not available after VPN permission."
      FAIL_COUNT=$((FAIL_COUNT+1))
      echo "$index,$preset,$expected,Verify,,,,,Verify restart after VPN permission failed,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
      adb -s "$DEVICE" logcat -d -t 3500 > "$RUN_DIR/$slug-logcat.txt" || true
      cleanup_vpn || true
      continue
    fi
    sleep 1
  fi

  # The completed result is a better completion signal than the transient loading
  # text: fast runs can finish between UIAutomator snapshots. Activity state is
  # recreated between presets, so this metric card belongs to the current run.
  result_xml="$RUN_DIR/$slug-verify.xml"
  finished=0
  loading_seen=0
  for second in $(seq 1 300); do
    dump_ui "$result_xml" || true
    if grep -q "جاري التحقق من الخطة…" "$result_xml" 2>/dev/null; then
      loading_seen=1
    fi

    # Completion is accepted only when a rendered metric row has the current
    # preset's plan, a baseline, an in-VPN measurement and a final verdict.
    # The spinner does not have to be captured by UIAutomator.
    if python3 - "$result_xml" "$expected" <<'PY' >/dev/null 2>&1
import re, sys, xml.etree.ElementTree as ET

path, expected_raw = sys.argv[1:]
expected = int(expected_raw)
try:
    texts = [(n.attrib.get("text", "") or "").strip()
             for n in ET.parse(path).getroot().iter("node")]
except (OSError, ET.ParseError):
    raise SystemExit(1)

status_labels = {
    "✅ السرعة متطابقة", "❌ السرعة غير متطابقة",
    "⚠️ لا يمكن الحكم", "✅ لا يظهر سقف واضح",
}
labels = {
    "baseline": "🌐 سرعة الإنترنت الأصلية",
    "plan": "🔒 السرعة المحجوزة / المحددة",
    "vpn": "🚀 السرعة الفعلية داخل VPN",
}
rate_re = re.compile(r"^([0-9]+(?:\.[0-9]+)?) ?(bps|Kbps|Mbps|Gbps)$")
mult = {"bps": 1.0, "Kbps": 1000.0, "Mbps": 1_000_000.0, "Gbps": 1_000_000_000.0}

def rate(value):
    match = rate_re.fullmatch(value)
    return None if not match else float(match.group(1)) * mult[match.group(2)]

def value_after(section, label):
    try:
        index = section.index(label)
    except ValueError:
        return None
    boundaries = set(status_labels) | set(labels.values()) | {
        "Download", "Upload", "الدقة مقارنة بالخيار"
    }
    for value in section[index + 1:index + 7]:
        if value == "بدون حد" or rate(value) is not None:
            return value
        if value in boundaries:
            break
    return None

anchors = [i for i, value in enumerate(texts)
           if value == labels["baseline"]]
for position, start in enumerate(anchors):
    end = anchors[position + 1] if position + 1 < len(anchors) else len(texts)
    section = texts[start:end]
    if not any(value in status_labels for value in section):
        continue
    baseline_raw = value_after(section, labels["baseline"])
    plan_raw = value_after(section, labels["plan"])
    vpn_raw = value_after(section, labels["vpn"])
    if baseline_raw is None or plan_raw is None or vpn_raw is None:
        continue
    if rate(baseline_raw) is None or rate(vpn_raw) is None:
        continue
    if expected == 0:
        plan_matches = plan_raw == "بدون حد"
    else:
        plan_bps = rate(plan_raw)
        expected_bps = expected * 1000.0
        # Allow only visible-label rounding when identifying the selected plan.
        plan_matches = plan_bps is not None and abs(plan_bps - expected_bps) <= max(1.0, expected_bps * 0.01)
    if plan_matches:
        raise SystemExit(0)
raise SystemExit(1)
PY
    then
      finished=1
      echo "Verify result exposed for $preset after ${second}s (loading_frame_seen=$loading_seen)." | tee -a "$RUN_DIR/sweep-console.log"
      break
    fi

    # Keep scrolling toward the current result card. This handles the case
    # where the result is complete but below the UIAutomator viewport.
    if [ "$((second % 3))" -eq 0 ]; then
      adb -s "$DEVICE" shell input swipe 160 500 160 220 650 || true
    fi
    sleep 1
  done

  adb -s "$DEVICE" logcat -d -t 3500 > "$RUN_DIR/$slug-logcat.txt" || true

  if [ "$finished" -ne 1 ]; then
    echo "FAIL $preset: no completed metric row appeared within 300 seconds (loading_frame_seen=$loading_seen)."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Verify,,,,,Completed metric row missing or timeout,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  # UIAutomator only exposes visible Compose semantics on some API levels.
  # Merge uniquely tagged values across snapshots; do not require both long metric
  # sections to be visible in the same viewport at the same time.
  result_xml="$RUN_DIR/$slug-verify.xml"
  metrics_file="$RUN_DIR/$slug-metrics.json"
  printf '{}' > "$metrics_file"
  metrics_visible=0

  # First move toward the top while retaining any fields already visible.
  for reset in $(seq 1 10); do
    dump_ui "$result_xml" || true
    if python3 scripts/e2e/verify_metrics.py collect "$result_xml" "$metrics_file" 2>>"$RUN_DIR/sweep-console.log"; then
      metrics_visible=1
      break
    fi
    adb -s "$DEVICE" shell input swipe 160 220 160 560 550 || true
    sleep 0.3
  done

  # Then advance through the result, accumulating values from every snapshot.
  if [ "$metrics_visible" -ne 1 ]; then
    for reveal in $(seq 1 30); do
      dump_ui "$result_xml" || true
      if python3 scripts/e2e/verify_metrics.py collect "$result_xml" "$metrics_file" 2>>"$RUN_DIR/sweep-console.log"; then
        metrics_visible=1
        echo "Tagged Verify metrics collected for $preset after $reveal scroll(s)." | tee -a "$RUN_DIR/sweep-console.log"
        break
      fi
      adb -s "$DEVICE" shell input swipe 160 550 160 220 550 || true
      sleep 0.3
    done
  fi

  if [ "$metrics_visible" -ne 1 ]; then
    echo "FAIL $preset: tagged Verify Speed fields are incomplete."
    python3 - "$metrics_file" <<'PY' | tee -a "$RUN_DIR/sweep-console.log"
import json, sys
from pathlib import Path
try:
    data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
except Exception:
    data = {}
required = [
    f"verify_{direction}_{field}_value"
    for direction in ("download", "upload")
    for field in ("baseline", "plan", "vpn", "accuracy")
] + [f"verify_{direction}_verdict" for direction in ("download", "upload")]
print("VERIFY_FIELDS_MISSING=" + ",".join(key for key in required if not data.get(key)))
PY
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Verify,,,,,Tagged metrics not fully captured,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi
  set +e
  parse_verify "$metrics_file" "$preset" "$expected" "$index" "$RUN_DIR/$slug-parsed.csv" | tee -a "$RUN_DIR/sweep-console.log"
  set -e

  if [ -s "$RUN_DIR/$slug-parsed.csv" ]; then
    cat "$RUN_DIR/$slug-parsed.csv" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    while IFS= read -r r_classification; do
      r_classification="$(printf '%s' "$r_classification" | tr -d '\r')"
      [ -n "$r_classification" ] || continue
      METRIC_RESULT_COUNT=$((METRIC_RESULT_COUNT+1))
      case "$r_classification" in
        PASS|PASS_UNLIMITED) PASS_COUNT=$((PASS_COUNT+1));;
        ENV_LIMITED) ENV_LIMITED_COUNT=$((ENV_LIMITED_COUNT+1));;
        FAIL) FAIL_COUNT=$((FAIL_COUNT+1));;
        *) echo "FAIL $preset: invalid classification '$r_classification'."; FAIL_COUNT=$((FAIL_COUNT+1));;
      esac
    done < <(python3 - "$RUN_DIR/$slug-parsed.csv" <<'PY'
import csv, sys
with open(sys.argv[1], newline="", encoding="utf-8") as handle:
    for row in csv.reader(handle):
        if not row:
            continue
        if len(row) != 10:
            raise SystemExit(f"Malformed parser CSV row: expected 10 columns, got {len(row)}")
        print(row[9].strip())
PY
    )
  else
    echo "FAIL $preset: parser produced no rows."
    FAIL_COUNT=$((FAIL_COUNT+1))
  fi

  if ! cleanup_vpn; then
    echo "FAIL $preset: VPN TUN remained after cleanup."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,VPN,,,,,VPN remained active after cleanup,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
  else
    echo "VPN OFF confirmed before next preset." | tee -a "$RUN_DIR/sweep-console.log"
  fi

  echo
done 3<<< "$SPEED_CASES"

# Enforce full coverage so a prematurely terminated loop can never pass CI.
if [ "$TESTED_PRESET_COUNT" -ne "$TOTAL" ]; then
  echo "COVERAGE FAIL: tested $TESTED_PRESET_COUNT/$TOTAL presets."
  FAIL_COUNT=$((FAIL_COUNT+1))
fi
EXPECTED_METRIC_RESULTS=$((TOTAL * 2))
if [ "$METRIC_RESULT_COUNT" -ne "$EXPECTED_METRIC_RESULTS" ]; then
  echo "COVERAGE FAIL: recorded $METRIC_RESULT_COUNT/$EXPECTED_METRIC_RESULTS Download/Upload results."
  FAIL_COUNT=$((FAIL_COUNT+1))
fi

if [ "$PASS_COUNT" -ne "$EXPECTED_METRIC_RESULTS" ]; then
  echo "STRICT COVERAGE FAIL: PASS=$PASS_COUNT/$EXPECTED_METRIC_RESULTS; ENV_LIMITED and mismatches cannot count as verified."
  FAIL_COUNT=$((FAIL_COUNT+1))
fi
if ! python3 scripts/e2e/verify_metrics.py validate-sweep "$RUN_DIR/speed-limit-sweep-results.csv"; then
  echo "STRICT GATE FAIL: independent CSV validation did not reach PASS=38/38."
  FAIL_COUNT=$((FAIL_COUNT+1))
fi

cat > "$RUN_DIR/speed-limit-sweep-summary.md" <<MD
# SpeedVPN Speed-Limit Sweep

- Presets attempted: $TESTED_PRESET_COUNT/$TOTAL
- Download/Upload metric results recorded: $METRIC_RESULT_COUNT/$EXPECTED_METRIC_RESULTS
- PASS: $PASS_COUNT
- ENV_LIMITED (physical/emulator baseline below selected plan): $ENV_LIMITED_COUNT
- HARD FAIL: $FAIL_COUNT

Every labeled preset is covered: 10/25/50/75/100/130/250/500/750/950 KB/s, 1/2/3/4/5 MB/s, 9/10/11 MB/s, and Unlimited.

HARD FAIL means UI selection/persistence failed, Verify Speed produced MISMATCH, verification failed/timed out, or VPN did not shut down between cases.

ENV_LIMITED means the test environment cannot demonstrate the selected cap; it is not a pass and fails the strict 38/38 gate.

The slider itself is continuous from 1 KB/s to 100 MB/s, so no finite test can enumerate every slider value.
MD

cp "$RUN_DIR/speed-limit-sweep-results.csv" speed-limit-sweep-results.csv
cp "$RUN_DIR/speed-limit-sweep-summary.md" speed-limit-sweep-summary.md

{
  echo "## SpeedVPN Speed-Limit Sweep"
  echo
  echo "- PASS: $PASS_COUNT"
  echo "- ENV_LIMITED: $ENV_LIMITED_COUNT"
  echo "- HARD FAIL: $FAIL_COUNT"
  echo
  echo "| # | Preset | Direction | Plan | VPN | Verdict | Classification |"
  echo "|---:|---|---|---|---|---|---|"
  awk -F',' 'NR>1 && NF>=10 {printf "| %s | %s | %s | %s | %s | %s | %s |\\n",$1,$2,$4,$6,$7,$8,$10}' speed-limit-sweep-results.csv
} >> "$GITHUB_STEP_SUMMARY"

if grep -Eiq "FATAL EXCEPTION|AndroidRuntime.*FATAL" "$RUN_DIR/"*-logcat.txt 2>/dev/null; then
  echo "FATAL EXCEPTION detected in sweep logcats."
  FAIL_COUNT=$((FAIL_COUNT+1))
fi

echo "===== SPEED LIMIT SWEEP COMPLETE ====="
echo "PRESETS_TESTED=$TESTED_PRESET_COUNT/$TOTAL"
echo "METRIC_RESULTS=$METRIC_RESULT_COUNT/$EXPECTED_METRIC_RESULTS"
echo "PASS=$PASS_COUNT"
echo "ENV_LIMITED=$ENV_LIMITED_COUNT"
echo "HARD_FAIL=$FAIL_COUNT"

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
\\r'}"
      METRIC_RESULT_COUNT=$((METRIC_RESULT_COUNT+1))
      echo "RESULT: $r_preset / $r_direction / plan=$r_plan / vpn=$r_vpn / verdict=$r_verdict / class=$r_classification / reason=$r_reason"
      case "$r_classification" in
        PASS|PASS_UNLIMITED) PASS_COUNT=$((PASS_COUNT+1));;
        ENV_LIMITED) ENV_LIMITED_COUNT=$((ENV_LIMITED_COUNT+1));;
        FAIL) FAIL_COUNT=$((FAIL_COUNT+1));;
      esac
    done < "$RUN_DIR/$slug-parsed.csv"
  else
    echo "FAIL $preset: parser produced no rows."
    FAIL_COUNT=$((FAIL_COUNT+1))
  fi

  if ! cleanup_vpn; then
    echo "FAIL $preset: VPN TUN remained after cleanup."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,VPN,,,,,VPN remained active after cleanup,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
  else
    echo "VPN OFF confirmed before next preset." | tee -a "$RUN_DIR/sweep-console.log"
  fi

  echo
done <<< "$SPEED_CASES"

cat > "$RUN_DIR/speed-limit-sweep-summary.md" <<MD
# SpeedVPN Speed-Limit Sweep

- PASS: $PASS_COUNT
- ENV_LIMITED (physical/emulator baseline below selected plan): $ENV_LIMITED_COUNT
- HARD FAIL: $FAIL_COUNT

Every labeled preset is covered: 10/25/50/75/100/130/250/500/750/950 KB/s, 1/2/3/4/5 MB/s, 9/10/11 MB/s, and Unlimited.

HARD FAIL means UI selection/persistence failed, Verify Speed produced MISMATCH, verification failed/timed out, or VPN did not shut down between cases.

ENV_LIMITED is expected when the emulator's physical baseline is below the selected plan; it is recorded separately and does not fail the sweep.

The slider itself is continuous from 1 KB/s to 100 MB/s, so no finite test can enumerate every slider value.
MD

cp "$RUN_DIR/speed-limit-sweep-results.csv" speed-limit-sweep-results.csv
cp "$RUN_DIR/speed-limit-sweep-summary.md" speed-limit-sweep-summary.md

{
  echo "## SpeedVPN Speed-Limit Sweep"
  echo
  echo "- PASS: $PASS_COUNT"
  echo "- ENV_LIMITED: $ENV_LIMITED_COUNT"
  echo "- HARD FAIL: $FAIL_COUNT"
  echo
  echo "| # | Preset | Direction | Plan | VPN | Verdict | Classification |"
  echo "|---:|---|---|---|---|---|---|"
  awk -F',' 'NR>1 && NF>=10 {printf "| %s | %s | %s | %s | %s | %s | %s |\\n",$1,$2,$4,$6,$7,$8,$10}' speed-limit-sweep-results.csv
} >> "$GITHUB_STEP_SUMMARY"

if grep -Eiq "FATAL EXCEPTION|AndroidRuntime.*FATAL" "$RUN_DIR/"*-logcat.txt 2>/dev/null; then
  echo "FATAL EXCEPTION detected in sweep logcats."
  FAIL_COUNT=$((FAIL_COUNT+1))
fi

echo "===== SPEED LIMIT SWEEP COMPLETE ====="
echo "PASS=$PASS_COUNT"
echo "ENV_LIMITED=$ENV_LIMITED_COUNT"
echo "HARD_FAIL=$FAIL_COUNT"

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
