# نتائج التحقق — رصيدي 1.0.0-beta1

تاريخ التحقق: 2026-11-23. كل ما يلي جرى فعلاً في بيئة البناء، ما لم يُذكر غير ذلك صراحة.

## البيئة
| | |
|---|---|
| النظام | Linux x86_64 (حاوية سحابية، 4 أنوية، بلا KVM وبلا جهاز أندرويد موصول) |
| JDK | OpenJDK 21.0.13 |
| Android SDK | platform 35، build-tools 35.0.0 |
| Gradle / AGP / Kotlin | 8.14.3 / 8.7.3 / 2.0.21 |
| اختبارات أندرويد على JVM | Robolectric 4.16، SDK 34، رسم أصلي (Native Graphics) |
| فتح الملفات | LibreOffice (headless)، openpyxl، python-docx، pdfplumber، poppler (pdftoppm) |

## الأوامر
```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleBeta
python3 scripts/verify_samples.py samples build/verify
./gradlew :app:connectedDebugAndroidTest     # يحتاج جهازاً أو محاكياً
```
النتيجة: **76 اختباراً، 0 إخفاق**. lint: 0 أخطاء، تحذيران تنسيقيان (ModifierParameter). البناء الكامل من الصفر في 3 دقائق و49 ثانية.

## الحزم
| الملف | الحجم | SHA-256 |
|---|---|---|
| `dist/rasidi-1.0.0-beta1.apk` (`app.rasidi.ledger.beta`، R8، موقّع بمفتاح الاختبار المشترك) | 3.3 MB | `5b7cd52cb353bb7cf6141beaf8fa907b68897c089bcab74d585113892a764e75` |
| `app-debug.apk` (`app.rasidi.ledger.debug`، غير مصغّر، يُولَّد في CI) | 20 MB | يُطبع في CI |

بصمة مفتاح الاختبار: SHA-1 `8D:E3:AE:52:07:3C:6D:B6:DB:31:44:0C:2F:21:26:65:C1:00:AA:2E`. الإصدار الموقّع (`app.rasidi.ledger`) **ينتظر مفتاح التوقيع الفعلي** (انظر README وCI).

## البوابات

### 1. الدفتر والنسخة المحلية — ✅ (JVM/Robolectric)
| الفحص | الاختبار | النتيجة |
|---|---|---|
| أول محفظة ومصروف، إغلاق القاعدة وإعادة فتحها، الرصيد نفسه | `LedgerRepositoryTest.firstWalletAndExpenseSurviveReopen` | ✅ |
| نسخة مشفّرة إلى ملف ثم استعادة على قاعدة فارغة: تساوي البصمة والأعداد والأرصدة | `BackupRestoreTest.encryptedBackupRestoresIntoEmptyDatabase` | ✅ |
| رمز خاطئ، أو ملف تالف، أو ترويسة معدّلة، أو صيغة أحدث: توقف قبل الكتابة | `BackupTest.*`، `BackupRestoreTest.wrongCodeOrCorruptFileStopsBeforeWriting` | ✅ |
| فشل الاستبدال في منتصفه يترك السجل السابق كاملاً | `BackupRestoreTest.failedReplaceLeavesPreviousLedgerIntact` | ✅ |
| نسخة أمان قبل الاستبدال، وكشف تغيّر السجل بعد المعاينة | `restoreOverExistingCreatesSafetyAndDetectsStalePreview` | ✅ |
| كشف بسيط بالصيغ الثلاث والعربية | `ReportSamplesTest` و`scripts/verify_samples.py` | ✅ |

### 2. Google Drive — ⚠️ منطق مختبر، **لم يُختبر مع حساب Google حقيقي**
| الفحص | كيف | النتيجة |
|---|---|---|
| رفع، تحقق md5/SHA-256، رفض الحساب الخاطئ، انقطاع الشبكة بعد وصول الملف (ملف واحد)، إعادة المحاولة بالمعرّف نفسه، 409، الاحتفاظ بآخر 7 نسخ تلقائية، التفويض المنتهي | `DriveEngineTest` بعميل **وهمي** | ✅ |
| عميل REST الحقيقي: ترويسة Bearer، `appDataFolder`، رفع multipart، `generateIds`، 409 ثم تحقق بالتنزيل، القائمة، 401 | `DriveRestClientTest` أمام **خادم HTTP محلي** يحاكي Drive v3 | ✅ |
| ربط فعلي، ورفع إلى Drive حقيقي، واستعادة على تثبيت جديد بالحساب نفسه ورمز الاستعادة | يحتاج جهازاً فيه Google Play Services ومشروع OAuth (انظر `docs/google-drive-setup.md`) | ⏳ **لم يُنفَّذ** |

الفرق: الاختبارات الوهمية والمحلية تثبت منطق المهمة وصيغة الطلبات، لا سلوك خوادم Google ولا شاشة الموافقة ولا AuthorizationClient. هذه تحتاج تجربة يدوية بحسابك.

### 3. السجل والتقارير — ✅
- تحويل بين عملتين مع رسوم، والنقر المكرر ينتج قيداً واحداً، والسعر الناتج محفوظ: `doubleTapProducesOneEntry`.
- دين قديم بلا حركة نقدية، وسداد جزئي بعملة أخرى، ورفض التجاوز مع السبب، وعكس الدفعة يعيد رصيدي الحساب والدين معاً: `oldDebtPartialPaymentInOtherCurrencyAndReversal`.
- شراء الذهب وبيعه بلا تكرار: `goldBuySellNoDuplication`. السالب يحتاج إذناً: `negativeBalanceNeedsPermission`. التعديل مع سجل والتراجع السريع: `editSimpleKeepsAuditAndUndoRecent`. تاريخ قرب منتصف الليل: `midnightDateKeepsBusinessDay`.
- كشف حساب، وسند تبديل، وكشف دين، وتقرير سنوي بالصيغ الثلاث فُتحت وفُحصت: انظر قسم التصدير.

### 4. الجرد والزكاة — ✅
- مثال القبول: 11,300 ← وعاء 11,500 ← **287.50 USD**، ودفعات 120 USD و2,000 TRY و14,100 SYP ⇒ «المتبقي 0.00»: `ZakatTest.acceptanceExample`، وعبر Room والنسخ في `BackupRestoreTest`.
- النصاب بلغ الحد بالضبط، وقيمة أقل منه بكسر رغم أن عرضها مقرّب إلى 8,500.00: `nisabExactlyReachedIsDue`.
- حلي تحت المراجعة، وديون مستبعدة، وخصم جزئي يتجاوز الأصل، وحول غير مؤكّد: `jewelryAndExcludedDebtsNeedReview`، `partialDeductionCappedAndHawlRequired`.
- سعر مفقود أو أحدث من تاريخ الجرد يمنع الاعتماد، والسعر الأقدم اقتراح يحتاج تأكيداً: `missingOrFuturePriceBlocksValue`. سعر جديد اليوم لا يغيّر جرداً سابقاً: `newRateTodayDoesNotChangeOldInventory`.
- الليرة القديمة: معامل 100 مرة واحدة، وقبل النفاذ وبعد نهاية التداول: `oldSyrianValuedOnce`، و`CsvImportTest.sypOldNewChoiceAppliedOnce`.
- التوزيع بفرق تقريب: `distributionRoundingResidual`.

### 5. إغلاق الإصدار
| الفحص | النتيجة |
|---|---|
| استعادة كل أنواع السجلات (حركات، ديون، دفعات، ذهب، أسعار، جرد معتمد، زكاة، دفعات زكاة) | ✅ `BackupRestoreTest` |
| المخطط المصدَّر يطابق الكيانات، وإعادة فتح قاعدة مستخدمة تحفظ البيانات | ✅ `MigrationAndUpgradeTest` (لا ترحيلات بعد: الإصدار 1) |
| العربية على أحجام عدة وفي الوضعين | ✅ صور Robolectric (أدناه). على جهاز حقيقي: انظر قسم الجهاز |
| استيراد CSV مع دفعة مكررة | ✅ `CsvImportTest`، `CsvImportRepoTest.reimportSameBatchDoesNotDuplicate` |

## التصدير: فتح الملفات فعلياً
`scripts/verify_samples.py` فتح 21 ملفاً (7 تقارير × 3 صيغ): **114/114 فحصاً ناجحاً** (`docs/verify-samples.md`).
- **XLSX** (openpyxl + LibreOffice Calc): كل الأوراق RTL، صف العناوين مثبّت (A2)، الفلترة مفعّلة، المبالغ خلايا رقمية والتواريخ خلايا تاريخية قابلة للفرز (239/239)، دخل كل عملة في التفاصيل يطابق الملخّص. الأرقام فوق 15 خانة لها عمود نصّي دقيق (`longNumbersKeepExactTextColumn`).
- **DOCX** (python-docx + LibreOffice Writer): جداول `bidiVisual`، تكرار رأس الجدول، تذييل «صفحة X من Y». حوّله LibreOffice إلى 9 صفحات.
- **PDF** (pdfplumber + poppler): A4، خط Cairo مضمَّن (CID TrueType + ToUnicode)، النص قابل للاستخراج، الترويسة والترقيم في كل صفحة، التقرير السنوي 8 صفحات، السند بقالب A5.
- أسماء المستفيدين مخفية افتراضياً في تقرير الزكاة (مختبر).
- لم يُجرَّب: فتح الملفات في Microsoft Excel/Word نفسيهما، والطباعة الورقية.

## الأداء (10,077 حركة اصطناعية، Robolectric على JVM)
```
txns=10077 entries=10099
استيراد في Room (معاملة واحدة): 601 ms
فتح الدفتر (قراءة لقطة + بناء العرض): 235 ms
حساب الرئيسية (صافي الأموال): 14 ms
بحث نصي في البيان: 5 ms (111 نتيجة)
إنشاء نسخة مشفّرة: 400 ms، الحجم 297 KB
```

هذه أرقام JVM لا هاتف؛ الهاتف أبطأ عادةً بعدة أضعاف.

## صور الواجهة (بيانات اصطناعية، Robolectric)
`docs/screens/`: الرئيسية نهاري/ليلي، الديون نهاري/ليلي، الجرد، النسخ الاحتياطي، الحركات، المزيد، إدخال مصروف، خط مكبّر 130%، لوحي 800dp، هاتف ضيّق 320dp.

## الجهاز أو المحاكي
| | |
|---|---|
| الجهاز | محاكي Android 14 (API 34، google_apis، x86_64، Pixel 5) **بمحاكاة برمجية كاملة (TCG، بلا KVM)** |
| الحزمة | `dist/rasidi-1.0.0-beta1.apk` (نسخة R8 المصغّرة نفسها التي تُسلَّم) |

ما تحقق فعلاً على المحاكي:
- ✅ التثبيت نجح (`adb install` ← Success).
- ✅ **وضع الطيران مفعّل** (`airplane_mode_on=1`) ثم التشغيل: التطبيق فتح شاشة البداية «أهلاً في رصيدي» (ابدأ محلياً / استرجع سجلي) بخط Cairo واتجاه RTL صحيح، دون أي استثناء من التطبيق في logcat. هذا يثبت أن تصغير R8 لم يكسر Room أو kotlinx.serialization أو Compose عند الإقلاع. الصورة: `docs/screens/00-emulator-api34-airplane-onboarding.png`.
- ❌ **لم يكتمل** مسار القبول على المحاكي (إضافة محفظة، ثم مصروف، ثم إعادة الفتح). تحت المحاكاة البرمجية كان متوسط الحمل نحو 10 على 4 أنوية، وأعاد Android تشغيل system_server مراراً (انهارت systemui وphone وbluetooth وsettings، وتوقفت خدمتا UiAutomation و`input`). **لم يظهر التطبيق في قائمة الانهيارات**، لكن لم يعد ممكناً قيادة الواجهة.
- البديل المنفَّذ: المسار نفسه مختبر على مستوى المستودع وRoom بإغلاق القاعدة وإعادة فتحها (`firstWalletAndExpenseSurviveReopen`). وكُتب اختبار جهاز كامل للواجهة `app/src/androidTest/.../FirstRunOfflineTest.kt`، وقد تُرجم بنجاح لكنه **لم يُشغَّل**. يُشغَّل على جهاز حقيقي أو محاكٍ بتسريع عتادي بالأمر `./gradlew :app:connectedDebugAndroidTest`.

## غير مُختبر بعد (يحتاج جهازاً أو حساباً)
- Drive مع حساب Google حقيقي: الربط والرفع والاستعادة على تثبيت جديد، وإلغاء الموافقة، وانتهاء الرمز، وغياب خدمات Google على جهاز حقيقي.
- TalkBack فعلياً (أُضيفت أوصاف المحتوى والعناوين والأدوار وأهداف 48dp، دون تجربة بقارئ الشاشة).
- قفل البصمة على جهاز حقيقي.
- قتل التطبيق أثناء الاستبدال على جهاز (المعاملة الواحدة مختبرة على JVM).
