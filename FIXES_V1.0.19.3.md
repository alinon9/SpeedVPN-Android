# SpeedVPN v1.0.19.3 — Final fixes before GitHub upload

## Core fixes
- Removed the manual 32 KiB TCP send/receive buffer override from upstream SOCKS sockets.
- Fixed the local authenticated SOCKS health probe so it completes SOCKS5 auth and validates an explicit `0x07 Command not supported` reply.
- Kept UDP source-IP pinning while updating the reply destination to the latest source port.
- Replaced the Android-incompatible 4-argument `DatagramPacket(..., SocketAddress)` construction with the portable `(byte[], offset, length, InetAddress, port)` overload.
- Set hev `task-stack-size` to `86016` with explicit `tcp-buffer-size: 65536`.
- Set hev UDP read/write timeout to `60000` ms.
- Kept the local relay at 64 sessions / 192 workers rather than multiplying a blocking-thread architecture to 256 / 768 workers.

## Lifecycle and dashboard fixes
- Preserve `ERROR` after service destruction and clear traffic rates on connection failure.
- Clear stale `ERROR` state when a new CONNECT request begins.
- Persist remote dashboard speed limits into the same local limit store used by the UI.
- Stop dashboard-agent retry loops on authentication/session loss (401/403); transient token-refresh 429/5xx errors do not erase credentials.
- Put the VPN-permission alert on a dedicated high-importance notification channel.
- Disable the main connect button while CONNECTING/DISCONNECTING and show the latest error on the home screen.
- Make the speed slider use an explicit unlimited sentinel beyond 100 Mbps.

## Compatibility and release metadata
- IPv6 remains enabled by default; DNS automatically becomes IPv4-only when IPv6 is disabled.
- MTU choices remain 1280 / 1400 / 1500, with 1280 as the conservative default.
- Version: `1.0.19.3` / versionCode `21`.

## Verification limit
A complete Android/NDK build is not performed in this environment. GitHub Actions remains the authoritative compile/package check, followed by testing on a real device.
