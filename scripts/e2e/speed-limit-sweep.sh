#!/usr/bin/env bash
set -euo pipefail

DEVICE="emulator-5554"
PACKAGE="com.speedvpn.app"
SCREEN_SIZE="$(adb -s "$DEVICE" shell wm size 2>/dev/null | awk -F': ' '/Override size:/{size=$2} /Physical size:/{if(size=="")size=$2} END{print size}')"
SCREEN_WIDTH="${SCREEN_SIZE%x*}"
SCREEN_HEIGHT="${SCREEN_SIZE#*x}"
RUN_DIR="${SPEEDVPN_RUN_DIR:-speed-limit-sweep}"
CSV_RUN_ID="${GITHUB_RUN_ID:-local}"
recorded_at_utc() { date -u +"%Y-%m-%dT%H:%M:%S.%3NZ"; }
RUN_STARTED_AT="$(recorded_at_utc)"
export SPEEDVPN_SWEEP_STARTED_AT="$RUN_STARTED_AT"
if [ -n "${GITHUB_ENV:-}" ]; then
  printf 'SPEEDVPN_SWEEP_STARTED_AT=%s\n' "$RUN_STARTED_AT" >> "$GITHUB_ENV"
fi
mkdir -p "$RUN_DIR"
: > "$RUN_DIR/speed-limit-sweep-results.csv"
: > "$RUN_DIR/sweep-console.log"

python3 scripts/e2e/verify_metrics.py init-csv "$RUN_DIR/speed-limit-sweep-results.csv"

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
if [ -n "${SPEEDVPN_CASES_OVERRIDE:-}" ]; then
  SPEED_CASES="$SPEEDVPN_CASES_OVERRIDE"
fi

dump_ui() {
  local out="$1"
  local remote="/sdcard/speedvpn-sweep-ui.xml"
  for attempt in 1 2 3 4 5; do
    rm -f "$out"
    if adb -s "$DEVICE" shell uiautomator dump "$remote" >/dev/null 2>&1 &&
       adb -s "$DEVICE" exec-out cat "$remote" > "$out" 2>/dev/null &&
       [ -s "$out" ] &&
       python3 -c 'import sys, xml.etree.ElementTree as ET; ET.parse(sys.argv[1])' "$out" >/dev/null 2>&1; then
      adb -s "$DEVICE" shell rm -f "$remote" >/dev/null 2>&1 || true
      return 0
    fi
    if [ -s "$out" ] && ! python3 -c 'import sys, xml.etree.ElementTree as ET; ET.parse(sys.argv[1])' "$out" >/dev/null 2>&1; then
      echo "UI hierarchy XML is malformed (attempt ${attempt}/5); retrying."
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
def normalize(value):
    return " ".join((value or "").split())
wanted = normalize(wanted)
for node in root.iter("node"):
    text = normalize(node.attrib.get("text", ""))
    content_desc = normalize(node.attrib.get("content-desc", ""))
    if wanted == "🚀 فحص السرعة":
        if not ({wanted, "فحص السرعة"} & {text, content_desc}):
            continue
    elif wanted not in {text, content_desc}:
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

tap_resource_id() {
  local wanted="$1"
  local xml="$RUN_DIR/current-ui.xml"
  for scroll in 0 1 2 3 4 5 6 7; do
    dump_ui "$xml" || true
    local c=""
    c="$(coord_for_resource_id "$xml" "$wanted" 2>/dev/null || true)"
    if [ -n "$c" ]; then
      read -r x y <<< "$c"
      adb -s "$DEVICE" shell input tap "$x" "$y"
      return 0
    fi
    [ "$scroll" -lt 7 ] || break
    adb -s "$DEVICE" shell input swipe 160 500 160 220 650
    sleep 1
  done
  echo "UI_ERROR: resource id '$wanted' is not visible."
  return 1
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
  python3 - "$xml" "$wanted" "$SCREEN_WIDTH" "$SCREEN_HEIGHT" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, wanted, width_raw, height_raw = sys.argv[1:]
width = int(width_raw) if width_raw.isdigit() else 0
height = int(height_raw) if height_raw.isdigit() else 0
root = ET.parse(path).getroot()
parents = {child: parent for parent in root.iter() for child in list(parent)}

def visible_center(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        return None
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        return None
    ancestor = parents.get(node)
    while ancestor is not None:
        class_name = ancestor.attrib.get("class", "")
        if "ScrollView" in class_name or ancestor.attrib.get("scrollable", "false").lower() == "true":
            pm = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", ancestor.attrib.get("bounds", ""))
            if pm:
                pl, pt, pr, pb = map(int, pm.groups())
                if pr <= pl or pb <= pt:
                    return None
                left, top, right, bottom = max(left, pl), max(top, pt), min(right, pr), min(bottom, pb)
                if right <= left or bottom <= top:
                    return None
        ancestor = parents.get(ancestor)
    if width:
        left, right = max(left, 0), min(right, width)
    if height:
        top, bottom = max(top, 0), min(bottom, height)
    if right <= left or bottom <= top:
        return None
    return (left + right) // 2, (top + bottom) // 2

for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    if resource_id != wanted and not resource_id.endswith("/" + wanted) and not resource_id.endswith(":id/" + wanted):
        continue
    if node.attrib.get("enabled", "true").lower() == "false":
        continue
    if node.attrib.get("visible-to-user", "true").lower() == "false":
        continue
    point = visible_center(node)
    if point is None:
        continue
    print(point[0], point[1])
    raise SystemExit(0)
raise SystemExit(1)
PY
}

coord_for_content_desc() {
  local xml="$1"
  local wanted="$2"
  python3 - "$xml" "$wanted" "$SCREEN_WIDTH" "$SCREEN_HEIGHT" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, wanted, width_raw, height_raw = sys.argv[1:]
width = int(width_raw) if width_raw.isdigit() else 0
height = int(height_raw) if height_raw.isdigit() else 0
root = ET.parse(path).getroot()
parents = {child: parent for parent in root.iter() for child in list(parent)}

def visible_center(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        return None
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        return None
    ancestor = parents.get(node)
    while ancestor is not None:
        class_name = ancestor.attrib.get("class", "")
        if "ScrollView" in class_name or ancestor.attrib.get("scrollable", "false").lower() == "true":
            pm = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", ancestor.attrib.get("bounds", ""))
            if pm:
                pl, pt, pr, pb = map(int, pm.groups())
                if pr <= pl or pb <= pt:
                    return None
                left, top, right, bottom = max(left, pl), max(top, pt), min(right, pr), min(bottom, pb)
                if right <= left or bottom <= top:
                    return None
        ancestor = parents.get(ancestor)
    if width:
        left, right = max(left, 0), min(right, width)
    if height:
        top, bottom = max(top, 0), min(bottom, height)
    if right <= left or bottom <= top:
        return None
    return (left + right) // 2, (top + bottom) // 2

for node in root.iter("node"):
    if node.attrib.get("content-desc") != wanted:
        continue
    if node.attrib.get("enabled", "true").lower() == "false":
        continue
    if node.attrib.get("visible-to-user", "true").lower() == "false":
        continue
    point = visible_center(node)
    if point is None:
        continue
    print(point[0], point[1])
    raise SystemExit(0)
raise SystemExit(1)
PY
}

preset_scroll_bounds() {
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
parents = {child: parent for parent in root.iter() for child in list(parent)}
rows = set()
for node in root.iter("node"):
    desc = node.attrib.get("content-desc", "")
    if not desc.startswith(prefix + ": "):
        continue
    if desc[len(prefix) + 2:] not in labels:
        continue
    if node.attrib.get("visible-to-user", "true").lower() == "false":
        continue
    child_match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not child_match:
        continue
    cl, ct, cr, cb = map(int, child_match.groups())
    if cr <= cl or cb <= ct:
        continue
    ancestor = parents.get(node)
    while ancestor is not None:
        class_name = ancestor.attrib.get("class", "")
        if "HorizontalScrollView" in class_name:
            match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", ancestor.attrib.get("bounds", ""))
            if match:
                left, top, right, bottom = map(int, match.groups())
                if right > left and bottom > top:
                    left, top, right, bottom = max(left, cl), max(top, ct), min(right, cr), min(bottom, cb)
                    if right > left and bottom > top:
                        rows.add(tuple(map(int, match.groups())))
            break
        ancestor = parents.get(ancestor)
if not rows:
    raise SystemExit(1)
left, top, right, bottom = sorted(rows, key=lambda b: (b[1], b[0]))[0]
print(left, top, right, bottom)
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
      local bounds=""
      local group_title=""
      group_title="$(preset_group_title "$wanted" 2>/dev/null || true)"
      bounds="$(preset_scroll_bounds "$xml" "$card_title" "$group_title" 2>/dev/null || true)"
      if [ -n "$bounds" ]; then
        local x1 y1 x2 y2
        read -r x1 y1 x2 y2 <<< "$bounds"
        # Derive the drag from the actual HorizontalScrollView bounds. The old
        # x=300 start point was outside the observed [34,286] container.
        local inset=8
        local from_x=$((x2 - inset))
        local to_x=$((x1 + inset))
        local y=$(((y1 + y2) / 2))
        if [ "$from_x" -gt "$to_x" ]; then
          adb -s "$DEVICE" shell input swipe "$from_x" "$y" "$to_x" "$y" 700
        else
          adb -s "$DEVICE" shell input swipe 160 500 160 300 700
        fi
      else
        # If the preset row is not visible yet, reveal it in the main ScrollView.
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
  printf '%s\n' "$xml" | python3 scripts/e2e/preference_matches.py "$target_key" "$expected" "$check_download"
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

record_failed_pair() {
  local index="$1"
  local preset="$2"
  local expected="$3"
  local status="$4"
  local reason="$5"
  python3 scripts/e2e/verify_metrics.py record-failure \
    "$RUN_DIR/speed-limit-sweep-results.csv" "$index" "$preset" "$expected" "$status" "$reason"
}

TOTAL="${SPEEDVPN_TOTAL_PRESETS:-19}"
FAIL_COUNT=0
METRIC_FAIL_COUNT=0
ENV_LIMITED_COUNT=0
PASS_COUNT=0
TESTED_PRESET_COUNT=0
METRIC_RESULT_COUNT=0
BASELINE_PREFLIGHT_STATUS="NOT_RUN"
BASELINE_PREFLIGHT_REASON="Preflight did not complete"
BASELINE_PREFLIGHT_DOWNLOAD="unknown"
BASELINE_PREFLIGHT_UPLOAD="unknown"

echo "===== SPEED LIMIT SWEEP START ====="
echo "18 finite presets + Unlimited (19 total choices; 38 Download/Upload metrics)."
echo "Real UI selection for Download and Upload, then Verify Speed, record, stop VPN, next preset."
echo

# Measure whether the emulator can deliver at least 1.2x the highest finite
# target in both physical directions. A failed headroom check blocks acceptance,
# but does not prevent lower presets from producing useful diagnostic measurements.
preflight_fail() {
  local reason="$1"
  echo "BASELINE_PREFLIGHT_FAIL=$reason" | tee -a "$RUN_DIR/sweep-console.log"
  echo "ENV_INSUFFICIENT — skipped sweep" | tee -a "$RUN_DIR/sweep-console.log"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "preflight_status=ENV_INSUFFICIENT" >> "$GITHUB_OUTPUT"; fi
  adb -s "$DEVICE" logcat -d -v threadtime > "$RUN_DIR/baseline-preflight-logcat.txt" 2>&1 || true
  {
    echo "# SpeedVPN baseline preflight"
    echo
    echo "- GitHub run ID: $CSV_RUN_ID"
    echo "- Result: INFRASTRUCTURE_BLOCKED"
    echo "- Target: 88 Mbps"
    echo "- Required median baseline: Download >= 96 Mbps; Upload >= 106 Mbps"
    echo "- Maximum sample spread: 15% of the median in each direction"
    echo "- Failure: $reason"
    echo "- No speed presets were counted as tested; the 38-metric sweep was not started."
  } > "$RUN_DIR/speed-limit-sweep-summary.md"
  # Preserve a root-level CSV for the strict workflow gate even on early exits.
  # The header-only CSV remains a hard failure; if [ "${SPEEDVPN_SKIP_BASELINE_PREFLIGHT:-0}" = "1" ]; then
  PREFLIGHT_SUMMARY="${SPEEDVPN_PREFLIGHT_SUMMARY:-}"
  if [ -z "$PREFLIGHT_SUMMARY" ] || [ ! -s "$PREFLIGHT_SUMMARY" ]; then
    echo "ENV_INSUFFICIENT — skipped sweep" | tee -a "$RUN_DIR/sweep-console.log"
    echo "ERROR: SPEEDVPN_SKIP_BASELINE_PREFLIGHT=1 requires a validated SPEEDVPN_PREFLIGHT_SUMMARY file."
    if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "preflight_status=ENV_INSUFFICIENT" >> "$GITHUB_OUTPUT"; fi
    exit 75
  fi
  if ! python3 - "$PREFLIGHT_SUMMARY" <<'PY' | tee -a "$RUN_DIR/sweep-console.log"
import json, sys
from pathlib import Path
payload=json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload.get("status") == "PASS", "preflight summary status is not PASS"
for direction in ("download", "upload"):
    item=payload["directions"][direction]
    assert item.get("passed") is True, f"{direction} preflight did not pass"
    print(f"REUSED_PREFLIGHT_{direction.upper()}_MEDIAN={item['median_mbps']} Mbps")
PY
  then
    echo "ENV_INSUFFICIENT — skipped sweep" | tee -a "$RUN_DIR/sweep-console.log"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "preflight_status=ENV_INSUFFICIENT" >> "$GITHUB_OUTPUT"; fi
    exit 75
  fi
  BASELINE_PREFLIGHT_STATUS="PASS"
  BASELINE_PREFLIGHT_REASON="Reused validated three-sample preflight summary."
  BASELINE_PREFLIGHT_DOWNLOAD="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["directions"]["download"]["median_mbps"])' "$PREFLIGHT_SUMMARY") Mbps (median)"
  BASELINE_PREFLIGHT_UPLOAD="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["directions"]["upload"]["median_mbps"])' "$PREFLIGHT_SUMMARY") Mbps (median)"
  echo "BASELINE_PREFLIGHT_SKIPPED=validated three-sample preflight reused." | tee -a "$RUN_DIR/sweep-console.log"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "preflight_status=PASS" >> "$GITHUB_OUTPUT"; fi
else
  PREFLIGHT_START_EPOCH="$(date +%s)"
  PREFLIGHT_SAMPLES_DIR="$RUN_DIR/baseline-preflight"
  mkdir -p "$PREFLIGHT_SAMPLES_DIR"

  for preflight_round in 1 2 3; do
    echo "===== BASELINE PREFLIGHT SAMPLE $preflight_round/3 =====" | tee -a "$RUN_DIR/sweep-console.log"
    if [ "$(( $(date +%s) - PREFLIGHT_START_EPOCH ))" -ge 165 ]; then
      preflight_fail "Three-sample preflight exceeded its 165-second time budget."
      exit 75
    fi
    if ! cleanup_vpn; then
      preflight_fail "VPN TUN could not be confirmed stopped before baseline sample $preflight_round."
      exit 75
    fi
    adb -s "$DEVICE" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
    sleep 3
    if ! tap_text "السرعة"; then
      preflight_fail "Speed screen could not be opened for baseline sample $preflight_round."
      exit 75
    fi
    if ! select_preset_and_wait "11 MB" top 88000 dl none; then
      preflight_fail "Could not select/persist 88 Mbps Download plan for baseline sample $preflight_round."
      exit 75
    fi
    adb -s "$DEVICE" shell input swipe 160 570 160 190 850
    sleep 1
    if ! select_preset_and_wait "11 MB" bottom 88000 ul both; then
      preflight_fail "Could not select/persist 88 Mbps Upload plan for baseline sample $preflight_round."
      exit 75
    fi
    if ! tap_resource_id "baseline_preflight_button"; then
      preflight_fail "Baseline preflight button could not be selected for sample $preflight_round."
      exit 75
    fi

    preflight_json="$PREFLIGHT_SAMPLES_DIR/round-$preflight_round.json"
    preflight_xml="$PREFLIGHT_SAMPLES_DIR/round-$preflight_round.xml"
    printf '{}' > "$preflight_json"
    preflight_collected=0
    for preflight_poll in $(seq 1 30); do
      dump_ui "$preflight_xml" || true
      if python3 scripts/e2e/verify_metrics.py collect-preflight "$preflight_xml" "$preflight_json" >> "$RUN_DIR/sweep-console.log" 2>&1; then
        preflight_collected=1
        echo "Baseline sample $preflight_round collected after ${preflight_poll}s." | tee -a "$RUN_DIR/sweep-console.log"
        break
      fi
      if [ "$((preflight_poll % 2))" -eq 0 ]; then
        adb -s "$DEVICE" shell input swipe 160 500 160 220 650 || true
      fi
      if [ "$(( $(date +%s) - PREFLIGHT_START_EPOCH ))" -ge 165 ]; then
        break
      fi
      sleep 1
    done
    if [ "$preflight_collected" -ne 1 ]; then
      preflight_fail "The UI did not expose both fresh baseline values for sample $preflight_round within the preflight time budget."
      cp "$preflight_json" "$PREFLIGHT_SAMPLES_DIR/round-$preflight_round-failed.json" 2>/dev/null || true
      exit 75
    fi
    python3 - "$preflight_json" "$preflight_round" <<'PY' | tee -a "$RUN_DIR/sweep-console.log"
import json, sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(
    f"PREFLIGHT_SAMPLE_{sys.argv[2]} "
    f"download={data.get('baseline_preflight_download_value', 'missing')} "
    f"upload={data.get('baseline_preflight_upload_value', 'missing')}"
)
PY
    if ! cleanup_vpn; then
      preflight_fail "VPN TUN was not stopped after baseline sample $preflight_round."
      exit 75
    fi
    if [ "$(( $(date +%s) - PREFLIGHT_START_EPOCH ))" -ge 165 ]; then
      preflight_fail "Three-sample preflight exceeded its 165-second time budget."
      exit 75
    fi
  done

  preflight_summary="$PREFLIGHT_SAMPLES_DIR/summary.json"
  if python3 scripts/e2e/preflight_baseline.py \
      "$PREFLIGHT_SAMPLES_DIR/round-1.json" \
      "$PREFLIGHT_SAMPLES_DIR/round-2.json" \
      "$PREFLIGHT_SAMPLES_DIR/round-3.json" \
      --download-min 96 --upload-min 106 --max-variability 15 \
      --output "$preflight_summary" 2>&1 | tee -a "$RUN_DIR/sweep-console.log"; then
    BASELINE_PREFLIGHT_STATUS="PASS"
    BASELINE_PREFLIGHT_REASON="Three samples per direction passed median headroom and 15% stability gates."
    BASELINE_PREFLIGHT_DOWNLOAD="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["directions"]["download"]["median_mbps"])' "$preflight_summary") Mbps (median)"
    BASELINE_PREFLIGHT_UPLOAD="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["directions"]["upload"]["median_mbps"])' "$preflight_summary") Mbps (median)"
    echo "BASELINE_PREFLIGHT_PASS=3/3 samples per direction; download>=96 Mbps; upload>=106 Mbps; spread<=15%." | tee -a "$RUN_DIR/sweep-console.log"
  else
    BASELINE_PREFLIGHT_STATUS="ENV_INSUFFICIENT"
    BASELINE_PREFLIGHT_REASON="Baseline headroom or variability failed. No speed sweep was started."
    echo "ENV_INSUFFICIENT — skipped sweep" | tee -a "$RUN_DIR/sweep-console.log"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "preflight_status=ENV_INSUFFICIENT" >> "$GITHUB_OUTPUT"; fi
    {
      echo "# SpeedVPN baseline preflight"
      echo
      echo "- Result: ENV_INSUFFICIENT"
      echo "- Download median minimum: 96 Mbps"
      echo "- Upload median minimum: 106 Mbps"
      echo "- Maximum sample range: 15% of the median in each direction"
      echo "- Three baseline samples were collected before any speed sweep."
      echo "- The sweep was skipped; one retry on a new GitHub-hosted runner is permitted."
    } > "$RUN_DIR/speed-limit-sweep-summary.md"
    cp "$RUN_DIR/speed-limit-sweep-results.csv" speed-limit-sweep-results.csv
    cp "$RUN_DIR/speed-limit-sweep-summary.md" speed-limit-sweep-summary.md
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
      {
        echo "## ENV_INSUFFICIENT — skipped sweep"
        echo
        echo "The 3-sample baseline gate failed. One automatic retry is delegated to a separate runner job."
        cat "$preflight_summary" 2>/dev/null || true
      } >> "$GITHUB_STEP_SUMMARY"
    fi
    exit 75
  fi
fi

if [ "${SPEEDVPN_PREFLIGHT_ONLY:-0}" = "1" ]; then
  echo "PREFLIGHT_ONLY_COMPLETE status=$BASELINE_PREFLIGHT_STATUS" | tee -a "$RUN_DIR/sweep-console.log"
  exit 0
fi

"BASELINE_PREFLIGHT_ENV_LIMITED=$BASELINE_PREFLIGHT_REASON" | tee -a "$RUN_DIR/sweep-console.log"
fi
if ! cleanup_vpn; then
  preflight_fail "VPN TUN was not stopped after baseline preflight."
  exit 1
fi

# Keep the case list on FD 3 rather than stdin: adb shell can consume
# stdin and otherwise discard all remaining speed presets after the first one.
while IFS='|' read -r index preset expected <&3; do
  [ -n "$index" ] || continue
  TESTED_PRESET_COUNT=$((TESTED_PRESET_COUNT+1))
  slug="$(printf '%02d' "$index")-$(echo "$preset" | tr ' ' '_' | tr -cd '[:alnum:]_-')"
  export SPEEDVPN_CASE_STARTED_AT="$(recorded_at_utc)"
  echo "===== SWEEP $index/$TOTAL: $preset =====" | tee -a "$RUN_DIR/sweep-console.log"

  adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
  sleep 2
  adb -s "$DEVICE" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
  sleep 4

  if ! tap_text "السرعة"; then
    echo "FAIL $preset: Speed tab not found."
    FAIL_COUNT=$((FAIL_COUNT+1))
    record_failed_pair "$index" "$preset" "$expected" "CONFIGURATION_ERROR" "Speed tab not found"
    continue
  fi
  sleep 2

  if ! select_preset_and_wait "$preset" top "$expected" dl none; then
    dl_limits="$(read_limits)"
    echo "Download prefs after failed selection: $dl_limits" | tee -a "$RUN_DIR/sweep-console.log"
    FAIL_COUNT=$((FAIL_COUNT+1))
    record_failed_pair "$index" "$preset" "$expected" "CONFIGURATION_ERROR" "Preset selection/persistence failed"
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
    record_failed_pair "$index" "$preset" "$expected" "CONFIGURATION_ERROR" "Preset selection/persistence failed"
    cleanup_vpn || true
    continue
  fi

  ul_limits="$(read_limits)"
  echo "Upload prefs: $ul_limits" | tee -a "$RUN_DIR/sweep-console.log"

  echo "UI selection verified for $preset." | tee -a "$RUN_DIR/sweep-console.log"

  if ! tap_text "✅ تحقق من السرعة"; then
    echo "FAIL $preset: Verify button not found."
    FAIL_COUNT=$((FAIL_COUNT+1))
    record_failed_pair "$index" "$preset" "$expected" "CONFIGURATION_ERROR" "Verify button not found"
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
    record_failed_pair "$index" "$preset" "$expected" "CONFIGURATION_ERROR" "VPN approval failed"
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
    record_failed_pair "$index" "$preset" "$expected" "CONFIGURATION_ERROR" "Verify restart after VPN permission failed"
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
    if python3 scripts/e2e/metric_result_status.py "$result_xml" "$expected" >/dev/null 2>&1
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
    record_failed_pair "$index" "$preset" "$expected" "MEASUREMENT_ERROR" "Completed metric row missing or timeout"
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
    record_failed_pair "$index" "$preset" "$expected" "MEASUREMENT_ERROR" "Tagged Verify Speed fields not fully captured"
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
        FAIL) FAIL_COUNT=$((FAIL_COUNT+1)); METRIC_FAIL_COUNT=$((METRIC_FAIL_COUNT+1));;
        *) echo "FAIL $preset: invalid classification '$r_classification'."; FAIL_COUNT=$((FAIL_COUNT+1)); METRIC_FAIL_COUNT=$((METRIC_FAIL_COUNT+1));;
      esac
    done < <(python3 - "$RUN_DIR/$slug-parsed.csv" <<'PY'
import csv, sys
with open(sys.argv[1], newline="", encoding="utf-8") as handle:
    for row in csv.reader(handle):
        if not row:
            continue
        if len(row) != 24:
            raise SystemExit(f"Malformed parser CSV row: expected 24 columns, got {len(row)}")
        print(row[10].strip())
PY
    )
  else
    echo "FAIL $preset: parser produced no rows."
    FAIL_COUNT=$((FAIL_COUNT+1))
  fi

  if ! cleanup_vpn; then
    echo "FAIL $preset: VPN TUN remained after cleanup."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "CLEANUP_FAILURE|$index|$preset|VPN remained active after cleanup" | tee -a "$RUN_DIR/sweep-console.log"
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
export SPEEDVPN_SWEEP_HARD_FAILURE_COUNT="$FAIL_COUNT"
if [ -n "${GITHUB_ENV:-}" ]; then
  printf 'SPEEDVPN_SWEEP_HARD_FAILURE_COUNT=%s\n' "$FAIL_COUNT" >> "$GITHUB_ENV"
fi
if ! python3 scripts/e2e/verify_metrics.py validate-sweep "$RUN_DIR/speed-limit-sweep-results.csv"; then
  echo "STRICT GATE FAIL: independent CSV validation did not reach PASS=38/38."
  FAIL_COUNT=$((FAIL_COUNT+1))
fi

SUMMARY_RECORDED_AT_UTC="$(recorded_at_utc)"
cat > "$RUN_DIR/speed-limit-sweep-summary.md" <<MD
# SpeedVPN Speed-Limit Sweep

- GitHub run ID: $CSV_RUN_ID
- Summary generated at (UTC): $SUMMARY_RECORDED_AT_UTC
- Presets attempted: $TESTED_PRESET_COUNT/$TOTAL
- Download/Upload metric results recorded: $METRIC_RESULT_COUNT/$EXPECTED_METRIC_RESULTS
- Three-sample baseline preflight: $BASELINE_PREFLIGHT_STATUS (Download median=$BASELINE_PREFLIGHT_DOWNLOAD; Upload median=$BASELINE_PREFLIGHT_UPLOAD; minimums=96/106 Mbps; max spread=15%)
- Preflight detail: $BASELINE_PREFLIGHT_REASON
- Metric PASS: $PASS_COUNT
- Metric FAIL (validly collected but outside criteria, or invalid verification result): $METRIC_FAIL_COUNT
- Metric ENV_LIMITED (insufficient physical-baseline headroom): $ENV_LIMITED_COUNT
- Additional hard-fail events outside metric rows, including coverage/strict-gate events: $((FAIL_COUNT-METRIC_FAIL_COUNT))
- HARD FAIL event count (not a unique metric count): $FAIL_COUNT

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
  echo "- Metric PASS: $PASS_COUNT"
  echo "- Metric FAIL: $METRIC_FAIL_COUNT"
  echo "- Metric ENV_LIMITED: $ENV_LIMITED_COUNT"
  echo "- Additional hard-fail events beyond metric rows: $((FAIL_COUNT-METRIC_FAIL_COUNT))"
  echo "- HARD FAIL event count (not unique metric count): $FAIL_COUNT"
  echo
  echo "| # | Preset | Direction | Plan | VPN | Verdict | Classification |"
  echo "|---:|---|---|---|---|---|---|"
  python3 - "speed-limit-sweep-results.csv" <<'PY'
import csv, sys
with open(sys.argv[1], newline="", encoding="utf-8-sig") as handle:
    for row in csv.DictReader(handle):
        if not row:
            continue
        fields = (row["index"], row["preset"], row["direction"], row["plan_raw"], row["vpn_raw"], row["verdict"], row["classification"])
        print("| " + " | ".join(fields) + " |")
PY
} >> "$GITHUB_STEP_SUMMARY"

if grep -Eiq "FATAL EXCEPTION|AndroidRuntime.*FATAL" "$RUN_DIR/"*-logcat.txt 2>/dev/null; then
  echo "FATAL EXCEPTION detected in sweep logcats."
  FAIL_COUNT=$((FAIL_COUNT+1))
fi

echo "===== SPEED LIMIT SWEEP COMPLETE ====="
echo "PRESETS_TESTED=$TESTED_PRESET_COUNT/$TOTAL"
echo "METRIC_RESULTS=$METRIC_RESULT_COUNT/$EXPECTED_METRIC_RESULTS"
echo "PASS=$PASS_COUNT"
echo "METRIC_FAIL=$METRIC_FAIL_COUNT"
echo "ENV_LIMITED=$ENV_LIMITED_COUNT"
echo "ADDITIONAL_HARD_FAIL_EVENTS=$((FAIL_COUNT-METRIC_FAIL_COUNT))"
echo "HARD_FAIL=$FAIL_COUNT"

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
