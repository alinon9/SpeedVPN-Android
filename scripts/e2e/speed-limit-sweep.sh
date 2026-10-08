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
    if node.attrib.get("text") not in labels:
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

tap_preset_row() {
  local wanted="$1"
  local mode="$2"
  local xml="$RUN_DIR/current-ui.xml"
  local group_title=""
  group_title="$(preset_group_title "$wanted" 2>/dev/null || true)"

  # Bring the requested speed group into the viewport before horizontal scrolling.
  if [ -n "$group_title" ]; then
    tap_text "$group_title" "$mode" || true
    sleep 1
  fi

  for attempt in $(seq 1 12); do
    dump_ui "$xml" || true

    local c=""
    c="$(coord_for_text "$xml" "$wanted" "$mode" 2>/dev/null || true)"
    if [ -n "$c" ]; then
      read -r x y <<< "$c"
      adb -s "$DEVICE" shell input tap "$x" "$y"
      sleep 1
      return 0
    fi

    local y=""
    y="$(preset_swipe_y "$xml" "$mode" "$group_title" 2>/dev/null || true)"
    if [ -n "$y" ]; then
      adb -s "$DEVICE" shell input swipe 300 "$y" 60 "$y" 750
    elif [ "$mode" = "bottom" ]; then
      adb -s "$DEVICE" shell input swipe 160 570 160 200 800
    else
      adb -s "$DEVICE" shell input swipe 160 210 160 580 800
    fi
    sleep 1
  done

  echo "UI_ERROR: cannot select '$wanted' in $mode row."
  return 1
}

read_limits() {
  adb -s "$DEVICE" shell run-as "$PACKAGE" cat shared_prefs/local_limits.xml 2>/dev/null || true
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
  local xml="$1"
  local preset="$2"
  local expected="$3"
  local index="$4"
  local out="$5"

  python3 - "$xml" "$preset" "$expected" "$index" "$out" <<'PY'
import csv, re, sys, xml.etree.ElementTree as ET

path,preset,expected_raw,index,out_path=sys.argv[1:]
expected=int(expected_raw)
texts=[n.attrib.get("text","").strip() for n in ET.parse(path).getroot().iter("node") if n.attrib.get("text","").strip()]

status_map={
 "✅ السرعة متطابقة":"PASS",
 "❌ السرعة غير متطابقة":"FAIL",
 "⚠️ لا يمكن الحكم":"ENV_LIMITED",
 "✅ لا يظهر سقف واضح":"PASS_UNLIMITED",
}
rate_re=re.compile(r"^([0-9]+(?:\.[0-9]+)?) ?(bps|Kbps|Mbps|Gbps)$")
pct_re=re.compile(r"^[0-9]+(?:\.[0-9]+)?%$")
mult={"bps":1,"Kbps":1000,"Mbps":1000000,"Gbps":1000000000}

def rate(v):
    m=rate_re.fullmatch(v)
    return None if not m else float(m.group(1))*mult[m.group(2)]

def section(metric,next_metric):
    s=texts.index(metric)
    e=texts.index(next_metric,s+1) if next_metric and next_metric in texts[s+1:] else len(texts)
    return texts[s:e]

def value_after(sec,label):
    i=sec.index(label)
    for v in sec[i+1:i+10]:
        if rate(v) is not None:
            return v
        if v in status_map:
            break
    raise RuntimeError(f"{label}: numeric value missing")

rows=[]
hard=False

for metric,next_metric in (("Download","Upload"),("Upload",None)):
    sec=section(metric,next_metric)
    status=next((s for s in status_map if s in sec),None)
    if not status:
        raise RuntimeError(f"{metric}: verdict missing")

    baseline=value_after(sec,"🌐 سرعة الإنترنت الأصلية")
    vpn=value_after(sec,"🚀 السرعة الفعلية داخل VPN")

    pi=sec.index("🔒 السرعة المحجوزة / المحددة")
    plan=None
    plan_kbps=None
    for v in sec[pi+1:pi+6]:
        if v=="بدون حد":
            plan=v
            break
        if rate(v) is not None:
            plan=v
            plan_kbps=rate(v)/1000.0
            break
    if plan is None:
        raise RuntimeError(f"{metric}: plan missing")

    reason=""
    si=sec.index(status)
    for v in sec[si+1:si+8]:
        if v in status_map or v in {
            metric,"🌐 سرعة الإنترنت الأصلية","🔒 السرعة المحجوزة / المحددة",
            "🚀 السرعة الفعلية داخل VPN","الدقة مقارنة بالخيار"
        }:
            continue
        if rate(v) is not None or pct_re.fullmatch(v):
            continue
        reason=v
        break

    classification=status_map[status]

    if expected==0:
        if plan!="بدون حد" or classification!="PASS_UNLIMITED":
            classification="FAIL"
            reason=reason or "Unlimited preset did not verify as unlimited."
    else:
        if plan_kbps is None or abs(plan_kbps-expected)>max(0.5,expected*0.002):
            classification="FAIL"
            reason=reason or f"Expected {expected} Kbps but UI reported {plan}."
        elif classification not in {"PASS","ENV_LIMITED"}:
            classification="FAIL"

    if classification=="FAIL":
        hard=True

    rows.append([index,preset,expected,metric,baseline,plan,vpn,status,reason,classification])

with open(out_path,"w",newline="",encoding="utf-8") as f:
    csv.writer(f).writerows(rows)

for row in rows:
    print("RESULT|"+"|".join(row))
print("OVERALL|"+("FAIL" if hard else "PASS"))
sys.exit(2 if hard else 0)
PY
}

FAIL_COUNT=0
ENV_LIMITED_COUNT=0
PASS_COUNT=0

echo "===== SPEED LIMIT SWEEP START ====="
echo "19 finite presets + Unlimited."
echo "Real UI selection for Download and Upload, then Verify Speed, record, stop VPN, next preset."
echo

while IFS='|' read -r index preset expected; do
  [ -n "$index" ] || continue
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

  if ! tap_preset_row "$preset" top; then
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Download,,,,,Preset selection failed,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  dl_limits="$(read_limits)"
  echo "Download prefs: $dl_limits" | tee -a "$RUN_DIR/sweep-console.log"
  if [ "$expected" -eq 0 ]; then
    ok_dl="$(grep -c 'name="dl" value="0"' <<<"$dl_limits" || true)"
  else
    ok_dl="$(grep -c 'name="dl" value="'"$expected"'"' <<<"$dl_limits" || true)"
  fi
  if [ "$ok_dl" -ne 1 ]; then
    echo "FAIL $preset: Download preference mismatch."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Download,,,,,Download persistence failed,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  adb -s "$DEVICE" shell input swipe 160 570 160 190 850
  sleep 2

  if ! tap_preset_row "$preset" bottom; then
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Upload,,,,,Preset selection failed,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  ul_limits="$(read_limits)"
  echo "Upload prefs: $ul_limits" | tee -a "$RUN_DIR/sweep-console.log"
  if [ "$expected" -eq 0 ]; then
    ok_ul="$(grep -c 'name="ul" value="0"' <<<"$ul_limits" || true)"
    ok_both="$(grep -c 'name="dl" value="0"' <<<"$ul_limits" || true)"
  else
    ok_ul="$(grep -c 'name="ul" value="'"$expected"'"' <<<"$ul_limits" || true)"
    ok_both="$(grep -c 'name="dl" value="'"$expected"'"' <<<"$ul_limits" || true)"
  fi
  if [ "$ok_ul" -ne 1 ] || [ "$ok_both" -ne 1 ]; then
    echo "FAIL $preset: persisted Upload/Download values mismatch."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Upload,,,,,Upload persistence failed,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

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
    sleep 3
  fi

  loading=0
  for second in $(seq 1 30); do
    dump_ui "$RUN_DIR/$slug-loading.xml" || true
    if grep -q "جاري التحقق من الخطة…" "$RUN_DIR/$slug-loading.xml" 2>/dev/null; then
      loading=1
      break
    fi
    sleep 1
  done

  if [ "$loading" -ne 1 ]; then
    echo "FAIL $preset: Verify loading state missing."
    FAIL_COUNT=$((FAIL_COUNT+1))
    adb -s "$DEVICE" logcat -d -t 2500 > "$RUN_DIR/$slug-logcat.txt" || true
    echo "$index,$preset,$expected,Verify,,,,,Loading state missing,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  result_xml="$RUN_DIR/$slug-verify.xml"
  finished=0
  for second in $(seq 1 300); do
    if dump_ui "$result_xml" 2>/dev/null &&
       grep -q "نتيجة التحقق" "$result_xml" 2>/dev/null &&
       ! grep -q "جاري التحقق من الخطة…" "$result_xml" 2>/dev/null; then
      finished=1
      echo "Verify completed for $preset after $second seconds." | tee -a "$RUN_DIR/sweep-console.log"
      break
    fi
    sleep 1
  done

  adb -s "$DEVICE" logcat -d -t 3500 > "$RUN_DIR/$slug-logcat.txt" || true

  if [ "$finished" -ne 1 ]; then
    echo "FAIL $preset: Verify timeout."
    FAIL_COUNT=$((FAIL_COUNT+1))
    echo "$index,$preset,$expected,Verify,,,,,Verification timeout,FAIL" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    cleanup_vpn || true
    continue
  fi

  for reveal in 0 1 2 3 4 5 6; do
    if grep -q "🚀 السرعة الفعلية داخل VPN" "$result_xml" 2>/dev/null; then
      break
    fi
    adb -s "$DEVICE" shell input swipe 160 500 160 220 650
    sleep 1
    dump_ui "$result_xml" || true
  done

  set +e
  parse_verify "$result_xml" "$preset" "$expected" "$index" "$RUN_DIR/$slug-parsed.csv" | tee -a "$RUN_DIR/sweep-console.log"
  set -e

  if [ -s "$RUN_DIR/$slug-parsed.csv" ]; then
    cat "$RUN_DIR/$slug-parsed.csv" >> "$RUN_DIR/speed-limit-sweep-results.csv"
    while IFS=',' read -r r_index r_preset r_expected r_direction r_baseline r_plan r_vpn r_verdict r_reason r_classification; do
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
