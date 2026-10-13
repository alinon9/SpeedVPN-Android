# CI limitations and final acceptance policy

**Status:** Final policy recorded on 2026-10-13.

## Observed GitHub-hosted runner limitations

Across 8+ recent acceptance attempts for this project, GitHub-hosted runners repeatedly failed to provide a stable environment for the full 19-preset × 2-direction speed sweep:

- Observed upload throughput was approximately 40–68 Mbps, below the headroom needed to reliably validate the 72, 80, and 88 Mbps plans.
- Network measurement variability was approximately 30–46%.
- The preflight could exceed its 155-second budget or time out before a trustworthy capacity decision.
- Android UI automation intermittently failed to capture or interact with the expected screen.

These observations describe this project's CI runs; they are not a claim that every GitHub-hosted runner everywhere has the same performance.

## Verified Unlimited Download failure — unresolved

**Status: real application-path failure; keep it as a hard `FAIL` until resolved. Do not relabel it `ENV_LIMITED`.**

The available acceptance evidence reports:

- Unlimited Download retained **59.9%** in Run #19 and **58.6%** in Run #134, both below the unchanged **80%** requirement.
- Unlimited Upload retained **81.5%** across **3/3 samples** and passed. Keep the upload fix/path intact while diagnosing download.
- For the reported **142.47 Mbps** baseline, the 80% floor is **113.98 Mbps**. The two download retentions imply approximately **85.34 Mbps** and **83.49 Mbps** respectively: short by **28.64 Mbps** and **30.49 Mbps**.
- The baseline is also sufficient for the 88 Mbps reference plan: its 1.2× floor is **105.60 Mbps**, leaving **36.87 Mbps** of headroom. These failures are therefore not explained by the documented baseline-headroom rule.

### Static code comparison (not a substitute for a runtime trace)

- `SpeedTestEngine` uses **3 parallel streams** for Unlimited verification and splits a **20,000,000-byte aggregate sample** across them. Its response-body read buffer is **256 KiB**.
- `Socks5Server.pipe` uses a **256 KiB backing buffer** in both directions. When Unlimited is selected, `recommendedReadBytes()` also returns up to **256 KiB**. Download and upload call the same relay/pacing path, with the socket endpoints reversed.
- `SpeedLimiter.download` and `SpeedLimiter.upload` are separate token buckets with the same implementation. A rate of zero means unlimited; no download-only token-bucket cap was found in the Kotlin path. The TCP relay does not explicitly set `SO_RCVBUF` or `SO_SNDBUF`; the explicit 64 KiB socket-buffer settings found in `Socks5Server` apply to UDP `DatagramChannel` flows, not this TCP download path.
- The native TCP configuration guard in `scripts/e2e/patch_hev_tcp_buffers.py` expects `TCP_MSS=1220`, `TCP_WND=(256 * TCP_MSS)`, window scaling enabled, and **`TCP_SND_BUF=65535`**. That per-PCB send-buffer size is a plausible asymmetric constraint for the HEV/lwIP **download-to-app** path, because native HEV must send the response through lwIP to the app. It is not yet a proven root cause.

As a numerical plausibility check only, three 65,535-byte per-flow send buffers represent **196,605 bytes** in flight. That amount corresponds to roughly **83.5–85.3 Mbps** at an RTT of approximately **18.4–18.9 ms**, near the observed download rates. Sustaining the 113.98 Mbps acceptance floor with the same aggregate window would require an effective RTT around **13.8 ms or lower**. **No RTT has been measured for these runs; this is a BDP-based hypothesis, not a measured socket or CPU bottleneck.** Do not increase `TCP_SND_BUF` above 65,535 by changing the macro alone: the native patch script explicitly warns that the underlying field is 16-bit and larger values can overflow.

### Required next diagnostic and repair gate

After the already-running PR #20 validation has a confirmed result, run an isolated **Unlimited Download-only** measurement with baseline and VPN using the same three-stream, 20 MB aggregate profile. Do not run the test before that result is available. Capture per-flow chunk size and inter-chunk time; Java SOCKS socket send/receive buffer sizes; CPU, process RSS/PSS and active-thread count; and native HEV/lwIP send-window/backpressure details (`tcp_sndbuf`, ring-buffer occupancy, bytes accepted by `tcp_write`, `ERR_MEM` / `tcp_output` outcomes, and time waiting for ACK progress). The existing SOCKS pipe summary already records forwarded bytes, read/write calls, maximum read size, source-read time, limiter time and destination-write time; preserve those fields.

The first targeted repair candidate is the native HEV/lwIP **response send/backpressure path**, only if the trace shows send-buffer exhaustion or repeated write/backpressure stalls. Choose a safe per-flow window/backpressure change based on those measurements, with regression tests; do not weaken the 80% floor, change any of the 19 plans, modify the successful Unlimited Upload path, or classify a strict download failure as environmental.

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
