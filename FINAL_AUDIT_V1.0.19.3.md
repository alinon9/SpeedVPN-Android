# SpeedVPN v1.0.19.3 — Final pre-upload audit

## Applied fixes
- Removed the manual 32 KiB TCP send/receive buffer override from the local SOCKS upstream sockets.
- Fixed the local authenticated SOCKS health probe: it now completes the handshake and validates an explicit unsupported-command response instead of waiting for a request that was never sent.
- Kept RFC 1928 UDP source-IP pinning while tracking the latest source port for replies.
- Switched UDP `DatagramPacket` construction to the Android-compatible `(byte[], offset, length, InetAddress, port)` overload.
- Set hev defaults to `task-stack-size: 86016`, `tcp-buffer-size: 65536`, and `udp-read-write-timeout: 60000`, matching the current upstream configuration guidance.
- Preserved the conservative 64-session / 192-worker Android relay cap rather than multiplying it to 256/768 threads. The dedicated health-probe worker remains outside this cap.
- Preserved `ERROR` runtime state across `onDestroy()` and reset traffic rates to zero on failure.
- Clear stale `ERROR` state when a new CONNECT attempt begins.
- Dashboard 401/403 session loss now signs out and stops the agent instead of retrying forever; transient 429/5xx token-refresh failures no longer erase credentials.
- Remote dashboard speed limits are persisted to the same local limit store used by the UI.
- VPN permission alert moved to a dedicated high-importance notification channel.
- CONNECT button is disabled during CONNECTING/DISCONNECTING and the latest error is visible on the home screen.
- Slider now reserves a clear sentinel for unlimited, with custom rates from 128 Kbps through 100 Mbps.
- Version metadata is `versionCode 21` / `versionName 1.0.19.3`.

## Deliberately unchanged
- IPv6 remains enabled by default; DNS automatically becomes IPv4-only when IPv6 is disabled.
- `setUnderlyingNetworks()` is not added blindly; the current code protects outbound sockets and dynamically tracks a usable non-VPN network.
- Authentication tokens remain in normal SharedPreferences for this release. Encrypted storage can be treated as a separate security-hardening change.

## Verification
- Source-tree static inspection and targeted consistency checks performed locally.
- Full Android/NDK compilation still must be confirmed by GitHub Actions.
- Final functional verification still requires installation on a real Android device, especially WhatsApp, Facebook Lite, ChatGPT, DeepSeek, QUIC sites, and speed-limit accuracy.
