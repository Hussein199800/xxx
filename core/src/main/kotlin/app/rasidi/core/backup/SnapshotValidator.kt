package app.rasidi.core.backup

import app.rasidi.core.money.Currencies
import app.rasidi.core.money.MoneyPolicy
import java.math.BigDecimal
import java.time.LocalDate

/** يتحقق من المعرّفات والعلاقات والقيم قبل الاستعادة. يعيد قائمة المشكلات (فارغة = سليمة). */
object SnapshotValidator {
    const val MAX_ROWS = 2_000_000
    const val MAX_TEXT = 2_000
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
        uniq("installments", s.installments) { it.id }; uniq("inventoryLines", s.inventoryLines) { it.id }; uniq("audit", s.audit) { it.id }
        uniq("settings", s.settings) { it.key }

        // حدود الحجم: عدد الصفوف وأطوال النصوص
        if (s.counts().values.sum() > MAX_ROWS) p += "عدد السجلات أكبر من الحد"
        fun txt(name: String, v: String?, max: Int = MAX_TEXT) { if (v != null && v.length > max) p += "نص طويل جداً في $name" }
        fun enum(name: String, v: String, allowed: Set<String>) { if (v !in allowed) p += "قيمة غير معروفة في $name: ${v.take(20)}" }
        fun <E : Enum<E>> names(vs: Array<E>) = vs.map { it.name }.toSet()
        val txnTypes = names(app.rasidi.core.ledger.TxnType.values()); val entryKinds = names(app.rasidi.core.ledger.EntryKind.values())
        val units = setOf(app.rasidi.core.money.Rate.GOLD24_GRAM, app.rasidi.core.money.Rate.SILVER_GRAM)
        fun grams(name: String, v: String) { val g = runCatching { BigDecimal(v) }.getOrNull(); if (g == null || g.abs() > BigDecimal(1_000_000_000) || g.scale() > 18) p += "وزن غير صالح في $name" }
        s.accounts.forEach { enum("account.type", it.type, names(app.rasidi.core.ledger.AccountType.values())); txt("account", it.name, 200) }
        s.categories.forEach { enum("category.kind", it.kind, setOf("INCOME", "EXPENSE")); txt("category", it.name, 200) }
        val txById = s.txns.associateBy { it.id }
        s.txns.forEach { t ->
            enum("txn.type", t.type, txnTypes); txt("txn.note", t.note); txt("txn.party", t.party, 200)
            t.reversesId?.let { r -> if (txById[r]?.reversedById != t.id) p += "حركة عكس لا تطابق أصلها" }
            t.reversedById?.let { r -> if (txById[r]?.reversesId != t.id) p += "حركة معكوسة بلا حركة عكس مطابقة" }
        }
        s.entries.forEach { enum("entry.kind", it.kind, entryKinds) }
        s.debts.forEach { enum("debt.direction", it.direction, names(app.rasidi.core.ledger.DebtDirection.values())); txt("debt.party", it.party, 200); txt("debt.note", it.note) }
        s.installments.forEach { amt("installment", it.amountMinor) }
        s.debtPayments.forEach { amt("payment", it.debtAmountMinor); it.walletAmountMinor?.let { w -> amt("payment.wallet", w) }; it.walletCurrency?.let { c -> cur("payment.wallet", c) } }
        s.goldHoldings.forEach { enum("gold.kind", it.kind, names(app.rasidi.core.ledger.GoldKind.values())); txt("gold", it.name, 200) }
        s.goldMoves.forEach { enum("gold.move", it.type, names(app.rasidi.core.ledger.GoldMoveType.values())); grams("gold", it.grams) }
        s.rates.forEach { if (Currencies.find(it.unit) == null && it.unit !in units) p += "وحدة سعر غير معروفة"; enum("rate.direction", it.direction, names(app.rasidi.core.money.RateDirection.values())); txt("rate.source", it.source, 200) }
        s.inventories.forEach { date("inventory", it.date); enum("inventory.status", it.status, setOf("FINAL", "DRAFT")); dec("inventory", it.pricedNetUsd) }
        s.zakatPayments.forEach { date("zakatPayment", it.date); amt("zakatPayment", it.amountMinor); dec("zakatPayment", it.usd) }
        s.zakatEstimates.forEach { z -> listOf(z.assetsUsd, z.receivablesUsd, z.deductionsUsd, z.baseUsd, z.zakatUsd, z.percent).forEach { dec("zakat", it) } }
        s.parties.forEach { txt("party", it.name, 200); txt("party.note", it.note) }
        s.settings.forEach { txt("setting", it.value, 4000) }

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
        val members = s.members.map { it.id }.toSet()
        uniq("members", s.members) { it.id }; uniq("budgets", s.budgets) { it.id }; uniq("recurring", s.recurring) { it.id }
        s.txns.forEach { if (it.memberId != null && it.memberId !in members) p += "حركة تشير لفرد مفقود" }
        s.budgets.forEach { if (it.categoryId !in cat) p += "ميزانية بفئة مفقودة"; cur("budget", it.currency); amt("budget", it.limitMinor) }
        s.recurring.forEach { r ->
            if (r.accountId !in acc) p += "متكرر بحساب مفقود"; if (r.categoryId !in cat) p += "متكرر بفئة مفقودة"
            if (r.dayOfMonth !in 1..31) p += "يوم متكرر غير صالح"; if (r.memberId != null && r.memberId !in members) p += "متكرر بفرد مفقود"
        }
        uniq("parties", s.parties) { it.id }; uniq("parties.name", s.parties) { it.name.trim() }
        val partyNames = s.parties.map { it.name.trim() }.toSet()
        if (s.parties.isNotEmpty()) s.debts.forEach { if (it.party.trim() !in partyNames) p += "دين لشخص بلا حساب: ${it.party}" }
        s.settings.forEach { if (it.key !in Snapshot.BACKUP_SETTING_KEYS) p += "إعداد غير مسموح في النسخة: ${it.key}" }
        return p.distinct()
    }
}
