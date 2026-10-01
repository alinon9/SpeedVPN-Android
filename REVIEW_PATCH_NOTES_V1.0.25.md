# SpeedVPN v1.0.25 Patch Notes

## Applied fixes

1. **DNS fallback removed**
   - `Socks5Server.resolveAll()` now requires the current physical `Network`.
   - The generic `InetAddress.getAllByName()` VPN fallback is removed.
   - DNS execution is bounded by the same 12-second monotonic deadline as TCP connection attempts.

2. **Speed limits restored in the Service**
   - Added `SpeedLimitStore` as the single persistence layer.
   - `SpeedVpnService` restores limits before starting the native tunnel.
   - `MainActivity` and `AgentService` use the same store.

3. **Health model clarified**
   - Added `VpnHealth`: `DISCONNECTED`, `CONTROL_READY`, `UPSTREAM_READY`, `DEGRADED`.
   - Native/SOCKS recovery remains on the existing 2-second loop.
   - Physical upstream reachability is sampled every 30 seconds.
   - The upstream probe is explicitly protected/bound and is **not** a TUN-to-Internet E2E test.

4. **SOCKS diagnostics**
   - Normal cancellation/close is quiet.
   - Refused/reset/broken-pipe/unreachable/timeout events are classified as network events.
   - Unexpected errors are logged with stack traces.

5. **Timeout correctness**
   - `waitForReady()` uses `SystemClock.elapsedRealtime()` instead of wall-clock time.

6. **Release reproducibility**
   - Native dependency remains pinned to commit `d9dca26c7ad0e494492244f0309e80ee583e739e`.
   - Release workflow creates a full-source archive after resolving the native dependency.

## Deliberately not changed

- `UdpFlowTable` remains unchanged. A reply from a shared upstream UDP socket contains only the remote source endpoint; treating that same `IP:port` as a new correlation key would be misleading. If real-device tests prove ambiguous flows are unacceptable, the robust fix is per-logical-flow upstream UDP sockets.
- Native `hev-socks5-tunnel` source is not vendored in the normal repository checkout.

## Verification status in the current review environment

- Static source scan: PASS
- `UdpFlowTable.kt` JVM compilation: PASS
- `SpeedLimitStore.kt` JVM compilation with Android stubs: PASS
- Shell workflow/setup syntax checks: PASS
- Android Gradle build: NOT VERIFIED — the environment cannot resolve `services.gradle.org`.
- Real Android device matrix: NOT RUN in this environment.
