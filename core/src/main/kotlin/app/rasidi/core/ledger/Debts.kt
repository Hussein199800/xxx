package app.rasidi.core.ledger

import app.rasidi.core.money.CurrencyDef
import app.rasidi.core.money.Money
import java.time.LocalDate

enum class DebtDirection(val ar: String) {
    /** لي عند الناس */
    OWED_TO_ME("لي"),
    /** عليّ للناس */
    I_OWE("عليّ"),
}

enum class DebtStatus(val ar: String) { OPEN("مفتوح"), PARTIAL("مسدّد جزئياً"), SETTLED("مسدّد"), OVERDUE("متأخر"), CANCELLED("ملغى") }

data class Installment(val dueDate: LocalDate, val amount: Money)

data class Debt(
    val id: String,
    val party: String,
    val direction: DebtDirection,
    val principal: Money,
    val date: LocalDate,
    val dueDate: LocalDate? = null,
    val installments: List<Installment> = emptyList(),
    val note: String? = null,
    /**
     * أُلغي الدين بعكس حركة إنشائه (DEBT_NEW). يبقى السجل كاملاً للتاريخ، لكن أصله لا يُحتسب.
     * مشتق من البيانات (لا عمود في القاعدة): حركة DEBT_NEW للدين لها reversedById.
     */
    val cancelled: Boolean = false,
) {
    val currency: CurrencyDef get() = principal.currency
    /** الأصل المحتسب: صفر للدين الملغى. */
    val effectivePrincipal: Money get() = if (cancelled) Money.zero(principal.currency) else principal
}

/** دفعة: مبلغ الدين بعملة الدين، ومبلغ المحفظة بعملة الحساب (قد تختلف). */
data class DebtPayment(
    val id: String,
    val debtId: String,
    val date: LocalDate,
    val debtAmount: Money,
    val walletAmount: Money?,
    val accountId: String?,
)

object DebtCalc {
    fun paid(debt: Debt, payments: List<DebtPayment>, onOrBefore: LocalDate? = null): Money =
        payments.filter { it.debtId == debt.id && (onOrBefore == null || !it.date.isAfter(onOrBefore)) }
            .fold(Money.zero(debt.currency)) { a, p -> a + p.debtAmount }

    fun remaining(debt: Debt, payments: List<DebtPayment>, onOrBefore: LocalDate? = null): Money =
        debt.effectivePrincipal - paid(debt, payments, onOrBefore)

    /** المستحق حتى تاريخ: مجموع الأقساط المستحقة، أو كامل الأصل عند استحقاق واحد. */
    fun dueBy(debt: Debt, date: LocalDate): Money = when {
        debt.installments.isNotEmpty() -> debt.installments.filter { !it.dueDate.isAfter(date) }
            .fold(Money.zero(debt.currency)) { a, i -> a + i.amount }
        debt.dueDate != null && !debt.dueDate.isAfter(date) -> debt.principal
        else -> Money.zero(debt.currency)
    }

    fun status(debt: Debt, payments: List<DebtPayment>, today: LocalDate): DebtStatus {
        if (debt.cancelled) return DebtStatus.CANCELLED
        val rem = remaining(debt, payments)
        val paid = paid(debt, payments)
        if (rem.minor <= 0) return DebtStatus.SETTLED
        if (paid < dueBy(debt, today)) return DebtStatus.OVERDUE
        return if (paid.isZero) DebtStatus.OPEN else DebtStatus.PARTIAL
    }

    /** أقرب استحقاق غير مغطّى. */
    fun nextDue(debt: Debt, payments: List<DebtPayment>): LocalDate? {
        if (debt.cancelled) return null
        val paid = paid(debt, payments)
        if (remaining(debt, payments).minor <= 0) return null
        if (debt.installments.isEmpty()) return debt.dueDate
        var cum = Money.zero(debt.currency)
        for (i in debt.installments.sortedBy { it.dueDate }) { cum += i.amount; if (cum > paid) return i.dueDate }
        return null
    }

    sealed interface Validation {
        data object Ok : Validation
        data class Rejected(val reason: String) : Validation
    }

    /** يرفض الدفعة التي تتجاوز الرصيد المتبقي ويعرض السبب. */
    fun validatePayment(debt: Debt, payments: List<DebtPayment>, amount: Money, excludePaymentId: String? = null): Validation {
        if (debt.cancelled) return Validation.Rejected("الدين ملغى؛ لا تُسجَّل عليه دفعات")
        if (amount.currency.code != debt.currency.code) return Validation.Rejected("مبلغ الدين يجب أن يكون بعملة الدين ${debt.currency.code}")
        if (amount.minor <= 0) return Validation.Rejected("المبلغ يجب أن يكون أكبر من صفر")
        val rem = remaining(debt, payments.filter { it.id != excludePaymentId })
        if (amount > rem) return Validation.Rejected("المبلغ يتجاوز المتبقي (${app.rasidi.core.money.Fmt.money(rem, false)})")
        return Validation.Ok
    }

    /** إشارة قيد المحفظة: تحصيل دين لي يزيد المحفظة، وسداد دين عليّ ينقصها. */
    fun walletSign(direction: DebtDirection, isPrincipal: Boolean): Int = when (direction) {
        DebtDirection.OWED_TO_ME -> if (isPrincipal) -1 else +1 // أقرضت: خرج مال، حصّلت: دخل
        DebtDirection.I_OWE -> if (isPrincipal) +1 else -1      // اقترضت: دخل مال، سدّدت: خرج
    }
}
