package app.rasidi.core.ledger

import app.rasidi.core.data.LedgerView
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import java.time.LocalDate

/**
 * كشف حساب شخص (طرف الديون) على طريقة «عليه / له / الرصيد»:
 * - دين لي عند الشخص: أصل الدين «عليه»، وما يسدّده «له».
 * - دين عليّ للشخص: أصل الدين «له»، وما أسدّده له «عليه».
 * الرصيد = مجموع عليه − مجموع له؛ الموجب «الرصيد عليه» والسالب «الرصيد له». لكل عملة كشف مستقل.
 */
object PartyLedger {
    data class Line(val date: LocalDate, val onHim: Money?, val forHim: Money?, val details: String, val txnId: String?, val balance: Money, val createdAt: Long)
    data class Statement(val party: String, val currency: String, val prior: Money, val lines: List<Line>, val totalOn: Money, val totalFor: Money, val closing: Money) {
        val closingLabel get() = if (closing.minor > 0) "الرصيد عليه" else if (closing.minor < 0) "الرصيد له" else "الرصيد"
    }
    data class PartySummary(val party: String, val balances: Map<String, Money>, val lastDate: LocalDate)

    private data class Move(val date: LocalDate, val signed: Money, val details: String, val txnId: String?, val createdAt: Long)

    private fun moves(v: LedgerView, party: String): List<Move> {
        val out = mutableListOf<Move>()
        val txns = v.s.txns
        for (d in v.debts.filter { it.party.trim() == party.trim() }) {
            val sign = if (d.direction == DebtDirection.OWED_TO_ME) 1 else -1
            val principalTxn = txns.firstOrNull { it.debtId == d.id && it.type == TxnType.DEBT_NEW.name }
            out += Move(d.date, Money(sign * d.principal.minor, d.currency), d.note?.ifBlank { null } ?: if (sign > 0) "دين لي" else "دين عليّ", principalTxn?.id, principalTxn?.createdAtUtc ?: 0)
            v.s.debtPayments.filter { it.debtId == d.id }.forEach { p ->
                val t = p.txnId?.let { v.txnById[it] }
                val note = t?.note?.ifBlank { null } ?: if (sign > 0) "تحصيل" else "سداد"
                out += Move(LocalDate.parse(p.date), Money(-sign * p.debtAmountMinor, d.currency), note + if (p.debtAmountMinor < 0) " (عكس)" else "", p.txnId, t?.createdAtUtc ?: 0)
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.createdAt }))
    }

    fun parties(v: LedgerView): List<PartySummary> = v.debts.map { it.party.trim() }.distinct().map { p ->
        val ms = moves(v, p)
        PartySummary(p, ms.groupBy { it.signed.currency.code }.mapValues { (c, l) -> l.fold(Money.zero(Currencies.of(c))) { a, m -> a + m.signed } }, ms.maxOf { it.date })
    }.sortedByDescending { it.lastDate }

    fun currencies(v: LedgerView, party: String) = moves(v, party).map { it.signed.currency.code }.distinct()

    /** [includePrior] = «رصيد سابق»: يبدأ الكشف برصيد ما قبل الفترة. */
    fun statement(v: LedgerView, party: String, currency: String, from: LocalDate, to: LocalDate, includePrior: Boolean): Statement {
        val cur = Currencies.of(currency)
        val ms = moves(v, party).filter { it.signed.currency.code == currency }
        val prior = if (includePrior) ms.filter { it.date.isBefore(from) }.fold(Money.zero(cur)) { a, m -> a + m.signed } else Money.zero(cur)
        var run = prior; var on = Money.zero(cur); var fr = Money.zero(cur)
        val lines = ms.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.map { m ->
            run += m.signed
            if (m.signed.minor >= 0) on += m.signed else fr += -m.signed
            Line(m.date, m.signed.takeIf { it.minor >= 0 }, (-m.signed).takeIf { m.signed.minor < 0 }, m.details, m.txnId, run, m.createdAt)
        }
        return Statement(party, currency, prior, lines, on, fr, run)
    }
}
