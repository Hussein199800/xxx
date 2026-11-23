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
