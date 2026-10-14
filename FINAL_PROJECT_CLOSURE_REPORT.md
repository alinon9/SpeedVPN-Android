# Final Project Closure Report — SpeedVPN Android

**Date:** 2026-10-14  
**Repository:** [alinon9/SpeedVPN-Android](https://github.com/alinon9/SpeedVPN-Android)  
**Source audit revision before documentation-only commits:** `acc9508283c627781b27244d68b24faa2cdd0521`

## Executive status

The **Download Unlimited diagnostic investigation is documented and diagnostic PRs #19 and #20 are closed without merging**. The project cannot honestly be marked as fully implementation-closed yet: the audit found several differences between the implementation claimed in the PR #18 description and the source currently on main. These gaps are left visible rather than being hidden by closing the associated fixes.

## Findings documented

- Download Unlimited retention varied across the diagnostic runs: Run #7 **45.91%**, Run #8 **68.55%**, and Run #9 **107.17%**.
- Baseline throughput varied approximately **4.4×**, so the runner/network conditions were not stable enough to treat the readings as directly comparable.
- VPN RTT of **1.66 ms** does not appear to be the primary throughput constraint in these runs.
- Connection resets occurring as tests terminate are documented as teardown effects when their timing supports that interpretation.
- HTTP response-header delay is retained as a separate performance follow-up.
- The Unlimited **80% minimum retention policy**, finite-rate **±20% tolerance**, and current **1.2× baseline headroom rule** were not reduced.
- The application still contains **19 choices: 18 finite presets and Unlimited**.

## Pull-request status

| PR | Final state | Decision |
|---|---|---|
| [#19 — Upload/Run #179 diagnosis](https://github.com/alinon9/SpeedVPN-Android/pull/19) | Closed, not merged | Closed as a diagnostic-only PR; findings recorded in CI_LIMITATIONS.md. |
| [#20 — Unlimited Upload diagnostic trigger](https://github.com/alinon9/SpeedVPN-Android/pull/20) | Closed, not merged | Closed as a validation-trigger PR; no application code or policy was merged. |
| [#17 — Preflight/E2E test fix](https://github.com/alinon9/SpeedVPN-Android/pull/17) | Merged | Already merged before this task; no action required. |
| [#15 — Completed upload flush race](https://github.com/alinon9/SpeedVPN-Android/pull/15) | Open | Left open because the final-flush recovery described there was not found in current main's SpeedTestEngine.kt. |
| [#16 — OkHttp upload-body accounting](https://github.com/alinon9/SpeedVPN-Android/pull/16) | Open | Left open because requestBodyEnd/UploadBodyByteCounter and UploadByteAccountingTest.kt were not found in current main. |
| [#14 — 75/100 KB/s policy validation](https://github.com/alinon9/SpeedVPN-Android/pull/14) | Open | Also left open: the current main policy still uses 1.2× headroom for every finite plan, without the described low-rate exception. |

No application code was changed, and no new PR was merged into main. Diagnostic branches were not deleted because some open fix PRs still contain work relevant to the unresolved source discrepancies.

## Main audit checklist

| Required item | Status | Evidence from current source |
|---|---|---|
| 19 speed choices | **Present** | 10 slow + 5 medium + 3 fast + Unlimited. |
| Preflight fields | **Present** | Both Download and Upload baseline preflight tags exist in scripts/e2e/verify_metrics.py. |
| SOCKS-level Broken pipe preservation | **Partially present** | Present in Socks5Server.kt. |
| 2 MB/s active-upload Connection reset handling | **Not verified** | Explicit Connection reset preservation branch described by PR #14 is absent. |
| Special 75/100 KB/s headroom policy | **Not present** | SpeedVerificationPolicy.kt still applies the 1.2× rule globally. |
| Completed-upload final-flush recovery | **Not present** | The specific recovery logic from PR #15 is absent from SpeedTestEngine.kt. |
| OkHttp request-body byte counter | **Not present** | No EventListener.requestBodyEnd/UploadBodyByteCounter found; UploadByteAccountingTest.kt is absent from main. |
| CI_LIMITATIONS.md final findings | **Documented** | Updated in this closure task. |

## CI health observed

On audited source revision `acc9508283c627781b27244d68b24faa2cdd0521`, the latest reported main-branch workflows were:
- [Build SpeedVPN APK — success](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38532544370)
- [SpeedVPN Full Android Emulator E2E v2 — success](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38532544417)

Those successful workflows do not prove the missing source-level implementations are present, nor do they prove all 38 physical-throughput measurements passed. A separate Measure Download Path run on an earlier commit failed.

## Recommended follow-up

1. Reconcile main against the source changes described in PRs #14, #15, and #16. Do not treat their work as merged solely because PR #18's description says it was included.
2. After an authorized code change, run unit tests, APK build, emulator E2E, and the full 19-preset × 2-direction sweep. Preserve all raw metrics and the existing policy thresholds.
3. Repeat Download Unlimited testing on a controlled real-device/network setup or a stable self-hosted runner. Confirm results against the unchanged 80% rule.
4. Measure and optimize HTTP response-header delay separately from the throughput/runner variability investigation.

**Closure verdict:** diagnostic closure and documentation are complete; full implementation closure remains **blocked** by the source discrepancies listed above. This report intentionally does not claim all requested main-branch criteria passed.
