package app.rasidi.core

import app.rasidi.core.money.*
import app.rasidi.core.zakat.Distribution
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** R10: خطة التوزيع بالحصص — لا دفعة سالبة، والحصص صالحة. */
class DistributionTest {
    private val d = LocalDate.of(2026, 3, 1)
    private val usd = RateBook.USD_IDENTITY.copy(date = d)
    private val tl = Rate("t", "TRY", BigDecimal(40), RateDirection.UNITS_PER_USD, d, "t")
    private val syp = Rate("s", "SYP", BigDecimal("13000"), RateDirection.UNITS_PER_USD, d, "t")

    @Test fun coversRemainingWithoutNegativeOrZeroPayments() {
        val plan = Distribution.planByShares(BigDecimal("100.00"), listOf(Triple(BigDecimal("0.5"), usd, Currencies.USD), Triple(BigDecimal("0.5"), tl, Currencies.TRY)))
        assertTrue(plan.all { it.amount.minor > 0 })
        val tot = plan.fold(BigDecimal.ZERO) { a, p -> a + p.usd }
        assertTrue(tot >= BigDecimal("100.00"))
        assertTrue(tot - BigDecimal("100.00") < BigDecimal("0.01"))
    }

    @Test fun emptyZeroAndNegativeRemaining() {
        assertTrue(Distribution.planByShares(BigDecimal.ZERO, listOf(Triple(BigDecimal.ONE, usd, Currencies.USD))).isEmpty())
        assertTrue(Distribution.planByShares(BigDecimal("-3"), listOf(Triple(BigDecimal.ONE, usd, Currencies.USD))).isEmpty())
        assertTrue(Distribution.planByShares(BigDecimal.TEN, emptyList()).isEmpty())
    }

    @Test fun invalidSharesRejected() {
        assertNotNull(runCatching { Distribution.planByShares(BigDecimal.TEN, listOf(Triple(BigDecimal("-0.5"), usd, Currencies.USD), Triple(BigDecimal("1.5"), tl, Currencies.TRY))) }.exceptionOrNull())
        assertNotNull(runCatching { Distribution.planByShares(BigDecimal.TEN, listOf(Triple(BigDecimal("0.5"), usd, Currencies.USD))) }.exceptionOrNull())
        assertNotNull(runCatching { Distribution.planByShares(BigDecimal.TEN, listOf(Triple(BigDecimal.ONE, tl, Currencies.USD))) }.exceptionOrNull())
    }

    @Test fun roundingThatAlreadyCoversDoesNotAddNegativeLastPayment() {
        // حصة صغيرة جداً أخيرة بعملة كبيرة السعر: لا تنتج دفعة سالبة
        val plan = Distribution.planByShares(BigDecimal("0.01"), listOf(Triple(BigDecimal("0.999999"), usd, Currencies.USD), Triple(BigDecimal("0.000001"), syp, Currencies.SYP)))
        assertTrue(plan.all { it.amount.minor > 0 })
        assertTrue(plan.fold(BigDecimal.ZERO) { a, p -> a + p.usd } >= BigDecimal("0.01"))
    }
}
