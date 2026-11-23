package app.rasidi.core.household

import app.rasidi.core.data.LedgerView
import app.rasidi.core.data.RecurringEntity
import app.rasidi.core.ledger.EntryKind
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import java.time.LocalDate
import java.time.YearMonth

/** ميزات البيت: الميزانية الشهرية، والمتكررات، والمصروف حسب الفرد. كلها بالعملة الأصلية دون تحويل. */
object Household {

    data class BudgetLine(val budgetId: String, val categoryId: String, val categoryName: String, val limit: Money, val spent: Money) {
        val remaining get() = limit - spent
        val ratio: Float get() = if (limit.minor <= 0) 0f else spent.minor.toFloat() / limit.minor
        val over get() = spent > limit
        /** تنبيه عند تجاوز 80% */
        val near get() = !over && ratio >= 0.8f
    }

    /** المصروف الفعلي لكل ميزانية في الشهر [month] (المصروف والرسوم، بعملة الميزانية). */
    fun budgets(v: LedgerView, month: YearMonth): List<BudgetLine> {
        val from = month.atDay(1); val to = month.atEndOfMonth()
        return v.s.budgets.map { b ->
            val cur = Currencies.of(b.currency)
            val spent = v.entries.filter {
                it.categoryId == b.categoryId && it.amount.currency.code == b.currency && it.kind.countsAsExpense &&
                    !it.date.isBefore(from) && !it.date.isAfter(to)
            }.fold(Money.zero(cur)) { a, e -> a - e.amount }
            BudgetLine(b.id, b.categoryId, v.categoryNames[b.categoryId] ?: "", Money(b.limitMinor, cur), spent)
        }.sortedByDescending { it.ratio }
    }

    data class DueItem(val rec: RecurringEntity, val dueDate: LocalDate, val requestId: String, val done: Boolean, val txnId: String?)

    fun requestId(r: RecurringEntity, month: YearMonth) = "rec:${r.id}:$month"

    /** المتكررات المستحقة في الشهر: تاريخ استحقاقها، وهل سُجّلت (بمعرّف الطلب الثابت). */
    fun recurringDue(v: LedgerView, month: YearMonth): List<DueItem> = v.s.recurring.filter { it.active }.map { r ->
        val rid = requestId(r, month)
        val t = v.s.txns.firstOrNull { it.requestId == rid }
        DueItem(r, month.atDay(minOf(r.dayOfMonth, month.lengthOfMonth())), rid, t != null && t.reversedById == null, t?.id)
    }.sortedWith(compareBy({ it.done }, { it.dueDate }))

    data class MemberSpend(val memberId: String?, val name: String, val byCurrency: Map<String, Money>)

    /** مصروف كل فرد في الفترة بكل عملة. الحركات بلا فرد تظهر تحت «مشترك». */
    fun spendByMember(v: LedgerView, from: LocalDate, to: LocalDate): List<MemberSpend> {
        val memberOf = v.s.txns.associate { it.id to it.memberId }
        val names = v.s.members.associate { it.id to it.name }
        return v.entries.filter { it.kind.countsAsExpense && !it.date.isBefore(from) && !it.date.isAfter(to) && it.kind != EntryKind.ZAKAT }
            .groupBy { memberOf[it.txnId] }
            .map { (m, es) -> MemberSpend(m, m?.let { names[it] } ?: "مشترك", es.groupBy { it.amount.currency.code }.mapValues { (c, l) -> l.fold(Money.zero(Currencies.of(c))) { a, e -> a - e.amount } }) }
            .sortedBy { it.memberId == null }
    }
}
