## v1.0.14 follow-up fix

- Re-audited the uploaded v1.0.13 source independently.
- Fixed a real generation race in `SpeedVpnService` STOP handling: the expected generation was checked before `stateMutex`, but a newer CONNECT could replace `serviceGeneration` before the queued disconnect ran.
- STOP now passes the expected generation into `disconnect()` and re-validates both the instance generation and process-wide active generation while holding `stateMutex`; stale STOP commands therefore cannot disconnect a newer VPN generation.
- Version bumped to 1.0.14 / versionCode 15.


## v1.0.13 follow-up fix
- Fixed stale Service-instance cleanup reading the process-wide `nativeGeneration` and potentially targeting a newer VPN generation. Cleanup now uses only the instance-owned generation.
# SpeedVPN 1.0.3 patch

Implemented after independent source reviews.

## Runtime and VPN lifecycle
- Serialized Connect/Disconnect/Health Recovery through the same Mutex.
- Session IDs invalidate stale asynchronous work.
- Native Start/Stop/IsRunning calls share a lifecycle lock.
- onDestroy never waits on the state mutex or runs native teardown on the main thread; it performs best-effort emergency cleanup on a daemon thread.
- Connected state now requires both the native engine and the authenticated local SOCKS relay to respond. This is a control/data-plane-local check, not a claim that external Internet reachability has been verified.

## SOCKS5 security
- Local SOCKS5 now requires RFC1929 username/password authentication.
- Credentials are generated randomly for every VPN session and written only to the app-private tunnel.yml.
- TCP RSV and SOCKS method negotiation are validated.
- TCP relay preserves half-close semantics instead of immediately closing both directions on EOF.
- UDP source association is pinned to the first authenticated local sender for the lifetime of the association.
- Worker/session caps prevent unbounded thread growth.

## Speed limiting
- Replaced sleep-under-monitor token scheduling with a fair ReentrantLock/Condition pacing scheduler.
- Global download/upload limits remain shared across all flows.
- Rate changes signal waiting flows immediately.
- Counters record only bytes successfully forwarded.

## Network/DNS
- No hard-coded public DNS servers in VpnService.Builder.
- Domain resolution selects a current non-VPN physical Network on each lookup.

## Native/build
- hev-socks5-tunnel pinned to tag 2.18.0 / commit d9dca26 in both setup scripts.
- Android NDK updated to 30.0.16248370 (r30 LTS).
- AGP updated to 8.9.2 with Gradle 8.11.1 for API 35.
- CI verifies native pin, builds debug APK, checks APK zipalign, and checks native ELF LOAD alignment at 0x4000.
- Release build is no longer signed with the debug key by default. A real release keystore should be configured before publishing.

## Dashboard stats
- AgentService keeps pending unsent byte deltas in app-private SharedPreferences so a service/process restart does not replay an entire previous lifetime total.

## Validation limits
- Android/NDK build was not executed in this container.
- A real Android device/runtime test is still required for final approval, especially VPN traffic continuity, Wi-Fi/LTE switching, UDP/QUIC, and OEM battery behavior.


# v1.0.4 patch set applied
- Gradle 8.11.1 is now consistent in wrapper properties, CI, and build execution; distribution SHA-256 is pinned.
- Added self-bootstrapping gradlew/gradlew.bat. The official Gradle 8.11.1 wrapper JAR is downloaded only when missing and SHA-256 verified before execution.
- CI exports ANDROID_NDK_HOME and ANDROID_NDK_ROOT explicitly.
- CI checks for all expected native libraries and validates every ELF PT_LOAD segment for 16 KB alignment.
- Physical-network selection now prefers the app's current default network (SpeedVPN is excluded from the VPN), refreshes on default-network changes, and excludes VPN networks.
- Agent stats keep a persisted stats_batch_id for retry-safe client delivery; backend idempotency is still required for exactly-once accounting.
- Native pinning uses the full 2.18.0 commit SHA d9dca26c7ad0e494492244f0309e80ee583e739e in both setup scripts.
- Version bumped to 1.0.4 / versionCode 5.

Validation limit: Android SDK/NDK build was not executed in this container.


# v1.0.5 lifecycle/build patch

- Added the missing `kotlin.concurrent.thread` import required by `SpeedVpnService.onDestroy()`.
- Replaced native start call sites with `startNativeIfCurrent(...)`, atomically guarding session/destruction state with the native lifecycle lock.
- Moved `engineRunning = true` into the guarded native-start helper and centralized `engineRunning = false` in `stopNative()`.
- Prevented stale recovery/start attempts from being converted into a new failure after a disconnect/destroy.
- Bumped app version to 1.0.5 / versionCode 6.
- The official Gradle 8.11.1 wrapper JAR is intentionally not fabricated or replaced with an unverified binary in this environment; GitHub CI keeps checksum-verified bootstrap of the official JAR when absent.


## v1.0.6 review fixes applied
- Health probe now uses a source-port reservation recognized by the SOCKS accept loop, so it bypasses the user-session semaphore and remains testable at MAX_SESSIONS=24.
- SOCKS session admission remains capped at 24 user sessions; the health probe does not consume a user-session permit.
- Recovery fresh-relay publication is atomic with session/resource validity; a stale fresh relay is stopped immediately instead of being orphaned.
- TUN and SOCKS resource publication/detachment are serialized with a dedicated resource lifecycle lock to prevent post-destroy resource ownership races.
- All direct engineRunning=false writes outside native lifecycle methods were removed; native state mutation remains centralized under nativeLifecycleLock.
- SpeedLimiter no longer pre-reserves future pacing time for waiters. A waiter only advances nextAvailableNs when its turn is actually granted, preventing large phantom pacing debt after interruption/cancellation.
- SpeedLimiter schedulers are reset after SOCKS worker shutdown so a torn-down relay cannot delay a later session with stale pacing debt.
- Version bumped to 1.0.6 (versionCode 7).


## v1.0.7 hardening applied
- Replaced the instance-local native lifecycle lock with a process-wide lock shared by all SpeedVpnService instances.
- Added a process-wide native generation/owner token so stale asynchronous cleanup cannot call TProxyStopService() against a newer generation.
- Added owner-aware native status checks and native start/stop sequencing.
- Moved global SpeedLimiter scheduler ownership into SpeedLimiter.beginSession()/endSession(); removed resetScheduler() from Socks5Server.stop().
- Queued CONNECT arriving during DISCONNECTING and launched it after disconnect cleanup completes.
- Bumped app version to 1.0.7 / versionCode 8.

Verification note: Android SDK/NDK/ADB were not available in the editing environment, so this patch has not been Android-build/runtime verified here.


## v1.0.8 process-wide ownership hardening
- Added a process-wide active Service generation to invalidate older Service-instance data-plane work immediately when a newer instance begins a connection.
- Tied Native ownership to the active service generation and re-checks generation validity before process-wide TProxy operations.
- Added generation-aware health checks and runtime publication to stop stale instances from recovering or overwriting newer state.
- Stale Connect/Recovery paths clean their own TUN/SOCKS resources.
- Removed global SpeedLimiter reset responsibility from Socks5Server.stop(); scheduler reset is controlled by the active VPN generation.
- CONNECT during DISCONNECTING is queued and started after cleanup.
- Added safe tunnel-config write failure handling.
- Bumped app version to 1.0.8 / versionCode 9.

Verification note: Android SDK/NDK/ADB were not available in the editing environment, so this patch has not been Android-build/runtime verified here.


## v1.0.9 forensic fixes
- Fixed AgentService CONNECT state handling so DISCONNECTING is preserved and reconnect is queued by SpeedVpnService.
- Hardened SpeedLimiter API rate conversion against non-finite values and Long overflow.
- Bound SOCKS5 relay pacing to its owning VPN generation so stale relays cannot consume a newer generation's global scheduler.
- Bumped app version to 1.0.9 / versionCode 10.


## v1.0.10 pre-install safety fixes

- Bumped app version to 1.0.10 / versionCode 11 after post-v1.0.9 re-audit changes.
- `AgentService`: a rejected CONNECT start no longer overwrites an active/newer VPN generation with `ERROR`.
- `SpeedVpnService`: the new VPN generation claims the global SpeedLimiter before Native startup, eliminating the startup window where the new relay could be active while its limiter generation was inactive.
- Native generation takeover now invalidates the previous limiter generation before replacing the process-wide Native owner.


## v1.0.11 final pre-install audit fixes

- Added request-scoped CONNECT ownership in AgentService so a stale CONNECT waiter cannot treat a newer VPN generation as its own or stop that newer generation on timeout.
- Added generation-guarded DISCONNECT requests so stale dashboard commands cannot stop a newer VPN generation.
- Added a mutex around access-token refresh to prevent concurrent heartbeat/stats/command refresh races from invalidating rotated refresh tokens.
- Removed device ID from registration logs.
- Bumped app version to 1.0.11 / versionCode 12.

Verification note: Android SDK/NDK/ADB were not available in the editing environment, so Android build/runtime/ELF verification remains pending.


## v1.0.12 final audit fix
- Authentication generation invalidates in-flight sign-in/refresh responses after sign-out, preventing stale responses from repopulating credentials.
- Sign-in is serialized with token refresh.
- Version bumped to 1.0.12 / versionCode 13.


## v1.0.16 — process-wide Native safety hardening
- Native lifecycle remains process-wide, but `onDestroy()` no longer waits on the process-wide Native monitor on the Main thread.
- Native liveness queries are now tri-state (`RUNNING`, `STOPPED`, `UNKNOWN`). Unknown is fail-closed for teardown/takeover: the app will not start a replacement engine or close a TUN whose Native ownership cannot be confirmed.
- Native takeover is refused when a previous generation is still running or when its state cannot be determined.
- Recovery now requires confirmed Native shutdown before starting a replacement engine.
- Health/ready checks treat an indeterminate Native status as retryable instead of triggering destructive recovery.
- `onDestroy()` uses atomic/volatile generation snapshots and CAS instead of waiting for Native lifecycle lock ownership.
- Version bumped to 1.0.16 / versionCode 17.
