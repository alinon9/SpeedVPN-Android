# SpeedVPN v1.0.21 — Final Static Audit

## Scope
- Inspected the complete ZIP source tree: 35 files.
- Reviewed all application Kotlin/Java sources: 3,603 lines.
- Reviewed Gradle, Manifest, JNI makefiles, GitHub Actions, setup scripts, resources and documentation.
- Static validation was run after the fixes.

## Fixes applied

| Severity before | Location | Problem | Resolution |
|---|---|---|---|
| Critical | `SpeedVpnService.kt:264-285` | App firewall could fail-open into normal full-VPN routing when allowlist installation failed. | Replaced the allowlist strategy with a direct blocked-package list and require Android VPN Lockdown. Connection fails closed if Lockdown is unavailable. |
| High | `Socks5Server.kt:453-480` | UDP source validation used object identity and could reject valid packets from the same source IP. | Uses `InetAddress.equals()` for source-IP validation and does not pin the UDP source port. |
| High | `Socks5Server.kt:466,523-527,554-555` | One `clientEndpoint` could lose replies when a client rotated UDP source ports / used multiple remote flows. | Added destination-flow → client-endpoint mapping with bounded 256-entry table and fallback to latest endpoint. |
| High | `Socks5Server.kt:353-398` | TCP session used an unnecessary third worker. | TCP now keeps the upload direction on the handler and uses one worker for reverse traffic. |
| High | `Socks5Server.kt:309-333` | TCP connection timeout could multiply by the number of resolved addresses. | Added a 12s overall deadline with a maximum 5s per candidate. |
| High | `SpeedLimiter.kt:75-170` | Session totals could receive late bytes from an older VPN generation. | Added generation-aware session counters and reset/end ownership checks. |
| High | `SpeedVpnService.kt:392` | Native UDP receive buffer was unnecessarily large for a mobile profile. | Reduced HEV `udp-recv-buffer-size` from 524288 to 262144 bytes. HEV documents this field as the UDP socket receive buffer. |
| High | `Api.kt:15-112` | Auth tokens were stored in plaintext SharedPreferences. | Added Android Keystore AES-GCM storage with migration and deletion of legacy plaintext values. |
| Medium | `MainActivity.kt:452-470,528-554` | App list refreshed too frequently and had an artificial 30-app cap. | Refresh interval increased to 20s, removed cap, and added search. |
| Medium | `SpeedVpnService.kt` cleanup paths | Per-session tunnel config could remain on disk after teardown. | Config path is generation-scoped and deleted during cleanup. |
| Medium | `SpeedVpnService.kt` | DNS/IPv6 compatibility behavior needed tighter coupling. | IPv6-disabled mode automatically filters IPv6 DNS servers; explicit IPv4-only DNS mode remains available. |

## Traffic meters

- VPN session traffic uses the actual bytes successfully forwarded through the SOCKS relay.
- Per-app traffic uses Android `NetworkStatsManager` UID accounting. It is intentionally labelled as app/session usage rather than packet-perfect TUN accounting because Android may update accounting buckets with delay.

## App blocking limitation

`VpnService.Builder.addDisallowedApplication()` excludes an application from the VPN. By itself that application may continue to use the underlying network. Therefore SpeedVPN now requires Android Always-on VPN + Lockdown when the in-app firewall is enabled, so excluded/blocked applications cannot bypass the VPN. The app does not attempt to enable system Lockdown programmatically because ordinary VPN applications do not have device/profile-owner control over that setting.

## WhatsApp / Facebook Lite compatibility work

The code now addresses the strongest concrete compatibility defects found in the previous audit:
- UDP source-IP comparison bug.
- UDP source-port rotation handling.
- Per-destination UDP reply mapping.
- UDP IPv6 filtering consistent with the selected mode.
- TCP connect deadline and fallback behavior.
- Separate generation-aware traffic accounting so stale sessions cannot contaminate the current session.

These changes improve compatibility, but WhatsApp/Facebook Lite cannot be declared fixed without installing the new APK and testing real traffic on the target phone. Static inspection cannot prove application-level compatibility.

## Validation completed

- ZIP/source path validation: PASS
- Manifest XML parse: PASS
- GitHub Actions YAML parse: PASS
- Shell syntax: PASS
- Kotlin/Java structural/static checks: PASS
- Version check: `1.0.21`, `versionCode 23`
- Speed preset check: 10 KB/s minimum + 25/50/100 KB/s + 128/256/512/768 KB/s + 1/2/4/6/10/20 MB/s + Unlimited
- Forbidden old firewall allowlist fallback: absent
- Old UDP reference-identity comparison: absent
- Artificial 30-app cap: absent
- Plaintext auth writes: removed from normal token paths
- Generation argument on relay traffic counters: PASS

## Build limitation

A complete Android/NDK build could not be executed in this audit environment because the Gradle distribution/Android SDK/NDK are not locally available and the environment cannot resolve/download `services.gradle.org`. GitHub Actions remains the authoritative build environment for this repository, followed by real-device testing.

## Residual risks

1. No static audit can guarantee WhatsApp/FB Lite compatibility; real-device testing is required.
2. App-level NetworkStats accounting is delayed/non-TUN-exact by design.
3. Firewall requires user-configured Always-on VPN + Lockdown on supported Android versions.
4. The SOCKS relay is thread-based; it is improved but not yet an event-driven NIO architecture.
5. Production release signing/Play-distribution policy were not part of this change.
