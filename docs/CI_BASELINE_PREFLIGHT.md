# CI baseline preflight for PR #15 validation

The isolated verification workflow uses the temporary validation base branch and gates performance tests before the 2 MB/s attempts or the 19-preset sweep.

The preflight collects three fresh original-network Download and Upload measurements from the Android emulator. It passes only when the median Download baseline is at least 96 Mbps, the median Upload baseline is at least 106 Mbps, and each direction's measured range `(max - min) / median` is no more than 15%. The validator also records sample variance and coefficient of variation.

If any gate fails or the preflight cannot finish within the three-minute budget, the workflow logs `ENV_INSUFFICIENT — skipped sweep`, skips the expensive test suite on that worker, and performs one retry in a separate GitHub-hosted job. The retry uses a fresh runner VM, but GitHub-hosted `ubuntu-latest` does not guarantee a different Azure region. A second failed preflight fails the workflow and is not retried again.

This is test-harness / workflow infrastructure only. It does not modify application speed presets, the rate limiter, or verification tolerance, and PR #15 must remain unmerged until the configured acceptance checks pass.
