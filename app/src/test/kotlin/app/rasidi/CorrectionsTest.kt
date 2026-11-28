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

/** F01–F03: تعديل الصندوق، وتصحيح افتتاحيه، وتصحيح الرصيد السابق لحساب شخص. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CorrectionsTest {
    private val d = LocalDate.of(2026, 3, 20)
    private fun repo(): LedgerRepository = TestUtil.repo(TestUtil.memDb()).also { runBlocking { it.ensureCategories() } }
    private fun err(block: suspend () -> Unit): Throwable? = runBlocking { runCatching { block() }.exceptionOrNull() }
    private suspend fun bal(r: LedgerRepository, a: String, on: LocalDate? = null) = r.view().let { v -> v.balance(v.accountById[a]!!, on).minor }

    @Test fun editWalletRules() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 0, d)
        r.createAccount("بنك", AccountType.BANK, Currencies.USD, 0, d)
        r.editAccount(a, "محفظة", AccountType.EWALLET, Currencies.TRY, false, false, "e1") // فارغ: تغيير العملة مسموح
        assertEquals("TRY", r.snapshot().accounts.first { it.id == a }.currency)
        assertTrue(err { r.editAccount(a, " ", AccountType.CASH, Currencies.TRY, false, false, "e2") } is LedgerException)
        assertTrue("اسم مكرر بالعملة نفسها", err { r.editAccount(a, "بنك", AccountType.CASH, Currencies.USD, false, false, "e3") } is LedgerException)
        r.addIncomeExpense(true, a, 10_00, "cat-salary", d, "", "", "i")
        assertTrue("عملة صندوق عليه قيود", err { r.editAccount(a, "محفظة", AccountType.EWALLET, Currencies.USD, false, false, "e4") } is LedgerException)
        assertTrue("أرشفة برصيد", err { r.editAccount(a, "محفظة", AccountType.EWALLET, Currencies.TRY, false, true, "e5") } is LedgerException)
        // السماح بالسالب ثم سحب يجعله سالباً، فلا يُلغى السماح
        r.editAccount(a, "محفظة", AccountType.EWALLET, Currencies.TRY, true, false, "e6")
        r.addIncomeExpense(false, a, 15_00, "cat-food", d.plusDays(1), "", "", "x")
        assertTrue(err { r.editAccount(a, "محفظة", AccountType.EWALLET, Currencies.TRY, false, false, "e7") } is LedgerException)
        assertTrue(r.snapshot().audit.any { it.entityId == a && it.action == "edit" })
        // نقر مكرر
        assertEquals(a, r.editAccount(a, "محفظة", AccountType.EWALLET, Currencies.TRY, true, false, "e6"))
    }

    @Test fun correctOpeningReversesAndRecreatesWithAudit() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d.minusDays(10))
        r.addIncomeExpense(false, a, 80_00, "cat-food", d, "", "", "e")
        // تخفيض الافتتاحي تحت ما صُرف لاحقاً مرفوض
        assertTrue(err { r.correctOpening(a, 50_00, d.minusDays(10), "خطأ إدخال", "c0") } is LedgerException)
        assertTrue("السبب مطلوب", err { r.correctOpening(a, 120_00, d.minusDays(10), " ", "c1") } is LedgerException)
        // تغيير المبلغ والتاريخ (المسار المجمّع سليم رغم أن الإزالة وحدها تكسره)
        r.correctOpening(a, 120_00, d.minusDays(12), "عدّ الافتتاح من جديد", "c2")
        r.correctOpening(a, 120_00, d.minusDays(12), "عدّ الافتتاح من جديد", "c2") // نقر مكرر
        assertEquals(40_00, bal(r, a))
        assertEquals(120_00, bal(r, a, d.minusDays(12)))
        val info = r.openingInfo(a)
        assertEquals(1, info.txnIds.size); assertEquals(120_00, info.amountMinor); assertEquals(d.minusDays(12), info.date)
        val s = r.snapshot()
        assertEquals("الأصل باقٍ ومعكوس", 1, s.txns.count { it.type == TxnType.OPENING.name && it.reversedById != null })
        assertTrue(s.audit.any { it.action == "openingCorrection" })
        // تقارير الدخل والمصروف تستبعد الافتتاحي
        val tot = Balances.statement(r.view().accountById[a]!!, r.view().entries, d.minusDays(30), d)
        assertNotNull(tot)
        assertEquals(0, r.view().entries.count { it.kind.countsAsIncome && it.accountId == a })
    }

    @Test fun addOpeningToZeroWalletAndNegativeOnlyIfAllowed() = runBlocking {
        val r = repo()
        val a = r.createAccount("صفر", AccountType.CASH, Currencies.USD, 0, d)
        assertEquals(0, r.openingInfo(a).txnIds.size)
        r.correctOpening(a, 30_00, d, "نسيت الرصيد", "o1")
        assertEquals(30_00, bal(r, a))
        assertTrue(err { r.correctOpening(a, -5_00, d, "س", "o2") } is LedgerException)
        val b = r.createAccount("بطاقة", AccountType.BANK, Currencies.USD, 0, d, allowNegative = true)
        r.correctOpening(b, -5_00, d, "دين بطاقة", "o3")
        assertEquals(-5_00, bal(r, b))
    }

    @Test fun openingCorrectionKeepsFinalInventorySnapshot() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d.minusDays(10))
        val v = r.view()
        val invId = r.saveInventory(InventoryCalc.price(d, v.inventoryItems(d), v.rates), true, false, null, "")
        val before = r.snapshot().inventories.first { it.id == invId }.pricedNetUsd
        assertTrue(r.openingInfo(a, d.minusDays(10)).affectsFinalInventory)
        r.correctOpening(a, 150_00, d.minusDays(10), "تصحيح", "c")
        assertEquals(before, r.snapshot().inventories.first { it.id == invId }.pricedNetUsd)
        assertTrue(r.snapshot().audit.any { it.action == "openingCorrection" && it.detail.contains("جرداً معتمداً") })
        // نسخة واستعادة تحفظ التصحيح
        val dst = repo(); dst.replaceAll(r.snapshot())
        assertEquals(150_00, dst.view().let { vv -> vv.balance(vv.accountById[a]!!).minor })
    }

    @Test fun partyPreviousBalanceCorrection() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 1_000_00, d.minusDays(10))
        r.addParty("خالد", "", "", 300_00, Currencies.USD, true, d, "p")
        r.addDebt("خالد", DebtDirection.I_OWE, 50_00, Currencies.TRY, d, null, emptyList(), "رصيد سابق", null, null, "p2")
        val usdDebt = r.snapshot().debts.first { it.currency == "USD" }.id
        r.addDebtPayment(usdDebt, 100_00, a, null, d, "", "pay")
        assertTrue("أقل من المسدَّد", err { r.correctDebtPrincipal(usdDebt, 90_00, "خطأ", "c1") } is LedgerException)
        r.correctDebtPrincipal(usdDebt, 250_00, "مراجعة الدفتر الورقي", "c2")
        val p = PartyLedger.parties(r.view()).single()
        assertEquals(150_00, p.balances["USD"]!!.minor); assertEquals(-50_00, p.balances["TRY"]!!.minor)
        // لا حركة صندوق للتصحيح
        assertEquals("التحصيل وحده دخل الصندوق، لا التصحيح", 1_100_00, bal(r, a))
        assertTrue(r.snapshot().audit.any { it.action == "principalCorrection" && it.detail == "old=30000 new=25000" })
        // قرض بحركة نقدية لا يُصحَّح هكذا
        val cash = r.addDebt("خالد", DebtDirection.OWED_TO_ME, 10_00, Currencies.USD, d, null, emptyList(), "", a, null, "p3")
        assertTrue(err { r.correctDebtPrincipal(cash, 20_00, "س", "c3") } is LedgerException)
    }
}
