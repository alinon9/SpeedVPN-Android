# UID flow cache acceptance test (device)

This checklist validates the isolated UID detection POC. It is not a production firewall test.

## Build identity
- [ ] Confirm the APK was built from the PR merge commit shown on the Actions run.
- [ ] Save the artifact SHA-256 and install that exact APK on the physical device.
- [ ] Device is Android 10/API 29 or newer.

## 60-second run
1. Start the POC VPN once and confirm `UID_WORKER_READY stack=1048576` appears before native packet logging.
2. Start POC recording; create TCP traffic in Chrome and UDP traffic in a known app for at least 60 seconds.
3. Repeat requests/traffic on the same few flows so cache hits can be measured after warm-up.
4. Export `Crash.txt`, `SpeedVPN-POC-JNI.txt`, and the CSV result set before stopping the process.

## Expected metrics
Look for a log every 1000 candidate TCP/UDP packets:

```text
PACKETS=N CACHE_HITS=M CACHE_MISSES=K PENDING_HITS=P UNIQUE_LOOKUPS=U JNI_LOOKUPS=J HIT_RATE=R% QUEUE_FULL=Q LOOKUP_FAILS=F
```

Acceptance goals for this workload:
- [ ] Process survives at least 60 seconds with the VPN active.
- [ ] `HIT_RATE` exceeds 90% after warm-up for repeated flows.
- [ ] Actual `JNI_LOOKUPS` are below 100 per minute.
- [ ] Observed TCP and UDP tuples resolve to the expected app UID when Android exposes ownership.
- [ ] No unexplained `QUEUE_FULL`, JNI exception, native signal, or worker shutdown during the test.

The POC is diagnostic only: this cache does not block, delay, or alter packet forwarding. A successful CI build alone is not evidence that the crash is fixed.
