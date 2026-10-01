# SpeedVPN v1.0.22 — علاج P0/P1 ومهام QA

## الإصلاحات المطبقة
- C-01: `Socks5Server.kt` يستخدم `bucket.recordForwarded(...)` لأن الدالة عضو في `TokenBucket`.
- C-02: `SpeedVpnService.onDestroy()` يأخذ snapshot من `tunnelConfigFile` قبل تصفير الحقل.
- H-01: ردود UDP التي لا تطابق flow معروف يتم إسقاطها؛ لا يوجد fallback إلى آخر `clientEndpoint`.
- H-02: `UdpFlowTable` يستخدم `LinkedHashMap(accessOrder=true)` مع TTL حقيقي وإزالة أقدم flow عند بلوغ السعة.
- H-03: UDP يحتفظ بكل DNS candidates بترتيب IPv4-first، ويبدّل candidate فقط عند غياب reply بعد مهلة محددة.
- H-04: upstream TCP/UDP sockets تُربط بـ`Network.bindSocket()` قبل الاتصال/الاستخدام، ثم تُحمى عبر `VpnService.protect()`.
- H-05: عند تغير الشبكة الفيزيائية تُغلق upstream sockets القديمة ويُحدّث `setUnderlyingNetworks()`؛ الاتصالات الجديدة تعاد على الشبكة الحالية.
- H-06: SOCKS session ceiling = 80، UDP associations = 24، workers = 192. قيمة HEV `max-session-count` أصبحت 80 لمطابقة السقف.
- H-07: session traffic accounting يعتمد على `AtomicReference<SessionCounter>` لعزل generation القديمة عن الجديدة.
- H-08: native recovery يستخدم `endLimiterSession=false` ويحافظ على session accounting عند إعادة تشغيل data plane.
- H-09: IPv4-only DNS يفشل صراحةً عند غياب DNS IPv4 بدل fallback صامت.
- H-10: تعديل firewall/block list أثناء اتصال VPN يطلب إعادة إنشاء الـVPN لتطبيق قائمة Builder الجديدة. الحظر الكامل يتطلب Android Always-on VPN + Lockdown/Block connections without VPN.
- M-01: تمت إضافة `<queries>` للـlauncher visibility، مع إبقاء blocked packages المحفوظة قابلة للعرض عند نجاح lookup.
- M-02: AppStats refresh أصبح 60s، ويظهر للمستخدم أن القياس مبني على Android NetworkStats وقد يتأخر.
- M-03: Agent command polling يستخدم exponential backoff عند الخمول/الأخطاء بدل polling ثابت كل 2.5s.
- M-05: تمت إضافة unit-test sources للـSessionCounter وUDP flow table، وإضافة unit tests/lint/release build إلى CI.
- M-06: تمت المحافظة على semantics الخاصة بـAlways-on وعدم تقديم app exclusion على أنه Internet blocking مستقل.

## ملاحظات مهمة
- لا يوجد ادعاء بأن WhatsApp أو Facebook Lite أصبحا PASS دون APK واختبار هاتف حقيقي.
- `AppTraffic` مبني على `NetworkStatsManager` وليس packet-perfect TUN accounting.
- `gradle-wrapper.jar` ما زال غير موجود في حزمة المصدر الحالية؛ CI يستخدم Gradle 8.11.1 مباشرة عبر `gradle/actions/setup-gradle`، لذلك هذا لا يمنع CI build لكنه يمنع الاعتماد على `./gradlew` من clone نظيف حتى يتم توليد الـwrapper JAR على بيئة موثوقة.
