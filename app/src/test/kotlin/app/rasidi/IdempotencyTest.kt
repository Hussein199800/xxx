package app.rasidi

import app.rasidi.core.data.inventoryItems
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.data.LedgerException
import app.rasidi.data.LedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate

/** R07–R10: شخص برصيده في معاملة واحدة، وعقد منع التكرار، وصحة الذهب ودفعات الزكاة. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IdempotencyTest {
    private val d = LocalDate.of(2026, 3, 20)
    private fun repo(db: app.rasidi.data.RasidiDatabase = TestUtil.memDb()): LedgerRepository = TestUtil.repo(db).also { runBlocking { it.ensureCategories() } }
    private fun err(block: suspend () -> Unit): Throwable? = runBlocking { runCatching { block() }.exceptionOrNull() }

    // ---------------- R07 ----------------
    @Test fun partyAndOpeningAreOneTransaction() = runBlocking {
        for (stage in listOf("afterParty", "afterDebt")) {
            val r = repo()
            val before = r.snapshot().fingerprint()
            r.partyHook = { if (it == stage) throw IllegalStateException("فشل مصطنع $stage") }
            assertNotNull(err { r.addParty("خالد", "", "", 500_00, Currencies.USD, true, d, "p-$stage") })
            assertEquals("$stage: لا شخص بلا رصيده ولا رصيد بلا شخص", before, r.snapshot().fingerprint())
            r.partyHook = null
            // الطلب نفسه بعد الفشل يُنفَّذ فعلاً (لم يُسجَّل كنجاح)
            r.addParty("خالد", "", "", 500_00, Currencies.USD, true, d, "p-$stage")
            assertEquals(1, r.snapshot().parties.size); assertEquals(1, r.snapshot().debts.size)
        }
    }

    @Test fun samePartyRequestReplaysNewRequestWithSameNameRejected() = runBlocking {
        val db = TestUtil.fileDb("party-${System.nanoTime()}.db")
        val r = repo(db)
        assertEquals("خالد", r.addParty("  خالد ", "", "", 0, Currencies.USD, true, d, "p1"))
        assertEquals("الطلب نفسه يعيد النتيجة لا «الاسم موجود»", "خالد", r.addParty("  خالد ", "", "", 0, Currencies.USD, true, d, "p1"))
        // بعد «إعادة التشغيل»: مستودع جديد على القاعدة نفسها
        val r2 = TestUtil.repo(db)
        assertEquals("خالد", r2.addParty("  خالد ", "", "", 0, Currencies.USD, true, d, "p1"))
        assertTrue(err { r2.addParty("خالد", "", "", 0, Currencies.USD, true, d, "p2") } is LedgerException)
        assertEquals(1, r2.snapshot().parties.size)
        assertEquals(0, r2.snapshot().debts.size)
        db.close()
    }

    @Test fun concurrentDoubleTapCreatesOnce() = runBlocking {
        val r = repo()
        val res = withContext(Dispatchers.IO) { (1..6).map { async { runCatching { r.addParty("سامر", "", "", 100_00, Currencies.TRY, false, d, "same") } } }.awaitAll() }
        assertTrue(res.all { it.getOrNull() == "سامر" })
        assertEquals(1, r.snapshot().parties.size); assertEquals(1, r.snapshot().debts.size)
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d)
        val ids = withContext(Dispatchers.IO) { (1..6).map { async { r.addIncomeExpense(false, a, 5_00, "cat-food", d, "", "", "tap") } }.awaitAll() }
        assertEquals(1, ids.toSet().size)
        assertEquals(1, r.snapshot().txns.count { it.requestId == "tap" })
    }

    // ---------------- R08 ----------------
    @Test fun sameRequestIdWithDifferentPayloadIsRejected() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d, requestId = "acc")
        assertEquals("إعادة إنشاء الحساب بالطلب نفسه", a, r.createAccount("نقد", AccountType.CASH, Currencies.USD, 100_00, d, requestId = "acc"))
        assertEquals(1, r.snapshot().accounts.size)
        r.addIncomeExpense(false, a, 5_00, "cat-food", d, "", "", "e1")
        val e = err { r.addIncomeExpense(false, a, 7_00, "cat-food", d, "", "", "e1") }
        assertTrue("لا يُعاد نجاح عملية مختلفة: $e", e is LedgerRepository.RequestConflictException)
        assertTrue(err { r.transfer(a, a, 1, 1, 0, null, d, "", "e1") } != null)
        assertEquals(95_00, r.view().let { v -> v.balance(v.accountById[a]!!).minor })
    }

    @Test fun zeroOpeningAccountAndNoOpCountAreIdempotent() = runBlocking {
        val r = repo()
        val a = r.createAccount("صفر", AccountType.CASH, Currencies.USD, 0, d, requestId = "z")
        assertEquals(a, r.createAccount("صفر", AccountType.CASH, Currencies.USD, 0, d, requestId = "z"))
        assertEquals(1, r.snapshot().accounts.size)
        // عدّ يساوي الرصيد: لا أثر، ويُسجَّل كذلك فلا يُعاد تفسيره بعد تغيّر الرصيد
        assertNull(r.adjustToCount(a, 0, d, "cnt"))
        r.addIncomeExpense(true, a, 10_00, "cat-salary", d, "", "", "inc")
        assertNull(r.adjustToCount(a, 0, d, "cnt"))
        assertEquals(10_00, r.view().let { v -> v.balance(v.accountById[a]!!).minor })
    }

    @Test fun failedOperationDoesNotConsumeRequest() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 0, d)
        assertTrue(err { r.addIncomeExpense(false, a, 5_00, "cat-food", d, "", "", "x") } is LedgerException)
        r.addIncomeExpense(true, a, 10_00, "cat-salary", d.minusDays(1), "", "", "fund")
        r.addIncomeExpense(false, a, 5_00, "cat-food", d, "", "", "x")
        assertEquals(5_00, r.view().let { v -> v.balance(v.accountById[a]!!).minor })
    }

    @Test fun onWriteFailureDoesNotFailCommittedWrite() = runBlocking {
        val r = LedgerRepository(TestUtil.memDb(), System::currentTimeMillis) { throw IllegalStateException("callback") }
        r.ensureCategories()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 10_00, d)
        assertNotNull(r.view().accountById[a])
        assertTrue(r.version.value > 0)
    }

    // ---------------- R09 ----------------
    @Test fun goldTradeNeverSilentlyDropsCash() = runBlocking {
        val r = repo()
        val a = r.createAccount("نقد", AccountType.CASH, Currencies.USD, 1_000_00, d.minusDays(3))
        val h = r.addGoldHolding("ليرة", 21, GoldKind.SAVINGS, BigDecimal.ONE, d.minusDays(3))
        assertTrue("صندوق بلا مبلغ", err { r.goldTrade(h, true, BigDecimal(2), a, null, d, "", "g1") } is LedgerException)
        assertTrue("مبلغ صفر", err { r.goldTrade(h, true, BigDecimal(2), a, 0, d, "", "g2") } is LedgerException)
        assertTrue("مبلغ بلا صندوق", err { r.goldTrade(h, true, BigDecimal(2), null, 50_00, d, "", "g3") } is LedgerException)
        assertTrue("دقة أكبر من 3 منازل", err { r.goldTrade(h, true, BigDecimal("1.0001"), null, null, d, "", "g4") } is LedgerException)
        assertTrue("اسم فارغ", err { r.addGoldHolding(" ", 21, GoldKind.SAVINGS, BigDecimal.ZERO, d) } is LedgerException)
        assertTrue("افتتاحي سالب", err { r.addGoldHolding("س", 21, GoldKind.SAVINGS, BigDecimal(-1), d) } is LedgerException)
        val t = r.goldTrade(h, true, BigDecimal(2), a, 150_00, d, "", "g5")
        val s = r.snapshot()
        assertEquals(1, s.entries.count { it.txnId == t }); assertEquals(1, s.goldMoves.count { it.txnId == t })
        val w = r.goldTrade(h, true, BigDecimal(1), null, null, d, "", "g6")
        assertTrue(r.snapshot().txns.first { it.id == w }.note.contains("بلا حركة نقدية"))
        assertEquals(0, r.snapshot().entries.count { it.txnId == w })
        // بيع بتاريخ قديم يتجاوز ما كان متاحاً حينها
        assertTrue(err { r.goldTrade(h, false, BigDecimal(2), null, null, d.minusDays(1), "", "g7") } is LedgerException)
    }

    // ---------------- R10 ----------------
    @Test fun zakatPaymentRateMustMatchAccountAndDay() = runBlocking {
        val r = repo()
        val tl = r.createAccount("ليرة", AccountType.CASH, Currencies.TRY, 10_000_00, d.minusDays(30))
        val usd = r.createAccount("دولار", AccountType.CASH, Currencies.USD, 1_000_00, d.minusDays(30))
        r.addRate("TRY", BigDecimal(40), RateDirection.UNITS_PER_USD, d, "t", RateKind.LOCAL)
        r.addRate(Rate.GOLD24_GRAM, BigDecimal(100), RateDirection.USD_PER_UNIT, d, "t", RateKind.MANUAL)
        val v = r.view()
        val inv = app.rasidi.core.valuation.InventoryCalc.price(d, v.inventoryItems(d), v.rates)
        val invId = r.saveInventory(inv, true, false, null, "")
        val z = app.rasidi.core.zakat.ZakatCalc.compute(app.rasidi.core.zakat.ZakatInput(inv, inv.lines.associate { it.item.id to app.rasidi.core.zakat.LineDecision(app.rasidi.core.zakat.Decision.INCLUDE) },
            app.rasidi.core.zakat.NisabPolicy.Manual(BigDecimal(1), 24, true, "t"), v.rates, "1447", "t", true))
        val est = r.saveZakat(invId, z, "{}")
        val tryRate = r.view().rates.first { it.unit == "TRY" }
        assertTrue("سعر بعملة أخرى", err { r.addZakatPayment(est, usd, 10_00, tryRate, d, "", "z1") } is LedgerException)
        assertTrue("سعر يوم آخر", err { r.addZakatPayment(est, tl, 100_00, tryRate.copy(date = d.minusDays(1)), d, "", "z2") } is LedgerException)
        assertTrue("سعر مستقبلي", err { r.addZakatPayment(est, tl, 100_00, tryRate.copy(date = d.plusDays(1)), d.plusDays(1).minusDays(1), "", "z3") } is LedgerException)
        assertTrue("دولار بغير 1", err { r.addZakatPayment(est, usd, 10_00, Rate("x", "USD", BigDecimal(2), RateDirection.UNITS_PER_USD, d), d, "", "z4") } is LedgerException)
        assertTrue("تقدير غير موجود", err { r.addZakatPayment("nope", tl, 100_00, tryRate, d, "", "z5") } is LedgerException)
        assertTrue("قبل تاريخ الجرد", err { r.addZakatPayment(est, usd, 10_00, RateBook.USD_IDENTITY.copy(date = d.minusDays(1)), d.minusDays(1), "", "z6") } is LedgerException)
        val t = r.addZakatPayment(est, tl, 400_00, tryRate, d, "", "z7")
        assertEquals(t, r.addZakatPayment(est, tl, 400_00, tryRate, d, "", "z7"))
        assertEquals(1, r.snapshot().zakatPayments.size)
        r.reverse(t, "", "zr")
        val s = r.snapshot()
        assertEquals(0, s.zakatPayments.sumOf { it.amountMinor })
        assertEquals(10_000_00, r.view().let { vv -> vv.balance(vv.accountById[tl]!!).minor })
    }

    @Test fun migration3to4KeepsDataAndAddsRequests() = runBlocking {
        // يغطيه MigrationAndUpgradeTest لسلسلة الترحيل؛ هنا: الطلبات لا تدخل في النسخ
        val r = repo()
        r.createAccount("نقد", AccountType.CASH, Currencies.USD, 10_00, d, requestId = "acc")
        assertFalse(app.rasidi.core.backup.BackupJson.encodeToString(app.rasidi.core.backup.Snapshot.serializer(), r.snapshot()).contains("\"acc\"") && r.snapshot().txns.none { it.requestId == "acc" })
        val dst = repo(); dst.replaceAll(r.snapshot())
        // بعد الاستعادة: إعادة الطلب نفسه تعيد الحساب نفسه (من txns.requestId) لا حساباً ثانياً
        assertEquals(r.snapshot().accounts.single().id, dst.createAccount("نقد", AccountType.CASH, Currencies.USD, 10_00, d, requestId = "acc"))
        assertEquals(1, dst.snapshot().accounts.size)
    }
}
