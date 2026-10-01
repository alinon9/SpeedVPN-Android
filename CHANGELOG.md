## 1.0.26 — Final Repair 3

- Reworked live UDP upstream correlation to use one connected `DatagramChannel` per logical flow, giving each flow a distinct upstream source port and deterministic reply ownership.
- Added a selector-based UDP multiplexer so deterministic per-flow sockets do not require one thread per flow.
- GitHub build/release workflows now execute through `./gradlew` and verify/bootstrap the official Gradle 8.11.1 Wrapper JAR before building.
- Preserved Unix executable permissions for `gradlew` and `setup.sh`.

# Changelog

## [1.0.26]

### Fixed
- Fixed UDP logical-flow identity so different client source endpoints cannot overwrite each other's `clientEndpoint` when they target the same remote destination.
- Persisted local speed, IPv6, DNS, and MTU settings with synchronous `commit()` to reduce loss on immediate process death.
- Removed the generated `kotlinc.out` diagnostic artifact from the source archive.

### Hardened
- Revalidated socket ownership/cleanup paths instead of adding unsafe blanket `use {}` blocks around sockets that outlive their accepting method.
- Preserved the existing generation-aware lifecycle locks, monotonic speed limiter, durable stats batching, secure TLS defaults, and non-exported VPN/agent services.

### Release gates
- Android Gradle build, unit tests, lint, signed APK verification, 16 KB ELF/PT_LOAD verification, and real-device VPN/TUN/UDP/network-handoff tests remain required before release.

## [1.0.25]

### Fixed
- Removed the generic `InetAddress.getAllByName()` fallback from the VPN SOCKS DNS path. DNS is now resolved only on the current physical `Network`.
- Included DNS resolution in the 12-second outbound connection deadline using a monotonic clock and bounded resolver execution.
- Restored persisted local download/upload limits inside `SpeedVpnService` before native data-plane startup.
- Classified SOCKS teardown, timeout, and network errors separately for useful field diagnostics.

### Changed
- Added `VpnHealth` states for `CONTROL_READY`, `UPSTREAM_READY`, and `DEGRADED`. The upstream probe is explicitly not a TUN data-plane probe.
- Preserved the 2-second native/SOCKS control-health recovery loop; upstream reachability is sampled every 30 seconds.
- Release workflow publishes a full-source archive with the resolved pinned native tunnel source.

### Release gates
- Real-device TUN-to-Internet traffic remains a required manual test; source probes cannot prove end-to-end forwarding.
- Android 12-15, network handoff, Always-on/reboot, process-kill, auth-race, UDP collision, stress, APK signature, 16 KB ZIP alignment, and 16 KB ELF alignment remain release verification gates.


## [1.0.24]

### Fixed
- Made stats batches durable before network transmission with synchronous `commit()` and explicit `PENDING` / `SENT` / `IDLE` state.
- Serialized legacy token migration with the same authentication lock used by sign-out and token persistence.
- Bound DNS-triggered VPN rebuilds to the generation that observed the change and debounced callback bursts.

### Changed
- Release signing is injected through environment/CI secrets only.
- R8/minification remains disabled pending a real release APK test of Compose and JNI entry points.
- Documented the backend idempotency contract for `stats_batch_id`.

### Known release gates
- Android/NDK build and signed APK verification.
- 16 KB ELF/PT_LOAD verification from the packaged APK.
- Real-device VPN, network handoff, background FGS, stress, and process-kill tests.
- UDP replies from multiple logical destinations that resolve to the same remote IP:port still require real-device validation; the current single upstream UDP socket cannot prove per-flow correlation in that ambiguous case.

## [1.0.23]

### Fixed
- Closed the sign-out/token persistence generation race.
- Hardened foreground-service configuration and lifecycle ownership.

## [1.0.22]

### Fixed
- UDP compatibility, firewall/lockdown handling, network handoff, generation-safe native lifecycle, and traffic accounting hardening.


### Final repair follow-up
- Fixed the `UdpFlowTableTest` class-scope regression.
- Moved MainActivity SharedPreferences commits to serialized IO dispatch.
- Clarified that identical UDP 5-tuples are inherently indistinguishable on a shared upstream socket and therefore fail closed.
