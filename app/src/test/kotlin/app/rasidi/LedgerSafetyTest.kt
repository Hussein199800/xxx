package app.rasidi

import app.rasidi.core.data.inventoryItems
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.core.valuation.InventoryCalc
import app.rasidi.data.LedgerException
import app.rasidi.data.LedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate

/** R04/R05/R06: عكس أصل الدين، والتراجع السريع، ومنع السالب بأثر رجعي. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LedgerSafetyTest {
    private val d = LocalDate.of(2026, 3, 20)
    private var now = 1_800_000_000_000L
    private fun repo(): LedgerRepository = TestUtil.repo(TestUtil.memDb()) { now }.also { runBlocking { it.ensureCategories() } }
    private fun fails(block: suspend () -> Unit): String? = runBlocking { runCatching { block() }.exceptionOrNull()?.let { (it as? LedgerException)?.message ?: "!${it::class.simpleName}: ${it.message}" } }
    private suspend fun bal(r: LedgerRepository, a: String) = r.view().let { v -> v.balance(v.accountById[a]!!).minor }
    private suspend fun debtRow(r: LedgerRepository, id: String) = r.view().let { v -> v.debts.first { it.id == id } to DebtCalc.remaining(v.debts.first { it.id == id }, v.payments) }
    private suspend fun newTxnOf(r: LedgerRepository, debtId: String) = r.snapshot().txns.first { it.debtId == debtId && it.type == TxnType.DEBT_NEW.name }.id

    // ---------------- R04 ----------------
    @Test fun reversingCashLoanCancelsPrincipalAndKeepsHistory() = runBlocking {
        for (dir in DebtDirection.values()) {
            val r = repo()
            val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 1_000_00, d.minusDays(5))
            val debt = r.addDebt("خالد", dir, 300_00, Currencies.USD, d, null, emptyList(), "", a, null, "n-$dir")
            r.reverse(newTxnOf(r, debt), "خطأ", "rev-$dir")
            assertEquals(1_000_00, bal(r, a))
            val (dd, rem) = debtRow(r, debt)
            assertEquals("$dir: المتبقي صفر بعد الإلغاء", 0, rem.minor)
            assertTrue(dd.cancelled)
            assertEquals(DebtStatus.CANCELLED, DebtCalc.status(dd, r.view().payments, d))
            // الحساب (الشخص) يُظهر الأصل والإلغاء، والرصيد صفر
            val v = r.view()
            assertTrue(PartyLedger.parties(v).single { it.party == "خالد" }.settled)
            assertEquals(2, PartyLedger.statement(v, "خالد", "USD", d.minusDays(1), d, false).lines.size)
            // لا يظهر في الجرد ولا يقبل دفعات
            assertTrue(v.inventoryItems(d).none { it.refId == debt })
            assertNotNull(fails { r.addDebtPayment(debt, 10_00, a, null, d, "", "p-after") })
            // السجل محفوظ: الدين والحركتان
            assertEquals(1, r.snapshot().debts.size)
            // طلب مكرر لا يعكس مرتين
            assertEquals(r.reverse(newTxnOf(r, debt), "خطأ", "rev-$dir"), r.snapshot().txns.single { it.reversesId == newTxnOf(r, debt) }.id)
        }
    }

    @Test fun cancellationSurvivesBackupRestore() = runBlocking {
        val r = repo()
        val debt = r.addDebt("خالد", DebtDirection.OWED_TO_ME, 70_00, Currencies.USD, d, null, emptyList(), "", null, null, "n")
        r.reverse(newTxnOf(r, debt), "", "rv")
        val code = app.rasidi.core.backup.RecoveryCode.generate()
        val bytes = app.rasidi.backup.BackupService(TestUtil.ctx, r, { "a" }).create(code, "MANUAL").bytes
        val dst = TestUtil.repo(TestUtil.memDb())
        val svc = app.rasidi.backup.BackupService(TestUtil.ctx, dst, { "b" })
        assertTrue(svc.restore(svc.preview(bytes, code), null) is app.rasidi.backup.BackupService.RestoreResult.Done)
        val (dd, rem) = debtRow(dst, debt)
        assertTrue(dd.cancelled); assertEquals(0, rem.minor)
    }

    @Test fun legacyDebtWithoutCashCanBeCancelled() = runBlocking {
        val r = repo()
        val debt = r.addDebt("أبو سامر", DebtDirection.I_OWE, 50_00, Currencies.TRY, d, null, emptyList(), "", null, null, "old")
        r.reverse(newTxnOf(r, debt), "", "rv")
        assertEquals(0, debtRow(r, debt).second.minor)
        assertTrue(PartyLedger.parties(r.view()).single().settled)
    }

    @Test fun cannotCancelDebtWithLivePaymentsButCanAfterReversingThem() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 1_000_00, d.minusDays(5))
        val debt = r.addDebt("خالد", DebtDirection.OWED_TO_ME, 300_00, Currencies.USD, d, null, emptyList(), "", a, null, "n")
        val p = r.addDebtPayment(debt, 100_00, a, null, d, "", "p1")
        val msg = fails { r.reverse(newTxnOf(r, debt), "", "rv") }
        assertNotNull("عكس أصل له دفعات حيّة مرفوض", msg)
        assertEquals(200_00, debtRow(r, debt).second.minor)
        r.reverse(p, "", "rp")
        r.reverse(newTxnOf(r, debt), "", "rv2")
        assertEquals(0, debtRow(r, debt).second.minor)
        assertEquals(1_000_00, bal(r, a))
    }

    @Test fun twoCurrencyPartyCancellationOnlyAffectsThatDebt() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 1_000_00, d.minusDays(5))
        val d1 = r.addDebt("خالد", DebtDirection.OWED_TO_ME, 100_00, Currencies.USD, d, null, emptyList(), "", a, null, "a")
        r.addDebt("خالد", DebtDirection.OWED_TO_ME, 500_00, Currencies.TRY, d, null, emptyList(), "", null, null, "b")
        r.reverse(newTxnOf(r, d1), "", "rv")
        val p = PartyLedger.parties(r.view()).single()
        assertEquals(0, p.balances["USD"]!!.minor); assertEquals(500_00, p.balances["TRY"]!!.minor)
    }

    // ---------------- R05 ----------------
    @Test fun undoRefusesReversedOriginalsAndLoneReversals() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d.minusDays(5))
        val t = r.addIncomeExpense(false, a, 10_00, "cat-food", d, "", "", "e1")
        val rv = r.reverse(t, "", "rv")
        assertNotNull(fails { r.undoRecent(t) })
        assertNotNull(fails { r.undoRecent(rv) })
        assertEquals(100_00, bal(r, a))
    }

    @Test fun undoWindowAndClockSkew() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d.minusDays(5))
        val t = r.addIncomeExpense(false, a, 10_00, "cat-food", d, "", "", "e1")
        now += 10 * 60_000 + 1
        assertNotNull("بعد عشر دقائق", fails { r.undoRecent(t) })
        now -= 20 * 60_000 // رجعت الساعة إلى ما قبل إنشاء الحركة
        assertNotNull("وقت إنشاء في المستقبل", fails { r.undoRecent(t) })
        now += 10 * 60_000 // ضمن المهلة تماماً
        r.undoRecent(t)
        assertEquals(100_00, bal(r, a))
    }

    @Test fun undoRefusesDebtWithPaymentsAndFinalInventory() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 1_000_00, d.minusDays(5))
        val debt = r.addDebt("خالد", DebtDirection.OWED_TO_ME, 300_00, Currencies.USD, d, null, emptyList(), "", a, null, "n")
        r.addDebtPayment(debt, 100_00, a, null, d, "", "p1")
        assertNotNull(fails { r.undoRecent(newTxnOf(r, debt)) })
        assertNotNull(r.snapshot().txns.firstOrNull { it.debtId == debt && it.type == TxnType.DEBT_NEW.name })
        // جرد معتمد بعد الحركة يغطي تاريخها
        val e = r.addIncomeExpense(false, a, 5_00, "cat-food", d, "", "", "e2")
        r.addRate("USD", BigDecimal.ONE, RateDirection.UNITS_PER_USD, d, "t", RateKind.MANUAL)
        val v = r.view()
        r.saveInventory(InventoryCalc.price(d, v.inventoryItems(d), v.rates), true, false, null, "")
        assertNotNull(fails { r.undoRecent(e) })
    }

    @Test fun undoRefusesWhatLaterSpendingDependsOn() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 0, d.minusDays(5))
        val inc = r.addIncomeExpense(true, a, 100_00, "cat-salary", d, "", "", "i")
        r.addIncomeExpense(false, a, 80_00, "cat-food", d.plusDays(1), "", "", "e")
        assertNotNull(fails { r.undoRecent(inc) })
        assertNotNull("والعكس كذلك", fails { r.reverse(inc, "", "rv") })
        assertEquals(20_00, bal(r, a))
        // الذهب: لا تراجع عن شراء بيع لاحقاً
        val h = r.addGoldHolding("ليرة", 21, GoldKind.SAVINGS, BigDecimal.ZERO, d.minusDays(5))
        val buy = r.goldTrade(h, true, BigDecimal(8), null, null, d, "", "gb")
        r.goldTrade(h, false, BigDecimal(5), null, null, d.plusDays(1), "", "gs")
        assertNotNull(fails { r.undoRecent(buy) })
        assertNotNull(fails { r.reverse(buy, "", "rg") })
    }

    // ---------------- R06 ----------------
    @Test fun nonPositiveAndOutOfRangeAmountsRejected() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d.minusDays(5))
        val b = r.createAccount("بنك", AccountType.BANK, Currencies.USD, 0, d.minusDays(5))
        val t = r.addIncomeExpense(false, a, 10_00, "cat-food", d, "", "", "e1")
        assertNotNull(fails { r.editSimple(t, 0, "cat-food", d, "", "") })
        assertNotNull(fails { r.editSimple(t, -5_00, "cat-food", d, "", "") })
        assertNotNull(fails { r.transfer(a, b, 10_00, 10_00, -1_00, null, d, "", "tr") })
        assertNotNull(fails { r.addIncomeExpense(true, a, MoneyPolicy.MAX_MINOR + 1, "cat-salary", d, "", "", "big") })
        val m = fails { r.addIncomeExpense(true, a, MoneyPolicy.MAX_MINOR, "cat-salary", d, "", "", "big3") }
        assertTrue("تجاوز الحد برسالة عربية: $m", m != null && !m.startsWith("!"))
        assertNotNull(fails { r.createAccount("سالب", AccountType.CASH, Currencies.USD, -5_00, d) })
        assertNotNull(fails { r.adjustToCount(b, -1_00, d, "adj") })
        val h = r.addGoldHolding("ليرة", 21, GoldKind.SAVINGS, BigDecimal.ONE, d.minusDays(5))
        assertNotNull(fails { r.goldTrade(h, true, BigDecimal.ONE, a, -3_00, d, "", "g") })
    }

    @Test fun movingExpenseEarlierChecksTheWholePath() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 0, d.minusDays(10))
        r.addIncomeExpense(true, a, 100_00, "cat-salary", d, "", "", "i")
        val e = r.addIncomeExpense(false, a, 60_00, "cat-food", d.plusDays(1), "", "", "e")
        // نقل المصروف إلى ما قبل الدخل يجعل الرصيد سالباً في ذلك اليوم
        assertNotNull(fails { r.editSimple(e, 60_00, "cat-food", d.minusDays(1), "", "") })
        // تقليل الدخل بأثر رجعي تحت ما صُرف لاحقاً
        val i2 = r.snapshot().txns.first { it.requestId == "i" }.id
        assertNotNull(fails { r.editSimple(i2, 50_00, "cat-salary", d, "", "") })
        assertEquals(40_00, bal(r, a))
        // نقل الدخل إلى ما بعد المصروف
        assertNotNull(fails { r.editSimple(i2, 100_00, "cat-salary", d.plusDays(2), "", "") })
        // تعديل سليم يمر
        r.editSimple(e, 70_00, "cat-food", d.plusDays(1), "", "")
        assertEquals(30_00, bal(r, a))
    }

    @Test fun backdatedEntryCannotBreakLaterDayAndSameDayIsEndOfDay() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d.minusDays(10))
        r.addIncomeExpense(false, a, 90_00, "cat-food", d, "", "", "late")
        assertNotNull("مصروف قديم يكسر رصيداً لاحقاً", fails { r.addIncomeExpense(false, a, 20_00, "cat-food", d.minusDays(3), "", "", "old") })
        // السياسة الموثّقة: الرصيد يُفحص في نهاية كل يوم؛ مصروف ثم دخل في اليوم نفسه مقبول
        val b = r.createAccount("ب", AccountType.CASH, Currencies.USD, 0, d.minusDays(10))
        r.addIncomeExpense(true, b, 50_00, "cat-salary", d, "", "", "bi")
        r.addIncomeExpense(false, b, 50_00, "cat-food", d, "", "", "be")
        assertEquals(0, bal(r, b))
    }
}
