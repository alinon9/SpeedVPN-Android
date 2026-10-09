# TEAM INVESTIGATION PLAN — SpeedVPN Speed Limiter

Date: 2026-10-09 (UTC)
Repository: https://github.com/alinon9/SpeedVPN-Android
Branch under investigation: fix/speed-sweep-ui-reliability
Baseline commit: c879cf2b1c18ef2239150bac3218aa08f181f3a7
Pull request: [#7 — Fix SpeedVPN speed sweep UI automation](https://github.com/alinon9/SpeedVPN-Android/pull/7) (open at baseline)

## 1. Scope and coordination

This run uses seven named engineering roles sequentially in one coordinated tool session; the environment does not expose independent parallel sub-agent execution. Evidence is shared via this plan and CI artifacts. GitHub changes are prepared as a single guarded commit to the existing PR branch; no push to main, force-push, or PR merge is authorized.

| Role | Ownership | Initial evidence / acceptance |
|---|---|---|
| Strategist-Agent | Baseline, issue ledger, sequencing, integration | Inspect branch/PR/runs and prevent unsupported closure |
| UI-Diagnostics-Agent | Compose tags, Verify fields, emulator UI selection | Latest E2E reaches 10/10 on tested cases, but slow partial captures are observable; full repeated sweep must remain stable |
| Traffic-Shaping-Agent | SpeedLimiter.kt, tunnel call sites and limiter JVM tests | Current high-speed Download repeatedly misses ±20% while same-run baseline/Unlimited throughput is high; test token accrual outside the limiter lock and verify both directions |
| Test-Runner-Agent | scripts/e2e/verify_metrics.py, CSV and sweep tests | Require exactly 19 expected preset indexes × Download/Upload; malformed, missing, duplicate and ENV_LIMITED records must not pass |
| CI-CD-Gatekeeper-Agent | GitHub Actions workflows | Latest Debug build + JVM tests succeed, while E2E/sweep and their strict gates fail; preserve failure exit codes and artifacts |
| Independent-Verification-Agent | Adversarial review of code/test/evidence | Reject a claim of fixed until a new CI run validates the modified commit; inspect unchanged ±20% policy and Unlimited behavior |
| Android-VPN-Regression-Agent | Integration and VPN regression | Verify connect/disconnect/reconnect, settings, quotas, app blocking and overlay via available CI; unavailable test coverage remains inconclusive |

## 2. Baseline facts

- The branch was at c879cf2b1c18ef2239150bac3218aa08f181f3a7; PR #7 was open and pointed to main.
- Latest baseline Build SpeedVPN APK run [37925543095](https://github.com/alinon9/SpeedVPN-Android/actions/runs/37925543095) succeeded.
- Latest baseline E2E run [37925543041](https://github.com/alinon9/SpeedVPN-Android/actions/runs/37925543041) failed. Its build and JVM-test steps succeeded, but E2E/sweep strict validation failed.
- Latest baseline sweep run [37925543003](https://github.com/alinon9/SpeedVPN-Android/actions/runs/37925543003) failed the strict 38/38 gate after producing a 38-row record set. A complete row count is not proof all measurements pass.
- In the latest E2E run, Download baseline was 335.72 Mbps at an 80 Mbps setting and 357.20 Mbps at an 88 Mbps setting. Measured VPN Download was 60.36 and 66.31 Mbps, below respective 64 and 70.4 Mbps lower bounds. In that same run Unlimited Download was 354.16 Mbps against 411.94 Mbps baseline. This is evidence against a simple physical-link ceiling as the sole explanation.
- At 72 Mbps, Download 58.36 Mbps was just inside the 57.6 Mbps lower bound. Upload passed at 72/80/88 Mbps in that E2E run.
- In the separate sweep run, baseline headroom was weaker and some Upload observations were correctly marked ENV_LIMITED. That run cannot be used to conclude the app cap failed for those upload directions, but it also does not explain the independent high-baseline E2E Download failures.
- Verify-Speed logs often observed partial counts (e.g. 5/10 or 8/10) before later reaching 10/10. This demonstrates delay in field completion; those later fields should not be treated as present at the earlier snapshot.
- Existing acceptance policy is ±20%. This investigation does not widen that tolerance.

## 3. Unified issue ledger

| ID | Issue | Baseline classification | Owner | Resolution test |
|---|---|---|---|---|
| L-01 | Download under-delivery at 80/88 Mbps despite high baseline | Reproduced in CI; precise implementation cause pending regression evidence | Traffic-Shaping-Agent | Unit test proving monotonic refill during outside I/O, then multiple fresh capped Download/Upload measures |
| L-02 | 72 Mbps Download close to lower bound | Reproduced near lower bound; stability not established | Traffic-Shaping-Agent + Test-Runner-Agent | Repeated valid-baseline measurement; retain 57.6 Mbps lower limit |
| L-03 | Slow/partial Verify field capture | Observed in logs; not a missing-field failure in latest run | UI-Diagnostics-Agent | Repeated sweep with 10 fresh fields per measurement and no stale-value carry-over |
| L-04 | Sweep run has limited physical baseline at some upload levels | Observed; environment-limited, not app PASS | Test-Runner-Agent | Run on headroom-sufficient baseline; keep ENV_LIMITED red for strict all-pass gate |
| L-05 | Unlimited Download differs across runner environments | E2E passed; lower-baseline sweep failed | Test-Runner-Agent + Traffic-Shaping-Agent | Validate policy and repeated paired baseline/VPN measurements; don't hard-code PASS |
| L-06 | Preserve 800 Kbps behavior | Historical baseline PASS; current regression not yet re-run | Traffic-Shaping-Agent + Independent-Verification-Agent | JVM conversion/pacing tests plus fresh in-app Download and Upload measurements |

## 4. Proposed scoped change

The baseline limiter schedules a full per-chunk wait from a fixed deadline (nextAvailableNs = now + n/rate). Time spent doing network I/O between calls is not credited to the next chunk. This can make the total cycle approximately chunk pacing delay plus socket/write overhead, consistent with the observed ~60–66 Mbps under an 80–88 Mbps ceiling. This is a strong, testable hypothesis, not a claim that the performance fix has been verified end-to-end.

The proposed scoped remedy is a monotonic-clock token bucket with bounded burst credit that refills while the relay performs I/O. Direction-specific buckets, conversion units, tunnel wiring, session-generation checks, strict ±20% acceptance, and CSV classification remain unchanged. Add a bounded JVM regression test expected to fail the deadline-only implementation and pass the refillable implementation.

## 5. Change ownership and no-overlap rule

- Traffic-Shaping-Agent owns only SpeedLimiter.kt and SpeedLimiterConversionTest.kt in this change.
- Test-Runner-Agent and CI-CD-Gatekeeper-Agent review, but do not concurrently modify the same files.
- UI-Diagnostics-Agent records UI findings only; no UI changes are justified by the high-baseline speed evidence so far.
- Independent-Verification-Agent independently compares the new diff and test criteria after implementation.

## 6. Acceptance and stop conditions

1. Debug and Release builds, JVM tests, lint, and APK validation must be reported separately.
2. The new token-accrual test and existing pacing/conversion tests must pass.
3. A new CI sweep must contain 19 expected indexes and exactly one valid Download and Upload row for each index; all 38 must classify PASS for a strict PASS. ENV_LIMITED remains unresolved and is never counted as PASS.
4. 72/80/88 Mbps Download and Upload, 800 Kbps both directions, and Unlimited both directions require fresh post-change evidence with baseline, target, VPN measure, direction, and run URL.
5. Keep ±20%; don't alter Unlimited verdict semantics merely to force green.
6. Keep PR #7 open. No merge until the independent review and required gates pass.
7. If an external network/emulator prevents a result, report INCONCLUSIVE rather than PASS.
