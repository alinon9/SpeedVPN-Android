# SpeedVPN release verification

This document records tests that must be executed on real Android devices before calling a build Release-Verified. Source inspection alone is not sufficient.

## Background CONNECT matrix

| Scenario | Android 12 | Android 13 | Android 14 | Android 15 |
|---|---|---|---|---|
| App foreground | pending | pending | pending | pending |
| App background | pending | pending | pending | pending |
| Screen locked | pending | pending | pending | pending |
| After reboot | pending | pending | pending | pending |
| AgentService killed | pending | pending | pending | pending |

A failed background start must surface a clear user action path rather than being reported as a successful VPN connection.

## Required functional tests

1. Wi-Fi -> cellular -> Wi-Fi while connected; verify DNS lookups and new TCP/UDP connections continue.
2. Change DNS on the active physical network; verify exactly one VPN generation rebuild occurs and an older callback cannot disconnect the replacement generation.
3. Kill the process during stats flush; verify the persisted `PENDING` batch is retried with the same `stats_batch_id`.
4. Kill the process after backend acceptance; verify backend dedup prevents double accounting when the same batch is retried.
5. Sign in/sign out repeatedly (100 cycles) and verify no token reappears after sign-out.
6. Stress 80 TCP and 24 UDP sessions; observe memory, worker saturation, limiter accuracy, and teardown.
7. Test UDP aliases resolving to the same IP:port and record whether the application requires stronger per-flow correlation. A shared upstream UDP socket cannot guarantee attribution when replies are byte-for-byte indistinguishable; do not mark this case fixed by source-port bookkeeping alone.
8. Kill/restart the app while a local speed limit is active; verify `SpeedVpnService` restores both limits before native traffic starts.
9. Verify the health UI/state distinguishes control readiness from upstream reachability. The upstream probe must not be described as TUN-to-Internet E2E.
10. With VPN active, generate real client traffic (browser/`curl`/DNS/TCP/UDP) and verify TUN -> native -> SOCKS -> physical network end-to-end. This is the authoritative data-plane test.
11. Test Android Always-on VPN + Lockdown firewall behavior separately from ordinary VPN mode.

## APK / 16 KB gates

- `apksigner verify --print-certs <apk>`
- `zipalign -c -P 16 -v 4 <apk>`
- Extract every `lib/*/*.so` from the APK.
- Run `llvm-readelf -lW` and verify every `PT_LOAD` has `Align 0x4000`.

## Evidence labels

- **VERIFIED**: executed and observed in the stated environment/device.
- **STATIC-ONLY**: established by source inspection without runtime execution.
- **CONFIG-ONLY**: configuration is present, but behavior is not proven.
- **NOT TESTED**: the required environment/device was unavailable.
- **FAIL**: the test was executed and did not meet its acceptance criteria.
