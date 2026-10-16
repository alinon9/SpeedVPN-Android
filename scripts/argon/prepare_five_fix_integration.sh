#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
MANIFEST="${ROOT}/FIX_BRANCH_MANIFEST.json"
TARGET_BRANCH="fix/integrated-all-stages"

usage() {
  cat <<'USAGE'
Usage: scripts/argon/prepare_five_fix_integration.sh [--check-only] [--run-tests]

This repository already has a reconciled integration branch for PR #36–#40.
The script validates the manifest and integration-critical files. It deliberately
does not merge the original PR heads again: they were based on main and merging
them a second time would reintroduce known conflicts and duplicate history.

  --check-only  Validate branch, manifest, and required files (default)
  --run-tests   Also run Python E2E tests and required Gradle verification tasks
USAGE
}

RUN_TESTS=0
while (($#)); do
  case "$1" in
    --check-only) ;;
    --run-tests) RUN_TESTS=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

[[ -f "$MANIFEST" ]] || { echo "Missing $MANIFEST" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }

python3 - "$MANIFEST" "$TARGET_BRANCH" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
target = sys.argv[2]
data = json.loads(manifest_path.read_text(encoding="utf-8"))
if data.get("integration_branch") != target:
    raise SystemExit(f"manifest branch mismatch: {data.get('integration_branch')!r}")
prs = [item.get("pr") for item in data.get("integration_order", [])]
if prs != [36, 37, 38, 39, 40]:
    raise SystemExit(f"PR order must be [36, 37, 38, 39, 40], got {prs!r}")
for item in data["integration_order"]:
    if not item.get("head") or not item.get("head_sha"):
        raise SystemExit(f"PR #{item.get('pr')} lacks immutable source ref metadata")
print("MANIFEST_OK: PR #36 → #37 → #38 → #39 → #40")
PY

CURRENT_BRANCH="$(git branch --show-current)"
if [[ "$CURRENT_BRANCH" != "$TARGET_BRANCH" ]]; then
  echo "Wrong branch: expected $TARGET_BRANCH, got ${CURRENT_BRANCH:-DETACHED}" >&2
  exit 1
fi

required_files=(
  app/src/main/java/com/speedvpn/app/SpeedVpnService.kt
  app/src/main/java/com/speedvpn/app/QuotaEnforcementEngine.kt
  app/src/main/java/com/speedvpn/app/Socks5Server.kt
  app/src/main/java/com/speedvpn/app/UdpFlowTable.kt
  app/src/main/java/com/speedvpn/app/SpeedLimiter.kt
  app/src/main/java/com/speedvpn/app/SpeedTestEngine.kt
  app/src/main/java/com/speedvpn/app/SpeedVerificationEngine.kt
  app/src/main/java/com/speedvpn/app/MainActivity.kt
  scripts/e2e/verify_apk_native.py
  scripts/e2e/verify_metrics.py
  scripts/e2e/preflight_baseline.py
  .github/workflows/build-apk.yml
  .github/workflows/speedvpn-emulator-e2e-v2.yml
  .github/workflows/speedvpn-speed-limit-sweep.yml
)
for path in "${required_files[@]}"; do
  [[ -s "$path" ]] || { echo "Missing required integration file: $path" >&2; exit 1; }
done

python3 -m py_compile scripts/e2e/verify_apk_native.py scripts/e2e/verify_metrics.py scripts/e2e/preflight_baseline.py
bash -n setup.sh scripts/e2e/speed-limit-sweep.sh
echo "INTEGRATION_CHECK_OK: branch=$CURRENT_BRANCH; required files and Python/Bash syntax checked."

if (( RUN_TESTS )); then
  python3 -m unittest discover -s scripts/e2e/tests -v
  ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace --no-daemon
fi
