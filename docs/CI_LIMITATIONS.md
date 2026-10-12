# CI Limitations — Speed-Limit Sweep

Recorded: 2026-10-12

## Decision

The 19-preset GitHub-hosted CI sweep accepts `ENV_LIMITED` as a documented final classification only for the plan/direction pairs listed below. This is **not** a measurement PASS: the row remains classified as `ENV_LIMITED`, with its original measurements and explanation retained. CI exits successfully only if all 38 Download/Upload rows are present, there are no genuine `FAIL` events, and every `ENV_LIMITED` row is on the exact allowlist.

## Observed runner constraints

Across seven runs on GitHub-hosted runners, Upload baseline measurements ranged from **57 to 95 Mbps**. Approximately **95 Mbps** is the observed practical upper end in these runs, not a universal published bandwidth guarantee for every GitHub-hosted runner. The highest tested finite plan, **11 MB/s (88 Mbps)**, requires at least **105.6 Mbps** of baseline capacity to satisfy the existing 1.2× headroom requirement. That level of Upload capacity was not consistently available.

Observed throughput spread ranged from **29% to 45%**, above the target of **25% or less**. Android Emulator UI-hierarchy extraction also failed intermittently, which can prevent reliable metric collection even when the app is running.

These findings document the limitations of the current CI environment; they do not demonstrate a need to weaken the limiter or change the configured plans. `ENV_LIMITED` is used when missing physical-baseline headroom means the test cannot reliably confirm the cap. Missing/invalid measurements, a wrong selected plan, a genuine rate mismatch with sufficient baseline headroom, coverage failures, and other real faults remain failures.

## Exact ENV_LIMITED allowlist

The requested constants represent plan values in **KB/s**. In the sweep verifier, `expected_kbps` is expressed in **Kbit/s**; the equivalent target is therefore the KB/s plan value multiplied by 8. This is only unit conversion. It does not change the target or any measurement tolerance.

```python
ENV_LIMITED_ACCEPTED_PLANS_UPLOAD_KBPS = {9000, 10000, 11000}
ENV_LIMITED_ACCEPTED_PLANS_DOWNLOAD_KBPS = {10000, 11000}
```

| Direction | Preset | Plan rate | Verifier target | ENV_LIMITED accepted? |
|---|---:|---:|---:|---|
| Upload | 9 MB/s | 9,000 KB/s | 72,000 Kbit/s (72 Mbps) | Yes |
| Upload | 10 MB/s | 10,000 KB/s | 80,000 Kbit/s (80 Mbps) | Yes |
| Upload | 11 MB/s | 11,000 KB/s | 88,000 Kbit/s (88 Mbps) | Yes |
| Download | 10 MB/s | 10,000 KB/s | 80,000 Kbit/s (80 Mbps) | Yes |
| Download | 11 MB/s | 11,000 KB/s | 88,000 Kbit/s (88 Mbps) | Yes |

Any `ENV_LIMITED` result for another plan/direction pair fails CI. In particular, **9 MB/s Download is not allow-listed**. An allow-listed result is still logged as `ENV_LIMITED`, never rewritten as `PASS`.

## Behaviors and thresholds preserved

- **2 MB/s:** the SOCKS5/upload measurement fix is unchanged. The clean acceptance run must still prove **4/4 PASS** across two Download/Upload attempts.
- **75 KB/s and 100 KB/s:** the low-speed verdict logic is unchanged and both plans must continue to pass.
- **Unlimited:** the **80% baseline-retention** rule is unchanged.
- **Finite plans:** the **±20%** measurement acceptance window is unchanged.
- **Baseline capacity:** the **1.2×** headroom requirement is unchanged.
- **Application and limiter:** no app code, speed preset, rate limiter, TCP buffer, or SOCKS5 implementation is changed. Only the CI final acceptance/reporting logic, sweep-harness acceptance accounting, tests, and documentation change.

## Future recommendation

When suitable hardware becomes available, run the exhaustive performance sweep on a dedicated self-hosted runner with a known, stable uplink and enough headroom. Keep the documented allowlist narrow, and re-evaluate it using repeatable measurements from that runner rather than relaxing measurement criteria.

## Final rule

`PASS` means the existing measurement checks passed. The sweep may also exit with code 0 when `ENV_LIMITED` occurs **only** for the exact allow-listed plan/direction pairs and there are no genuine `FAIL` events. The workflow then prints `ACCEPTED WITH DOCUMENTED ENV LIMITATIONS`. Any non-allow-listed `ENV_LIMITED`, genuine `FAIL`, missing row, or hard-failure event keeps CI red.
