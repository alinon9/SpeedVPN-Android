# VPN lifecycle and socket-protection audit — 2026-10-16

## Scope
Changes are isolated on `fix/vpn-lifecycle-socket-protection-20261016`, based on `fix/backend-services-doh-overlay-20261016`. The patch does not change `main`, enable Lockdown, or remove IPv6 routes.

## Repairs
- Protect outbound TCP, UDP, DoH, and upstream health-probe sockets before binding them to the selected physical `Network`; fail closed if protection fails.
- Require native stop confirmation and completion of detached-resource cleanup before a new TUN is established.
- Invalidate in-flight START work on STOP and queue START requests that arrive during teardown. Per-stop tokens keep an old Service instance from releasing another instance's stop gate.
- Reap idle UDP flows on selector timeouts and close their per-flow channels, even without further client traffic.
- On network change, close TCP/UDP egress sockets, invalidate UDP mux bindings, and cancel/evict the previous encrypted-DNS client's connections.
- Add unit tests for protection order, UDP table clear/expiry, and 10 repeated STOP/START gate interleavings.

## Code locations
- `Socks5Server.kt`: TCP `connect()`; UDP `UdpUpstreamMux.createBindingLocked()`; idle cleanup `UdpUpstreamMux.runLoop()`; network invalidation `onUnderlyingNetworkChanged()`.
- `EncryptedDnsResolver.kt`: `ProtectedNetworkSocketFactory.createSocket()` and `close()`.
- `SpeedVpnService.kt`: `onStartCommand()`, `connect()/connectSerialized()`, `awaitPreviousNativeGenerationStopped()`, `upstreamReachabilityProbe()`, `cleanupLocked()`, `onDestroy()`.
- The TUN builder keeps IPv4 default route `0.0.0.0/0`, IPv6 default route `::/0`, and excludes only the VPN app itself. No Lockdown fallback is used.
- `TProxyService.java` remains the JNI boundary; native start/stop calls remain guarded by the process-wide lifecycle lock.

## Test status
Tests have been added but were not run in this environment: local cloning failed because `github.com` did not resolve. No green Gradle/Android result is claimed. The lifecycle unit test validates the command-gate interleaving; it does not substitute for FD counters, native restart tests, or real-device TUN traffic. Real-device IPv4/IPv6, DNS, TCP, UDP/QUIC, Wi-Fi↔Cellular, and rapid restart E2E tests are still needed.
