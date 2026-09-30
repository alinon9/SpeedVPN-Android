# SpeedVPN v1.0.19.2 — Final Pre-Build Audit

## Changes in this release
- Dedicated SOCKS health-probe executor; health checks no longer depend on data-plane worker availability.
- IPv6-disabled mode now forces IPv4-only DNS selection.
- UDP reply destination is explicitly typed as `SocketAddress` to avoid Kotlin/Android constructor inference issues.
- Version metadata bumped to `versionCode 20` / `versionName 1.0.19.2`.
- Speed slider minimum aligned with the published 128 Kbps preset.

## Verification performed here
- ZIP extraction and source-tree inspection.
- Kotlin/Java structural inspection of the touched files.
- XML structure inspection.
- No obvious TODO/FIXME/unimplemented markers in app Kotlin/Java sources.
- No secrets or private keys intentionally added by this release.

## Important limitation
A full Android/NDK build is not executed in this environment. The authoritative compile/runtime checks remain GitHub Actions plus installation/testing on an Android device.
