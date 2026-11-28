package app.rasidi.core

import app.rasidi.core.money.*
import app.rasidi.core.valuation.*
import app.rasidi.core.zakat.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** R11: حدود المال والأسعار والتحويل والنصاب. */
class MoneyEdgeTest {
    private val d = LocalDate.of(2028, 2, 29) // سنة كبيسة

    @Test fun arabicPersianDigitsAndSeparators() {
        assertEquals(BigDecimal("1234.5"), AmountParser.parse("١٬٢٣٤٫٥")!!.value)
        assertEquals(BigDecimal("1234.5"), AmountParser.parse("۱۲۳۴.۵")!!.value)
        assertTrue(AmountParser.parse("1,234")!!.ambiguous)
        assertEquals(BigDecimal("1234.56"), AmountParser.parse("1.234,56")!!.value)
        assertNull(AmountParser.parse("1.2.3"))
        assertNull("كسور زائدة عن العملة", AmountParser.parseMoney("0.001", Currencies.USD))
    }

    @Test fun maxBoundsFailCleanly() {
        assertNull(AmountParser.parseMoney("99999999999999999999", Currencies.USD))
        val max = Money(MoneyPolicy.MAX_MINOR, Currencies.USD)
        assertNotNull(runCatching { max + Money(1, Currencies.USD) }.exceptionOrNull())
        assertNotNull(runCatching { Money(Long.MAX_VALUE, Currencies.USD) }.exceptionOrNull())
    }

    @Test fun sameDayRatesUseLatestObservationDeterministically() {
        val a = Rate("a", "TRY", BigDecimal(40), RateDirection.UNITS_PER_USD, d, "x", observedAtUtc = 2)
        val b = Rate("b", "TRY", BigDecimal(41), RateDirection.UNITS_PER_USD, d, "x", observedAtUtc = 1)
        assertEquals("a", (RateBook(listOf(a, b)).lookup("TRY", d) as RateLookup.Exact).rate.id)
        assertEquals("a", (RateBook(listOf(b, a)).lookup("TRY", d) as RateLookup.Exact).rate.id)
        // تعادل وقت الرصد: النتيجة لا تتبع ترتيب الإدخال
        val c = b.copy(id = "c", observedAtUtc = 2)
        assertEquals((RateBook(listOf(a, c)).lookup("TRY", d) as RateLookup.Exact).rate.id, (RateBook(listOf(c, a)).lookup("TRY", d) as RateLookup.Exact).rate.id)
        // مستقبلي لا يُستخدم، والأقدم اقتراح
        assertTrue(RateBook(listOf(a.copy(date = d.plusDays(1)))).lookup("TRY", d) is RateLookup.Missing)
        assertTrue(RateBook(listOf(a.copy(date = d.minusDays(1)))).lookup("TRY", d) is RateLookup.OlderSuggestion)
    }

    @Test fun oldSyrianFactorAppliedOnceBothDirections() {
        val red = SyrianRedenomination.DEFAULT
        val upu = Rate("n", "SYP", BigDecimal(130), RateDirection.UNITS_PER_USD, d)
        val o1 = red.deriveOldRate(upu)
        assertTrue((o1.usdPerUnit - BigDecimal.ONE.divide(BigDecimal(13000), 30, java.math.RoundingMode.HALF_EVEN)).abs() < BigDecimal("1E-15"))
        val usdPer = Rate("n2", "SYP", BigDecimal("0.01"), RateDirection.USD_PER_UNIT, d)
        assertEquals(0, red.deriveOldRate(usdPer).usdPerUnit.compareTo(BigDecimal("0.0001")))
        // جرد: 13000 قديمة بعد النفاذ = 1 USD مرة واحدة فقط
        val inv = InventoryCalc.price(d, listOf(InventoryItem("o", LineKind.CASH, "قديمة", "SYP_OLD", BigDecimal(13000), "")), listOf(upu), oldSypDecisions = mapOf("o" to null))
        assertEquals(BigDecimal("1.00"), inv.pricedNet.setScale(2, MoneyPolicy.ROUNDING))
    }

    @Test fun nisabAtAndJustBelowBoundary() {
        val gold = Rate("g", Rate.GOLD24_GRAM, BigDecimal(100), RateDirection.USD_PER_UNIT, d)
        fun status(usd: String): ZakatStatus {
            val inv = InventoryCalc.price(d, listOf(InventoryItem("c", LineKind.CASH, "نقد", "USD", BigDecimal(usd), usd)), listOf(gold))
            return ZakatCalc.compute(ZakatInput(inv, mapOf("c" to LineDecision(Decision.INCLUDE)), NisabPolicy.Manual(BigDecimal(85), 24, true, "t"), listOf(gold), "1449", "t", true)).status
        }
        assertEquals(ZakatStatus.DUE, status("8500.00"))
        // 8499.995 يُعرض 8500.00 لكنه تحت النصاب: المقارنة قبل التقريب
        assertEquals(ZakatStatus.BELOW_NISAB, status("8499.995"))
    }
}
