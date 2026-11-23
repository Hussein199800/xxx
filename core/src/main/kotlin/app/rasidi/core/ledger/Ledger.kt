package app.rasidi.core.ledger

import app.rasidi.core.money.CurrencyDef
import app.rasidi.core.money.Money
import java.time.LocalDate

enum class AccountType(val ar: String) { CASH("نقد"), BANK("بنك"), EWALLET("محفظة إلكترونية"), SAVINGS("ادخار") }

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val currency: CurrencyDef,
    val allowNegative: Boolean = false,
    val archived: Boolean = false,
)

/** نوع الحركة (الوثيقة). */
enum class TxnType(val ar: String) {
    OPENING("رصيد افتتاحي"), INCOME("دخل"), EXPENSE("مصروف"), TRANSFER("تحويل"),
    DEBT_NEW("دين جديد"), DEBT_PAYMENT("دفعة دين"), GOLD_BUY("شراء ذهب"), GOLD_SELL("بيع ذهب"),
    ADJUSTMENT("تسوية"), ZAKAT("زكاة"), REVERSAL("عكس حركة"),
}

/**
 * نوع القيد. يفصل الرصيد الافتتاحي والتحويل وتسوية الدين عن الدخل والمصروف
 * حتى لا تُحسب في إجمالي الدخل والمصروف.
 */
enum class EntryKind(val countsAsIncome: Boolean = false, val countsAsExpense: Boolean = false) {
    OPENING,
    INCOME(countsAsIncome = true),
    EXPENSE(countsAsExpense = true),
    TRANSFER_OUT, TRANSFER_IN,
    FEE(countsAsExpense = true),
    DEBT_PRINCIPAL,   // قرض نقدي جديد أعطيته أو أخذته
    DEBT_SETTLEMENT,  // سداد أو تحصيل
    GOLD_TRADE,
    ADJUSTMENT,
    ZAKAT(countsAsExpense = true),
}

/** قيد موقّع على حساب واحد بعملة الحساب. */
data class Entry(
    val id: String,
    val txnId: String,
    val accountId: String,
    val kind: EntryKind,
    val amount: Money,
    val date: LocalDate,
    val categoryId: String? = null,
)

object Balances {
    /** رصيد الحساب في نهاية [onOrBefore] (شامل). */
    fun balance(account: Account, entries: List<Entry>, onOrBefore: LocalDate? = null): Money =
        entries.asSequence()
            .filter { it.accountId == account.id && (onOrBefore == null || !it.date.isAfter(onOrBefore)) }
            .fold(Money.zero(account.currency)) { acc, e -> acc + e.amount }

    data class Statement(
        val account: Account,
        val opening: Money,
        val lines: List<Pair<Entry, Money>>, // القيد والرصيد الجاري بعده
        val closing: Money,
    )

    /** كشف حساب: الرصيد قبل الفترة، حركاتها، الرصيد في نهايتها. */
    fun statement(account: Account, entries: List<Entry>, from: LocalDate, to: LocalDate): Statement {
        val mine = entries.filter { it.accountId == account.id }.sortedWith(compareBy({ it.date }, { it.id }))
        val opening = mine.filter { it.date.isBefore(from) }.fold(Money.zero(account.currency)) { a, e -> a + e.amount }
        var running = opening
        val lines = mine.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.map { e -> running += e.amount; e to running }
        return Statement(account, opening, lines, running)
    }

    /** يتحقق أن الحركة لن تجعل رصيد حساب لا يسمح بالسالب سالباً. */
    fun wouldGoNegative(account: Account, entries: List<Entry>, delta: Money, date: LocalDate): Boolean {
        if (account.allowNegative) return false
        // نفحص الرصيد في تاريخ الحركة وكل تاريخ لاحق
        val after = entries.filter { it.accountId == account.id }.map { it.date }.filter { !it.isBefore(date) }.toSortedSet()
        after.add(date)
        return after.any { d -> (balance(account, entries, d) + delta).isNegative }
    }
}

/** ملخص فترة: الدخل والمصروف بالعملات الأصلية (التحويل الداخلي مستبعد). */
data class PeriodTotals(
    val income: Map<String, Money>,
    val expense: Map<String, Money>,
    val expenseByCategory: Map<Pair<String?, String>, Money>,
    val debtSettled: Map<String, Money>,
) {
    companion object {
        fun of(entries: List<Entry>, from: LocalDate, to: LocalDate): PeriodTotals {
            val inRange = entries.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            fun sum(es: List<Entry>, negate: Boolean) = es.groupBy { it.amount.currency.code }
                .mapValues { (_, l) -> l.map { if (negate) -it.amount else it.amount }.reduce { a, b -> a + b } }
            val inc = inRange.filter { it.kind.countsAsIncome }
            val exp = inRange.filter { it.kind.countsAsExpense }
            return PeriodTotals(
                income = sum(inc, false),
                expense = sum(exp, true),
                expenseByCategory = exp.groupBy { it.categoryId to it.amount.currency.code }
                    .mapValues { (_, l) -> l.map { -it.amount }.reduce { a, b -> a + b } },
                debtSettled = sum(inRange.filter { it.kind == EntryKind.DEBT_SETTLEMENT }, false),
            )
        }
    }
}
