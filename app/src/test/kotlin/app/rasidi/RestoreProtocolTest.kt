package app.rasidi

import app.rasidi.backup.BackupService
import app.rasidi.core.backup.BackupFormat
import app.rasidi.core.backup.RecoveryCode
import app.rasidi.core.demo.DemoData
import app.rasidi.core.ledger.AccountType
import app.rasidi.core.money.Currencies
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** R01/R02: بروتوكول الاستعادة — مقارنة واستبدال وتحقق داخل معاملة واحدة، ونسخ الإصدارات القديمة حتى النهاية. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RestoreProtocolTest {
    private val d = LocalDate.of(2026, 3, 20)
    private fun res(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()
    private fun expected(v: Int) = Json.parseToJsonElement(String(res("backup-data-v$v.expected.json"), Charsets.UTF_8)).jsonObject

    private fun legacyRestoresEndToEnd(v: Int) = runBlocking {
        val name = "legacy-v$v-${System.nanoTime()}.db"
        val db = TestUtil.fileDb(name)
        val repo = TestUtil.repo(db)
        val svc = BackupService(TestUtil.ctx, repo, { "fresh" })
        val code = RecoveryCode.parse(expected(v)["recoveryCode"]!!.jsonPrimitive.content)!!
        val p = svc.preview(res("backup-data-v$v.rasidi"), code)
        assertTrue(p.currentEmpty)
        val r = svc.restore(p, null)
        assertTrue("الاستعادة يجب أن تكتمل: $r", r is BackupService.RestoreResult.Done)
        db.close()
        // إعادة فتح القاعدة: البيانات باقية ومطابقة
        val again = TestUtil.repo(TestUtil.fileDb(name))
        val s = again.snapshot()
        assertEquals(p.opened.payload.fingerprint, s.fingerprint())
        expected(v)["balances"]!!.jsonObject.forEach { (k, n) -> assertEquals(n.jsonPrimitive.long, s.balances()[k]) }
        assertEquals(3, s.parties.size)
        again.db.close()
    }

    @Test fun v1EncryptedBackupRestoresEndToEnd() = legacyRestoresEndToEnd(1)
    @Test fun v2EncryptedBackupRestoresEndToEnd() = legacyRestoresEndToEnd(2)

    @Test fun fingerprintMismatchAfterReplaceRollsBack() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb()); repo.ensureCategories()
        repo.createAccount("قديم", AccountType.CASH, Currencies.USD, 5_00, d)
        val before = repo.snapshot().fingerprint()
        val code = RecoveryCode.generate()
        val file = BackupFormat.encrypt(DemoData.snapshot(), code, "x", "MANUAL", 1, "t").second
        val svc = BackupService(TestUtil.ctx, repo, { "i" })
        val p = svc.preview(file, code)
        // معاينة تَعِد ببصمة لا تطابق ما سيُكتب فعلاً
        val lying = p.copy(opened = p.opened.copy(payload = p.opened.payload.copy(fingerprint = "0".repeat(64))))
        val e = runCatching { svc.restore(lying, code) }.exceptionOrNull()
        assertNotNull("يجب أن تفشل الاستعادة", e)
        assertEquals("السجل السابق يبقى كاملاً", before, repo.snapshot().fingerprint())
    }

    @Test fun ledgerWithOnlyPartiesIsNotTreatedAsEmpty() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb()); repo.ensureCategories()
        repo.addParty("خالد", "", "", 0, Currencies.USD, true, d, "p1")
        val before = repo.snapshot().fingerprint()
        val code = RecoveryCode.generate()
        val file = BackupFormat.encrypt(DemoData.snapshot(), code, "x", "MANUAL", 1, "t").second
        val svc = BackupService(TestUtil.ctx, repo, { "i" })
        val p = svc.preview(file, code)
        assertFalse("حساب شخص بلا حركات ليس سجلاً فارغاً", p.currentEmpty)
        assertNotNull(runCatching { svc.restore(p, null) }.exceptionOrNull())
        assertEquals(before, repo.snapshot().fingerprint())
    }

    @Test fun safetyWriteFailureLeavesLedgerIntact() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb()); repo.ensureCategories()
        repo.createAccount("قديم", AccountType.CASH, Currencies.USD, 5_00, d)
        val before = repo.snapshot().fingerprint()
        val code = RecoveryCode.generate()
        val file = BackupFormat.encrypt(DemoData.snapshot(), code, "x", "MANUAL", 1, "t").second
        val blocked = File(TestUtil.ctx.filesDir, "blocked-${System.nanoTime()}").apply { writeText("not a dir") }
        val svc = BackupService(TestUtil.ctx, repo, { "i" }, safetyRoot = { File(blocked, "safety") })
        val p = svc.preview(file, code)
        assertNotNull(runCatching { svc.restore(p, code) }.exceptionOrNull())
        assertEquals(before, repo.snapshot().fingerprint())
    }

    @Test fun writeArrivingDuringRestoreIsSerializedNotLost() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb()); repo.ensureCategories()
        val a = repo.createAccount("قديم", AccountType.CASH, Currencies.USD, 5_00, d)
        val code = RecoveryCode.generate()
        val demo = DemoData.snapshot()
        val file = BackupFormat.encrypt(demo, code, "x", "MANUAL", 1, "t").second
        val svc = BackupService(TestUtil.ctx, repo, { "i" })
        val p = svc.preview(file, code)
        // كتابة متزامنة تصل بعد المعاينة مباشرة: إما ترفض الاستعادة (Stale) أو تُطبَّق كاملة بعد الاستبدال، لا تختفي بصمت.
        var late: Throwable? = null
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
            runCatching { repo.addIncomeExpense(true, a, 1_00, "cat-salary", d, "", "", "late-write") }.onFailure { late = it }
        }
        val r = svc.restore(p, code)
        job.await()
        val s = repo.snapshot()
        val lateTxn = s.txns.any { it.requestId == "late-write" }
        when (r) {
            BackupService.RestoreResult.Stale -> assertTrue("عند الرفض تبقى الكتابة", lateTxn)
            is BackupService.RestoreResult.Done -> {
                // الكتابة جاءت بعد الاستبدال على حساب لم يعد موجوداً: يجب أن تفشل صراحةً لا أن تُفقد بصمت
                assertTrue("الكتابة إما محفوظة أو فشلت صراحةً", lateTxn || late != null)
                assertNotNull(r.safetyFile)
            }
        }
    }

    /** R13: نسخ الأمان تُقلَّم إلى آخر 10 بعد التحقق من الجديدة، ولا تُحذف الجديدة أبداً. */
    @Test fun safetyCopiesAreRetainedBounded() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb()); repo.ensureCategories()
        val root = File(TestUtil.ctx.filesDir, "safety-ret-${System.nanoTime()}")
        val svc = BackupService(TestUtil.ctx, repo, { "i" }, clock = { System.nanoTime() / 1000 }, safetyRoot = { root })
        val code = RecoveryCode.generate()
        var last: File? = null
        repeat(12) { n ->
            repo.createAccount("ح$n", AccountType.CASH, Currencies.USD, 1_00, d)
            val file = BackupFormat.encrypt(DemoData.snapshot(), code, "x", "MANUAL", 1, "t").second
            last = (svc.restore(svc.preview(file, code), code) as BackupService.RestoreResult.Done).safetyFile
        }
        val files = svc.safetyFiles()
        assertEquals(BackupService.SAFETY_KEEP, files.size)
        assertTrue(files.contains(last))
        files.forEach { assertNotNull(BackupFormat.decrypt(it.readBytes(), code)) }
    }
}
