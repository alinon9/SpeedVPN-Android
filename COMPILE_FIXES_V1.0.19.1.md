# v1.0.19.1 Compile Fixes

Fixes the three Kotlin compiler errors reported by GitHub Actions for v1.0.19:

1. `MainActivity.kt`: use the named `AnnotatedString(text=..., spanStyles=...)` constructor arguments.
2. `Socks5Server.kt`: use the `DatagramPacket(..., SocketAddress)` overload for UDP forwarding.
3. `SpeedVpnService.kt`: load `VpnCompatibilitySettings` inside data-plane recovery before writing the tunnel config.

No VPN architecture or UI behavior was intentionally changed beyond these compile fixes.

Local full Android build could not be executed in this environment because the Android/Gradle build dependencies are not locally available.
