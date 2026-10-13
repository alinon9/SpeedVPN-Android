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

## Preserved policies and fixes

This final CI-policy change does not modify application presets, limiter rates, or measurement thresholds. Preserve the existing work for:

- 2 MB/s SOCKS5 upload/reset handling.
- The 75 KB/s and 100 KB/s verification policy.
- Narrowly scoped upload `Broken pipe` handling.
- Completed upload-body byte accounting introduced in PR #16.
- Unlimited retention of at least 80%.
- The finite-rate ±20% tolerance and the 1.2× baseline-headroom floor.
