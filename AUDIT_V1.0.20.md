# SpeedVPN v1.0.20 — Static Audit & Implementation Review

## Scope

This audit covers the source package prepared from v1.0.19.3 and the requested v1.0.20 changes:

- low-speed controls down to 10 KB/s;
- 50 KB/s and 100 KB/s presets plus the existing higher presets;
- logarithmic speed slider up to 100 MB/s and Unlimited;
- VPN-session download/upload/total traffic counter;
- per-app traffic display during a VPN session;
- per-app Internet block list with Android lockdown gating;
- IPv6-aware TCP/UDP destination resolution for compatibility;
- hardening of the Android VPN/relay path without changing the process-wide generation model.

## Implemented

### Speed control

The UI uses byte-rate units while the existing limiter/API representation remains Kbps. Conversion is `1 Kbps = 125 bytes/s` and the minimum selectable rate is 10 KB/s (80 Kbps).

Presets: 10K, 25K, 50K, 100K, 128K, 256K, 512K, 768K, 1M, 2M, 4M, 6M, 10M, 20M, Unlimited.

### Traffic counters

`SpeedLimiter` records bytes actually forwarded successfully in each direction. `SpeedVpnService` snapshots the totals at VPN connect and exposes session deltas through `VpnRuntime`.

### Per-app traffic

`AppTrafficManager` uses Android `NetworkStatsManager` with user-granted Usage Access to query UID-level traffic for mobile, Wi-Fi, and Ethernet during the current VPN session, then maps visible launcher apps to their UIDs.

The UI explicitly states that Android network-stat buckets can lag; these values are not claimed to be packet-perfect real-time counters.

### App Internet control

The block list is persisted locally. Real blocking uses the Android VPN lockdown model only when `VpnService.isLockdownEnabled` reports active lockdown. Without lockdown, the app deliberately keeps the normal all-app VPN routing so `addDisallowedApplication()` is never misused as a firewall bypass.

The current allowlist is limited to launcher-visible applications. System/background-only packages may follow Android's own VPN/system-app exceptions and are not treated as user-selectable firewall targets.

### Compatibility

The SOCKS5 resolver now filters IPv6 destinations when IPv6 is disabled, for both TCP and UDP domain resolution. IPv6 UDP address type is rejected when IPv6 is disabled. The VPN DNS list also excludes IPv6 DNS in that mode.

### Firewall robustness fix

When Android lockdown is active, allowed packages are added defensively and the code counts successful `addAllowedApplication()` calls. If none succeed, the service falls back to the proven normal all-app VPN route rather than risking an accidental deny-all configuration.

## Static validation performed

- XML parse of `AndroidManifest.xml`: PASS.
- YAML parse of `.github/workflows/build-apk.yml`: PASS.
- Shell syntax (`setup.sh`, `gradlew`): PASS.
- Duplicate Kotlin/Java import scan: PASS.
- Kotlin/Java delimiter-balance scan: PASS.
- Version metadata (`1.0.20`, versionCode 22): PASS.
- `PACKAGE_USAGE_STATS` declaration: PASS.
- Session reset action and session byte fields: PASS.
- IPv6 TCP/UDP guards and IPv6 DNS filtering: PASS.
- 10 KB/s slider floor and requested presets: PASS.
- Manual 32 KB TCP socket buffer settings absent: PASS.
- SOCKS5 authenticated health-probe path present: PASS.
- Native tunnel pin remains `hev-socks5-tunnel` 2.18.0 at commit `d9dca26c7ad0e494492244f0309e80ee583e739e`: PASS.

### Kotlin parser check

The local `kotlinc` parser was run against the Kotlin sources. It exited non-zero because the sandbox has no Android SDK/AndroidX classpath, producing expected unresolved `android`, `androidx`, and coroutine references. No Kotlin syntax-error markers (`expecting`, `unexpected tokens`, `unclosed`, etc.) were produced.

## Build/runtime limitation

A complete Android/NDK compile was not run in this sandbox because the Android SDK, AndroidX dependencies, and NDK toolchain are not installed here. The repository's GitHub Actions workflow is configured to perform the authoritative build and APK verification.

The WhatsApp/Facebook Lite compatibility change is source-reviewed but cannot be called proven until the resulting APK is installed on the target phone and those applications are tested. The most useful device test matrix is:

1. IPv6 ON / DNS Auto / MTU 1280.
2. IPv6 OFF / DNS IPv4 / MTU 1280.
3. WhatsApp messaging + media.
4. Facebook Lite login/feed/media.
5. ChatGPT + DeepSeek.
6. Speed presets at 10 KB/s, 50 KB/s, 100 KB/s, 1 MB/s and Unlimited.
7. App traffic counters while each test runs.
8. Firewall test with Android Always-on + Block connections without VPN enabled.

## Conclusion

The source package passes the static checks above and includes the requested v1.0.20 feature set. It is **not** labeled runtime-error-free or stable yet because the full Android/NDK build and real-device compatibility tests remain external to this sandbox.
