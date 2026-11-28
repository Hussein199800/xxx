package app.rasidi.core

import app.rasidi.core.ledger.GoldKind
import app.rasidi.core.money.*
import app.rasidi.core.valuation.*
import app.rasidi.core.zakat.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** R03: النقد السالب ينقص الصافي مرة واحدة، والالتزام الموجب ينقصه مرة واحدة. */
class NegativeCashTest {
    private val d = LocalDate.of(2026, 3, 1)
    private val rates = listOf(
        Rate("r1", "TRY", BigDecimal(40), RateDirection.UNITS_PER_USD, d, "t"),
        Rate("r2", Rate.GOLD24_GRAM, BigDecimal(100), RateDirection.USD_PER_UNIT, d, "t"),
    )
    private fun cash(id: String, unit: String, q: String) = InventoryItem(id, LineKind.CASH, id, unit, BigDecimal(q), q)
    private fun r2(v: BigDecimal) = v.setScale(2, MoneyPolicy.ROUNDING)

    @Test fun positiveAndNegativeUsdCashNet() {
        val inv = InventoryCalc.price(d, listOf(cash("a", "USD", "1000"), cash("b", "USD", "-100")), rates)
        assertEquals(BigDecimal("900.00"), r2(inv.pricedNet))
        assertEquals(BigDecimal("900.00"), r2(inv.total(LineKind.CASH)))
        assertEquals(BigDecimal("-100.00"), r2(inv.lines[1].signedUsd!!))
        assertEquals("القيمة المطلقة تبقى للتسعير", BigDecimal("100.00"), r2(inv.lines[1].usd!!))
    }

    @Test fun zeroAndNonUsdNegative() {
        val inv = InventoryCalc.price(d, listOf(cash("z", "USD", "0"), cash("t", "TRY", "-400")), rates)
        assertEquals(BigDecimal("-10.00"), r2(inv.pricedNet))
        assertEquals(0, inv.lines[0].signedUsd!!.signum())
    }

    @Test fun liabilityAndNegativeCashEachReduceOnce() {
        val items = listOf(cash("a", "USD", "1000"), cash("b", "USD", "-100"),
            InventoryItem("p", LineKind.PAYABLE, "دين", "USD", BigDecimal(200), "200"),
            InventoryItem("r", LineKind.RECEIVABLE, "لي", "USD", BigDecimal(50), "50"),
            InventoryItem("g", LineKind.GOLD, "ذهب", Rate.GOLD24_GRAM, BigDecimal(2), "2", GoldKind.SAVINGS))
        val inv = InventoryCalc.price(d, items, rates)
        assertEquals(BigDecimal("950.00"), r2(inv.pricedNet)) // 1000 − 100 − 200 + 50 + 200
        assertEquals(BigDecimal("-200.00"), r2(inv.total(LineKind.PAYABLE)))
        // الزكاة: النقد السالب يُخصم مرة واحدة عند INCLUDE، ولا يُحتسب عند EXCLUDE، ويبقى REVIEW افتراضياً
        assertEquals(Decision.REVIEW, ZakatCalc.defaultDecision(inv.lines[1]).decision)
        fun base(dec: Decision) = ZakatCalc.compute(ZakatInput(inv, mapOf(
            "a" to LineDecision(Decision.INCLUDE), "b" to LineDecision(dec, partialUsd = BigDecimal(40)), "p" to LineDecision(Decision.INCLUDE),
            "r" to LineDecision(Decision.INCLUDE), "g" to LineDecision(Decision.INCLUDE)),
            NisabPolicy.Manual(BigDecimal(1), 24, true, "t"), rates, "1447", "t", true)).baseUsd
        assertEquals(BigDecimal("950.00"), r2(base(Decision.INCLUDE)))
        assertEquals(BigDecimal("1050.00"), r2(base(Decision.EXCLUDE)))
        assertEquals(BigDecimal("1010.00"), r2(base(Decision.PARTIAL)))
    }

    @Test fun savedInventoryLinesRebuildSameNet() {
        val inv = InventoryCalc.price(d, listOf(cash("a", "USD", "1000"), cash("b", "USD", "-100")), rates)
        val rebuilt = InventoryResult(inv.date, inv.lines.map { PricedLine(it.item, it.state, it.rate, it.usd, it.note) })
        assertEquals(inv.pricedNet, rebuilt.pricedNet)
    }
}
