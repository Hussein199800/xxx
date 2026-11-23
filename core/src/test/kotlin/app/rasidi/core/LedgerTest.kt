package app.rasidi.core

import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LedgerTest {
    private val d = LocalDate.of(2026, 5, 1)
    private val usdCash = Account("a1", "جيب", AccountType.CASH, Currencies.USD)
    private val tryBank = Account("a2", "بنك", AccountType.BANK, Currencies.TRY)
    private fun usd(s: String) = Money.of(s, Currencies.USD)
    private fun tl(s: String) = Money.of(s, Currencies.TRY)

    @Test fun transferAcrossCurrenciesWithFeeExcludedFromIncomeExpense() {
        val es = listOf(
            Entry("e1", "t0", "a1", EntryKind.OPENING, usd("1000"), d),
            Entry("e2", "t1", "a1", EntryKind.TRANSFER_OUT, usd("-100"), d.plusDays(1)),
            Entry("e3", "t1", "a2", EntryKind.TRANSFER_IN, tl("4000"), d.plusDays(1)),
            Entry("e4", "t1", "a1", EntryKind.FEE, usd("-2"), d.plusDays(1), "fees"),
            Entry("e5", "t2", "a1", EntryKind.INCOME, usd("500"), d.plusDays(2), "salary"),
        )
        assertEquals(usd("1398"), Balances.balance(usdCash, es))
        assertEquals(tl("4000"), Balances.balance(tryBank, es))
        assertEquals(usd("1000"), Balances.balance(usdCash, es, d))
        val t = PeriodTotals.of(es, d, d.plusDays(10))
        assertEquals(usd("500"), t.income["USD"]); assertEquals(usd("2"), t.expense["USD"]); assertNull(t.income["TRY"])
        val st = Balances.statement(usdCash, es, d.plusDays(1), d.plusDays(1))
        assertEquals(usd("1000"), st.opening); assertEquals(usd("898"), st.closing); assertEquals(2, st.lines.size)
    }

    @Test fun negativeGuard() {
        val es = listOf(Entry("e1", "t", "a1", EntryKind.OPENING, usd("50"), d))
        assertTrue(Balances.wouldGoNegative(usdCash, es, usd("-60"), d))
        assertFalse(Balances.wouldGoNegative(usdCash.copy(allowNegative = true), es, usd("-60"), d))
        assertTrue(Balances.wouldGoNegative(usdCash, es, usd("-10"), d.minusDays(1)))
    }

    @Test fun debtPartialPaymentInOtherCurrency() {
        val debt = Debt("d1", "أبو أحمد", DebtDirection.I_OWE, usd("300"), d.minusYears(1), dueDate = d.plusDays(30))
        val p = listOf(DebtPayment("p1", "d1", d, usd("100"), tl("4000"), "a2"))
        assertEquals(usd("200"), DebtCalc.remaining(debt, p))
        assertEquals(DebtStatus.PARTIAL, DebtCalc.status(debt, p, d))
        assertEquals(DebtStatus.OVERDUE, DebtCalc.status(debt, p, d.plusDays(31)))
        assertTrue(DebtCalc.validatePayment(debt, p, usd("250")) is DebtCalc.Validation.Rejected)
        assertTrue(DebtCalc.validatePayment(debt, p, usd("200")) is DebtCalc.Validation.Ok)
        assertTrue(DebtCalc.validatePayment(debt, p, tl("200")) is DebtCalc.Validation.Rejected)
        val settled = p + DebtPayment("p2", "d1", d, usd("200"), null, null)
        assertEquals(DebtStatus.SETTLED, DebtCalc.status(debt, settled, d))
        assertEquals(DebtStatus.OPEN, DebtCalc.status(debt, emptyList(), d))
        assertEquals(-1, DebtCalc.walletSign(DebtDirection.I_OWE, false))
    }

    @Test fun installmentsNextDue() {
        val debt = Debt("d", "x", DebtDirection.OWED_TO_ME, usd("300"), d,
            installments = listOf(Installment(d.plusMonths(1), usd("100")), Installment(d.plusMonths(2), usd("100")), Installment(d.plusMonths(3), usd("100"))))
        val p = listOf(DebtPayment("p", "d", d, usd("150"), null, null))
        assertEquals(d.plusMonths(2), DebtCalc.nextDue(debt, p))
        assertEquals(DebtStatus.OVERDUE, DebtCalc.status(debt, p, d.plusMonths(2)))
    }

    @Test fun goldPureGramsAndMoves() {
        assertEquals(0, GoldCalc.pureGrams(BigDecimal(80), 21).compareTo(BigDecimal(70)))
        val h = GoldHolding("g", "ليرة", 21, GoldKind.SAVINGS)
        val moves = listOf(
            GoldMove("m1", "g", GoldMoveType.OPENING, BigDecimal(50), d),
            GoldMove("m2", "g", GoldMoveType.BUY, BigDecimal(40), d.plusDays(1)),
            GoldMove("m3", "g", GoldMoveType.SELL, BigDecimal(-10), d.plusDays(2)),
        )
        assertEquals(0, GoldCalc.grams(h, moves).compareTo(BigDecimal(80)))
        assertEquals(0, GoldCalc.grams(h, moves, d).compareTo(BigDecimal(50)))
    }
}
