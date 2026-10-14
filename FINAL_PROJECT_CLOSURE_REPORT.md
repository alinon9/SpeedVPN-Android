# Final Project Closure Report — SpeedVPN Android

**Status:** **مغلق رسميًا 100%** — final documentation and required acceptance checks are closed. The additional main-branch E2E #203 was still running at the closeout audit and is tracked separately, not counted as a pass.  
**Date:** 2026-10-14  
**Repository:** [alinon9/SpeedVPN-Android](https://github.com/alinon9/SpeedVPN-Android)  
**Final feature merge:** PR [#22](https://github.com/alinon9/SpeedVPN-Android/pull/22), merge commit [`a41c4dc35db21ac06d60ce2a4efbe9232df7980f`](https://github.com/alinon9/SpeedVPN-Android/commit/a41c4dc35db21ac06d60ce2a4efbe9232df7980f)  
**Final main-branch validation target (E2E #203):** [`034e58898a7db2b6ebace0d14835da75d5e40de5`](https://github.com/alinon9/SpeedVPN-Android/commit/034e58898a7db2b6ebace0d14835da75d5e40de5)

## Executive closure summary

The documentation and verified-validation closure is complete. PR #22 was merged into `main` as `a41c4dc`; the PR-head APK build and emulator E2E passed, and the exhaustive Speed Limit Sweep #147 subsequently passed on `main`. The current source contains the low-rate headroom exception, upload final-flush recovery, completed OkHttp upload-body accounting, and the Robolectric regression-test runner. All 19 speed choices remain present.

The remaining SOCKS5 active-upload `Connection reset` path was a defensive hypothesis, not an observed failure in the final acceptance runs. Final evidence for Build #454, E2E #199, Speed Limit Sweep #147, and the 2 MB/s plan (4/4 PASS) shows that existing upload-side `Broken pipe` handling was sufficient for the reproduced issue. PR #13 was therefore closed as **superseded, not merged**. This records the evidence and closure decision; it does not claim connection resets are impossible in every network environment. No application-code change or new merge is part of this closeout.

## Final validation results

| Check | Result | Evidence |
|---|---|---|
| PR #22 | **Merged** | [Merge commit a41c4dc](https://github.com/alinon9/SpeedVPN-Android/commit/a41c4dc35db21ac06d60ce2a4efbe9232df7980f) |
| Debug APK Build #452 | **Success** | [Run #452](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38553740084), PR head `828b707` |
| Android Emulator E2E #199 | **Success** | [Run #199](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38553740094), PR head `828b707` |
| Speed Limit Sweep #147 | **Success** | [Run #147 on main](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555919975), commit `f53ebb9` |
| Post-merge Debug APK Build #454 | **Success** | [Run #454 on main](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555920128), commit `f53ebb9` |
| Post-merge Android Emulator E2E #201 | **Cancelled** | [Run #201](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555919968). It is not counted as a pass. |

| Android Emulator E2E #203 | **In progress at closeout audit** | [Run #203 on `main`](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38558177096), head `034e588`. Not counted as a pass while pending. |
| 2 MB/s upload acceptance | **4/4 PASS** | Final verification result supplied for this closeout. |

A green workflow conclusion is recorded as a workflow result; it must not be interpreted as proof that every physical-throughput measurement passed strict metric thresholds if an accepted environment state caused measurements to be skipped or classified as environment-limited.

## Main-branch source checklist

| Requested item | Current finding | Evidence / notes |
|---|---|---|
| SOCKS5 upload `Connection reset` hypothesis | **Closed as superseded / not observed** | The final acceptance runs did not reproduce this condition; upload-side `Broken pipe` handling was sufficient for the observed issue. [PR #13](https://github.com/alinon9/SpeedVPN-Android/pull/13) was closed without merging. This is not a claim that resets can never occur. |
| SOCKS5 `Broken pipe` handling | **Present** | `Socks5Server.kt` preserves the reverse response flow for the scoped upload-side broken-pipe / half-close cases. |
| 75 KB/s and 100 KB/s headroom policy | **Present** | `SpeedVerificationPolicy.kt` contains `setOf(600L, 800L)`; only these two low-rate targets use the 1.0× baseline floor. Other finite presets retain the 1.2× headroom rule. |
| Completed upload flush-race recovery | **Present** | `SpeedTestEngine.kt` contains `isRecoverableCompletedUploadFlushFailure`; it accepts only a narrowly recognized final-flush `Broken pipe` / `Connection reset` when all declared bytes were written and no bytes remain buffered. An HTTP success response is still required for accepting a sample. |
| OkHttp request-body byte counter | **Present** | `SpeedTestEngine.kt` uses `EventListener.requestBodyEnd(call, byteCount)` and `UploadBodyByteCounter`. |
| Upload byte-accounting regression under Robolectric | **Present** | `app/src/test/java/com/speedvpn/app/UploadByteAccountingTest.kt` uses `RobolectricTestRunner` pinned to API 35 and checks equality between callback bytes and server-received bytes. |
| `CI_LIMITATIONS.md` | **Present** | Records environment classifications and warns that `ENV_INSUFFICIENT` is an environment-limited outcome, not proof of 38/38 measured passes. |
| `FINAL_PROJECT_CLOSURE_REPORT.md` | **Updated** | This closeout records PR #22, PR #13's superseded closure, Sweep #147, E2E #203's pending status at audit time, and the evidence scope. |
| 19 speed choices | **Present** | MainActivity contains 18 finite presets plus Unlimited; no preset was deleted. |

## Documented CI and measurement limitations

- **Download Unlimited retention is environment-sensitive:** the diagnostic runs recorded **45.91%**, **68.55%**, and **107.17%** retention. The first two are below the unchanged **80%** minimum and are not converted into passes; the third is above it.
- **Baseline throughput varied by approximately 4.4×** between runs, so the physical network/runner did not provide a stable basis for comparing every result.
- **Measured VPN RTT was 1.66 ms** and does not support RTT as the primary cause of the observed throughput variation.
- **`ENV_INSUFFICIENT` is accepted as an environment-limited terminal state**, not as a measured speed pass. The policy thresholds have not been lowered to make CI green.
- **Policies remain intact:** finite plans retain the ±20% acceptance band and the 1.2× headroom requirement except for the documented 600/800 Kbps low-rate exception; Unlimited retains the 80% floor.
- A passing workflow outcome does not eliminate the need for a stable, sufficiently provisioned runner or controlled device/network validation where CI reports an environment-limited state.

## Pull-request audit

| PR | Final state | Closure decision |
|---|---|---|
| [#22 — Robolectric upload-byte accounting test](https://github.com/alinon9/SpeedVPN-Android/pull/22) | **Merged** | Merge commit `a41c4dc`. |
| [#21 — Restore fixes #14/#15/#16](https://github.com/alinon9/SpeedVPN-Android/pull/21) | **Merged** | Main contains the low-rate policy, flush-recovery, and upload byte-accounting implementations verified above. |
| [#18 — Verified speed-limit fixes and CI pipeline](https://github.com/alinon9/SpeedVPN-Android/pull/18) | **Merged** | Historical merge; not treated as evidence on its own for every current source criterion. |
| [#17 — Preflight/E2E test fix](https://github.com/alinon9/SpeedVPN-Android/pull/17) | **Merged** | No further action. |
| [#15 — Completed upload flush race](https://github.com/alinon9/SpeedVPN-Android/pull/15) | **Closed, not merged** | Closed on 2026-10-14 after confirming the scoped flush-recovery logic is present on main; closure rationale recorded in a PR comment. |
| [#16 — OkHttp upload-body accounting](https://github.com/alinon9/SpeedVPN-Android/pull/16) | **Closed, not merged** | Closed on 2026-10-14 after confirming the counter, test, and Robolectric runner are present on main; closure rationale recorded in a PR comment. |
| [#14 — 75/100 KB/s policy validation](https://github.com/alinon9/SpeedVPN-Android/pull/14) | **Closed, not merged** | Its required low-rate policy is present on main via later changes. |
| [#19 — Run #179 diagnosis](https://github.com/alinon9/SpeedVPN-Android/pull/19) | **Closed, not merged** | Diagnostic findings retained in the limitations record. |
| [#20 — Unlimited upload diagnostic trigger](https://github.com/alinon9/SpeedVPN-Android/pull/20) | **Closed, not merged** | Validation-only PR; no merge. |
| [#13 — Isolate 2 MB/s upload Connection reset handling](https://github.com/alinon9/SpeedVPN-Android/pull/13) | **Closed, not merged** | Closed as superseded: the `Connection reset` hypothesis did not appear in final acceptance runs; existing `Broken pipe` handling passed the observed scenario. |
| [#6](https://github.com/alinon9/SpeedVPN-Android/pull/6), [#7](https://github.com/alinon9/SpeedVPN-Android/pull/7), [#9](https://github.com/alinon9/SpeedVPN-Android/pull/9), [#10](https://github.com/alinon9/SpeedVPN-Android/pull/10), [#12](https://github.com/alinon9/SpeedVPN-Android/pull/12) | **Open** | Pre-existing feature/diagnostic PRs; not merged or altered during this finalization. |

## Change-scope guardrails

- No application code was modified in this closure task.
- No new pull request was merged.
- No speed preset was removed.
- PR #13, PR #15, and PR #16 were closed without merging.
- `Connection reset` was closed as an unobserved defensive hypothesis, not represented as a reproduced defect or as universally impossible.
- The existing `Broken pipe` handling remains the verified fix for the observed final acceptance scenario.

## Final verdict

The project is **officially closed — 100% closeout complete** for the agreed acceptance scope. Build #452, E2E #199, Speed Sweep #147, and post-merge Build #454 are confirmed successful; the 2 MB/s upload acceptance passed 4/4. The 19 plans and established policies remain intact. PR #13 is closed as superseded and not merged because `Connection reset` did not appear in final acceptance testing, while the existing `Broken pipe` handling covered the observed failure. E2E #203 was still running on `main` at the closeout audit and is explicitly not counted as a pass; its pending status does not change the completed acceptance evidence above.
