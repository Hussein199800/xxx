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
    data class PartySummary(val party: String, val balances: Map<String, Money>, val lastDate: LocalDate?, val phone: String = "", val note: String = "", val id: String? = null) {
        /** مدين (لي معه) إن كان له رصيد موجب بأي عملة، ودائن (له معي) إن كان له رصيد سالب. */
        val debtor get() = balances.values.any { it.minor > 0 }
        val creditor get() = balances.values.any { it.minor < 0 }
        val settled get() = balances.values.all { it.isZero }
    }

    private data class Move(val date: LocalDate, val signed: Money, val details: String, val txnId: String?, val createdAt: Long)

    private fun moves(v: LedgerView, party: String): List<Move> {
        val out = mutableListOf<Move>()
        val txns = v.s.txns
        for (d in v.debts.filter { it.party.trim() == party.trim() }) {
            val sign = if (d.direction == DebtDirection.OWED_TO_ME) 1 else -1
            val principalTxn = txns.firstOrNull { it.debtId == d.id && it.type == TxnType.DEBT_NEW.name }
            out += Move(d.date, Money(sign * d.principal.minor, d.currency), d.note?.ifBlank { null } ?: if (sign > 0) "دين لي" else "دين عليّ", principalTxn?.id, principalTxn?.createdAtUtc ?: 0)
            // دين ملغى: يبقى الأصل في الكشف ويقابله سطر «إلغاء» بحركة العكس نفسها (الرصيد صفر)
            if (d.cancelled) {
                val rv = principalTxn?.reversedById?.let { v.txnById[it] }
                out += Move(rv?.date?.let(LocalDate::parse) ?: d.date, Money(-sign * d.principal.minor, d.currency), "إلغاء الدين (عكس)", rv?.id, rv?.createdAtUtc ?: (principalTxn?.createdAtUtc ?: 0) + 1)
            }
            v.s.debtPayments.filter { it.debtId == d.id }.forEach { p ->
                val t = p.txnId?.let { v.txnById[it] }
                val note = t?.note?.ifBlank { null } ?: if (sign > 0) "تحصيل" else "سداد"
                out += Move(LocalDate.parse(p.date), Money(-sign * p.debtAmountMinor, d.currency), note + if (p.debtAmountMinor < 0) " (عكس)" else "", p.txnId, t?.createdAtUtc ?: 0)
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.createdAt }))
    }

    /** كل الحسابات: من جدول الحسابات (ولو برصيد صفر) ومن أسماء الديون. */
    /** [asOf]: الأرصدة كما في نهاية ذلك اليوم (null = كل الحركات). */
    fun parties(v: LedgerView, includeArchived: Boolean = false, asOf: LocalDate? = null): List<PartySummary> {
        val table = v.s.parties.filter { includeArchived || !it.archived }.associateBy { it.name.trim() }
        val archived = v.s.parties.filter { it.archived }.map { it.name.trim() }.toSet()
        val names = (table.keys + v.debts.map { it.party.trim() }).distinct().filter { includeArchived || it !in archived }
        return names.map { p ->
            val ms = moves(v, p).filter { asOf == null || !it.date.isAfter(asOf) }
            val e = table[p]
            PartySummary(p, ms.groupBy { it.signed.currency.code }.mapValues { (c, l) -> l.fold(Money.zero(Currencies.of(c))) { a, m -> a + m.signed } },
                ms.maxOfOrNull { it.date }, e?.phone.orEmpty(), e?.note.orEmpty(), e?.id)
        }.sortedWith(compareBy<PartySummary> { it.settled }.thenByDescending { it.lastDate ?: LocalDate.MIN }.thenBy { it.party })
    }

    /** إجمالي المدين (لي معها) والدائن (لها معي) بكل عملة. */
    fun totals(list: List<PartySummary>): Pair<Map<String, Money>, Map<String, Money>> {
        val deb = HashMap<String, Money>(); val cred = HashMap<String, Money>()
        list.forEach { p -> p.balances.forEach { (c, m) ->
            if (m.minor > 0) deb[c] = (deb[c] ?: Money.zero(m.currency)) + m
            if (m.minor < 0) cred[c] = (cred[c] ?: Money.zero(m.currency)) + (-m)
        } }
        return deb to cred
    }

    /**
     * حدود «الكل» المشتركة بين العرض والتصدير: من أول حركة إلى آخر حركة بهذه العملة، **شاملة المؤرخة مستقبلاً**
     * (السياسة: «الكل» لا يُسقط شيئاً). بلا حركات ⇒ اليوم فقط.
     */
    fun allRange(v: LedgerView, party: String, currency: String, today: LocalDate): Pair<LocalDate, LocalDate> {
        val ds = moves(v, party).filter { it.signed.currency.code == currency }.map { it.date }
        return (ds.minOrNull() ?: today) to (ds.maxOrNull() ?: today)
    }

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
