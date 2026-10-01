# SpeedVPN v1.0.26 final repair follow-up

Applied to the repaired source tree:

1. Fixed `UdpFlowTableTest.kt` by keeping all UDP flow-key tests inside `UdpFlowTableTest`, restoring fixture scope and test compilation.
2. Moved MainActivity `SharedPreferences.commit()` operations onto `Dispatchers.IO` and serialized them with a coroutine `Mutex`, preventing synchronous disk I/O from blocking the UI while preserving durable writes and ordering.
3. Restored Unix executable permissions (`0755`) for `gradlew` and `setup.sh`.
4. Kept UDP reply correlation fail-closed. Two packets with the exact same observable UDP 5-tuple are intrinsically indistinguishable on a shared upstream socket; guessing would reintroduce reply misrouting.
5. `gradle-wrapper.jar` was not fabricated. It remains absent because the repair environment cannot fetch the official binary. The existing `gradlew` verifies the official SHA-256 before using a bootstrapped JAR.

Not claimed as completed:
- Real Android-device/emulator TUN end-to-end testing still requires an Android test environment.
