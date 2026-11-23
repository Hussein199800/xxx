package app.rasidi.core.data

import app.rasidi.core.backup.Snapshot
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import java.math.BigDecimal
import java.time.LocalDate

/** تحويل الكيانات المخزّنة إلى نماذج الحساب في core. */
fun AccountEntity.toDomain() = Account(id, name, AccountType.valueOf(type), Currencies.of(currency), allowNegative, archived)
fun EntryEntity.toDomain() = Entry(id, txnId, accountId, EntryKind.valueOf(kind), Money(amountMinor, Currencies.of(currency)), LocalDate.parse(date), categoryId)
fun DebtEntity.toDomain(inst: List<InstallmentEntity>) = Debt(
    id, party, DebtDirection.valueOf(direction), Money(principalMinor, Currencies.of(currency)), LocalDate.parse(date),
    dueDate?.let(LocalDate::parse),
    inst.filter { it.debtId == id }.sortedBy { it.dueDate }.map { Installment(LocalDate.parse(it.dueDate), Money(it.amountMinor, Currencies.of(currency))) },
    note,
)
fun DebtPaymentEntity.toDomain(debtCurrency: CurrencyDef) = DebtPayment(
    id, debtId, LocalDate.parse(date), Money(debtAmountMinor, debtCurrency),
    walletAmountMinor?.let { Money(it, Currencies.of(walletCurrency!!)) }, accountId,
)
fun GoldHoldingEntity.toDomain() = GoldHolding(id, name, karat, GoldKind.valueOf(kind), archived)
fun GoldMoveEntity.toDomain() = GoldMove(id, holdingId, GoldMoveType.valueOf(type), BigDecimal(grams), LocalDate.parse(date), txnId)
fun RateEntity.toDomain() = Rate(id, unit, BigDecimal(entered), RateDirection.valueOf(direction), LocalDate.parse(date), source, RateKind.valueOf(kind), observedAtUtc)

/** عرض محسوب للدفتر من لقطة: أرصدة، ديون، ذهب. */
class LedgerView(val s: Snapshot) {
    val accounts = s.accounts.map { it.toDomain() }
    val entries = s.entries.map { it.toDomain() }
    val debts = s.debts.map { it.toDomain(s.installments) }
    val payments: List<DebtPayment> = s.debtPayments.mapNotNull { p -> debts.firstOrNull { it.id == p.debtId }?.let { p.toDomain(it.currency) } }
    val gold = s.goldHoldings.map { it.toDomain() }
    val goldMoves = s.goldMoves.map { it.toDomain() }
    val rates = s.rates.map { it.toDomain() }
    val categoryNames = s.categories.associate { it.id to it.name }
    val txnById = s.txns.associateBy { it.id }
    val accountById = accounts.associateBy { it.id }

    fun balance(a: Account, on: LocalDate? = null) = Balances.balance(a, entries, on)
}

/** يستخرج بنود الجرد كما كانت في نهاية [date]: أرصدة الحسابات والذهب والديون المتبقية. */
fun LedgerView.inventoryItems(date: LocalDate): List<app.rasidi.core.valuation.InventoryItem> {
    val out = mutableListOf<app.rasidi.core.valuation.InventoryItem>()
    for (a in accounts) {
        val b = balance(a, date)
        if (b.isZero) continue
        out += app.rasidi.core.valuation.InventoryItem("acc:${a.id}", app.rasidi.core.valuation.LineKind.CASH, a.name, a.currency.code, b.amount, Fmt.money(b, false), refId = a.id)
    }
    for (h in gold) {
        val g = GoldCalc.grams(h, goldMoves, date)
        if (g.signum() == 0) continue
        val pure = GoldCalc.pureGrams(g, h.karat)
        out += app.rasidi.core.valuation.InventoryItem("gold:${h.id}", app.rasidi.core.valuation.LineKind.GOLD, h.name, Rate.GOLD24_GRAM, pure,
            "${Fmt.plain(g)}غ عيار ${h.karat} = ${Fmt.number(pure, 3)}غ خالص", h.kind, h.id)
    }
    for (d in debts) {
        if (d.date.isAfter(date)) continue
        val rem = DebtCalc.remaining(d, payments, date)
        if (rem.minor <= 0) continue
        val kind = if (d.direction == DebtDirection.OWED_TO_ME) app.rasidi.core.valuation.LineKind.RECEIVABLE else app.rasidi.core.valuation.LineKind.PAYABLE
        out += app.rasidi.core.valuation.InventoryItem("debt:${d.id}", kind, d.party, d.currency.code, rem.amount, Fmt.money(rem, false), refId = d.id)
    }
    return out
}

/** صافي الأموال اليوم بكل عملة (الأرصدة فقط) وتقديره بالدولار بأحدث الأسعار المتاحة حتى [date]. */
data class NetWorth(val byCurrency: Map<String, Money>, val inventory: app.rasidi.core.valuation.InventoryResult)

fun LedgerView.netWorth(date: LocalDate): NetWorth {
    val byCur = accounts.filter { !it.archived || !balance(it).isZero }.groupBy { it.currency.code }
        .mapValues { (c, l) -> l.map { balance(it, date) }.fold(Money.zero(Currencies.of(c))) { a, b -> a + b } }
    val items = inventoryItems(date)
    // الشاشة الرئيسية تقبل أقرب سعر سابق كتقدير (مع وسم ذلك في «كيف انحسب؟»)
    val inv = app.rasidi.core.valuation.InventoryCalc.price(date, items, rates, confirmedOlder = items.map { it.id }.toSet())
    return NetWorth(byCur, inv)
}

/** يعيد بناء نتيجة جرد محفوظ من لقطته (الأسعار والقيم المثبّتة)، دون إعادة حساب بأسعار اليوم. */
fun savedInventory(inv: InventoryEntity, lines: List<InventoryLineEntity>): app.rasidi.core.valuation.InventoryResult =
    app.rasidi.core.valuation.InventoryResult(LocalDate.parse(inv.date), lines.filter { it.inventoryId == inv.id }.map { l ->
        val item = app.rasidi.core.valuation.InventoryItem(l.itemKey, app.rasidi.core.valuation.LineKind.valueOf(l.kind), l.label, l.unit, BigDecimal(l.quantity), l.original,
            l.goldKind?.let { GoldKind.valueOf(it) }, l.refId)
        val rate = if (l.rateEntered != null && l.rateDirection != null && l.rateDate != null)
            Rate("saved:${l.id}", l.unit, BigDecimal(l.rateEntered), RateDirection.valueOf(l.rateDirection), LocalDate.parse(l.rateDate), l.rateSource ?: "") else null
        app.rasidi.core.valuation.PricedLine(item, app.rasidi.core.valuation.PriceState.valueOf(l.state), rate, l.usd?.let(::BigDecimal), l.note)
    })
