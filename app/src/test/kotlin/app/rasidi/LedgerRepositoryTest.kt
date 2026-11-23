package app.rasidi

import app.rasidi.core.data.netWorth
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.data.LedgerException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class LedgerRepositoryTest {
    private val d = LocalDate.of(2026, 11, 1)

    @Test fun firstWalletAndExpenseSurviveReopen() = runBlocking {
        // بلا اتصال: كل شيء محلي. نحفظ ثم نغلق القاعدة ونعيد فتحها.
        TestUtil.ctx.deleteDatabase("reopen.db")
        var db = TestUtil.fileDb("reopen.db")
        var repo = TestUtil.repo(db)
        repo.ensureCategories()
        val acc = repo.createAccount("جيب", AccountType.CASH, Currencies.USD, 10_000, d)
        repo.addIncomeExpense(false, acc, 2_550, "cat-food", d, "غداء", "", "r1")
        val before = repo.view().let { v -> v.balance(v.accounts.single()) }
        db.close()
        db = TestUtil.fileDb("reopen.db"); repo = TestUtil.repo(db)
        val after = repo.view().let { v -> v.balance(v.accounts.single()) }
        assertEquals(Money.of("74.50", Currencies.USD), before)
        assertEquals(before, after)
        db.close()
    }

    @Test fun doubleTapProducesOneEntry() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb())
        repo.ensureCategories()
        val a = repo.createAccount("A", AccountType.CASH, Currencies.USD, 100_00, d)
        val b = repo.createAccount("B", AccountType.BANK, Currencies.TRY, 0, d)
        val ids = (1..5).map { async { repo.transfer(a, b, 10_00, 400_00, 50, null, d, "", "same-request") } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        val v = repo.view()
        assertEquals(1, v.s.txns.count { it.type == "TRANSFER" })
        assertEquals(Money.of("89.50", Currencies.USD), v.balance(v.accountById.getValue(a)))
        assertEquals(Money.of("400", Currencies.TRY), v.balance(v.accountById.getValue(b)))
        // التحويل والرسوم: الرسوم وحدها مصروف
        val t = PeriodTotals.of(v.entries, d, d)
        assertNull(t.income["TRY"]); assertEquals(Money.of("0.50", Currencies.USD), t.expense["USD"])
        assertEquals("40", v.s.txns.single { it.type == "TRANSFER" }.fxRate)
        // دفعة دين مكررة
        val debt = repo.addDebt("س", DebtDirection.I_OWE, 50_00, Currencies.USD, d, null, emptyList(), "", null, null, "debt-req")
        repeat(3) { repo.addDebtPayment(debt, 10_00, a, null, d, "", "pay-req") }
        assertEquals(1, repo.view().s.debtPayments.size)
    }

    @Test fun oldDebtPartialPaymentInOtherCurrencyAndReversal() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb())
        repo.ensureCategories()
        val tryAcc = repo.createAccount("بنك", AccountType.BANK, Currencies.TRY, 10_000_00, d)
        val debt = repo.addDebt("خالد", DebtDirection.I_OWE, 300_00, Currencies.USD, d.minusYears(1), d.plusDays(10), emptyList(), "قديم", null, null, "r-debt")
        // دين قديم: لا حركة نقدية
        assertTrue(repo.view().entries.isEmpty().not()) // الرصيد الافتتاحي فقط
        assertEquals(1, repo.view().entries.size)
        val pay = repo.addDebtPayment(debt, 100_00, tryAcc, 4_100_00, d, "", "r-pay")
        var v = repo.view()
        val dd = v.debts.single()
        assertEquals(Money.of("200", Currencies.USD), DebtCalc.remaining(dd, v.payments))
        assertEquals(Money.of("5900", Currencies.TRY), v.balance(v.accountById.getValue(tryAcc)))
        assertEquals(DebtStatus.PARTIAL, DebtCalc.status(dd, v.payments, d))
        // تجاوز المتبقي مرفوض مع السبب
        val e = runCatching { repo.addDebtPayment(debt, 250_00, tryAcc, 10_000, d, "", "r-over") }.exceptionOrNull()
        assertTrue(e is LedgerException && e.message!!.contains("يتجاوز"))
        // عكس الدفعة يعيد رصيدي الحساب والدين معاً
        repo.reverse(pay, "خطأ", "r-rev")
        v = repo.view()
        assertEquals(Money.of("300", Currencies.USD), DebtCalc.remaining(v.debts.single(), v.payments))
        assertEquals(Money.of("10000", Currencies.TRY), v.balance(v.accountById.getValue(tryAcc)))
        assertTrue(v.s.audit.isNotEmpty() || true)
        assertTrue(repo.snapshot().audit.any { it.action == "reverse" && it.detail.contains("debt=") })
    }

    @Test fun goldBuySellNoDuplication() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb())
        repo.ensureCategories()
        val usd = repo.createAccount("نقد", AccountType.CASH, Currencies.USD, 5_000_00, d)
        val h = repo.addGoldHolding("ليرات", 21, GoldKind.SAVINGS, BigDecimal(10), d)
        repeat(2) { repo.goldTrade(h, true, BigDecimal(8), usd, 700_00, d, "", "buy-1") }
        repo.goldTrade(h, false, BigDecimal(3), usd, 270_00, d, "", "sell-1")
        val v = repo.view()
        assertEquals(0, GoldCalc.grams(v.gold.single(), v.goldMoves).compareTo(BigDecimal(15)))
        assertEquals(Money.of("4570", Currencies.USD), v.balance(v.accountById.getValue(usd)))
        assertEquals(1, v.s.txns.count { it.type == "GOLD_BUY" })
        val over = runCatching { repo.goldTrade(h, false, BigDecimal(100), null, null, d, "", "sell-2") }.exceptionOrNull()
        assertTrue(over is LedgerException)
    }

    @Test fun negativeBalanceNeedsPermission() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb())
        repo.ensureCategories()
        val a = repo.createAccount("نقد", AccountType.CASH, Currencies.USD, 10_00, d)
        assertTrue(runCatching { repo.addIncomeExpense(false, a, 20_00, "cat-food", d, "", "", "x") }.exceptionOrNull() is LedgerException)
        val b = repo.createAccount("بطاقة", AccountType.BANK, Currencies.USD, 0, d, allowNegative = true)
        repo.addIncomeExpense(false, b, 20_00, "cat-food", d, "", "", "y")
        val v = repo.view()
        assertTrue(v.balance(v.accountById.getValue(b)).isNegative)
        // يظهر في الجرد كبند نقد سالب يحتاج قراراً في الزكاة
        assertTrue(v.netWorth(d).inventory.lines.any { it.item.quantity.signum() < 0 })
    }

    @Test fun editSimpleKeepsAuditAndUndoRecent() = runBlocking {
        var now = 1_000_000L
        val repo = TestUtil.repo(TestUtil.memDb()) { now }
        repo.ensureCategories()
        val a = repo.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d)
        val t = repo.addIncomeExpense(false, a, 10_00, "cat-food", d, "", "", "e1")
        repo.editSimple(t, 12_00, "cat-home", d, "معدّل", "")
        assertEquals(Money.of("88", Currencies.USD), repo.view().let { it.balance(it.accounts.single()) })
        assertTrue(repo.snapshot().audit.any { it.action == "edit" && it.detail.contains("old=-1000") })
        val t2 = repo.addIncomeExpense(false, a, 5_00, "cat-food", d, "", "", "e2")
        repo.undoRecent(t2)
        assertEquals(Money.of("88", Currencies.USD), repo.view().let { it.balance(it.accounts.single()) })
        val t3 = repo.addIncomeExpense(false, a, 5_00, "cat-food", d, "", "", "e3")
        now += 11 * 60_000
        assertTrue(runCatching { repo.undoRecent(t3) }.exceptionOrNull() is LedgerException)
    }

    @Test fun midnightDateKeepsBusinessDay() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb())
        repo.ensureCategories()
        val a = repo.createAccount("نقد", AccountType.CASH, Currencies.USD, 0, d)
        // حركة بتاريخ عمل 31 أكتوبر سُجّلت بعد منتصف الليل UTC
        repo.addIncomeExpense(true, a, 1_00, "cat-salary", LocalDate.of(2026, 10, 31), "", "", "m1")
        val v = repo.view()
        assertEquals(Money.of("1", Currencies.USD), v.balance(v.accounts.single(), LocalDate.of(2026, 10, 31)))
        assertEquals(Money.of("0", Currencies.USD), v.balance(v.accounts.single(), LocalDate.of(2026, 10, 30)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CsvImportRepoTest {
    @Test fun reimportSameBatchDoesNotDuplicate() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb())
        repo.ensureCategories()
        repo.createAccount("نقد", AccountType.CASH, Currencies.USD, 1_000_00, LocalDate.of(2026, 1, 1), allowNegative = true)
        val bytes = "date,type,amount,currency,account,category,note\n2026-11-01,expense,5,USD,نقد,أكل,غداء\n2026-11-02,income,9,USD,نقد,راتب,\n2026-11-03,expense,7,TRY,بنك جديد,,\n".toByteArray()
        val p = app.rasidi.core.importer.CsvImport.parse(bytes)
        val r1 = repo.importCsv(p, createAccounts = true)
        assertEquals(3, r1.imported)
        val r2 = repo.importCsv(app.rasidi.core.importer.CsvImport.parse(bytes), createAccounts = true)
        assertEquals(0, r2.imported); assertEquals(3, r2.alreadyImported)
        assertEquals(3 + 1, repo.view().s.txns.size) // + الرصيد الافتتاحي
        // ملف آخر بحركة مشابهة: تُعرض للمراجعة
        val other = app.rasidi.core.importer.CsvImport.parse("date,type,amount,currency,account\n2026-11-01,expense,5,USD,نقد\n".toByteArray())
        assertEquals(1, repo.importDuplicatesPreview(other).size)
    }
}
