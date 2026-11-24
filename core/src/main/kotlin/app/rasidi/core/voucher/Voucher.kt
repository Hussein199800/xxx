package app.rasidi.core.voucher

import app.rasidi.core.data.LedgerView
import app.rasidi.core.ledger.DebtCalc
import app.rasidi.core.ledger.DebtDirection
import app.rasidi.core.ledger.TxnType
import app.rasidi.core.money.Fmt
import app.rasidi.core.money.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** مقاس الورق: A4، A5، أو بكرة حرارية 80/58 مم. */
enum class Paper(val ar: String, val widthPt: Float, val heightPt: Float) {
    A4("A4", 595.28f, 841.89f), A5("A5", 419.53f, 595.28f),
    R80("حراري 80 مم", 226.77f, 0f), R58("حراري 58 مم", 164.41f, 0f);
    val roll get() = heightPt == 0f
}

enum class VoucherKind(val title: String, val sentence: String) {
    RECEIPT("سند قبض", "استلمنا من الأخ/ {party} مبلغاً وقدره"),
    PAYMENT("سند صرف", "صرفنا إلى الأخ/ {party} مبلغاً وقدره"),
    EXCHANGE("سند صرف عملات", "تم تبديل العملة حسب التفاصيل التالية"),
    TRANSFER("سند تحويل", "تم التحويل بين الحسابات حسب التفاصيل التالية"),
    DEBIT_NOTE("سند إشعار مدين", "نود إشعاركم أننا قيدنا على حسابكم لدينا حسب التفاصيل التالية"),
    CREDIT_NOTE("سند إشعار دائن", "نود إشعاركم أننا قيدنا لحسابكم لدينا حسب التفاصيل التالية"),
    ENTRY("سند قيد", "قيد محاسبي حسب التفاصيل التالية"),
}

/** بيانات صاحب الدفتر في ترويسة السند (من «البيانات الشخصية»). */
data class Profile(val name: String = "", val city: String = "", val phone: String = "")

/** خيارات السندات (من «إعدادات السندات» و«إعدادات الطباعة»). */
data class VoucherOptions(
    val profile: Profile = Profile(),
    val paper: Paper = Paper.A4,
    val showBalance: Boolean = true,
    val signatures: List<String> = listOf("المستلم", "المحاسب"),
    val footer: String = "",
    val hideParty: Boolean = false,
)

/** السند جاهزاً للطباعة والمشاركة، مستخرجاً من حركة موجودة. */
data class Voucher(
    val kind: VoucherKind,
    val number: Int,
    val reference: String,
    val date: LocalDate,
    val party: String,
    val amount: Money,
    val words: String,
    val statement: String,
    /** سطور إضافية: الحسابات، المبلغ المقابل، السعر، الرصيد المتبقي… */
    val details: List<Pair<String, String>>,
    val options: VoucherOptions,
    val printedAt: String,
) {
    val sentence get() = if (party.isBlank() || party == "—") when (kind) {
        VoucherKind.RECEIPT -> "استلمنا مبلغاً وقدره"; VoucherKind.PAYMENT -> "صرفنا مبلغاً وقدره"; else -> kind.sentence.replace("الأخ/ {party} ", "")
    } else kind.sentence.replace("{party}", party)
    val dateLine get() = "${weekday(date)} ${date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))}"

    /** نص مختصر للمشاركة في واتساب أو رسالة. */
    fun shareText(): String = buildString {
        if (options.profile.name.isNotBlank()) appendLine(options.profile.name)
        appendLine("${kind.title} رقم $number")
        appendLine("التاريخ: $dateLine")
        if (party.isNotBlank()) appendLine("الأخ/ $party المحترم")
        appendLine("المبلغ: ${Fmt.money(amount)}")
        appendLine(words)
        if (statement.isNotBlank()) appendLine("البيان: $statement")
        details.forEach { (k, v) -> appendLine("$k: $v") }
        if (options.footer.isNotBlank()) appendLine(options.footer)
        append("— رصيدي")
    }

    companion object {
        private val days = mapOf(1 to "الاثنين", 2 to "الثلاثاء", 3 to "الأربعاء", 4 to "الخميس", 5 to "الجمعة", 6 to "السبت", 7 to "الأحد")
        fun weekday(d: LocalDate) = days.getValue(d.dayOfWeek.value)
    }
}

object VoucherBuilder {
    /** رقم السند: ترتيب الحركة بين كل الحركات (عدا الأرصدة الافتتاحية) حسب وقت الإنشاء. ثابت لأن الحركات لا تُحذف. */
    fun number(v: LedgerView, txnId: String): Int {
        val list = v.s.txns.filter { it.type != TxnType.OPENING.name }.sortedWith(compareBy({ it.createdAtUtc }, { it.id }))
        return list.indexOfFirst { it.id == txnId } + 1
    }

    /** هل للحركة صيغة «إشعار» (حركات الديون). */
    fun hasNotice(v: LedgerView, txnId: String) = v.txnById[txnId]?.debtId != null

    /**
     * يستخرج السند من الحركة. [asNotice] لحركات الديون: «إشعار مدين/دائن» بدل «قبض/صرف».
     */
    fun build(v: LedgerView, txnId: String, opt: VoucherOptions, printedAt: String, asNotice: Boolean = false): Voucher {
        val t = v.txnById.getValue(txnId)
        val type = TxnType.valueOf(t.type)
        val es = v.entries.filter { it.txnId == txnId }
        val debt = t.debtId?.let { id -> v.debts.firstOrNull { it.id == id } }
        val details = mutableListOf<Pair<String, String>>()
        var party = t.party
        var amount: Money
        val kind: VoucherKind
        when {
            debt != null -> {
                party = debt.party
                val payment = v.payments.firstOrNull { p -> v.s.debtPayments.any { it.id == p.id && it.txnId == txnId } }
                amount = (payment?.debtAmount ?: debt.principal).abs()
                val isPrincipal = type == TxnType.DEBT_NEW
                // لي عند الناس: الأصل يُقيد عليه (مدين)، والتحصيل يُقيد له (دائن). والعكس لما عليّ.
                val debitToParty = (debt.direction == DebtDirection.OWED_TO_ME) == isPrincipal
                kind = when {
                    asNotice -> if (debitToParty) VoucherKind.DEBIT_NOTE else VoucherKind.CREDIT_NOTE
                    es.isEmpty() -> if (debitToParty) VoucherKind.DEBIT_NOTE else VoucherKind.CREDIT_NOTE
                    else -> if (es.sumOf { it.amount.minor } >= 0) VoucherKind.RECEIPT else VoucherKind.PAYMENT
                }
                es.forEach { e -> if (e.amount.currency.code != amount.currency.code) details += "المبلغ بعملة الحساب" to Fmt.money(e.amount.abs()) }
                es.firstOrNull()?.let { e -> details += (if (e.amount.minor >= 0) "استُلم في" else "دُفع من") to (v.accountById[e.accountId]?.name ?: "") }
                if (opt.showBalance) {
                    val upTo = v.payments.filter { p -> p.debtId == debt.id && !p.date.isAfter(LocalDate.parse(t.date)) }
                    val rem = DebtCalc.remaining(debt, upTo)
                    val side = if (debt.direction == DebtDirection.OWED_TO_ME) "عليه" else "له"
                    details += "الرصيد المتبقي $side" to Fmt.money(rem)
                }
            }
            type == TxnType.TRANSFER -> {
                val out = es.first { it.amount.minor < 0 && it.kind.name == "TRANSFER_OUT" }
                val inn = es.first { it.kind.name == "TRANSFER_IN" }
                amount = out.amount.abs()
                kind = if (out.amount.currency.code != inn.amount.currency.code) VoucherKind.EXCHANGE else VoucherKind.TRANSFER
                details += "من حساب" to (v.accountById[out.accountId]?.name ?: "")
                details += "إلى حساب" to (v.accountById[inn.accountId]?.name ?: "")
                if (kind == VoucherKind.EXCHANGE) details += "المبلغ المستلم" to Fmt.money(inn.amount)
                t.fxRate?.let { details += "السعر الفعلي" to "1 ${out.amount.currency.code} = $it ${inn.amount.currency.code}" }
                es.firstOrNull { it.kind.name == "FEE" }?.let { details += "الرسوم" to Fmt.money(it.amount.abs()) }
            }
            else -> {
                val main = es.maxByOrNull { kotlin.math.abs(it.amount.minor) }
                amount = main?.amount?.abs() ?: Money(0, app.rasidi.core.money.Currencies.USD)
                kind = when (type) {
                    TxnType.INCOME -> VoucherKind.RECEIPT
                    TxnType.EXPENSE, TxnType.ZAKAT -> VoucherKind.PAYMENT
                    TxnType.GOLD_SELL -> VoucherKind.RECEIPT
                    TxnType.GOLD_BUY -> VoucherKind.PAYMENT
                    else -> VoucherKind.ENTRY
                }
                main?.let { e ->
                    details += (if (e.amount.minor >= 0) "استُلم في" else "دُفع من") to (v.accountById[e.accountId]?.name ?: "")
                    e.categoryId?.let { c -> v.categoryNames[c]?.let { details += "الفئة" to it } }
                }
            }
        }
        if (opt.hideParty && party.isNotBlank()) party = "—"
        return Voucher(kind, number(v, txnId), t.id.takeLast(8).uppercase(), LocalDate.parse(t.date), party, amount,
            Tafqit.money(amount), t.note, details, opt, printedAt)
    }
}
