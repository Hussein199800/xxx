# نموذج البيانات

المصدر الوحيد للكيانات: `core/src/main/kotlin/app/rasidi/core/data/Entities.kt`. الكيانات نفسها تُستخدم في Room وفي لقطة النسخة الاحتياطية. المخططات المصدَّرة: `app/schemas/app.rasidi.data.RasidiDatabase/{1,2,3}.json`. الترحيلات 1 ← 2 و2 ← 3 في `RasidiDatabase.kt` ويقابله `BackupMigrations` للنسخ القديمة.

| الجدول | المفتاح والعلاقات | ملاحظات |
|---|---|---|
| `accounts` | id | عملة واحدة لكل حساب، `allowNegative`، `archived` يحافظ على السجل |
| `categories` | id | INCOME/EXPENSE، قابلة للأرشفة؛ «زكاة» و«رسوم تحويل» فئتا نظام |
| `txns` | id، `requestId` فريد | الوثيقة: نوع وتاريخ عمل محلي و`createdAtUtc`. `reversesId`/`reversedById` للعكس، `debtId`، `fxRate` للتحويل بين عملتين |
| `entries` | id → txns (CASCADE)، accounts (RESTRICT)، categories (RESTRICT) | قيد موقّع بالوحدات الصغرى. فهارس (accountId,date) وdate |
| `debts` | id | الطرف، الاتجاه، الأصل والعملة، التاريخ والاستحقاق، `txnId` للقرض النقدي (أو null لدين قديم) |
| `installments` | → debts | جدول الأقساط |
| `debt_payments` | → debts، → txns | مبلغ الدين بعملته، ومبلغ المحفظة وعملتها والحساب |
| `gold_holdings` / `gold_moves` | moves → holdings، → txns | الوزن الصافي الموقّع لكل حركة |
| `rates` | id، فهرس (unit,date) | القيمة المدخلة واتجاهها والمصدر والنوع ووقت الرصد |
| `inventories` / `inventory_lines` | lines → inventories | لقطة مستقلة برقم مراجعة (`groupId`, `revision`)، والقيم والأسعار المثبّتة لكل سطر |
| `zakat_estimates` | → inventories (RESTRICT) | أرقام الوعاء والنصاب والحول والقرارات (JSON) |
| `zakat_payments` | → estimates، → txns (فريد) | كل دفعة = قيد مصروف واحد، بسعر يوم الدفع |
| `settings` | key | تُنسخ فقط المفاتيح المسموحة (`Snapshot.BACKUP_SETTING_KEYS`) |
| `audit_log` | id | سجل التعديلات والعكس والتراجع والاستيراد |
| `members` (v2) | id | أفراد البيت؛ `txns.memberId` اختياري |
| `budgets` (v2) | id → categories، فريد (categoryId,currency) | حد شهري لفئة بعملتها |
| `recurring` (v2) | id → accounts، categories | متكرر شهري؛ يُسجَّل بتأكيد بمعرّف طلب `rec:id:yyyy-MM` فلا يتكرر في الشهر نفسه |
| `parties` (v3) | id، اسم فريد | حساب الشخص (اسم، هاتف، ملاحظة، أرشفة)؛ يوجد ولو برصيد صفر. الديون ترتبط به بالاسم، وإعادة التسمية تنتقل إليها |
| `backup_jobs` | jobId | **محلي فقط**؛ لا يدخل في النسخة، ويُمسح بعد الاستعادة |
| `requests` (Room v4) | requestId | **محلي فقط** (منع التكرار): نوع العملية وبصمة مدخلاتها ونتيجتها، يُسجَّل في معاملة العملية نفسها. لا يدخل في النسخة (لذلك `DATA_VERSION` بقي 3) ويُمسح عند الاستعادة؛ الطلبات الأقدم تُعرف من `txns.requestId` |

### الإصدارات
- Room: **4** (`MIGRATION_1_2`، `MIGRATION_2_3`، `MIGRATION_3_4`)، والمخططات المصدَّرة 1–4 في `app/schemas`. كل ترحيل مختبر على قاعدة قديمة حقيقية ببيانات.
- بيانات النسخ `DATA_VERSION`: **3** (لم تتغير اللقطة في 1.5). عينات مشفّرة حقيقية من كود الإصدارين 1 و2 في `core/src/test/resources/fixtures`.
- `MIGRATION_2_3` وخطوة النسخ 2→3 يطبّعان أسماء الأشخاص بالدالة نفسها `PartyIds.normalize` (Kotlin `trim`).
- **الدين الملغى** مشتق لا مخزَّن: دين حركةُ إنشائه (`DEBT_NEW`) لها `reversedById` ⇒ أصله لا يُحتسب (`Debt.cancelled`). لا عمود جديد، فتعمل النسخ والقواعد القديمة بالمعنى نفسه.

## صيغة ملف `.rasidi`
```
"RASIDI" 00 01 | u32 طول الترويسة | ترويسة JSON | AES-256-GCM( gzip(JSON {fingerprint, counts, snapshot}) )
```
- الترويسة (غير مشفّرة لكنها AAD): `format`، `dataVersion`، `backupId` عشوائي، `createdAtUtc`، `installId`، `kind` (MANUAL/AUTO/SAFETY)، `salt`، `nonce`.
- المفتاح: HKDF-SHA256 من رمز الاستعادة (256 بت) مع salt لكل ملف. nonce عشوائي 96 بت لكل تشفير.
- رمز الاستعادة: Base32 Crockford في 13 مجموعة + مجموعة تحقق (4 أحرف من SHA-256) لاكتشاف أخطاء الكتابة.
- قبل الاستعادة يُفحص: الحجم (≤ 64MB أثناء القراءة، و≤ 192MB بعد فك الضغط)، والترويسة (salt 16 بايت، nonce 12، cipher/kdf/kind الموثّقة فقط)، والإصدار (الأحدث مرفوض)، وسلامة GCM، والبصمة والأعداد **على المحتوى الخام قبل الترحيل لكل الإصدارات**، ثم العلاقات والمعرّفات والأنواع وروابط العكس والوحدات وأطوال النصوص وعدد الصفوف (`SnapshotValidator`). رسائل الخطأ لا تعرض محتوى الملف.
- بروتوكول الاستعادة (`LedgerRepository.replaceIfUnchanged`): في **معاملة واحدة** — مقارنة بصمة السجل بالمعاينة (تغيّر ⇒ «Stale» بلا كتابة)، ثم نسخة أمان مكتوبة ذرّياً (tmp + fsync + rename) ومفكوكة ومتحقق منها إن لم يكن السجل «فارغاً فعلاً»، ثم الاستبدال، ثم مطابقة البصمة الجديدة؛ أي فشل يُرجع السجل السابق كاملاً. «فارغ فعلاً» = لا أي سجل مستخدم (التصنيفات الافتراضية والإعدادات المظهرية فقط). تُحفظ آخر 10 نسخ أمان.
- ترحيل اللقطات القديمة: `BackupMigrations` (خطوة لكل إصدار، مع اختبار).
