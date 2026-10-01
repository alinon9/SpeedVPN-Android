# SpeedVPN v1.0.21 — Final hardening patch

## Critical fixes

1. **Real app blocking under Android Lockdown**
   - Removed the previous allowlist/fallback design.
   - `SpeedVpnService.kt` now calls `addDisallowedApplication()` for each blocked package.
   - If the firewall is enabled but Android Lockdown is not active, VPN startup fails closed with an explicit error instead of silently bypassing the block list.

2. **WhatsApp/UDP compatibility**
   - Fixed an actual UDP source-address comparison bug where `InetAddress` objects were compared by reference (`!=`) instead of value (`equals`). This could discard valid subsequent UDP packets.
   - Kept IPv4-first resolution with IPv6 fallback.
   - Reduced UDP buffer sizes to avoid excessive per-association memory pressure.

3. **TCP compatibility/performance**
   - Added a 12-second overall connect deadline with a maximum 5 seconds per address candidate.
   - Reused the SOCKS handler thread for one TCP relay direction and only one pool worker for the reverse direction, reducing normal TCP worker cost from 3 to 2 per connection.

4. **Traffic counter correctness**
   - Added generation-scoped session counters so stale traffic from a previous VPN generation cannot be counted in the new session totals.

5. **Token storage**
   - Access/refresh tokens are now AES-GCM encrypted with an Android Keystore key.
   - Legacy plaintext tokens are migrated on first read and removed.

6. **App list usability**
   - Removed the artificial 30-app display cap.
   - Added package/name search.

## Deliberate platform limitation

Android's public `VpnService.Builder.addDisallowedApplication()` excludes an app from the VPN; the excluded app can normally use the underlying network. Therefore SpeedVPN requires system Always-on VPN Lockdown before declaring an app **blocked**. Without Lockdown, the app refuses to start with the firewall enabled instead of pretending the block is effective.

## Validation

Static validation is performed after patching. Full Android/NDK runtime validation still requires GitHub Actions and an actual Android device.
