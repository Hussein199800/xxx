package app.rasidi.core

import app.rasidi.core.backup.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** R02: نسخ حقيقية مشفّرة أنتجها كود الإصدارين التاريخيين 1 و2 (انظر fixtures/README.md). */
class LegacyBackupTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()
    private fun expected(v: Int) = Json.parseToJsonElement(String(res("backup-data-v$v.expected.json"), Charsets.UTF_8)).jsonObject
    private fun code(v: Int) = RecoveryCode.parse(expected(v)["recoveryCode"]!!.jsonPrimitive.content)!!

    private fun checkFixture(v: Int) {
        val exp = expected(v)
        val opened = BackupFormat.decrypt(res("backup-data-v$v.rasidi"), code(v))
        assertEquals(v, opened.header.dataVersion)
        assertEquals(v, opened.sourceDataVersion)
        // بصمة الإصدار القديم تحققت على المحتوى الخام قبل الترحيل
        assertEquals(exp["fingerprint"]!!.jsonPrimitive.content, opened.sourceFingerprint)
        // بعد الترحيل: البصمة والأعداد محسوبة للّقطة الحالية فعلاً
        assertEquals(opened.payload.snapshot.fingerprint(), opened.payload.fingerprint)
        assertEquals(opened.payload.snapshot.counts(), opened.payload.counts)
        val s = opened.payload.snapshot
        exp["counts"]!!.jsonObject.forEach { (k, n) -> assertEquals("عدد $k", n.jsonPrimitive.int, s.counts()[k]) }
        exp["balances"]!!.jsonObject.forEach { (k, n) -> assertEquals("رصيد $k", n.jsonPrimitive.long, s.balances()[k]) }
        val parties = exp["debtParties"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(parties, s.parties.map { it.name }.sorted())
        s.debts.forEach { d -> assertTrue("دين بلا حساب", s.parties.any { it.name == d.party.trim() }) }
        assertTrue(SnapshotValidator.validate(s).isEmpty())
    }

    @Test fun v1FixtureOpensWithVerifiedFingerprint() = checkFixture(1)
    @Test fun v2FixtureOpensWithVerifiedFingerprint() = checkFixture(2)

    @Test fun legacyPayloadWithWrongFingerprintIsRejected() {
        val c = RecoveryCode.generate()
        val opened = BackupFormat.decrypt(res("backup-data-v1.rasidi"), code(1))
        // نعيد بناء حمولة v1 خام ببصمة خاطئة ونختمها بترويسة dataVersion=1
        val raw = BackupFormat.openRaw(res("backup-data-v1.rasidi"), code(1))
        val bad = JsonObject(raw + ("fingerprint" to JsonPrimitive("0".repeat(64))))
        val file = BackupFormat.sealRaw(bad.toString(), 1, c, "i", "MANUAL", 1, "t")
        val e = runCatching { BackupFormat.decrypt(file, c) }.exceptionOrNull() as? BackupFormat.BackupException
        assertEquals(BackupFormat.Reason.INVALID_DATA, e?.reason)
        // وتعديل الأعداد كذلك يُرفض
        val badCounts = JsonObject(raw + ("counts" to JsonObject(raw["counts"]!!.jsonObject + ("txns" to JsonPrimitive(1)))))
        val f2 = BackupFormat.sealRaw(badCounts.toString(), 1, c, "i", "MANUAL", 1, "t")
        assertEquals(BackupFormat.Reason.INVALID_DATA, (runCatching { BackupFormat.decrypt(f2, c) }.exceptionOrNull() as? BackupFormat.BackupException)?.reason)
        assertNotNull(opened)
    }

    @Test fun currentVersionTamperedFingerprintIsRejected() {
        val c = RecoveryCode.generate()
        val file = BackupFormat.encrypt(app.rasidi.core.demo.DemoData.snapshot(), c, "i", "MANUAL", 1, "t").second
        val raw = BackupFormat.openRaw(file, c)
        val bad = JsonObject(raw + ("fingerprint" to JsonPrimitive("f".repeat(64))))
        val f2 = BackupFormat.sealRaw(bad.toString(), BackupFormat.DATA_VERSION, c, "i", "MANUAL", 1, "t")
        assertEquals(BackupFormat.Reason.INVALID_DATA, (runCatching { BackupFormat.decrypt(f2, c) }.exceptionOrNull() as? BackupFormat.BackupException)?.reason)
    }

    @Test fun effectivelyEmptyRequiresNoUserRecords() {
        val defaults = app.rasidi.core.demo.DemoData.defaultCategories
        assertTrue(Snapshot().isEffectivelyEmpty())
        assertTrue(Snapshot(categories = defaults, settings = listOf(app.rasidi.core.data.SettingEntity("ui.theme", "dark"))).isEffectivelyEmpty())
        val party = app.rasidi.core.data.PartyEntity("p1", "خالد", "", "", false, 0)
        assertFalse(Snapshot(categories = defaults, parties = listOf(party)).isEffectivelyEmpty())
        assertFalse(Snapshot(settings = listOf(app.rasidi.core.data.SettingEntity("profile.name", "س"))).isEffectivelyEmpty())
        assertFalse(Snapshot(categories = defaults + defaults.first().copy(id = "cat-custom", name = "مخصص")).isEffectivelyEmpty())
        assertFalse(Snapshot(categories = defaults.drop(1) + defaults.first().copy(name = "معدّل")).isEffectivelyEmpty())
    }
}
