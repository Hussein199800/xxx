package app.rasidi

import app.rasidi.backup.BackupService
import app.rasidi.core.backup.BackupFormat
import app.rasidi.core.backup.RecoveryCode
import app.rasidi.core.data.inventoryItems
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.core.valuation.InventoryCalc
import app.rasidi.core.zakat.*
import app.rasidi.data.LedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupRestoreTest {
    private val d = LocalDate.of(2026, 3, 20)

    /** دفتر يشمل كل أنواع السجلات: حركات وديون ودفعات وذهب وأسعار وجرد معتمد وتقدير زكاة ودفعاتها. */
    private suspend fun richLedger(repo: LedgerRepository) {
        repo.ensureCategories()
        val usd = repo.createAccount("نقد دولار", AccountType.CASH, Currencies.USD, 4_000_00, d.minusMonths(13))
        val tl = repo.createAccount("بنك تركي", AccountType.BANK, Currencies.TRY, 8_000_00, d.minusMonths(13))
        val sy = repo.createAccount("نقد سوري", AccountType.CASH, Currencies.SYP, 12_000_00, d.minusMonths(13))
        repo.addGoldHolding("ليرات", 21, GoldKind.SAVINGS, BigDecimal(80), d.minusMonths(13))
        val recv = repo.addDebt("أبو سامر", DebtDirection.OWED_TO_ME, 400_00, Currencies.USD, d.minusMonths(6), null, emptyList(), "", null, null, "dr")
        repo.addDebt("المحل", DebtDirection.I_OWE, 200_00, Currencies.USD, d.minusMonths(2), d.plusMonths(1), emptyList(), "", null, null, "dp")
        repo.addIncomeExpense(false, usd, 25_00, "cat-food", d.minusDays(3), "سوق", "", "e1")
        repo.addIncomeExpense(true, usd, 25_00, "cat-salary", d.minusDays(2), "", "", "e2")
        repo.addRate("TRY", BigDecimal(40), RateDirection.UNITS_PER_USD, d, "صرّاف", RateKind.LOCAL)
        repo.addRate("SYP", BigDecimal(120), RateDirection.UNITS_PER_USD, d, "صرّاف", RateKind.LOCAL)
        repo.addRate(Rate.GOLD24_GRAM, BigDecimal(100), RateDirection.USD_PER_UNIT, d, "صائغ", RateKind.MANUAL)
        val v = repo.view()
        val inv = InventoryCalc.price(d, v.inventoryItems(d), v.rates)
        assertTrue(inv.isComplete)
        val invId = repo.saveInventory(inv, true, false, null, "")
        val decisions = inv.lines.associate { it.item.id to LineDecision(Decision.INCLUDE, reason = "اختبار") }
        val z = ZakatCalc.compute(ZakatInput(inv, decisions, NisabPolicy.Manual(BigDecimal(85), 24, true, "اختبار"), v.rates, "1447/09/01", "يدوي", true))
        assertEquals(BigDecimal("287.50"), z.zakatUsd)
        val est = repo.saveZakat(invId, z, "{}")
        // دخل لاحق يغطي الدفع بالليرة السورية (الدفعات بعد تاريخ الجرد وبأسعاره نفسها)
        val pd = d.plusDays(1)
        repo.addIncomeExpense(true, sy, 5_000_00, "cat-gift", pd, "", "", "inc-syp")
        repo.addZakatPayment(est, usd, 120_00, RateBook.USD_IDENTITY.copy(date = pd), pd, "جمعية", "z1")
        repo.addZakatPayment(est, tl, 2_000_00, v.rates.first { it.unit == "TRY" }, pd, "", "z2")
        repo.addZakatPayment(est, sy, 14_100_00, v.rates.first { it.unit == "SYP" }, pd, "", "z3")
        val paid = repo.snapshot().zakatPayments.fold(BigDecimal.ZERO) { a, p -> a + BigDecimal(p.usd) }
        assertEquals("المتبقي 0.00", Distribution.Progress(BigDecimal("287.50"), BigDecimal.ZERO, paid).remainingLabel)
        repo.addDebtPayment(recv, 100_00, usd, null, d, "", "rp")
    }

    @Test fun encryptedBackupRestoresIntoEmptyDatabase() = runBlocking {
        val src = TestUtil.repo(TestUtil.memDb())
        richLedger(src)
        val code = RecoveryCode.generate()
        val created = BackupService(TestUtil.ctx, src, { "install-A" }).create(code, "MANUAL")
        val before = src.snapshot()

        // تثبيت جديد: قاعدة فارغة
        val dst = TestUtil.repo(TestUtil.memDb())
        val svc = BackupService(TestUtil.ctx, dst, { "install-B" })
        val preview = svc.preview(created.bytes, RecoveryCode.parse(code.display())!!)
        assertTrue(preview.currentEmpty)
        assertEquals(before.counts(), preview.backupCounts)
        val r = svc.restore(preview, null)
        assertTrue(r is BackupService.RestoreResult.Done)
        val after = dst.snapshot()
        assertEquals(before.fingerprint(), after.fingerprint())
        assertEquals(before.counts(), after.counts())
        assertEquals(before.balances(), after.balances())
        listOf("accounts", "txns", "debts", "debtPayments", "goldMoves", "rates", "inventories", "zakatEstimates", "zakatPayments").forEach { assertTrue("$it فارغ", (after.counts()[it] ?: 0) > 0) }
    }

    @Test fun wrongCodeOrCorruptFileStopsBeforeWriting() = runBlocking {
        val src = TestUtil.repo(TestUtil.memDb()); richLedger(src)
        val created = BackupService(TestUtil.ctx, src, { "i" }).create(RecoveryCode.generate(), "MANUAL")
        val dst = TestUtil.repo(TestUtil.memDb()); dst.ensureCategories()
        dst.createAccount("موجود", AccountType.CASH, Currencies.USD, 1_00, d)
        val fp = dst.snapshot().fingerprint()
        val svc = BackupService(TestUtil.ctx, dst, { "i2" })
        assertTrue(runCatching { svc.preview(created.bytes, RecoveryCode.generate()) }.exceptionOrNull() is BackupFormat.BackupException)
        val corrupt = created.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertTrue(runCatching { svc.preview(corrupt, RecoveryCode.generate()) }.exceptionOrNull() is BackupFormat.BackupException)
        assertEquals(fp, dst.snapshot().fingerprint())
    }

    @Test fun restoreOverExistingCreatesSafetyAndDetectsStalePreview() = runBlocking {
        val src = TestUtil.repo(TestUtil.memDb()); richLedger(src)
        val code = RecoveryCode.generate()
        val created = BackupService(TestUtil.ctx, src, { "i" }).create(code, "MANUAL")
        val dst = TestUtil.repo(TestUtil.memDb()); dst.ensureCategories()
        val a = dst.createAccount("قديم", AccountType.CASH, Currencies.USD, 5_00, d)
        val svc = BackupService(TestUtil.ctx, dst, { "i2" })
        val p = svc.preview(created.bytes, code)
        assertFalse(p.currentEmpty)
        // تغيّر السجل بعد المعاينة
        dst.addIncomeExpense(true, a, 1_00, "cat-salary", d, "", "", "late")
        assertEquals(BackupService.RestoreResult.Stale, svc.restore(p, code))
        val p2 = svc.preview(created.bytes, code)
        val r = svc.restore(p2, code) as BackupService.RestoreResult.Done
        assertNotNull(r.safetyFile)
        // نسخة الأمان تحتوي السجل السابق كاملاً
        val safety = BackupFormat.decrypt(r.safetyFile!!.readBytes(), code)
        assertEquals(1, safety.payload.snapshot.accounts.size)
        assertEquals(src.snapshot().fingerprint(), dst.snapshot().fingerprint())
    }

    @Test fun failedReplaceLeavesPreviousLedgerIntact() = runBlocking {
        val dst = TestUtil.repo(TestUtil.memDb()); dst.ensureCategories()
        dst.createAccount("قديم", AccountType.CASH, Currencies.USD, 5_00, d)
        val fp = dst.snapshot().fingerprint()
        // لقطة تخالف المفاتيح الأجنبية: الاستبدال يفشل داخل المعاملة
        val bad = app.rasidi.core.demo.DemoData.snapshot().let { s -> s.copy(entries = s.entries + s.entries.first().copy(id = "orphan", txnId = "missing-txn")) }
        val e = runCatching { dst.replaceAll(bad) }.exceptionOrNull()
        assertNotNull(e)
        assertEquals("إما السجل السابق كاملاً أو الجديد كاملاً", fp, dst.snapshot().fingerprint())
    }

    @Test fun demoDataRoundTripsThroughRoom() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb())
        val demo = app.rasidi.core.demo.DemoData.snapshot()
        repo.replaceAll(demo)
        assertEquals(demo.fingerprint(), repo.snapshot().fingerprint())
    }
}
