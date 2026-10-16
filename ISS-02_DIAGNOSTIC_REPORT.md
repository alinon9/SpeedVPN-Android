# ISS-02 — تقرير تدقيق دورة حياة المقابس والتوجيه

## النطاق والحالة

- **Commit الأساس الذي تمت مراجعته:** `f8422c615778aa8552709a72ef569eb135593983`
- **فرع التعديلات:** `fix/iss-02-socket-lifecycle`
- **الدمج وPR:** لم يتم الدمج إلى `main` ولم يتم فتح PR.
- **حدود التنفيذ:** التغييرات اقتصرت على `SpeedVpnService.kt` و`Socks5Server.kt` و`UdpFlowTable.kt` و`EncryptedDnsResolver.kt` واختبارات الوحدة ذات الصلة، مع هذا التقرير. لم تُعدّل `SpeedLimiter.kt` أو `QuotaEnforcementEngine.kt` أو `MainActivity.kt`.

## أ) حماية المقابس الخارجية

### قبل الإصلاح

- TCP في `Socks5Server.kt` الأسطر 689–695: كان إنشاء المقبس ثم تسجيله، وبعدها استدعاء `network.bindSocket(socket)` في السطر 693، قبل `protectTcp(socket)` في السطر 694.
- UDP في `Socks5Server.kt` الأسطر 1277–1291: كان `network.bindSocket` و`socket.bind` يسبقان `protectUdp`.
- DNS المشفر في `EncryptedDnsResolver.kt` الأسطر 44–49: كان `network.bindSocket` يسبق التحقق من نتيجة `protect`.
- فحص الوصول في `SpeedVpnService.kt` الأسطر 781–790: كان ربط المقبس بالشبكة يسبق الحماية؛ وعند `false` كان يتجه إلى المحاولة التالية دون تسجيل سبب واضح.

### بعد الإصلاح

- أضيفت `protectSocketOrClose` في [Socks5Server.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/Socks5Server.kt#L83). إذا أعادت `protect` القيمة `false` أو رمت استثناءً، يُغلق المقبس ويُسجّل الخطأ ثم يُرفع الاستثناء؛ ولا يُسمح بمتابعة الربط أو الاتصال.
- TCP الخارجي: [Socks5Server.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/Socks5Server.kt#L834-L854).
- UDP الخارجي: [Socks5Server.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/Socks5Server.kt#L1459-L1503).
- DNS المشفر: [EncryptedDnsResolver.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/EncryptedDnsResolver.kt#L110-L131).
- فحص الوصول الخارجي: [SpeedVpnService.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/SpeedVpnService.kt#L786-L796).

**نتيجة الفحص الساكن:** كل مواضع `network.bindSocket(socket)` في هذه المسارات تسبقها الآن دعوة الحماية الموحّدة. المقابس التي لم تُحمَ هي مقابس الخدمة المحلية على `127.0.0.1` (مقبس الاستماع، وفحص SOCKS المحلي، ومقبس استقبال UDP المحلي)، وهي ليست مقابس خروج إلى الشبكة الخارجية.

## ب) مهلات SOCKS5 والإيقاف

### قبل الإصلاح

كانت هناك مهلة مصافحة `HANDSHAKE_TIMEOUT_MS = 10_000` ومهلة اتصال TCP، لكن `client.soTimeout = 0` كان يُنفّذ قبل قراءة طلب SOCKS كاملًا؛ كما لم تكن مهلة قراءة واضحة تُضبط على تدفقي TCP بعد الاتصال.

### بعد الإصلاح

- المصافحة وقراءة الطلب تبقيان ضمن مهلة 10 ثوانٍ: [Socks5Server.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/Socks5Server.kt#L691-L744).
- أضيفت `RELAY_READ_TIMEOUT_MS = 120_000`، وتُطبّق على طرف العميل وتدفق TCP الخارجي؛ وتبقى مهلة الاتصال محددة في `Socket.connect(..., remainingMs)`.
- اتصالات `UDP ASSOCIATE` تترك اتصال التحكم المحلي مفتوحًا أثناء عمر الارتباط؛ هذا مقصود في البروتوكول. يُغلق هذا الاتصال عند إيقاف الخادم أو إغلاق العميل، ويُغلق مقبس استقبال UDP المحلي في مسار التنظيف.
- يحتفظ الخادم بمقبض خيط القبول؛ ويغلق المقابس النشطة، ويلغي DNS المشفر، ويوقف مجمّعات العمال ضمن مهلة انتظار مشتركة لا تتجاوز 3 ثوانٍ، مع تسجيل الانقطاع أو انتهاء المهلة: [Socks5Server.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/Socks5Server.kt#L323-L373).

## ج) سعة جدول UDP وTTL والتزامن

### قبل الإصلاح

كان الجدول متزامنًا عبر `@Synchronized`، ويُطبّق الحد الأقصى والإخلاء حسب TTL وLRU. لكن `clear` و`purgeLocked` و`evictOldestLocked` كانت تزيل التدفقات وفهارس الرد دون إشعار مالك تدفق UDP كي يغلق `DatagramChannel` المرتبطة فورًا.

### بعد الإصلاح

- يحتفظ الجدول بقفل واحد، وتُنفّذ callbacks الخاصة بالإخلاء بعد تحرير هذا القفل. وإذا وقع استثناء أثناء العملية أو أثناء callback، تتم محاولة كل عمليات التنظيف، ويُعاد رفع الخطأ الأصلي مع أخطاء التنظيف الملحقة: [UdpFlowTable.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/UdpFlowTable.kt#L39-L75).
- إشعار الإخلاء موصول بمُضاعِف UDP: [Socks5Server.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/Socks5Server.kt#L1122-L1135)، ويُغلق `closeFlow` القناة فقط إذا كان كائن التدفق مطابقًا لهوية التدفق الذي أُخلي، لمنع إغلاق قناة أحدث أعادت استخدام المفتاح: [Socks5Server.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/Socks5Server.kt#L1421-L1431).
- إضافة فحص `isCurrent` تمنع استخدام مرجع قديم أو إنشاء قناة لتدفق انتهت صلاحيته.
- يستخدم إعداد قناة الخروج الحماية قبل `bind` و`connect`، ويُغلق المقبس والقناة عند فشل أي خطوة من خطوات الإعداد.

## د) دورة حياة الخدمة والموارد

- `cleanupLocked()` يلغي الآن `quotaMonitor` أيضًا: [SpeedVpnService.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/SpeedVpnService.kt#L1148-L1156).
- `onRevoke()` يعطل محاولة إعادة الاتصال، وينفّذ قطع الاتصال ثم إيقاف الخدمة: [SpeedVpnService.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/SpeedVpnService.kt#L1322-L1332).
- `onDestroy()` يلغي نطاق الكوروتينات والمراقبين قبل فصل الموارد، ثم يستمر مسار التنظيف الطارئ في إيقاف Native قبل إغلاق TUN والـ relay، حفاظًا على ملكية واصف TUN.
- أُضيف إغلاق صريح لـ `EncryptedDnsResolver` عند إيقاف SOCKS أو تبدل الشبكة؛ ويُلغي طلبات OkHttp النشطة، ويُفرغ الاتصالات الخاملة، ويمنع إنشاء مقبس DNS جديد بعد الإغلاق: [EncryptedDnsResolver.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/EncryptedDnsResolver.kt#L33-L60).

## هـ) IPv4 وIPv6 وDNS

### قبل الإصلاح

- كان `SpeedVpnService.kt` يمرر `ipv6Enabled = true` إلى `Socks5Server` رغم وجود إعداد محفوظ في `VpnSettings`.
- كان مسار IPv6 في TUN مفعّلًا دائمًا، وهو سلوك مناسب لمنع تجاوز VPN؛ لكن تفعيل IPv6 في relay لم يكن متطابقًا مع إعداد المستخدم.
- كان خيار DNS IPv4-only غير موصول بقرار طلب سجلات AAAA داخل `EncryptedDnsResolver`.

### بعد الإصلاح

- يأخذ relay قيمة `compatibility.ipv6Enabled` الفعلية، ويُعاد تمريرها مع `dnsIpv4Only` عند إعادة الاتصال: [SpeedVpnService.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/SpeedVpnService.kt#L250-L265) و[مسار الاستعادة](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/SpeedVpnService.kt#L828-L842).
- تُترك مسارات `0.0.0.0/0` و`::/0` داخل TUN عمدًا: [SpeedVpnService.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/SpeedVpnService.kt#L286-L305). إذا كان تمرير IPv6 معطلًا، تُرفض وجهات IPv6 داخل relay بدل تركها تتجاوز النفق. ويسجّل مسار UDP رفض وجهة IPv6 عند تعطيلها.
- يوجّه Android إلى عنوان MapDNS اصطناعي داخل TUN (`MAP_DNS_V4`)؛ لا يُستبدل بخادم DNS فعلي خارج النفق.
- يطبّق resolver الآن خيار DNS IPv4-only عند تقرير ما إذا كان سيطلب سجل AAAA: [EncryptedDnsResolver.kt](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/main/java/com/speedvpn/app/EncryptedDnsResolver.kt#L18-L73).

## الاختبارات المضافة

- `UdpFlowTableTest.kt`: التأكد من إغلاق قناة التدفق المنتهي بـTTL، وإخلاء 64 تدفقًا تحت 8 خيوط بالتوازي دون إغلاق مكرر، واختبار إخلاء السعة مع حد أقصى 24 تدفقًا وإغلاق القنوات المرتبطة بها: [الاختبارات](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/test/java/com/speedvpn/app/UdpFlowTableTest.kt#L44-L62) و[اختبارات التزامن](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/test/java/com/speedvpn/app/UdpFlowTableTest.kt#L155-L277).
- `Socks5ServerProtectionTest.kt`: رفض `protect=false) وإغلاق TCP/UDP، وإغلاق المقبس إذا رمى protect استثناءً، وسياسة DNS IPv4-only، وإيقاف الخادم مع اتصال نشط والتحقق من إمكانية إعادة استخدام المنفذ وعدم بقاء خيوط هذا الخادم: [الاختبارات](https://github.com/alinon9/SpeedVPN-Android/blob/fix/iss-02-socket-lifecycle/app/src/test/java/com/speedvpn/app/Socks5ServerProtectionTest.kt#L22-L127).
- تم فحص `Socks5RelayReadBatchTest.kt` و`VpnServiceContractTest.kt`، ولم تكن هناك حاجة لتعديلهما.

## حالة تشغيل الاختبارات — غير منفّذة في هذه البيئة

**لا يوجد سجل تشغيل فعلي يمكن إرفاقه، ولم تُعلن الاختبارات ناجحة.** لم يكن مستودع المشروع أو ملف `gradlew` متاحًا في بيئة التنفيذ المحلية، كما تعذّر حل اسم مضيف GitHub من بيئة الحاوية. كذلك تُظهر واجهة GitHub عدم وجود أي تشغيل Actions لهذا الفرع؛ وملف workflow الحالي لا يشغّل الدفع إلى هذا الفرع تلقائيًا. لم يُفتح PR لأن المهمة تمنع ذلك.

الأمر المطلوب تشغيله في بيئة المشروع:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.speedvpn.app.Socks5RelayReadBatchTest" \
  --tests "com.speedvpn.app.UdpFlowTableTest"
```

ولإدراج الاختبارات الجديدة في التشغيل المستهدف:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.speedvpn.app.Socks5RelayReadBatchTest" \
  --tests "com.speedvpn.app.UdpFlowTableTest" \
  --tests "com.speedvpn.app.Socks5ServerProtectionTest"
```

ويجب بعد ذلك تشغيل مجموعة وحدات التطبيق كاملة قبل اعتبار معيار القبول محققًا. حالة هذا الفرع هي **تعديلات منفّذة ومرفوعة إلى فرع معزول، لكن القبول الاختباري ما زال غير محسوم حتى يُشغّل Gradle فعليًا**.
