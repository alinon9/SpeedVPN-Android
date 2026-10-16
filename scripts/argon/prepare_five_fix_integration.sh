#!/usr/bin/env bash
set -euo pipefail

# Validate the five requested fix branches in deterministic order before integration.
# This script deliberately does not run blind git merge: PR #36-#40 overlap in
# SpeedVpnService, SpeedLimiter, Socks5Server and MainActivity and require review.
MANIFEST="${1:-FIX_BRANCH_MANIFEST.json}"
BASE_REF="${BASE_REF:-fix/integrated-all-stages}"
TARGET_BRANCH="${TARGET_BRANCH:-fix/integrated-all-stages-hardening-20261016}"

command -v git >/dev/null || { echo "ERROR: git is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required" >&2; exit 2; }
test -f "$MANIFEST" || { echo "ERROR: manifest not found: $MANIFEST" >&2; exit 2; }

python3 - "$MANIFEST" <<'PY'
import json, sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
prs = data.get("source_pull_requests", [])
expected = [36, 37, 38, 39, 40]
actual = [item.get("number") for item in sorted(prs, key=lambda x: x.get("order", 0))]
if actual != expected:
    raise SystemExit(f"ERROR: expected PR order {expected}, got {actual}")
for item in sorted(prs, key=lambda x: x["order"]):
    if not item.get("branch") or not item.get("head_sha"):
        raise SystemExit(f"ERROR: incomplete manifest entry: {item}")
    print(f"MANIFEST_OK PR #{item['number']} order={item['order']} branch={item['branch']} sha={item['head_sha']}")
PY

git rev-parse --is-inside-work-tree >/dev/null
git show-ref --verify --quiet "refs/heads/$TARGET_BRANCH" || {
  echo "ERROR: target branch '$TARGET_BRANCH' is not checked out/available locally." >&2
  echo "Fetch source branches, inspect conflicts, and merge in PR order; do not auto-resolve overlapping VPN files." >&2
  exit 3
}

echo "Manifest validated. Review PR #36-#40 and their overlap before creating merge commits."
echo "Current target: $(git -C . rev-parse "$TARGET_BRANCH")"
