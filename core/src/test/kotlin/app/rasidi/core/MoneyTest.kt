package app.rasidi.core

import app.rasidi.core.money.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class MoneyTest {
    @Test fun arabicPersianWesternDigitsGiveSameValue() {
        val a = AmountParser.parse("١٢٣٫٤٥")!!.value
        val b = AmountParser.parse("۱۲۳٫۴۵")!!.value
        val c = AmountParser.parse("123.45")!!.value
        assertEquals(0, a.compareTo(BigDecimal("123.45")))
        assertEquals(0, a.compareTo(b)); assertEquals(0, b.compareTo(c))
    }

    @Test fun separators() {
        assertEquals(BigDecimal("1234567.5"), AmountParser.parse("1,234,567.5")!!.value)
        assertEquals(BigDecimal("1234567.5"), AmountParser.parse("1.234.567,5")!!.value)
        assertEquals(BigDecimal("1234567"), AmountParser.parse("١٬٢٣٤٬٥٦٧")!!.value)
        val amb = AmountParser.parse("1,234")!!
        assertTrue(amb.ambiguous); assertEquals(BigDecimal("1234"), amb.value)
        assertFalse(AmountParser.parse("12.5")!!.ambiguous)
        assertEquals(BigDecimal("-50"), AmountParser.parse("−50")!!.value)
        assertNull(AmountParser.parse("abc"))
        assertNull(AmountParser.parse("1.2.3,4,5"))
    }

    @Test fun largeAmountsAndBounds() {
        val m = AmountParser.parseMoney("9,999,999,999,999.99", Currencies.USD)!!
        assertEquals(999_999_999_999_999L, m.minor)
        assertNull(AmountParser.parseMoney("99,999,999,999,999.99", Currencies.USD))
        // ملتبس يُفسَّر كفاصل آلاف ويُعرض للتأكيد
        assertEquals(1234L, AmountParser.parseMoney("1.234", Currencies.SYP_OLD)!!.minor)
    }

    @Test fun rejectsExtraFraction() {
        assertNull(AmountParser.parseMoney("10.5555", Currencies.USD))
        assertNull(AmountParser.parseMoney("10.5", Currencies.SYP_OLD))
    }

    @Test fun inverseRateKeepsPrecision() {
        val r = Rate("r", "SYP", BigDecimal(120), RateDirection.UNITS_PER_USD, LocalDate.of(2026, 1, 1))
        assertEquals(0, r.toUsd(BigDecimal(12000)).compareTo(BigDecimal(100)))
        assertEquals(0, r.toUsd(BigDecimal(14100)).compareTo(BigDecimal("117.5")))
        assertEquals(0, r.fromUsd(BigDecimal(100)).compareTo(BigDecimal(12000)))
    }

    @Test fun goldKaratNormalization() {
        val r = Rate.goldGramFromKarat("g", BigDecimal("87.5"), 21, LocalDate.of(2026, 1, 1), "صائغ")
        assertEquals(0, r.entered.compareTo(BigDecimal(100)))
    }

    @Test fun rateBookExcludesFutureAndSuggestsOlder() {
        val d = LocalDate.of(2026, 3, 10)
        val book = RateBook(listOf(
            Rate("a", "TRY", BigDecimal(38), RateDirection.UNITS_PER_USD, d.minusDays(5)),
            Rate("b", "TRY", BigDecimal(41), RateDirection.UNITS_PER_USD, d.plusDays(1)),
        ))
        val l = book.lookup("TRY", d)
        assertTrue(l is RateLookup.OlderSuggestion); assertEquals(5, (l as RateLookup.OlderSuggestion).daysOld)
        assertTrue(book.lookup("TRY", d.minusDays(6)) is RateLookup.Missing)
        assertTrue(book.lookup("EUR", d) is RateLookup.Missing)
    }

    @Test fun formatIsolatesNegative() {
        val s = Fmt.money(Money.of("-1234.5", Currencies.TRY))
        assertEquals("⁦-1,234.50 TRY⁩", s)
    }

    @Test fun syrianRedenominationAppliedOnce() {
        val rd = SyrianRedenomination(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))
        assertEquals(Money.of("150", Currencies.SYP), rd.oldToNew(Money.of("15000", Currencies.SYP_OLD)))
        val newRate = Rate("n", "SYP", BigDecimal(120), RateDirection.UNITS_PER_USD, LocalDate.of(2026, 2, 1))
        val old = rd.deriveOldRate(newRate)
        assertEquals(0, old.toUsd(BigDecimal(1_200_000)).compareTo(BigDecimal(100)))
        assertEquals(SyrianRedenomination.OldCashStatus.CURRENT, rd.status(LocalDate.of(2025, 12, 31)))
        assertEquals(SyrianRedenomination.OldCashStatus.CONVERTIBLE, rd.status(LocalDate.of(2026, 3, 1)))
        assertEquals(SyrianRedenomination.OldCashStatus.SUBJECT_TO_WITHDRAWAL, rd.status(LocalDate.of(2026, 7, 1)))
    }
}
