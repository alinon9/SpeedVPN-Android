# SpeedVPN Android

Current version: **v1.0.26** (versionCode 28)

## Overview

SpeedVPN is a local Android `VpnService` that routes device traffic through a local TUN/tun2socks path for upload/download shaping and traffic accounting. Remote dashboard control is optional.

## v1.0.26 highlights

- Fixed UDP IPv4 acceptance when IPv6 is disabled; IPv6 destinations are rejected only when IPv6 is disabled.
- Hardened UDP association resource cleanup so socket-construction failures always release the association permit and close owned resources.
- UDP upstream sockets now fail closed when no physical network is available.
- UDP upstream flows use dedicated connected `DatagramChannel` instances so replies are correlated to their owning flow without a remote-IP:port recency heuristic; ambiguous table lookups remain defensive only.
- Release CI verifies/bootstraps the pinned Gradle Wrapper before fetching the pinned native tunnel, and both build and release use `./gradlew`.
- Release CI artifact/source-archive names are derived from the current versionName.

## v1.0.25 highlights

- Removed the unsafe generic DNS fallback; hostname resolution is bound to the current physical `Network` and included in the 12-second outbound connection deadline.
- Persisted speed limits are restored by `SpeedVpnService` itself, so process/background/Always-on starts do not depend on `MainActivity`.
- Added a health model separating local control readiness from physical upstream reachability. The upstream probe deliberately uses `protect()` and therefore does **not** claim to measure TUN-to-Internet data-plane integrity.
- Kept native lifecycle recovery on the fast 2-second control-health loop while running the upstream reachability check every 30 seconds.
- SOCKS network failures are classified instead of being silently swallowed; normal teardown remains quiet.
- Native source remains fetched at a full pinned commit; release CI also publishes a source archive containing the resolved native tree.

## v1.0.24 highlights

- Stats accounting is durably staged with synchronous `commit()` and explicit `PENDING` / `SENT` / `IDLE` states.
- Legacy authentication-token migration is serialized with sign-out/token persistence.
- Physical DNS changes trigger a generation-bound VPN rebuild; repeated callbacks are debounced so an old DNS event cannot disconnect a newer generation.
- Release signing can be supplied only through environment/CI secrets. R8 remains disabled until a real release APK is tested with the native/JNI path.
- The health monitor separates control readiness from upstream reachability; neither probe is treated as proof of TUN-to-Internet end-to-end forwarding.

## Backend contract for statistics

`stats_batch_id` must be treated as an idempotency key by the backend. The Android client is intentionally **at-least-once**: if the process dies after the server accepts a batch but before local acknowledgement is finalized, the same batch ID can be retried. The backend must therefore deduplicate by `(device_id, stats_batch_id)` (or an equivalent unique key).

## Build

1. Install Android Studio, Git, and the required Android SDK/NDK versions declared by Gradle.
2. Run `setup.bat` on Windows or `setup.sh` on macOS/Linux to fetch the pinned native tunnel source.
3. Open the project in Android Studio or use the repository workflow.

For a signed release build, provide `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` through CI/local environment variables. Never commit a keystore or passwords.

## Native source reproducibility

The repository does not vendor the `hev-socks5-tunnel` source in the normal checkout. `setup.sh` / `setup.bat` fetch the pinned upstream source and detach it at the exact commit below:

- Repository: `https://github.com/heiher/hev-socks5-tunnel`
- Version/tag: `2.18.0`
- Commit: `d9dca26c7ad0e494492244f0309e80ee583e739e`

For release review, use the `*-full-src.tar.gz` artifact produced by the release workflow; it contains the resolved native source tree.

## Testing gates

See `TESTING.md` for the Android 12–15 matrix and release verification checklist. Android/NDK compilation, APK verification, 16 KB ELF alignment, and real-device behavior are release gates and must not be represented as verified until actually executed.

## v1.0.19.3 historical

- Final compile-oriented fixes for local SOCKS probing/UDP packet handling.
- Conservative Android relay capacity retained to avoid excessive thread creation.
- Hev task stack/buffer settings aligned with the upstream configuration.
- Error state, dashboard authentication loss, persisted speed limits, notifications, and UI status reporting hardened.

## v1.0.16 process-wide Native safety hardening

- Fixed Unix executable permissions on `gradlew` and `setup.sh` so Linux/macOS builds can run them directly.
- Clean delivery archive contains no generated `.class`, `.apk`, `.aab`, or `.dex` artifacts.
- Source-level lifecycle/generation fixes from v1.0.14 are retained unchanged.

## v1.0.14 audit fix

- Fixed a generation race in generation-guarded STOP handling: the expected generation is now revalidated inside the service state mutex before disconnecting, preventing a stale queued STOP from stopping a newer VPN generation.

## v1.0.13 audit fix
- Fixed a stale `SpeedVpnService` cleanup race: `cleanupLocked()` and `onDestroy()` now snapshot only the native generation owned by that Service instance, so an older instance cannot accidentally stop a newer process-wide Native engine.

# SpeedVPN — تطبيق أندرويد (وضع التحكم بالسرعة المحلي)

التطبيق يشغّل VPN حقيقي داخل الجوال (android.net.VpnService). كل إنترنت الجوال يمر من خلاله،
فيتحكم في سرعة التحميل والرفع ويحسب الاستهلاك. ما يحتاج خادم، والإنترنت يطلع من اتصالك العادي
(رقم IP ما يتغير). يستقبل أوامر "اتصال / فصل / تحديد السرعة" من لوحة التحكم في الموقع.

## التجهيز (مرة وحدة)
1. ثبّت **Android Studio** (مجاني) و **Git**.
2. من داخل Android Studio: Settings > SDK Manager > SDK Tools > فعّل **NDK (Side by side)**.
3. شغّل `setup.bat` (ويندوز) أو `setup.sh` (ماك/لينكس). ينزّل محرك النفق مفتوح المصدر hev-socks5-tunnel.
4. افتح هذا المجلد في Android Studio وانتظر حتى يخلص Gradle.

## التثبيت على الجوال
1. في الجوال: الإعدادات > حول الهاتف > اضغط "رقم الإصدار" 7 مرات، ثم فعّل **تصحيح USB** من خيارات المطور.
2. وصّل الجوال بالكمبيوتر، واختره من الأعلى في Android Studio، واضغط ▶ Run.
3. أو: Build > Build APK(s) وانقل ملف `app-debug.apk` للجوال وثبّته.

## الاختبار
1. افتح التطبيق وسجّل الدخول بنفس حساب الموقع.
2. اضغط **منح إذن VPN** ووافق على نافذة النظام.
3. من الموقع اضغط **اتصال** → تظهر علامة المفتاح أعلى الجوال، والموقع يعرض "متصل".
4. من صفحة السرعة اختر مثلاً 1 Mbps، وافتح أي موقع لاختبار السرعة في الجوال (مثل fast.com).
5. اضغط **فصل** في الموقع → تختفي علامة المفتاح.

## السجلات
في Android Studio > Logcat اكتب `tag:SpeedVPN` لترى كل خطوة (CONNECT requested، permission، Creating interface، Verifying، Connected، Disconnected، الأخطاء).

## الوضع المحلي (بدون حساب)
التطبيق يشتغل بدون تسجيل دخول: امنح إذن VPN، اضغط "اتصال"، وحدّد السرعة من الشريطين داخل التطبيق.
السرعة تنحفظ في الجوال. "ربط بالموقع" اختياري، وتحتاجه فقط إذا تبغى تتحكم بالجوال من الموقع.


## بناء APK على GitHub بدون Android Studio

هذا المستودع يحتوي على Workflow جاهز في `.github/workflows/build-apk.yml`. بعد رفع ملفات المشروع إلى جذر مستودع GitHub:

1. افتح المستودع ثم تبويب **Actions**.
2. اختر **Build SpeedVPN APK**.
3. اضغط **Run workflow** ثم **Run workflow** مرة أخرى.
4. بعد نجاح المهمة افتح نتيجة التشغيل، ثم من قسم **Artifacts** نزّل **SpeedVPN-APKs**.
5. فك الضغط على الجوال؛ الملف الناتج هو `app-debug.apk` ويمكن تثبيته مباشرة للاختبار.

كما سيُعاد البناء تلقائيًا عند كل `push` إلى فرعي `main` أو `master`.

> ملاحظة: هذه نسخة Debug للاختبار وليست توقيع متجر Google Play.


### Stability fixes in v1.0.24
- Stable VPN service lifecycle with serialized connect/disconnect operations.
- Current physical network is selected for DNS lookups instead of retaining a stale Network object.
- All relay sockets are tracked and closed on disconnect.
- Download/upload shaping is global across concurrent flows.
- Native tunnel health failures are retried before the VPN is torn down.
- hev-socks5-tunnel is pinned to release 2.18.0.


## v1.0.5 review patch
- Gradle 8.11.1 is declared consistently and its distribution checksum is pinned.
- The Gradle wrapper scripts bootstrap and verify the official wrapper JAR if it is not already present.
- CI exports the NDK paths explicitly and validates all packaged native ABIs plus 16 KB ELF LOAD alignment.
- Physical-network discovery follows the current default physical network where available and refreshes on default-network callbacks, excluding VPN transports.
- Dashboard stats include a persisted `stats_batch_id`; the backend should deduplicate that identifier to make accounting exactly-once.


## v1.0.7 lifecycle hardening
- Health probes bypass the 24-user-session admission cap via an in-process source-port reservation; the 25th user session remains rejected.
- Recovery publishes a freshly created SOCKS relay only while the same session/TUN ownership is still valid; stale relays are stopped immediately.
- TUN/SOCKS resource publication and destruction detachment are serialized with a dedicated lifecycle lock.
- `engineRunning` writes are centralized under the native lifecycle lock.
- SpeedLimiter waiters no longer pre-book future pacing time while sleeping; interrupted waiters cannot leave the previous large pacing debt.
- Limiter scheduler deadlines are reset after relay worker shutdown to prevent a torn-down session from delaying a later reconnect.
- App version is 1.0.7 / versionCode 8.


## v1.0.7 fixes
- Native lifecycle lock is process-wide because hev exposes process-wide/static lifecycle calls.
- Native generation ownership prevents old Service cleanup from stopping a newer Service generation.
- Global SpeedLimiter scheduler reset is owned by the VPN session generation, not individual SOCKS relays.
- CONNECT requests arriving during DISCONNECTING are queued and started after cleanup instead of being lost.


## v1.0.10 post-audit safety fixes

- Historical v1.0.10 was versionCode 11 after its additional pre-install audit.
- CONNECT start failures no longer overwrite another active VPN generation's state.
- Global SpeedLimiter generation is claimed before Native startup to remove the relay-startup pacing window.
- Native owner takeover invalidates the old limiter generation before replacing the process-wide Native owner.

## v1.0.9 forensic fixes and re-audit
- Added a process-wide active service generation so an older Service instance cannot continue data-plane work after a newer instance takes ownership.
- Native start/stop remains serialized process-wide and is guarded by owner generation; stale cleanup cannot stop newer Native.
- CONNECT/Recovery checks now reject stale process generations before touching process-wide Native state.
- Stale connect/recovery paths clean their own TUN/SOCKS resources.
- Global SpeedLimiter scheduler ownership is generation-scoped; old relay cleanup cannot reset a newer generation.
- CONNECT arriving during DISCONNECTING is queued for a clean reconnect after teardown.
- Global runtime status updates from meters/destroy/failure paths are prevented from overwriting a newer service generation.
- Historical v1.0.10 version / versionCode 11.


### v1.0.9 forensic fixes
- AgentService no longer overwrites DISCONNECTING before SpeedVpnService can queue a reconnect.
- SpeedLimiter rejects non-finite/invalid API rates and uses overflow-safe Kbps-to-bytes conversion.
- SOCKS5 relay limiter activity is generation-gated so an older relay cannot consume the newer generation's global scheduler.


### v1.0.12 final pre-install audit fixes
- Dashboard CONNECT/DISCONNECT commands are bound to explicit VPN request/generation ownership, preventing stale commands from treating a newer generation as their own or stopping it.
- Native takeover now refuses to start a new generation while the previous native engine still reports running.
- Native shutdown verifies `TProxyIsRunning()` and retries before closing a TUN FD, preventing native use-after-close on shutdown failure.
- Access-token refresh is serialized to avoid concurrent refresh-token rotation races.
- Traffic counters and pending stats accumulation are overflow-safe.
- Device IDs are no longer written to registration logs.
- Sign-out invalidates in-flight authentication refresh responses so a stale token refresh cannot restore a signed-out session.


### v1.0.11 audit fixes
- Dashboard commands are generation-bound and stale CONNECT/DISCONNECT requests cannot mutate a newer VPN generation.
- Native shutdown is verified before closing the TUN descriptor.
- Authentication refresh is serialized and invalidated on sign-out.

### v1.0.12 audit fixes
- In-flight authentication responses are rejected after sign-out, preventing stale refresh/sign-in responses from restoring credentials.
- Version bumped to 1.0.12 / versionCode 13.


### v1.0.18 compatibility
- Conservative 1280-byte TUN MTU.
- Uses current underlying-network DNS servers when available.
- Higher local SOCKS session/worker limits and larger UDP socket buffers.
- Hev UDP burst buffering increased.

## v1.0.21 — Traffic & App Controls
- Speed controls use KB/s in the UI, with a 10 KB/s minimum and presets up to 20 MB/s plus Unlimited. Internal/remote API values remain Kbps.
- The Home screen exposes per-session download/upload/total byte counters based on bytes actually forwarded by the limiter.
- The Apps screen can read per-app session usage through Android `NetworkStatsManager` after the user grants Usage Access. Android may update these counters with delay.
- The Apps screen stores a per-app block list. Actual blocking requires Android VPN Always-on + "Block connections without VPN" (lockdown). The app does not pretend that `addDisallowedApplication()` is a firewall; without lockdown it preserves normal all-app VPN routing.
- Compatibility changes also make the SOCKS resolver respect the IPv6 setting for TCP and UDP destinations, reducing accidental IPv6 use when disabled.


## v1.0.24 treatment
See `FIXES_V1.0.22.md` for the P0/P1 treatment and acceptance tests.


## Changelog

See `CHANGELOG.md` for the version history and `TESTING.md` for the device-test matrix and evidence requirements.


### v1.0.26 final repair notes
- Fixed `UdpFlowTableTest` scope so all UDP flow-key tests compile inside the test class.
- Moved synchronous SharedPreferences persistence in `MainActivity` off the UI thread while serializing writes.
- UDP reply correlation remains fail-closed for an intrinsically indistinguishable identical UDP 5-tuple; this is not safely solvable by heuristics.
- The Gradle Wrapper JAR is bootstrapped only from Gradle's official pinned URL when absent and is SHA-256 verified before execution; GitHub CI runs through `./gradlew` so the wrapper path itself is validated.
