package app.rasidi.core.backup

import app.rasidi.core.money.Currencies
import app.rasidi.core.money.MoneyPolicy
import java.math.BigDecimal
import java.time.LocalDate

/** يتحقق من المعرّفات والعلاقات والقيم قبل الاستعادة. يعيد قائمة المشكلات (فارغة = سليمة). */
object SnapshotValidator {
    fun validate(s: Snapshot): List<String> {
        val p = mutableListOf<String>()
        fun <T> uniq(name: String, l: List<T>, id: (T) -> String) {
            val d = l.groupBy(id).filter { it.value.size > 1 }.keys
            if (d.isNotEmpty()) p += "معرّفات مكررة في $name"
        }
        fun date(name: String, v: String?) { if (v != null && runCatching { LocalDate.parse(v) }.isFailure) p += "تاريخ غير صالح في $name: $v" }
        fun dec(name: String, v: String?) { if (v != null && runCatching { BigDecimal(v) }.isFailure) p += "رقم غير صالح في $name" }
        fun cur(name: String, c: String) { if (Currencies.find(c) == null) p += "عملة غير معروفة في $name: $c" }
        fun amt(name: String, m: Long) { if (m !in -MoneyPolicy.MAX_MINOR..MoneyPolicy.MAX_MINOR) p += "مبلغ خارج الحد في $name" }

        uniq("accounts", s.accounts) { it.id }; uniq("categories", s.categories) { it.id }; uniq("txns", s.txns) { it.id }
        uniq("entries", s.entries) { it.id }; uniq("debts", s.debts) { it.id }; uniq("debtPayments", s.debtPayments) { it.id }
        uniq("gold", s.goldHoldings) { it.id }; uniq("goldMoves", s.goldMoves) { it.id }; uniq("rates", s.rates) { it.id }
        uniq("inventories", s.inventories) { it.id }; uniq("zakat", s.zakatEstimates) { it.id }; uniq("zakatPayments", s.zakatPayments) { it.id }
        uniq("txn.requestId", s.txns) { it.requestId }

        val acc = s.accounts.associateBy { it.id }; val txn = s.txns.map { it.id }.toSet(); val cat = s.categories.map { it.id }.toSet()
        val debts = s.debts.associateBy { it.id }; val gold = s.goldHoldings.map { it.id }.toSet()
        val inv = s.inventories.map { it.id }.toSet(); val est = s.zakatEstimates.map { it.id }.toSet()

        s.accounts.forEach { cur("account", it.currency) }
        s.txns.forEach { date("txn", it.date); if (it.debtId != null && it.debtId !in debts) p += "حركة تشير لدين مفقود" }
        s.entries.forEach { e ->
            if (e.txnId !in txn) p += "قيد بلا حركة"
            val a = acc[e.accountId]
            if (a == null) p += "قيد بلا حساب" else if (a.currency != e.currency) p += "عملة القيد تخالف عملة الحساب"
            if (e.categoryId != null && e.categoryId !in cat) p += "قيد بفئة مفقودة"
            date("entry", e.date); amt("entry", e.amountMinor)
        }
        s.debts.forEach { cur("debt", it.currency); date("debt", it.date); date("debt.due", it.dueDate); amt("debt", it.principalMinor)
            if (it.txnId != null && it.txnId !in txn) p += "دين يشير لحركة مفقودة" }
        s.installments.forEach { if (it.debtId !in debts) p += "قسط بلا دين"; date("installment", it.dueDate) }
        s.debtPayments.forEach { dp ->
            val d = debts[dp.debtId]
            if (d == null) p += "دفعة بلا دين"
            if (dp.txnId != null && dp.txnId !in txn) p += "دفعة تشير لحركة مفقودة"
            if (dp.accountId != null && dp.accountId !in acc) p += "دفعة تشير لحساب مفقود"
            date("payment", dp.date)
        }
        // لا تتجاوز الدفعات أصل الدين
        s.debtPayments.groupBy { it.debtId }.forEach { (id, l) ->
            val d = debts[id] ?: return@forEach
            if (l.sumOf { it.debtAmountMinor } > d.principalMinor) p += "دفعات الدين ${d.party} تتجاوز أصله"
        }
        s.goldHoldings.forEach { if (it.karat !in 1..24) p += "عيار غير صالح" }
        s.goldMoves.forEach { if (it.holdingId !in gold) p += "حركة ذهب بلا قطعة"; dec("gold", it.grams); date("gold", it.date)
            if (it.txnId != null && it.txnId !in txn) p += "حركة ذهب تشير لحركة مفقودة" }
        s.rates.forEach { dec("rate", it.entered); date("rate", it.date); if (runCatching { BigDecimal(it.entered).signum() <= 0 }.getOrDefault(true)) p += "سعر غير موجب" }
        s.inventoryLines.forEach { if (it.inventoryId !in inv) p += "سطر جرد بلا جرد"; dec("inventoryLine", it.usd) }
        s.zakatEstimates.forEach { if (it.inventoryId !in inv) p += "زكاة بلا جرد" }
        s.zakatPayments.forEach { if (it.estimateId !in est) p += "دفعة زكاة بلا تقدير"; if (it.txnId !in txn) p += "دفعة زكاة بلا حركة"; cur("zakatPayment", it.currency) }
        s.settings.forEach { if (it.key !in Snapshot.BACKUP_SETTING_KEYS) p += "إعداد غير مسموح في النسخة: ${it.key}" }
        return p.distinct()
    }
}
