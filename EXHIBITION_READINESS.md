# SpeedVPN — Exhibition Readiness

## Candidate and scope

- App version in Gradle: **1.1.1** (versionCode 30).
- This checklist applies to the source snapshot used for PR #59, plus the small UDP endpoint-index hardening change in this branch.
- This is an exhibition candidate checklist, not a claim that every release gate or physical-device test has passed.

## Evidence from the current CI baseline

The successful Build APK run [40175906950](https://github.com/alinon9/SpeedVPN-Android/actions/runs/40175906950) built Debug and Release APKs and completed the emulator smoke job.

The successful parts of the full emulator run [40175906957](https://github.com/alinon9/SpeedVPN-Android/actions/runs/40175906957) include:

- Focused TCP/UDP accounting test: **10/10 passes**.
- Complete JVM unit suite: **2/2 passes**.
- Speed Test against the public Cloudflare endpoint returned HTTP 200 for tested download and upload samples up to 5 MB in the recorded emulator smoke log.
- Verify Speed's strict low-speed check passed for both directions: 800 Kbps plan, measured VPN download 799 Kbps and upload 790 Kbps.
- Separate-UID TCP and UDP app-policy scenario passed: blocking one fixture app did not stop the other fixture app; unblocking restored traffic.
- The one-megabyte quota scenario truncated the target app's 2 MB transfer at the quota boundary, persisted the block after service restart, and continued allowing the unrelated fixture app.
- Automated checks for usage history, stats toggle, and XLSX export exist in the Python regression suite; these are code-level/fixture checks, not a claim that every export path was tested on a physical phone.

## Remaining gates — do not describe these as passed

The strict 19-plan × 2-direction speed sweep did **not** complete. The CI runner's upload preflight median was 67.80 Mbps, below the required 105.60 Mbps headroom for the top 11 MB/s plan. The workflow correctly marked this `ENV_INSUFFICIENT` and left the 38/38 gate blocked; this is inconclusive for the full limiter sweep.

No physical Android phone test is recorded for this candidate. In particular, test app blocking on the actual presentation phone before demonstrating it against everyday apps. The emulator results do not prove that every device's UID attribution, DNS, IPv6, MTU, and network handoff behavior is correct.

## Suggested exhibition run-through

1. Open the app while disconnected and confirm the main screen loads without an error.
2. Connect the VPN and confirm the status becomes Connected and ordinary browsing still works.
3. Run Speed Test once; repeat only if the network is stable. Explain that measurements depend on the venue's Wi-Fi/mobile connection.
4. Use Verify Speed with a conservative preset first. Show one limited-speed example before attempting Unlimited.
5. Demonstrate app blocking with a non-critical test app on the presentation phone; immediately confirm a separate allowed app still has internet, then unblock the test app.
6. If showing quota enforcement, apply a small quota only to the test app, verify the block, and remove/reset the test policy afterwards.
7. If showing statistics, generate live traffic first, open Usage History, export XLSX, and confirm the file can be opened.
8. Disconnect cleanly and confirm normal internet connectivity is restored.

## Small hardening change in this branch

The UDP flow table previously formed endpoint keys from the display string `InetAddress.hostAddress`, which is nullable to Kotlin on Android and can vary in IPv6 scope/presentation formatting. The new implementation derives the key from address-family length, raw IP bytes, IPv6 scope ID, and port without performing DNS or relying on text formatting. Regression tests cover flow-table cleanup between sessions and scope-aware IPv6 endpoint identity.

## Release recommendation

Use this as a **candidate build** only after the PR checks pass and a hands-on test is completed on the exact phone that will be used at the exhibition. Do not claim the exhaustive speed-plan sweep or physical-device compatibility until those checks have actually passed.
