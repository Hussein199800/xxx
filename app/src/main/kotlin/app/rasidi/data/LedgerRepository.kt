package app.rasidi.data

import androidx.room.withTransaction
import app.rasidi.core.backup.Snapshot
import app.rasidi.core.data.*
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.core.valuation.InventoryResult
import app.rasidi.core.zakat.ZakatResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** خطأ عمل يُعرض للمستخدم كما هو. */
class LedgerException(message: String) : Exception(message)

/**
 * كل الكتابة على الدفتر تمر من هنا، وكل عملية متعددة البنود في معاملة Room واحدة.
 * [requestId] يمنع حفظ الضغط المكرر: إعادة الطلب نفسه تعيد الحركة الموجودة دون تكرار.
 */
class LedgerRepository(val db: RasidiDatabase, private val clock: () -> Long = System::currentTimeMillis, private val onWrite: () -> Unit = {}) {
    private val dao = db.dao()
    private val _version = MutableStateFlow(0L)
    /** يزداد مع كل كتابة ناجحة؛ تراقبه الواجهة والنسخ التلقائي. */
    val version: StateFlow<Long> = _version

    private fun uuid() = UUID.randomUUID().toString()

    private suspend fun <T> write(block: suspend () -> T): T {
        val r = db.withTransaction { block() }
        _version.value = _version.value + 1
        onWrite()
        return r
    }

    private suspend fun audit(type: String, id: String, action: String, detail: String = "") =
        dao.insertAudit(AuditEntity(uuid(), clock(), type, id, action, detail))

    // ---------------- قراءة ----------------
    suspend fun snapshot(includeAudit: Boolean = true): Snapshot = db.withTransaction {
        Snapshot(
            dao.accounts(), dao.categories(), dao.txns(), dao.entries(), dao.debts(), dao.installments(), dao.debtPayments(),
            dao.goldHoldings(), dao.goldMoves(), dao.rates(), dao.inventories(), dao.inventoryLines(),
            dao.zakatEstimates(), dao.zakatPayments(),
            dao.settings().filter { it.key in Snapshot.BACKUP_SETTING_KEYS },
            if (includeAudit) dao.audit() else emptyList(),
        )
    }

    suspend fun view(): LedgerView = LedgerView(snapshot(includeAudit = false))

    suspend fun isEmpty() = dao.accounts().isEmpty() && dao.txnCount() == 0

    suspend fun setting(key: String) = dao.setting(key)
    suspend fun putSetting(key: String, value: String) = write { dao.putSetting(SettingEntity(key, value)) }

    // ---------------- إعداد ----------------
    suspend fun ensureCategories() {
        if (dao.categories().isEmpty()) write { app.rasidi.core.demo.DemoData.defaultCategories.forEach { dao.insertCategory(it) } }
    }

    suspend fun createAccount(name: String, type: AccountType, currency: CurrencyDef, openingMinor: Long, openingDate: LocalDate, allowNegative: Boolean = false, requestId: String = uuid()): String = write {
        require(name.isNotBlank()) { "اسم الحساب مطلوب" }
        val id = uuid()
        dao.insertAccount(AccountEntity(id, name.trim(), type.name, currency.code, allowNegative, false, dao.accounts().size + 1, clock()))
        if (openingMinor != 0L) {
            val t = uuid()
            dao.insertTxn(TxnEntity(t, TxnType.OPENING.name, openingDate.toString(), "رصيد افتتاحي", requestId = requestId, createdAtUtc = clock(), updatedAtUtc = clock()))
            dao.insertEntries(listOf(EntryEntity(uuid(), t, id, EntryKind.OPENING.name, openingMinor, currency.code, openingDate.toString())))
        }
        audit("account", id, "create", name)
        id
    }

    suspend fun updateAccount(a: AccountEntity) = write { dao.updateAccount(a); audit("account", a.id, "update", a.name) }

    suspend fun addCategory(name: String, kind: String): String = write {
        val id = uuid(); dao.insertCategory(CategoryEntity(id, name.trim(), kind, sort = 100)); id
    }
    suspend fun archiveCategory(c: CategoryEntity, archived: Boolean) = write { dao.updateCategory(c.copy(archived = archived)) }

    // ---------------- الحركات ----------------
    private suspend fun existing(requestId: String): String? = dao.txnByRequest(requestId)?.id

    private suspend fun account(id: String): Account = dao.accounts().firstOrNull { it.id == id }?.toDomain() ?: throw LedgerException("الحساب غير موجود")

    private suspend fun guardNegative(acc: Account, delta: Long, date: LocalDate) {
        if (delta >= 0 || acc.allowNegative) return
        val es = dao.entries().filter { it.accountId == acc.id }.map { it.toDomain() }
        if (Balances.wouldGoNegative(acc, es, Money(delta, acc.currency), date))
            throw LedgerException("الرصيد في «${acc.name}» لا يكفي. فعّل «السماح بالسالب» للحساب إن كان يدعم السحب")
    }

    /** دخل أو مصروف بسيط. يعيد معرّف الحركة. */
    suspend fun addIncomeExpense(income: Boolean, accountId: String, amountMinor: Long, categoryId: String?, date: LocalDate, note: String, party: String, requestId: String): String {
        existing(requestId)?.let { return it }
        if (amountMinor <= 0) throw LedgerException("المبلغ يجب أن يكون أكبر من صفر")
        return write {
            existing(requestId)?.let { return@write it }
            val acc = account(accountId)
            val signed = if (income) amountMinor else -amountMinor
            guardNegative(acc, signed, date)
            val t = uuid()
            dao.insertTxn(TxnEntity(t, (if (income) TxnType.INCOME else TxnType.EXPENSE).name, date.toString(), note.trim(), party.trim(), requestId, createdAtUtc = clock(), updatedAtUtc = clock()))
            dao.insertEntries(listOf(EntryEntity(uuid(), t, acc.id, (if (income) EntryKind.INCOME else EntryKind.EXPENSE).name, signed, acc.currency.code, date.toString(), categoryId)))
            t
        }
    }

    /**
     * تحويل برجلين: المبلغان الفعليان بعملتيهما، والسعر الناتج يُحفظ. الرسوم قيد مصروف مرتبط بحسابه وعملته.
     * التحويل الداخلي لا يدخل في الدخل والمصروف.
     */
    suspend fun transfer(fromId: String, toId: String, fromMinor: Long, toMinor: Long, feeMinor: Long, feeAccountId: String?, date: LocalDate, note: String, requestId: String): String {
        existing(requestId)?.let { return it }
        if (fromId == toId) throw LedgerException("اختر حسابين مختلفين")
        if (fromMinor <= 0 || toMinor <= 0) throw LedgerException("أدخل المبلغين")
        return write {
            existing(requestId)?.let { return@write it }
            val from = account(fromId); val to = account(toId)
            if (from.currency.code == to.currency.code && fromMinor != toMinor) throw LedgerException("نفس العملة: المبلغان يجب أن يتساويا")
            val feeAcc = feeAccountId?.let { account(it) } ?: from
            guardNegative(from, -fromMinor - (if (feeAcc.id == from.id) feeMinor else 0), date)
            if (feeMinor > 0 && feeAcc.id != from.id) guardNegative(feeAcc, -feeMinor, date)
            val fx = if (from.currency.code != to.currency.code)
                Money(toMinor, to.currency).amount.divide(Money(fromMinor, from.currency).amount, 10, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() else null
            val t = uuid()
            dao.insertTxn(TxnEntity(t, TxnType.TRANSFER.name, date.toString(), note.trim(), "", requestId, fxRate = fx, createdAtUtc = clock(), updatedAtUtc = clock()))
            val es = mutableListOf(
                EntryEntity(uuid(), t, from.id, EntryKind.TRANSFER_OUT.name, -fromMinor, from.currency.code, date.toString()),
                EntryEntity(uuid(), t, to.id, EntryKind.TRANSFER_IN.name, toMinor, to.currency.code, date.toString()),
            )
            if (feeMinor > 0) es += EntryEntity(uuid(), t, feeAcc.id, EntryKind.FEE.name, -feeMinor, feeAcc.currency.code, date.toString(), "cat-fees".takeIf { dao.categories().any { c -> c.id == it } })
            dao.insertEntries(es)
            t
        }
    }

    /** تسوية بعد العدّ اليدوي: قيد مستقل بالفرق عند تأكيد المستخدم. */
    suspend fun adjustToCount(accountId: String, countedMinor: Long, date: LocalDate, requestId: String): String? {
        existing(requestId)?.let { return it }
        return write {
            val acc = account(accountId)
            val cur = Balances.balance(acc, dao.entries().map { it.toDomain() }, date)
            val diff = countedMinor - cur.minor
            if (diff == 0L) return@write null
            val t = uuid()
            dao.insertTxn(TxnEntity(t, TxnType.ADJUSTMENT.name, date.toString(), "تسوية بعد العدّ (فرق ${Fmt.money(Money(diff, acc.currency), false)})", "", requestId, createdAtUtc = clock(), updatedAtUtc = clock()))
            dao.insertEntries(listOf(EntryEntity(uuid(), t, acc.id, EntryKind.ADJUSTMENT.name, diff, acc.currency.code, date.toString())))
            audit("txn", t, "adjust", "counted=$countedMinor previous=${cur.minor}")
            t
        }
    }

    // ---------------- الديون ----------------
    /**
     * دين جديد. [cashAccountId] = null لدين قديم بلا حركة نقدية؛ وإلا يُربط قرض نقدي جديد بحركة حساب.
     */
    suspend fun addDebt(party: String, direction: DebtDirection, principalMinor: Long, currency: CurrencyDef, date: LocalDate, due: LocalDate?, installments: List<Pair<LocalDate, Long>>, note: String, cashAccountId: String?, cashMinor: Long?, requestId: String): String {
        if (party.isBlank()) throw LedgerException("اسم الطرف مطلوب")
        if (principalMinor <= 0) throw LedgerException("أصل الدين يجب أن يكون أكبر من صفر")
        if (installments.isNotEmpty() && installments.sumOf { it.second } != principalMinor) throw LedgerException("مجموع الأقساط يجب أن يساوي أصل الدين")
        existing(requestId)?.let { t -> return dao.txn(t)?.debtId ?: t }
        return write {
            val id = uuid()
            var txnId: String? = null
            if (cashAccountId != null) {
                val acc = account(cashAccountId)
                val amt = if (acc.currency.code == currency.code) principalMinor else (cashMinor ?: throw LedgerException("أدخل مبلغ الحساب بعملته ${acc.currency.code}"))
                val sign = DebtCalc.walletSign(direction, isPrincipal = true)
                guardNegative(acc, sign * amt, date)
                txnId = uuid()
                dao.insertDebt(DebtEntity(id, party.trim(), direction.name, principalMinor, currency.code, date.toString(), due?.toString(), note.trim(), null, createdAtUtc = clock()))
                dao.insertTxn(TxnEntity(txnId, TxnType.DEBT_NEW.name, date.toString(), note.ifBlank { if (direction == DebtDirection.OWED_TO_ME) "قرض أعطيته" else "قرض أخذته" }, party.trim(), requestId, debtId = id, createdAtUtc = clock(), updatedAtUtc = clock()))
                dao.insertEntries(listOf(EntryEntity(uuid(), txnId, acc.id, EntryKind.DEBT_PRINCIPAL.name, sign * amt, acc.currency.code, date.toString())))
                dao.updateDebt(dao.debts().first { it.id == id }.copy(txnId = txnId))
            } else {
                dao.insertDebt(DebtEntity(id, party.trim(), direction.name, principalMinor, currency.code, date.toString(), due?.toString(), note.trim(), null, createdAtUtc = clock()))
                // حركة بلا قيود لتثبيت requestId ومنع التكرار
                dao.insertTxn(TxnEntity(uuid(), TxnType.DEBT_NEW.name, date.toString(), "دين مسجّل دون حركة نقدية", party.trim(), requestId, debtId = id, createdAtUtc = clock(), updatedAtUtc = clock()))
            }
            if (installments.isNotEmpty()) dao.insertInstallments(installments.map { (d, m) -> InstallmentEntity(uuid(), id, d.toString(), m) })
            audit("debt", id, "create", "$party $principalMinor ${currency.code}")
            id
        }
    }

    /**
     * سداد أو تحصيل: [debtMinor] بعملة الدين، و[walletMinor] بعملة الحساب إن اختلفت. يرفض تجاوز المتبقي.
     * يحدّث الدين والحساب معاً في معاملة واحدة.
     */
    suspend fun addDebtPayment(debtId: String, debtMinor: Long, accountId: String?, walletMinor: Long?, date: LocalDate, note: String, requestId: String): String {
        existing(requestId)?.let { return it }
        return write {
            existing(requestId)?.let { return@write it }
            val de = dao.debts().firstOrNull { it.id == debtId } ?: throw LedgerException("الدين غير موجود")
            val debt = de.toDomain(dao.installments())
            val pays = dao.debtPayments().filter { it.debtId == debtId }.map { it.toDomain(debt.currency) }
            when (val v = DebtCalc.validatePayment(debt, pays, Money(debtMinor, debt.currency))) {
                is DebtCalc.Validation.Rejected -> throw LedgerException(v.reason)
                DebtCalc.Validation.Ok -> {}
            }
            val t = uuid()
            dao.insertTxn(TxnEntity(t, TxnType.DEBT_PAYMENT.name, date.toString(), note.ifBlank { if (debt.direction == DebtDirection.OWED_TO_ME) "تحصيل من ${debt.party}" else "سداد إلى ${debt.party}" }, debt.party, requestId, debtId = debtId, createdAtUtc = clock(), updatedAtUtc = clock()))
            var wMinor: Long? = null; var wCur: String? = null
            if (accountId != null) {
                val acc = account(accountId)
                val amt = if (acc.currency.code == debt.currency.code) debtMinor else (walletMinor ?: throw LedgerException("أدخل المبلغ بعملة الحساب ${acc.currency.code}"))
                val signed = DebtCalc.walletSign(debt.direction, isPrincipal = false) * amt
                guardNegative(acc, signed, date)
                dao.insertEntries(listOf(EntryEntity(uuid(), t, acc.id, EntryKind.DEBT_SETTLEMENT.name, signed, acc.currency.code, date.toString())))
                wMinor = signed; wCur = acc.currency.code
            }
            dao.insertDebtPayment(DebtPaymentEntity(uuid(), debtId, t, date.toString(), debtMinor, wMinor, wCur, accountId))
            t
        }
    }

    suspend fun archiveDebt(d: DebtEntity, archived: Boolean) = write { dao.updateDebt(d.copy(archived = archived)); audit("debt", d.id, if (archived) "archive" else "unarchive") }

    // ---------------- الذهب ----------------
    suspend fun addGoldHolding(name: String, karat: Int, kind: GoldKind, openingGrams: BigDecimal, date: LocalDate, note: String = ""): String = write {
        if (karat !in 1..24) throw LedgerException("العيار بين 1 و24")
        val id = uuid()
        dao.insertGoldHolding(GoldHoldingEntity(id, name.trim(), karat, kind.name, note))
        if (openingGrams.signum() != 0) dao.insertGoldMove(GoldMoveEntity(uuid(), id, GoldMoveType.OPENING.name, openingGrams.toPlainString(), date.toString()))
        audit("gold", id, "create", "$name $karat")
        id
    }

    /**
     * شراء أو بيع ذهب: النقد يتغير مرة واحدة (قيد واحد) والوزن يتغير بالكمية الفعلية (حركة وزن واحدة)، في معاملة واحدة.
     */
    suspend fun goldTrade(holdingId: String, buy: Boolean, grams: BigDecimal, accountId: String?, cashMinor: Long?, date: LocalDate, note: String, requestId: String): String {
        existing(requestId)?.let { return it }
        if (grams.signum() <= 0) throw LedgerException("الوزن يجب أن يكون أكبر من صفر")
        return write {
            existing(requestId)?.let { return@write it }
            val h = dao.goldHoldings().firstOrNull { it.id == holdingId } ?: throw LedgerException("القطعة غير موجودة")
            if (!buy) {
                val have = GoldCalc.grams(h.toDomain(), dao.goldMoves().map { it.toDomain() }, date)
                if (grams > have) throw LedgerException("الوزن المباع أكبر من الرصيد (${Fmt.plain(have)}غ)")
            }
            val t = uuid()
            dao.insertTxn(TxnEntity(t, (if (buy) TxnType.GOLD_BUY else TxnType.GOLD_SELL).name, date.toString(), note.ifBlank { "${if (buy) "شراء" else "بيع"} ${Fmt.plain(grams)}غ ${h.name}" }, "", requestId, createdAtUtc = clock(), updatedAtUtc = clock()))
            if (accountId != null && cashMinor != null && cashMinor > 0) {
                val acc = account(accountId)
                val signed = if (buy) -cashMinor else cashMinor
                guardNegative(acc, signed, date)
                dao.insertEntries(listOf(EntryEntity(uuid(), t, acc.id, EntryKind.GOLD_TRADE.name, signed, acc.currency.code, date.toString())))
            }
            dao.insertGoldMove(GoldMoveEntity(uuid(), holdingId, (if (buy) GoldMoveType.BUY else GoldMoveType.SELL).name, (if (buy) grams else grams.negate()).toPlainString(), date.toString(), t))
            t
        }
    }

    // ---------------- التصحيح ----------------
    /**
     * عكس حركة: حركة جديدة بقيود معاكسة، ومعها عكس السجل المرتبط (دفعة الدين، وزن الذهب، دفعة الزكاة) في معاملة واحدة.
     */
    suspend fun reverse(txnId: String, reason: String, requestId: String): String {
        existing(requestId)?.let { return it }
        return write {
            val t = dao.txn(txnId) ?: throw LedgerException("الحركة غير موجودة")
            if (t.reversedById != null) throw LedgerException("الحركة معكوسة مسبقاً")
            if (t.reversesId != null) throw LedgerException("لا يمكن عكس حركة عكس")
            val r = uuid()
            dao.insertTxn(TxnEntity(r, TxnType.REVERSAL.name, t.date, // بتاريخ الأصل حتى تتعادل تقارير الفترة
                "عكس: ${t.note}".take(200) + if (reason.isNotBlank()) " — $reason" else "", t.party, requestId, debtId = t.debtId, reversesId = t.id, createdAtUtc = clock(), updatedAtUtc = clock()))
            dao.insertEntries(dao.entriesOf(t.id).map { it.copy(id = uuid(), txnId = r, amountMinor = -it.amountMinor) })
            dao.paymentsOfTxn(t.id).forEach { p -> dao.insertDebtPayment(p.copy(id = uuid(), txnId = r, debtAmountMinor = -p.debtAmountMinor, walletAmountMinor = p.walletAmountMinor?.let { -it })) }
            dao.goldMovesOfTxn(t.id).forEach { m -> dao.insertGoldMove(m.copy(id = uuid(), txnId = r, type = GoldMoveType.ADJUST.name, grams = BigDecimal(m.grams).negate().toPlainString())) }
            dao.zakatPaymentsOfTxn(t.id).forEach { z -> dao.insertZakatPayment(z.copy(id = uuid(), txnId = r, amountMinor = -z.amountMinor, usd = BigDecimal(z.usd).negate().toPlainString())) }
            dao.updateTxn(t.copy(reversedById = r, updatedAtUtc = clock()))
            audit("txn", t.id, "reverse", "by=$r ${if (t.debtId != null) "debt=${t.debtId}" else ""} $reason")
            r
        }
    }

    /** تراجع سريع عن حركة جديدة (خلال 10 دقائق ولا يرتبط بها جرد معتمد): حذف كامل مع تسجيله. */
    suspend fun undoRecent(txnId: String) = write {
        val t = dao.txn(txnId) ?: return@write
        if (clock() - t.createdAtUtc > 10 * 60_000) throw LedgerException("انتهت مهلة التراجع السريع؛ استخدم «عكس الحركة»")
        if (dao.zakatPaymentsOfTxn(txnId).isNotEmpty()) throw LedgerException("دفعة زكاة: استخدم «عكس الحركة»")
        dao.deleteTxn(txnId) // القيود والدفعات وحركات الوزن تُحذف بالتتابع (CASCADE)
        t.debtId?.let { d -> if (t.type == TxnType.DEBT_NEW.name) dao.debts().firstOrNull { it.id == d }?.let { if (dao.debtPayments().none { p -> p.debtId == d }) db.openHelper.writableDatabase.execSQL("DELETE FROM debts WHERE id = ?", arrayOf(d)) } }
        audit("txn", txnId, "undo", "${t.type} ${t.date} ${t.note}")
    }

    /**
     * تعديل دخل/مصروف بسيط في مكانه مع سجل تعديل يحفظ القيم السابقة.
     * الحركات المرتبطة بدين أو تحويل تُصحَّح بالعكس ثم إعادة الإدخال.
     */
    suspend fun editSimple(txnId: String, amountMinor: Long, categoryId: String?, date: LocalDate, note: String, party: String) = write {
        val t = dao.txn(txnId) ?: throw LedgerException("الحركة غير موجودة")
        if (t.type != TxnType.INCOME.name && t.type != TxnType.EXPENSE.name) throw LedgerException("هذه الحركة تُصحَّح بالعكس")
        if (t.reversedById != null) throw LedgerException("الحركة معكوسة")
        val es = dao.entriesOf(txnId)
        val e = es.single()
        val signed = if (t.type == TxnType.INCOME.name) amountMinor else -amountMinor
        val acc = account(e.accountId)
        guardNegative(acc, signed - e.amountMinor, date)
        val inInventory = dao.inventories().any { it.status == "FINAL" && !LocalDate.parse(it.date).isBefore(LocalDate.parse(t.date).coerceAtMost(date)) }
        audit("txn", txnId, "edit", "old=${e.amountMinor} ${t.date} cat=${e.categoryId} note=${t.note}; new=$signed $date cat=$categoryId" + if (inInventory) "; يمسّ جرداً معتمداً" else "")
        dao.updateEntries(listOf(e.copy(amountMinor = signed, date = date.toString(), categoryId = categoryId)))
        dao.updateTxn(t.copy(date = date.toString(), note = note.trim(), party = party.trim(), updatedAtUtc = clock()))
    }

    // ---------------- الأسعار ----------------
    suspend fun addRate(unit: String, entered: BigDecimal, direction: RateDirection, date: LocalDate, source: String, kind: RateKind): String = write {
        if (entered.signum() <= 0) throw LedgerException("السعر يجب أن يكون موجباً")
        val id = uuid()
        dao.upsertRate(RateEntity(id, unit, entered.stripTrailingZeros().toPlainString(), direction.name, date.toString(), source.ifBlank { "يدوي" }, kind.name, clock()))
        id
    }
    suspend fun deleteRate(r: RateEntity) = write { dao.deleteRate(r) }

    // ---------------- الجرد ----------------
    /** يثبّت الجرد في لقطة مستقلة برقم مراجعة. مراجعة جديدة لنفس المجموعة لا تمسّ السابقة. */
    suspend fun saveInventory(inv: InventoryResult, final: Boolean, yearClose: Boolean, groupId: String?, note: String): String = write {
        if (final && !inv.isComplete) throw LedgerException("لا يمكن اعتماد جرد فيه ${inv.missingCount} بند بلا سعر؛ احفظه مسودة")
        val gid = groupId ?: uuid()
        val rev = (dao.inventories().filter { it.groupId == gid }.maxOfOrNull { it.revision } ?: 0) + 1
        val id = uuid()
        dao.insertInventory(InventoryEntity(id, gid, rev, inv.date.toString(), if (final) "FINAL" else "DRAFT", yearClose, inv.pricedNet.toPlainString(), inv.missingCount, note, clock()))
        dao.insertInventoryLines(inv.lines.map { l ->
            InventoryLineEntity(uuid(), id, l.item.id, l.item.kind.name, l.item.label, l.item.unit, l.item.quantity.toPlainString(), l.item.original,
                l.item.goldKind?.name, l.item.refId, l.state.name, l.usd?.toPlainString(), l.rate?.label(), l.rate?.source, l.rate?.date?.toString(), l.note,
                l.rate?.entered?.toPlainString(), l.rate?.direction?.name)
        })
        audit("inventory", id, "save", "rev=$rev final=$final yearClose=$yearClose")
        id
    }

    suspend fun saveZakat(inventoryId: String, z: ZakatResult, decisionsJson: String): String = write {
        val id = uuid()
        dao.insertZakatEstimate(ZakatEstimateEntity(id, inventoryId, z.input.hawlHijri, z.input.hawlMethod, z.input.hawlComplete, z.input.nisab.label,
            z.nisabUsd?.toPlainString(), z.input.percent.toPlainString(), z.assetsUsd.toPlainString(), z.receivablesUsd.toPlainString(), z.deductionsUsd.toPlainString(),
            z.baseUsd.toPlainString(), z.zakatUsd.toPlainString(), z.status.name, decisionsJson, clock()))
        audit("zakat", id, "save", "base=${z.baseUsd} zakat=${z.zakatUsd}")
        id
    }

    /** دفعة زكاة منفّذة مرتبطة بمصروف «زكاة» في معاملة واحدة، بسعر يوم الدفع. */
    suspend fun addZakatPayment(estimateId: String, accountId: String, amountMinor: Long, rate: Rate, date: LocalDate, beneficiary: String, requestId: String): String {
        existing(requestId)?.let { return it }
        if (amountMinor <= 0) throw LedgerException("المبلغ يجب أن يكون أكبر من صفر")
        return write {
            existing(requestId)?.let { return@write it }
            val acc = account(accountId)
            guardNegative(acc, -amountMinor, date)
            val usd = rate.toUsd(Money(amountMinor, acc.currency).amount)
            val t = uuid()
            dao.insertTxn(TxnEntity(t, TxnType.ZAKAT.name, date.toString(), "دفعة زكاة", beneficiary.trim(), requestId, createdAtUtc = clock(), updatedAtUtc = clock()))
            dao.insertEntries(listOf(EntryEntity(uuid(), t, acc.id, EntryKind.ZAKAT.name, -amountMinor, acc.currency.code, date.toString(), "cat-zakat".takeIf { dao.categories().any { c -> c.id == it } })))
            dao.insertZakatPayment(ZakatPaymentEntity(uuid(), estimateId, t, date.toString(), amountMinor, acc.currency.code, rate.entered.toPlainString(), rate.direction.name, rate.date.toString(), usd.toPlainString(), acc.id, beneficiary.trim()))
            t
        }
    }

    // ---------------- الاستعادة ----------------
    /** يستبدل الدفتر كاملاً بلقطة في معاملة واحدة: إما السجل السابق كاملاً أو الجديد كاملاً. */
    suspend fun replaceAll(s: Snapshot) = write {
        dao.clearZakatPayments(); dao.clearZakatEstimates(); dao.clearInventoryLines(); dao.clearInventories()
        dao.clearDebtPayments(); dao.clearInstallments(); dao.clearGoldMoves(); dao.clearEntries(); dao.clearTxns()
        dao.clearDebts(); dao.clearGoldHoldings(); dao.clearRates(); dao.clearCategories(); dao.clearAccounts(); dao.clearSettings(); dao.clearAudit()
        dao.insertAccounts(s.accounts); dao.insertCategories(s.categories); dao.insertTxns(s.txns); dao.insertEntries(s.entries)
        dao.insertDebts(s.debts); dao.insertInstallments(s.installments); dao.insertDebtPayments(s.debtPayments)
        dao.insertGoldHoldings(s.goldHoldings); dao.insertGoldMoves(s.goldMoves); dao.insertRates(s.rates)
        dao.insertInventories(s.inventories); dao.insertInventoryLines(s.inventoryLines); dao.insertZakatEstimates(s.zakatEstimates); dao.insertZakatPayments(s.zakatPayments)
        dao.insertSettings(s.settings); dao.insertAudits(s.audit)
        db.jobs().clear()
    }
}
