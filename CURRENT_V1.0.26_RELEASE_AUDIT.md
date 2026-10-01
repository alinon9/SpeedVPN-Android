# SpeedVPN v1.0.26 — Current Release Audit

This file describes the state of the source tree in the repair build, not historical versions.

## Applied repairs

- UDP candidate fallback no longer retargets a live `DatagramChannel`. Each selected remote endpoint owns an immutable binding/channel; stale selector events are rejected by an identity check before relay delivery.
- DNS lookups now use a small bounded, fail-fast worker pool. A blocked `Network.getAllByName()` call can consume a worker until the platform returns, but new requests are rejected rather than queued behind an unbounded backlog.
- Stats batches are immutable while awaiting acknowledgement. Traffic observed after a batch is frozen is accumulated separately and gets a new `stats_batch_id`; this prevents a failed local `SENT` write from reusing one id with different byte totals.
- Health probes run outside `stateMutex`. The lock is held only for state snapshots, validation, state publication, and serialized recovery.
- MainActivity loads authentication state on `Dispatchers.IO`, avoiding synchronous Keystore/SharedPreferences work during UI startup.
- Release workflow action versions were aligned with the current official action major versions used by the build workflow.

## Deliberately not changed without device evidence

- `MAX_UDP_ASSOCIATIONS = 24`: raising this changes a resource limit but is not proven necessary by static analysis.
- `MAX_WORKERS = 192`: lowering it could make the existing 80-session / two-direction TCP model reject otherwise valid sessions.
- Network handoff behavior: existing upstream sockets are closed on physical-network changes; this is a compatibility trade-off, not a safe socket-migration primitive.
- App traffic counters: Android `NetworkStatsManager` remains session accounting, not packet-perfect VPN/TUN accounting.
- DNS startup does not invent a public fallback resolver. The VPN continues to use DNS servers published by the current physical link.

## Release verification still required

The container used for this repair does not have an Android SDK/NDK or a connected Android device, so these gates were not truthfully marked as passed:

1. `:app:testDebugUnitTest`
2. `:app:lintDebug`
3. `:app:assembleDebug` / `:app:assembleRelease`
4. APK signature and 16 KB ZIP alignment
5. 16 KB ELF `PT_LOAD` alignment of all packaged native libraries
6. Real-device TCP/UDP, DNS, WhatsApp/Facebook Lite, IPv6, MTU, Wi-Fi↔cellular handoff, process-kill and Always-on VPN tests

The pinned native dependency remains external and is fetched by `setup.sh` / `setup.bat`; the source archive intentionally does not vendor the native tree.
