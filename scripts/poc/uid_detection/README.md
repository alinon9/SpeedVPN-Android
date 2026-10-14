# UID Detection POC — isolated branch

**Branch:** \`poc/uid-detection-test\`  
**Target:** pinned \`hev-socks5-tunnel 2.18.0\` commit \`d9dca26c7ad0e494492244f0309e80ee583e739e\`  
**State:** instrumentation added; device result matrix is intentionally pending until this APK is run with an active VPN on Android 10+.

## License audit

- SpeedVPN root \`LICENSE\`: proprietary / “All rights reserved”; redistribution or modification is not granted without prior written permission.
- \`hev-socks5-tunnel\` at the pinned commit: MIT.
- Pinned tunnel submodules: \`hev-task-system\`, \`hev-socks5-core\`, and \`yaml\` carry MIT-style licenses; \`lwIP\` carries a BSD-3-Clause-style license.
- App Gradle declarations include AndroidX libraries, Kotlin coroutines, and OkHttp (Apache-2.0); JUnit 4 and Robolectric are test dependencies with their own licenses. This is a source-level spot audit, not an exhaustive generated inventory of every transitive artifact.
- NetGuard is GPL-3.0. Do not copy or adapt its implementation into this current all-rights-reserved codebase unless the licensing strategy is deliberately changed and reviewed. A clean independent implementation is not required to continue using the pinned MIT-licensed hev engine.

## What this POC instruments

1. In the pinned native \`lwip_io_task_entry\`, immediately after a packet is read from TUN, parse the packet's IPv4/IPv6 source and destination addresses, transport protocol, and ports.
2. Send only the tuple to a temporary JNI callback. The packet is not modified and no block/allow decision is made.
3. Kotlin uses a bounded worker queue so \`getConnectionOwnerUid()\` is not called synchronously on the native TUN loop.
4. Results are written to Logcat with tag \`UidDetectionPOC\`. The log includes the exact five-tuple, returned UID, package names for that UID, and whether it matches the common target apps' PackageManager UIDs.
5. Clear the JNI callback at tunnel shutdown. The hook exists only in this branch and is inserted into the downloaded pinned checkout by \`setup.sh\`.

## Build and run

\`\`\`bash
git checkout poc/uid-detection-test
./setup.sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
\`\`\`

Start SpeedVPN and grant the Android VPN consent dialog, then generate traffic in Chrome, WhatsApp, and YouTube (or replace \`com.google.android.youtube\` in \`UidDetectionPoc.kt\` with an installed third app). Capture:

\`\`\`bash
adb logcat -c
adb logcat -s UidDetectionPOC:I
\`\`\`

To record expected UIDs, use the \`EXPECTED package=... uid=...\` lines emitted from PackageManager. Each \`RESULT\` line records:
\`protocol|srcIp|srcPort|dstIp|dstPort\`, the returned UID, UID-to-package mapping, and match verdict.

## Known POC coverage boundaries

- API below 29 is explicitly unsupported.
- IPv4 non-initial fragments and IPv6 extension-header packets are skipped in this first diagnostic parser.
- Only IPv4/IPv6 TCP and UDP are sent to UID lookup; ICMP is out of scope.
- A \`Process.INVALID_UID\` result is retried up to four times with rate limiting; it is not treated as a crash or as proof that UID filtering is impossible.
- DNS may be owned by Android's resolver/system UID rather than the foreground app; record the observed UID and do not assume Chrome UID in advance.
- This is diagnostics only. It must not be used as enforcement logic or merged to \`main\`.

## Test matrix

No device or emulator has executed this POC from this workspace yet. Do not infer PASS from a successful compile.

| # | Case | Expected UID | Extracted UID | Five-tuple | Status |
|---|---|---|---|---|---|
| 1 | Chrome TCP | Fill from \`EXPECTED\` log | Not run | Not captured | ⚠️ Pending device run |
| 2 | WhatsApp TCP | Fill from \`EXPECTED\` log | Not run | Not captured | ⚠️ Pending device run |
| 3 | Third-party app TCP | Fill from PackageManager / device output | Not run | Not captured | ⚠️ Pending device run |
| 4 | Chrome UDP/DNS | Chrome UID is a comparison target; resolver UID may differ | Not run | Not captured | ⚠️ Pending device run |
| 5 | IPv6 TCP | Same app's expected UID | Not run | Not captured | ⚠️ Pending IPv6-capable device/network |

## Gate for option A

Do not spend 3–5 weeks on full UID filtering until a physical Android 10+ device (preferred) or API 30+ emulator completes cases 1–5 and confirms how often the API returns the target app UID versus \`INVALID_UID\`/resolver UID. A successful build proves JNI compilation only, not runtime UID discovery.
