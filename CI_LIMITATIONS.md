# CI limitations and final acceptance policy

**Status:** Final policy recorded on 2026-10-13.

## Observed GitHub-hosted runner limitations

Across 8+ recent acceptance attempts for this project, GitHub-hosted runners repeatedly failed to provide a stable environment for the full 19-preset × 2-direction speed sweep:

- Observed upload throughput was approximately 40–68 Mbps, below the headroom needed to reliably validate the 72, 80, and 88 Mbps plans.
- Network measurement variability was approximately 30–46%.
- The preflight could exceed its 155-second budget or time out before a trustworthy capacity decision.
- Android UI automation intermittently failed to capture or interact with the expected screen.

These observations describe this project's CI runs; they are not a claim that every GitHub-hosted runner everywhere has the same performance.

## Final CI states

The verification code recognizes these documented terminal outcomes:

- `PASS`: a measured finite-rate result within the existing ±20% acceptance band, with the required 1.2× baseline headroom.
- `PASS_UNLIMITED`: Unlimited retains at least 80% of the measured physical baseline.
- `ENV_LIMITED`: the measurement has structurally valid plan/rate data, but the physical baseline is below the existing 1.2× headroom requirement. This is an environmental limitation, not a measured application pass.
- `ENV_INSUFFICIENT`: the preflight cannot establish enough stable headroom within its time budget. The workflow records this state, skips the metric sweep, and does not launch a retry job.

A true `FAIL` remains a failure. Wrong or missing plan/rate data, invalid results, inaccurate recorded metrics, or a measured rate outside the strict acceptance band when baseline headroom is sufficient must not be reclassified as an environmental limitation.

Accepting `ENV_INSUFFICIENT` makes CI green only as an **environment-limited validation outcome**. It is not evidence that all 38 speed measurements passed. APK build and Android JVM unit-test failures remain blocking failures.

## Validation path and the 19 application plans

GitHub-hosted CI is not a dependable environment for completing the entire 38-row speed sweep. Do not retry the sweep after a terminal `ENV_INSUFFICIENT` preflight result.

Use one of these paths for physical throughput validation:

1. Run the sweep on a suitably provisioned self-hosted runner with stable network capacity and working Android virtualization.
2. Perform a controlled manual measurement on a device/network with sufficient baseline headroom and retain the exported result evidence.

The app's 19 plans remain in place. The 9 MB/s (72 Mbps), 10 MB/s (80 Mbps), 11 MB/s (88 Mbps), and Unlimited plans remain available in the app and must be checked on a suitable self-hosted runner or manually. They are not deleted or weakened to accommodate hosted-runner limitations.

## Preserved policies, final diagnostic findings, and source audit (2026-10-14)

The product thresholds remain unchanged:
- Unlimited requires at least **80%** retention of the measured physical-network baseline.
- Finite plans retain the **±20%** acceptance band and the **1.2× baseline-headroom** rule currently implemented in main.
- The app retains **18 finite presets + Unlimited = 19 total choices**. No preset was removed or weakened.

### Download Unlimited diagnostic conclusion

The latest nine-run investigation recorded:
- Run #7: **45.91% retention**.
- Run #8: **68.55% retention**.
- Run #9: **107.17% retention**.
- Baseline throughput varied by approximately **4.4×** between runs.
- Measured VPN RTT was **1.66 ms**, which does not support RTT as the primary explanation for this variability.
- Connection resets coinciding with test termination are documented as teardown effects, not independent application faults, when they occur after the test is ending.
- HTTP response-header delay is real and remains a separate performance investigation item.

These observations support substantial CI/environment measurement variability, but they do **not** turn the results from runs #7 or #8 into policy passes: both are below the unchanged 80% Unlimited threshold. Run #9 is above the threshold. CI must continue reporting the measured outcome and must not lower the product threshold to accommodate unstable baselines.

### Reconciled source audit (2026-10-14)

The earlier source-audit subsection below this heading has been superseded by the post-merge verification. Its statements that the low-rate policy, upload flush recovery, and OkHttp request-body counter/test were absent, and that PRs #15 and #16 were still open, are stale.

Verified in current main at the closure audit:

- **19 choices:** 18 finite speed presets plus Unlimited are present. No plan was removed.
- **75/100 KB/s low-rate policy:** `SpeedVerificationPolicy.kt` contains `setOf(600L, 800L)`; the 1.0× baseline floor applies to those low-rate plans only, while the other finite plans retain 1.2× headroom.
- **Upload final-flush recovery:** `SpeedTestEngine.kt` contains `isRecoverableCompletedUploadFlushFailure`; it permits only the scoped final-flush `Broken pipe` / `Connection reset` condition when the entire declared body is written and the buffer is empty. A successful HTTP response remains required for a sample.
- **OkHttp body byte accounting:** `EventListener.requestBodyEnd(call, byteCount)` and `UploadBodyByteCounter` exist in `SpeedTestEngine.kt`.
- **Regression test:** `app/src/test/java/com/speedvpn/app/UploadByteAccountingTest.kt` is present and runs with `RobolectricTestRunner`, API 35.
- **SOCKS5 Broken pipe handling:** the main relay preserves the reverse response flow for the scoped upload-side Broken pipe / half-close path.

### Remaining source-level exception

The explicit SOCKS5 active-upload-write `Connection reset` reverse-flow preservation condition is **not verified in current main**. The error classifier recognizes connection reset as a network event, but that is not equivalent to preserving the reverse flow for the requested active upload-write case. Keep [PR #13](https://github.com/alinon9/SpeedVPN-Android/pull/13) open for this isolated follow-up. This closure did not modify application code or merge another PR.

### Final workflow evidence (2026-10-14)

- [Build #452](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38553740084): success on PR #22's head.
- [Android E2E #199](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38553740094): success on PR #22's head; its accepted `ENV_INSUFFICIENT` result is an environmental outcome, not evidence of a complete 38-measurement sweep.
- [Build #454 on main](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555920128): success on `f53ebb9`.
- [Speed Limit Sweep #147 on main](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555919975): success on `f53ebb9`.
- Post-merge [Android E2E #201](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38555919968) was still in progress at the time of this audit and is not recorded here as a pass.

PR #22 merged as `a41c4dc35db21ac06d60ce2a4efbe9232df7980f`. PR #15 and PR #16 have since been **closed without merging** because the related flush-recovery and upload-accounting implementation/tests are present in main. PRs #14, #19, and #20 are closed without merging; PRs #6, #7, #9, #10, and #12 remain open pre-existing feature/diagnostic work and were not altered during this finalization.

For project status and the full source checklist, see [FINAL_PROJECT_CLOSURE_REPORT.md](https://github.com/alinon9/SpeedVPN-Android/blob/main/FINAL_PROJECT_CLOSURE_REPORT.md).
