# SpeedVPN v1.0.26 Patch Notes

## Applied fixes

1. **Fixed UDP IPv4 handling when IPv6 is disabled**
   - SOCKS5 UDP ATYP=1 (IPv4) is now accepted regardless of the IPv6 setting.
   - ATYP=4 (IPv6) is accepted only when IPv6 is enabled.
   - Added unit coverage for IPv4/IPv6 address-family behavior.

2. **Hardened UDP association resource ownership**
   - DatagramSocket creation now occurs inside the protected ownership scope.
   - A failure while creating/configuring either socket cannot leak the relay socket or permanently consume a UDP-association semaphore permit.
   - Final cleanup always closes/untracks created sockets and releases the semaphore.

3. **Made UDP fail closed when no physical network exists**
   - UDP association setup now requires `currentNetwork()` and binds the upstream socket to that physical network before use.

4. **Fixed release artifact naming**
   - `.github/workflows/release.yml` now exports the detected version to `GITHUB_ENV` and uses it for both the artifact name and full-source archive path.
   - The stale v1.0.25 paths are removed.


5. **Fixed the confirmed UDP DNS compile error**
   - `Socks5Server.resolveAll()` requires a timeout.
   - The UDP hostname path now calls `resolveAll(host, UDP_DNS_TIMEOUT_MS)` with a 3-second bound.
   - The resolver remains pinned to the current physical `Network`; the unsafe generic `InetAddress.getAllByName()` fallback remains removed.

6. **Preserved the existing 12-second TCP outbound deadline**
   - TCP DNS receives the remaining time from the same monotonic deadline.
   - No separate unbounded DNS phase was introduced.

7. **Revalidated resource ownership**
   - Existing SOCKS/UDP cleanup paths close relay and upstream sockets in `finally`/stop paths.
   - No blanket `use {}` was added where sockets are intentionally handed to worker threads.

8. **Preserved verified security/lifecycle fixes**
   - No permissive `X509TrustManager` was found, so no speculative TLS change was made.
   - `SpeedVpnService` and `AgentService` remain non-exported.
   - Existing `stateMutex`, resource lifecycle locking, generation checks, and native lifecycle locking remain intact.
   - `SpeedLimiter` continues to use monotonic `System.nanoTime()` timing.

9. **Did not introduce unsupported performance changes**
   - UDP buffer size remains 65,535 because the alleged truncation bug was not present.
   - Stats checkpoint remains 30 seconds; the durable PENDING/SENT mechanism was not replaced by a battery/network-expensive 5-second loop.
   - Gradle remains 8.11.1; it was not downgraded to 8.2.

## Additional final fixes

10. **Prevented UDP client-endpoint overwrite**
   - UDP flow identity now includes the client's source IP/port and the resolved destination endpoint set.
   - Two client source ports targeting the same remote endpoint can no longer reuse one table entry and overwrite its reply destination.
   - Hostname text is intentionally excluded from the identity so different names resolving to the same endpoint do not create a false collision.

11. **Made local VPN settings durable**
   - Speed limits, IPv6, DNS IPv4-only, and MTU persistence now use synchronous `commit()`.
   - This reduces the window in which an immediate process death can lose the latest setting.

12. **Removed generated diagnostics**
   - `kotlinc.out` was removed from the source archive and added to `.gitignore`.

## Remaining verification / limitations

- **UDP replies can still be ambiguous** when genuinely distinct logical flows have the same client source endpoint and the same remote IP:port. The current single-upstream-socket design cannot deterministically recover that distinction from a received UDP packet alone, so such replies still fail closed instead of being misrouted. A per-flow socket/port design would be a larger architectural change and should be validated with device stress tests first.
- **Gradle wrapper JAR:** the ZIP still contains the checksum file but not the binary JAR. The official Gradle 8.11.1 wrapper checksum is known and matches the existing `.sha256`, but the binary could not be materialized in this offline build environment. It was not replaced with an unverified binary.

## Verification status

- ZIP extraction: PASS
- Static source checks: PASS for the targeted changes
- Shell syntax checks: PASS
- Full Android Gradle build/unit tests: must be run in an environment with the required Gradle distribution/Android dependencies available
- Real Android device matrix: not run in this environment


## Final repair follow-up
- `UdpFlowTableTest.kt`: moved the UDP flow-key tests back inside `UdpFlowTableTest` so their fixture fields are in scope.
- `MainActivity.kt`: synchronous SharedPreferences commits are now serialized on `Dispatchers.IO` rather than blocking the UI thread.
- UDP correlation: the implementation remains fail-closed for genuinely identical observable UDP 5-tuples; inventing a heuristic would reintroduce misrouting risk.
- `gradle-wrapper.jar` remains intentionally absent because no trusted binary could be fetched in the offline repair environment.
