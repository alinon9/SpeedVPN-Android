# SpeedVPN v1.0.26 final hardening

## Applied
- Startup DNS selection exceptions are now routed through `failLocked()`, guaranteeing relay/resource cleanup and an explicit ERROR state instead of leaving startup in CONNECTING.
- UDP reply correlation now fails closed when a remote IP:port maps to multiple logical flows. The previous most-recently-active heuristic could misdeliver a UDP reply.
- Added regression coverage for ambiguous UDP endpoint collisions and unique endpoint correlation.
- README version/highlights updated to v1.0.26.

## Not fabricated
- `gradle-wrapper.jar` is still absent because the build environment has no outbound DNS/network access to retrieve the official Gradle 8.11.1 wrapper binary. The repository retains the official published SHA-256 and no unverified binary was inserted.
- Full TUN E2E testing still requires a real Android device/emulator with the native tunnel and network transitions.
