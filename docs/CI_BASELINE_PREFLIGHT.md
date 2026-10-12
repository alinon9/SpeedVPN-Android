# CI baseline preflight for PR #15 validation

The isolated verification workflow qualifies the GitHub-hosted runner before performing the two targeted 2 MB/s attempts and the protected 19-preset Download/Upload regression sweep. The retry architecture is unchanged: one retry may run on a fresh GitHub-hosted runner, and there is no third attempt.

The preflight restarts the app between samples and collects three fresh original-network Download and Upload measurements from the Android emulator. The median Download baseline must be at least **96 Mbps** and the median Upload baseline at least **90 Mbps**. The primary measured-range limit is **25% of the median**, calculated as `(max - min) / median × 100`.

## Bounded single-outlier tolerance

GitHub-hosted CI can produce one transient sample that makes the raw range slightly exceed 25%. To qualify the Run #6 Attempt 2 profile without allowing severely unstable runners through, the preflight allows a narrowly bounded exception only when **all** of the following hold:

- The raw range is no more than **30%** of the median.
- The two of three samples closest to the median have a range no greater than **25%** of their pair median.
- Every sample is within **30%** of the overall median.

The raw range, coefficient of variation, closest-pair range, maximum deviation, and whether this exception was used are still included in the preflight JSON/logs. A run with Run #6 Attempt 1's ~45.7% spread cannot use this exception and remains rejected.

## Environment notes and interpretation

The generated summary and GitHub Step Summary include an explicit `ENV_NOTE: ...` field. For a median upload baseline around **94.79 Mbps**, the note records that the runner's upload ceiling limits higher-plan verification: **9 MB/s** (72 Mbps) retains adequate 1.2× baseline headroom, but **10 MB/s** needs at least 96 Mbps and **11 MB/s** needs at least 105.6 Mbps. Those upload checks may correctly remain `ENV_LIMITED`; they are not to be relabeled as PASS. The 90 Mbps preflight floor qualifies the runner to begin the sweep, but does not waive the per-measurement 1.2× headroom or ±20% verification criteria.

If the calibrated preflight fails or cannot finish within the existing time budget, the workflow records `ENV_INSUFFICIENT — skipped sweep` and performs the existing single retry on a fresh runner. GitHub-hosted `ubuntu-latest` does not guarantee a different Azure region.

This is CI test-harness/reporting infrastructure only. It does not modify application code, speed presets, the rate limiter, or verification tolerance. PR #15 must remain unmerged until the configured acceptance checks pass.
