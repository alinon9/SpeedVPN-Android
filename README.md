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
4. بعد نجاح المهمة افتح نتيجة التشغيل، ثم من قسم **Artifacts** نزّل **SpeedVPN-debug-APK**.
5. فك الضغط على الجوال؛ الملف الناتج هو `app-debug.apk` ويمكن تثبيته مباشرة للاختبار.

كما سيُعاد البناء تلقائيًا عند كل `push` إلى فرعي `main` أو `master`.

> ملاحظة: هذه نسخة Debug للاختبار وليست توقيع متجر Google Play.


### Stability fixes in v2.0.0
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
