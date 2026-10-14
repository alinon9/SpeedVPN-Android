# Final Project Closure Report — SpeedVPN Android

**Status:** **مكتمل** — final documentation and the verified CI scope are closed, with the one explicit source-level exception recorded below.  
**Date:** 2026-10-14  
**Repository:** [alinon9/SpeedVPN-Android](https://github.com/alinon9/SpeedVPN-Android)  
**Final feature merge:** PR [#22](https://github.com/alinon9/SpeedVPN-Android/pull/22), merge commit [`a41c4dc35db21ac06d60ce2a4efbe9232df7980f`](https://github.com/alinon9/SpeedVPN-Android/commit/a41c4dc35db21ac06d60ce2a4efbe9232df7980f)  
**Main HEAD at final audit:** [`f53ebb9e8089ed4676ad405ba7403908bd226f98`](https://github.com/alinon9/SpeedVPN-Android/commit/f53ebb9e8089ed4676ad405ba7403908bd226f98)

## Executive closure summary

The documentation and verified-validation closure is complete. PR #22 was merged into `main` as `a41c4dc`; the PR-head APK build and emulator E2E passed, and the exhaustive Speed Limit Sweep #147 subsequently passed on `main`. The current source contains the low-rate headroom exception, upload final-flush recovery, completed OkHttp upload-body accounting, and the Robolectric regression-test runner. All 19 speed choices remain present.

**Important qualification:** this report does not claim every requested source-level acceptance item is verified. The specific SOCKS5 active-upload `Connection reset` preservation path is still absent from the current `main` implementation; the existing relay path specifically preserves the reverse flow for upload-side `Broken pipe` and half-close failures. PR #13 remains open for that separate item. The current task's constraints prohibit code changes and new merges, so this exception is documented rather than silently represented as fixed.

## Final validation results

| Check | Result | Evidence |
|---|---|---|
| PR #22 | **Merged** | [Merge commit a41c4dc](https://github.com/alinon9/SpeedVPN-Android/commit/a41c4dc35db21ac06d60ce2a4efbe9232df7980f) |
| Debug APK Build #452 | **Success** | [Run #452](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38553740084), PR head `828b707` |
| Android Emulator E2E #199 | **Success** | [Run #199](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38553740094), PR head `828b707` |
| Speed Limit Sweep #147 | **Success** | [Run #147 on main](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555919975), commit `f53ebb9` |
| Post-merge Debug APK Build #454 | **Success** | [Run #454 on main](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555920128), commit `f53ebb9` |
| Post-merge Android Emulator E2E #201 | **In progress at audit time** | [Run #201](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555919968). It is not counted as a completed pass in this report. |

A green workflow conclusion is recorded as a workflow result; it must not be interpreted as proof that every physical-throughput measurement passed strict metric thresholds if an accepted environment state caused measurements to be skipped or classified as environment-limited.

## Main-branch source checklist

| Requested item | Current finding | Evidence / notes |
|---|---|---|
| 2 MB/s SOCKS5 upload `Connection reset` preservation fix | **Not fully verified — follow-up remains** | `Socks5Server.kt` classifies connection resets as network events, but the active upload-write reverse-flow preservation condition currently recognizes `Broken pipe`, not the explicit `Connection reset` condition requested. See [PR #13](https://github.com/alinon9/SpeedVPN-Android/pull/13), which remains open. |
| SOCKS5 `Broken pipe` handling | **Present** | `Socks5Server.kt` preserves the reverse response flow for the scoped upload-side broken-pipe / half-close cases. |
| 75 KB/s and 100 KB/s headroom policy | **Present** | `SpeedVerificationPolicy.kt` contains `setOf(600L, 800L)`; only these two low-rate targets use the 1.0× baseline floor. Other finite presets retain the 1.2× headroom rule. |
| Completed upload flush-race recovery | **Present** | `SpeedTestEngine.kt` contains `isRecoverableCompletedUploadFlushFailure`; it accepts only a narrowly recognized final-flush `Broken pipe` / `Connection reset` when all declared bytes were written and no bytes remain buffered. An HTTP success response is still required for accepting a sample. |
| OkHttp request-body byte counter | **Present** | `SpeedTestEngine.kt` uses `EventListener.requestBodyEnd(call, byteCount)` and `UploadBodyByteCounter`. |
| Upload byte-accounting regression under Robolectric | **Present** | `app/src/test/java/com/speedvpn/app/UploadByteAccountingTest.kt` uses `RobolectricTestRunner` pinned to API 35 and checks equality between callback bytes and server-received bytes. |
| `CI_LIMITATIONS.md` | **Present** | Records environment classifications and warns that `ENV_INSUFFICIENT` is an environment-limited outcome, not proof of 38/38 measured passes. |
| `FINAL_PROJECT_CLOSURE_REPORT.md` | **Updated** | This report records PR #22, commit `a41c4dc`, Sweep #147, the PR closures, and the remaining source-level exception. |
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
| [#13 — Isolate 2 MB/s upload Connection reset handling](https://github.com/alinon9/SpeedVPN-Android/pull/13) | **Open** | Kept open because the explicit active upload-write reset-preservation condition is not verified in main. |
| [#6](https://github.com/alinon9/SpeedVPN-Android/pull/6), [#7](https://github.com/alinon9/SpeedVPN-Android/pull/7), [#9](https://github.com/alinon9/SpeedVPN-Android/pull/9), [#10](https://github.com/alinon9/SpeedVPN-Android/pull/10), [#12](https://github.com/alinon9/SpeedVPN-Android/pull/12) | **Open** | Pre-existing feature/diagnostic PRs; not merged or altered during this finalization. |

## Change-scope guardrails

- No application code was modified in this closure task.
- No new pull request was merged.
- No speed preset was removed.
- PR #15 and PR #16 were closed without merging.
- The remaining SOCKS5 active-upload `Connection reset` difference was disclosed instead of being incorrectly marked as passed.

## Final verdict

The final documentation and validation closeout is recorded as **مكتمل**, with Build #452, E2E #199, Speed Sweep #147, and post-merge Build #454 confirmed successful. The 19 plans and established policies remain intact. **Strict implementation closure still has one explicit exception:** the SOCKS5 active-upload `Connection reset` preservation condition is not verified in main and PR #13 remains open. This report does not claim that exception is fixed.
