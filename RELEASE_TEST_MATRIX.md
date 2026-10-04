# SpeedVPN Release Test Matrix

This document deliberately separates code/CI evidence from device evidence.

## P0
- DNS leak: Wi-Fi + mobile, VPN connected, compare resolver addresses with the expected policy.
- IPv6 leak: IPv6-capable Wi-Fi + mobile, verify public IPv6 and IPv6 reachability under both IPv6 ON/OFF configurations.
- End-to-end: browser traffic through TUN -> native tunnel -> local SOCKS -> physical network.
- Speed limiter: 100 KB/s, 1 MB/s, 10 MB/s, Unlimited; use a large download and record sustained throughput.

## P1
- Quota: document that the current quota is Android UID/App Usage, not packet-exact VPN traffic.
- Handoff: Wi-Fi -> mobile -> Wi-Fi while downloading and while an interactive connection is active.
- UDP/QUIC: YouTube/HTTP3 and a real-time call where available.
- Per-app quota block: verify restart is coalesced and the user sees a reconnecting state rather than repeated restart loops.
- Crash reporting: intentionally trigger a test crash in a non-production build and verify the report arrives.

## P2
- Battery: one hour idle VPN and one hour active transfer.
- MTU: 1280/1400/1420/1500 matrix on Wi-Fi/mobile.
- OEM: Redmi/Xiaomi Android 11+ and Samsung Android 13/14+.
- Install/upgrade/uninstall/restore tests.

Record device model, Android version, network type, APK version and exact result for every test.
