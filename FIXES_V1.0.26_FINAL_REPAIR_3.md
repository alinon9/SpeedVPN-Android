# SpeedVPN v1.0.26 — Final Repair 3

## Repairs in this revision

### 1. Deterministic UDP reply correlation

The previous repair used a fail-closed remote `IP:port` index. That prevented misrouting, but could legitimately drop replies when two UDP flows shared the same upstream endpoint.

The live SOCKS5 UDP path now uses:

- one connected `DatagramChannel` per logical UDP flow;
- one unique local upstream source port per flow;
- one `Selector` thread per UDP association to service all flow channels;
- candidate reconnect/fallback on the same flow channel when the selected DNS candidate does not answer;
- reply delivery based on the channel/flow that received the datagram.

This removes the production dependency on ambiguous remote `IP:port` correlation. The existing `UdpFlowTable` index is retained only for defensive/unit-test coverage.

### 2. Gradle Wrapper validation in GitHub CI

The repository intentionally keeps the official Gradle Wrapper bootstrap path:

- Gradle 8.11.1 distribution is pinned by SHA-256.
- Wrapper JAR is fetched only from Gradle's official distribution URL when absent.
- The wrapper JAR is checked against the official SHA-256 before execution.
- GitHub CI now executes builds through `./gradlew`, so the bootstrap/checksum path is exercised instead of silently bypassing the wrapper.

The current offline environment cannot download the official binary, so the JAR is not fabricated or replaced with an unverified binary. On GitHub, the wrapper bootstrap step materializes and verifies it before the build.

### 3. Unix executable permissions

The repaired archive preserves executable mode `0755` for:

- `gradlew`
- `setup.sh`

### 4. Validation performed locally

- `Socks5Server.kt` + `UdpFlowTable.kt` focused Kotlin compilation: PASS using Android/network test stubs.
- `gradlew` shell syntax: PASS.
- `setup.sh` shell syntax: PASS.
- GitHub workflow YAML parsing: PASS.
- Gradle Wrapper checksum declaration matches the official published SHA-256 for Gradle 8.11.1.
- ZIP packaging will be checked after all file changes.

## Still requiring GitHub/device execution

This repair does not claim:

- a real Android TUN-to-Internet E2E pass;
- a successful native `hev-socks5-tunnel` build in this offline environment;
- a real-device UDP/TCP forwarding test.

Those remain release verification gates.
