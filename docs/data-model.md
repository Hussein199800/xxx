# نموذج البيانات

المصدر الوحيد للكيانات: `core/src/main/kotlin/app/rasidi/core/data/Entities.kt`. الكيانات نفسها تُستخدم في Room وفي لقطة النسخة الاحتياطية. المخطط المصدَّر: `app/schemas/app.rasidi.data.RasidiDatabase/1.json`.

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
| `backup_jobs` | jobId | **محلي فقط**؛ لا يدخل في النسخة، ويُمسح بعد الاستعادة |

## صيغة ملف `.rasidi`
```
"RASIDI" 00 01 | u32 طول الترويسة | ترويسة JSON | AES-256-GCM( gzip(JSON {fingerprint, counts, snapshot}) )
```
- الترويسة (غير مشفّرة لكنها AAD): `format`، `dataVersion`، `backupId` عشوائي، `createdAtUtc`، `installId`، `kind` (MANUAL/AUTO/SAFETY)، `salt`، `nonce`.
- المفتاح: HKDF-SHA256 من رمز الاستعادة (256 بت) مع salt لكل ملف. nonce عشوائي 96 بت لكل تشفير.
- رمز الاستعادة: Base32 Crockford في 13 مجموعة + مجموعة تحقق (4 أحرف من SHA-256) لاكتشاف أخطاء الكتابة.
- قبل الاستعادة يُفحص: الحجم (≤ 64MB)، والترويسة، والإصدار (الأحدث مرفوض)، وسلامة GCM، والبصمة، والعلاقات والمعرّفات والقيم (`SnapshotValidator`).
- ترحيل اللقطات القديمة: `BackupMigrations` (خطوة لكل إصدار، مع اختبار).
