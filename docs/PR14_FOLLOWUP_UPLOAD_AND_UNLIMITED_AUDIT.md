# PR #14 follow-up: upload measurement and Unlimited audit

Date: 2026-10-12

## 1. Intermittent 2 MB/s upload measurement error

Run: [Isolated verification run #3](https://github.com/alinon9/SpeedVPN-Android/actions/runs/38262291396)

The first isolated attempt failed the upload measurement with `SocketException: Broken pipe` from `SpeedTestEngine.FixedUploadBody.writeTo()` during the final `sink.flush()`. The SOCKS relay log showed that 6,000,000 bytes had been forwarded, and the local fixture recorded full `Content-Length` receipt for the corresponding upload request. The verification sampler then failed with `validSamples=1/3; remainingSamples=1`.

The cause is a late close race at the end of a completed upload request, combined with the verification profile allowing only one attempt per sample. The measurement-layer change only defers that particular final-flush exception when:
- all declared body bytes have been written,
- the Okio buffer has no pending bytes, and
- the exception chain contains `Broken pipe` or `Connection reset`.

The request is **not** accepted solely on those conditions: OkHttp must still complete the request and receive a successful HTTP response. Partial writes, pending buffered bytes, unrelated I/O failures, and non-success HTTP responses remain errors. No rate-limiter, SOCKS5, TCP, plan value, or tolerance code is changed.

## 2. Unlimited upload classification

The Unlimited policy was not changed by PR #14. `SpeedVerificationPolicy.evaluate()` handles `planKbps == null || planKbps <= 0` before reaching the new finite-plan-only headroom selection for 600/800 Kbps. Unlimited continues to require at least 80% VPN-to-baseline retention.

The two logged runs demonstrate why the verdict changed:

| Run | Physical upload baseline | VPN upload | Retention | Expected policy result |
|---|---:|---:|---:|---|
| PR #13 isolated run #2 | 59.08 Mbps | 57.27 Mbps | 96.9% | `PASS_UNLIMITED` |
| PR #14 isolated run #3 | 84.52 Mbps | 65.54 Mbps | 77.5% | `MISMATCH` |

For PR #14, 65.54 / 84.52 = 77.54%, below the unchanged 80% floor. The PR #14 run logged successful 3/3 upload samples and sufficient tunnel byte evidence; this was not the missing-measurement error seen in the first 2 MB/s attempt. The baseline increased substantially between runs while the VPN measurement increased by less, so the run-to-run CI fixture/emulator path and relative VPN overhead are plausible contributors.

This evidence confirms there is no Unlimited-policy leakage from the 75/100 KB/s change. It does **not** prove the difference was caused solely by the outside network: the fixture is local to the runner, and the current run's valid measurements fail the existing 80% rule. The correct behavior is to keep the result as a mismatch, not force `PASS_UNLIMITED` or relax the threshold. A fresh complete CI run is required to establish a passing result under the unchanged policy.

## Acceptance guardrails

- 75 KB/s and 100 KB/s low-rate verification policy remains unchanged from PR #14.
- Unlimited's 80% retention rule remains unchanged.
- Any run counts as green only if the workflow's actual acceptance gates pass; this note is diagnosis, not a substitute for CI evidence.
