package app.rasidi.core

import app.rasidi.core.ledger.GoldCalc
import app.rasidi.core.ledger.GoldKind
import app.rasidi.core.money.*
import app.rasidi.core.valuation.*
import app.rasidi.core.zakat.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ZakatTest {
    private val d = LocalDate.of(2026, 3, 20)
    private fun r(unit: String, v: String, dir: RateDirection = RateDirection.UNITS_PER_USD, date: LocalDate = d) = Rate("r-$unit", unit, BigDecimal(v), dir, date, "يدوي")
    private val rates = listOf(r("TRY", "40"), r("SYP", "120"), r(Rate.GOLD24_GRAM, "100", RateDirection.USD_PER_UNIT))
    private val items = listOf(
        InventoryItem("c1", LineKind.CASH, "دولار", "USD", BigDecimal(4000), "4,000 USD"),
        InventoryItem("c2", LineKind.CASH, "تركي", "TRY", BigDecimal(8000), "8,000 TRY"),
        InventoryItem("c3", LineKind.CASH, "سوري", "SYP", BigDecimal(12000), "12,000 SYP"),
        InventoryItem("g1", LineKind.GOLD, "ذهب 21", Rate.GOLD24_GRAM, GoldCalc.pureGrams(BigDecimal(80), 21), "80غ عيار 21", GoldKind.SAVINGS),
        InventoryItem("r1", LineKind.RECEIVABLE, "دين لي", "USD", BigDecimal(400), "400 USD"),
        InventoryItem("p1", LineKind.PAYABLE, "دين عليّ", "USD", BigDecimal(200), "200 USD"),
    )

    @Test fun acceptanceExample() {
        val inv = InventoryCalc.price(d, items, rates)
        assertTrue(inv.isComplete)
        val cashGold = inv.total(LineKind.CASH) + inv.total(LineKind.GOLD)
        assertEquals(0, cashGold.compareTo(BigDecimal(11300)))
        val res = ZakatCalc.compute(ZakatInput(inv,
            mapOf("r1" to LineDecision(Decision.INCLUDE, reason = "مرجوّ"), "p1" to LineDecision(Decision.INCLUDE, reason = "قسط مستحق")),
            NisabPolicy.Manual(BigDecimal(85), 24, true, "اختبار"), rates + r(Rate.GOLD24_GRAM, "100", RateDirection.USD_PER_UNIT), "1447/9/1", "اختيار المستخدم", true))
        assertEquals(0, res.baseUsd.compareTo(BigDecimal(11500)))
        assertEquals(0, res.nisabUsd!!.compareTo(BigDecimal(8500)))
        assertEquals(ZakatStatus.DUE, res.status)
        assertEquals(BigDecimal("287.50"), res.zakatUsd)

        // التوزيع: 120 USD + 2000 TRY + 14100 SYP
        val pays = listOf(
            ZakatPayment("z1", Money.of("120", Currencies.USD), RateBook.USD_IDENTITY, d, "a"),
            ZakatPayment("z2", Money.of("2000", Currencies.TRY), rates[0], d, "b"),
            ZakatPayment("z3", Money.of("14100", Currencies.SYP), rates[1], d, "c"),
        )
        val prog = Distribution.progress(res.zakatUsd, emptyList(), pays)
        assertEquals(BigDecimal("0.00"), prog.remainingUsd)
        assertEquals("المتبقي 0.00", prog.remainingLabel)
    }

    @Test fun nisabExactlyReachedIsDue() {
        val inv = InventoryCalc.price(d, listOf(InventoryItem("c", LineKind.CASH, "x", "USD", BigDecimal("8500"), "")), rates)
        val res = ZakatCalc.compute(ZakatInput(inv, emptyMap(), NisabPolicy.Gold85, rates, "h", "m", true))
        assertEquals(ZakatStatus.DUE, res.status)
        val inv2 = InventoryCalc.price(d, listOf(InventoryItem("c", LineKind.CASH, "x", "TRY", BigDecimal("339999.99"), "")), rates)
        // 339999.99/40 = 8499.99975 → يُقرَّب للعرض 8500.00 لكنه أقل من النصاب فعلياً
        val res2 = ZakatCalc.compute(ZakatInput(inv2, emptyMap(), NisabPolicy.Gold85, rates, "h", "m", true))
        assertEquals(ZakatStatus.BELOW_NISAB, res2.status)
        assertEquals(BigDecimal("8500.00"), InventoryCalc.round2(res2.baseUsd))
    }

    @Test fun jewelryAndExcludedDebtsNeedReview() {
        val inv = InventoryCalc.price(d, items + InventoryItem("g2", LineKind.GOLD, "حلق", Rate.GOLD24_GRAM, BigDecimal(10), "10غ", GoldKind.JEWELRY), rates)
        val res = ZakatCalc.compute(ZakatInput(inv, mapOf("r1" to LineDecision(Decision.EXCLUDE, reason = "متعثر"), "p1" to LineDecision(Decision.EXCLUDE)), NisabPolicy.Gold85, rates, "h", "m", true))
        assertEquals(ZakatStatus.NEEDS_REVIEW, res.status)
        assertTrue(res.reasons.any { it.contains("حلق") })
        assertEquals(BigDecimal("0.00"), res.zakatUsd)
    }

    @Test fun partialDeductionCappedAndHawlRequired() {
        val inv = InventoryCalc.price(d, items, rates)
        val over = ZakatCalc.compute(ZakatInput(inv, mapOf("r1" to LineDecision(Decision.INCLUDE), "p1" to LineDecision(Decision.PARTIAL, BigDecimal(300))), NisabPolicy.Gold85, rates, "h", "m", true))
        assertEquals(ZakatStatus.NEEDS_REVIEW, over.status)
        val ok = ZakatCalc.compute(ZakatInput(inv, mapOf("r1" to LineDecision(Decision.INCLUDE), "p1" to LineDecision(Decision.PARTIAL, BigDecimal(150))), NisabPolicy.Gold85, rates, "h", "m", false))
        assertEquals(ZakatStatus.NEEDS_REVIEW, ok.status)
        assertEquals(0, ok.baseUsd.compareTo(BigDecimal(11550)))
    }

    @Test fun missingOrFuturePriceBlocksValue() {
        val future = listOf(r("TRY", "40", date = d.plusDays(1)), r("SYP", "120"), r(Rate.GOLD24_GRAM, "100", RateDirection.USD_PER_UNIT))
        val inv = InventoryCalc.price(d, items, future)
        assertFalse(inv.isComplete)
        assertEquals(PriceState.MISSING, inv.lines.first { it.item.id == "c2" }.state)
        assertEquals(0, inv.pricedNet.compareTo(BigDecimal(4000 + 100 + 7000 + 400 - 200)))
        val older = listOf(r("TRY", "38", date = d.minusDays(3))) + future
        val inv2 = InventoryCalc.price(d, items, older)
        assertEquals(PriceState.SUGGESTED, inv2.lines.first { it.item.id == "c2" }.state)
        val inv3 = InventoryCalc.price(d, items, older, confirmedOlder = setOf("c2"))
        assertTrue(inv3.isComplete)
        assertTrue(inv3.explain().items.isNotEmpty())
    }

    @Test fun newRateTodayDoesNotChangeOldInventory() {
        val inv = InventoryCalc.price(d, items, rates)
        val later = InventoryCalc.price(d, items, rates + r("TRY", "50", date = d.plusDays(30)))
        assertEquals(0, inv.pricedNet.compareTo(later.pricedNet))
    }

    @Test fun oldSyrianValuedOnce() {
        val rd = SyrianRedenomination(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        val it = listOf(InventoryItem("o", LineKind.CASH, "قديم", "SYP_OLD", BigDecimal(1_200_000), "1,200,000 ل.س.ق"))
        val inv = InventoryCalc.price(d, it, rates, redenom = rd)
        assertEquals(0, inv.pricedNet.compareTo(BigDecimal(100)))
        // قبل النفاذ: يحتاج سعر «سوري قديم» صريحاً
        val before = InventoryCalc.price(LocalDate.of(2025, 12, 1), it, rates + r("SYP_OLD", "12000", date = LocalDate.of(2025, 12, 1)), redenom = rd)
        assertEquals(0, before.pricedNet.compareTo(BigDecimal(100)))
        // بعد انتهاء التداول: يحتاج قراراً
        val rd2 = SyrianRedenomination(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1))
        assertEquals(PriceState.NEEDS_DECISION, InventoryCalc.price(d, it, rates, redenom = rd2).lines[0].state)
        assertEquals(0, InventoryCalc.price(d, it, rates, redenom = rd2, oldSypDecisions = mapOf("o" to null)).pricedNet.compareTo(BigDecimal(100)))
    }

    @Test fun distributionRoundingResidual() {
        val tryRate = rates[0]; val syp = rates[1]
        val plan = Distribution.planByShares(BigDecimal("287.50"), listOf(
            Triple(BigDecimal("0.333333"), RateBook.USD_IDENTITY, Currencies.USD),
            Triple(BigDecimal("0.333333"), tryRate, Currencies.TRY),
            Triple(BigDecimal("0.333334"), syp, Currencies.SYP),
        ))
        val total = plan.fold(BigDecimal.ZERO) { a, p -> a + p.usd }
        assertTrue(total >= BigDecimal("287.50"))
        assertTrue(total - BigDecimal("287.50") < BigDecimal("0.01"))
        val (m, residual) = Distribution.shareIn(BigDecimal("10.005"), tryRate, Currencies.TRY)
        assertEquals(Money.of("400.20", Currencies.TRY), m)
        assertEquals(0, residual.compareTo(BigDecimal.ZERO))
    }
}
