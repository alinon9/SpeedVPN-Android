# SpeedVPN v1.0.22 — علاج ومراجعة بعد الإصلاح

## حالة الإصدار

- Source package: `SpeedVPN-Android-v1.0.22`
- Version code: 24
- Build status in this audit environment: **Android build unverified**
- Pure-Kotlin validation: **PASS**
- XML/Shell/static regression checks: **PASS**
- Real-device WhatsApp/Facebook Lite: **NOT TESTED — needs device verification**

## إثباتات المصدر الحالية

### P0 compile blockers from v1.0.21
- `Socks5Server.kt` no longer contains a `SpeedLimiter.recordForwarded(...)` call. The forwarding call is now `bucket.recordForwarded(...)`.
- `SpeedVpnService.onDestroy()` snapshots `tunnelConfigFile` into a local before clearing the field; no out-of-scope `configFile` reference remains in `onDestroy()`.

### UDP
- `UdpFlowTable` uses synchronized access-order `LinkedHashMap`.
- Each flow has `lastSeenMs`, `lastReplyMs`, and `lastSentMs`.
- Missing destination mapping returns `null` and the caller `continue`s; it never falls back to an unrelated client endpoint.
- Domain UDP targets retain multiple resolved addresses; IPv4 is preferred when IPv6 is allowed.
- Candidate fallback occurs only when a send has remained unanswered for the configured wait interval; a successful reply prevents that fallback.
- A flow's selected candidate is preserved across re-resolution when the same address remains present.

### Physical network
- Upstream TCP sockets call `Network.bindSocket(socket)` before `connect()`.
- Upstream UDP sockets are created unconnected, bound to the current `Network`, then protected and locally bound.
- Physical-network changes close stale upstream sockets and update `VpnService.setUnderlyingNetworks()` when the TUN exists.

### Session accounting
- `TokenBucket` stores one atomic `SessionCounter(generation, bytes)`.
- `recordForwarded()` ignores stale generations for session counters.
- Data-plane recovery stops native without ending limiter-session ownership and starts native with `preserveLimiterSession=true`.

### Firewall
- `addDisallowedApplication()` is used only as an application exclusion from the VPN.
- The app refuses to establish the VPN when firewall rules are requested but Android Lockdown is not active.
- Changes to the blocked-package set while connected request VPN re-establishment so Builder rules are applied to a new VPN interface.
- This remains subject to Android Always-on VPN + Lockdown; exclusion alone is not a standalone Internet kill switch.

### App traffic
- `NetworkStatsManager` remains the source for per-app usage. It is presented as Android-recorded session usage, not packet-perfect TUN accounting.
- AppStats refresh is 60 seconds.
- The app list no longer has a hard `take(30)` cap.

### CI/tests
- Unit tests were added for UDP flow TTL/LRU/candidate fallback and session-generation isolation.
- CI invokes unit tests, lint, debug build, release build, and APK/native alignment checks.
- Full Android/NDK build was not run in this environment because Android SDK/NDK and installed Gradle are unavailable and the source package intentionally has no wrapper JAR yet.

## Outstanding release gates

1. Run `./gradlew :app:assembleDebug` or `gradle :app:assembleDebug` on a machine with Android SDK/NDK.
2. Run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lintDebug`.
3. Run `connectedDebugAndroidTest` on an Android device/emulator.
4. Install the resulting APK on a real phone and test WhatsApp, Facebook Lite, ChatGPT, DeepSeek, DNS modes, network handoff, and firewall Lockdown behavior.
5. Verify APK ZIP alignment and every native ELF PT_LOAD segment at 16 KB.

## Static validation performed here

- Manifest XML parse: PASS
- `gradlew` shell syntax: PASS
- `setup.sh` shell syntax: PASS
- Regression grep for known v1.0.21 compile/fallback patterns: PASS
- No `take(30)` remains in app source: PASS
- Pure Kotlin tests: PASS
