# عينات نسخ احتياطية تاريخية (اصطناعية)

ملفات `.rasidi` حقيقية مشفّرة أنتجها **كود الإصدار التاريخي نفسه**، لا محاكاة بالكود الحالي.
البيانات اصطناعية بالكامل (`DemoData.snapshot(2026-11-20)`)، ورمز الاستعادة رمز اختبار ثابت
(البايتات 0..31) منشور عمداً في `*.expected.json` — لا يحمي أي بيانات حقيقية.

| الملف | DATA_VERSION | commit المولِّد |
|---|---|---|
| `backup-data-v1.rasidi` | 1 | `61e2ad9` |
| `backup-data-v2.rasidi` | 2 | `82999ee` |

طريقة التوليد: `git worktree add --detach <dir> <commit>` ثم إضافة اختبار `gen.FixtureGen` مؤقتاً
في `core/src/test/kotlin/gen/` يستدعي `BackupFormat.encrypt(DemoData.snapshot(LocalDate.of(2026,11,20)),
RecoveryCode.fromBytes(ByteArray(32){it.toByte()}), "fixture-install", "MANUAL", 1_790_000_000_000, "fixture")`
ويكتب الملف ومعه `expected.json` (البصمة الأصلية، الأعداد، الأرصدة، أسماء أطراف الديون)، وتشغيله بـ
`FIXTURE_OUT=<out> ./gradlew :core:test --tests 'gen.FixtureGen'`. المولّد لا يُضاف إلى الفرع.

تستخدمها `LegacyBackupTest` (core) و`RestoreProtocolTest` (app، عبر `sourceSets.test.resources`).
لا تُعدَّل هذه الملفات؛ أي تغيير في الترحيل يجب أن يبقى قادراً على فتحها.
