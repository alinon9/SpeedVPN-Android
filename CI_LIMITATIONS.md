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

### Verified directly in the current main source

- **19 choices:** MainActivity defines 10 slow, 5 medium, 3 fast finite presets and a separate Unlimited choice.
- **Broken pipe handling (partial):** Socks5Server contains a SOCKS relay path that preserves the reverse response flow for an upload-side Broken pipe.
- **Preflight fields:** verify_metrics.py declares both `baseline_preflight_download_value` and `baseline_preflight_upload_value` and reports `PREFLIGHT_FIELDS_CAPTURED=2/2`.
- **Unlimited policy:** the main-source verification policy still uses the 80% retention floor (equivalent to the existing 20% tolerance for Unlimited).

### Source-level discrepancies that prevent claiming full implementation closure

The PR #18 description says several earlier fixes were included, but the current source at the time of this audit does not contain all of those implementations. This discrepancy is recorded rather than silently assuming that a PR description proves the code is present.

- **75/100 KB/s policy — NOT verified in main:** the 75 KB/s and 100 KB/s presets exist in the UI, but SpeedVerificationPolicy.kt still applies the 1.2× headroom multiplier to every finite plan and has no special 600/800 Kbps headroom exception. PR #14 remains open.
- **2 MB/s upload Connection reset handling — NOT verified in main:** Socks5Server.kt contains the Broken pipe preservation path, but its active upload-write preservation condition does not include the explicit Connection reset branch described by PR #14. PR #14 remains open.
- **Completed-upload final-flush recovery — NOT verified in main:** SpeedTestEngine.kt does not contain the narrowly scoped final-flush recovery checks described in PR #15. PR #15 remains open.
- **OkHttp completed request-body byte counter — NOT verified in main:** SpeedTestEngine.kt does not contain `EventListener.requestBodyEnd(call, byteCount)` / `UploadBodyByteCounter`, and `app/src/test/java/com/speedvpn/app/UploadByteAccountingTest.kt` is not present on main. PR #16 remains open.

The above are source-audit findings, not application code changes. In keeping with the requested guardrails, this closure did not edit app code, weaken thresholds, remove presets, or merge any additional PR. The diagnostic PRs #19 and #20 were closed without merging after the investigation was documented. The implementation items above must not be reported as complete until main is reconciled and the relevant tests are present.

### Future validation

Run the 19-preset × 2-direction sweep on a suitably provisioned self-hosted runner or manually on real Android devices and a controlled network. Record repeated baseline samples and raw Download/Upload results. Keep HTTP response-header delay as a separate performance item and retain the existing 80%, ±20%, and headroom policies.
