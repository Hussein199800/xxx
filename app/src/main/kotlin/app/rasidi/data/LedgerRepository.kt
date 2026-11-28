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
import kotlinx.coroutines.flow.update
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
    companion object {
        const val UNDO_WINDOW_MS = 10 * 60_000L
        val MAX_GRAMS = BigDecimal(1_000_000)
    }
    private val dao = db.dao()
    private val _version = MutableStateFlow(0L)
    /** يزداد مع كل كتابة ناجحة؛ تراقبه الواجهة والنسخ التلقائي. */
    val version: StateFlow<Long> = _version

    private fun uuid() = UUID.randomUUID().toString()

    private suspend fun <T> write(block: suspend () -> T): T {
        val r = db.withTransaction { block() }
        committed()
        return r
    }

    /** بعد الالتزام: إشعار الواجهة والنسخ. فشل الأثر الجانبي لا يجعل العملية الملتزمة تبدو فاشلة. */
    private fun committed() {
        _version.update { it + 1 }
        runCatching { onWrite() }
    }

    private val reqDao = db.requests()

    private fun sha256(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    class RequestConflictException(message: String) : Exception(message)

    /**
     * عقد منع التكرار الموحّد: [requestId] ثابت لكل نية حفظ.
     * - الطلب يُفحص داخل المعاملة قبل أي أثر، ويُسجَّل مع نوع العملية وبصمة مدخلاتها ونتيجتها في المعاملة نفسها.
     * - إعادة الطلب نفسه (ولو بعد إعادة التشغيل أو بالتزامن) تعيد النتيجة السابقة دون أي كتابة.
     * - المعرّف نفسه بعملية أو مدخلات مختلفة يُرفض صراحةً ولا يُعاد نجاح عملية أخرى.
     * - العملية التي لا تُحدث أثراً (مثل عدّ يساوي الرصيد) تُسجَّل نتيجتها null، فلا يُعاد تفسير الطلب لاحقاً.
     */
    private suspend fun once(requestId: String, op: String, payload: String, block: suspend () -> String?): String? {
        if (requestId.isBlank()) throw LedgerException("رقم الطلب مفقود")
        val hash = sha256("$op\n$payload")
        fun replay(prev: RequestEntity): String? {
            if (prev.op != op || prev.payloadHash != hash) throw RequestConflictException("هذا الطلب استُخدم لعملية مختلفة؛ أعد فتح الشاشة وأعد المحاولة")
            return prev.resultId
        }
        reqDao.get(requestId)?.let { return replay(it) }
        val (fresh, result) = try {
            db.withTransaction {
                val prev = reqDao.get(requestId)
                if (prev != null) false to replay(prev)
                else { val r = block(); reqDao.insert(RequestEntity(requestId, op, hash, r, clock())); true to r }
            }
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // طلب متزامن بالمعرّف نفسه التزم قبلنا: نعيد نتيجته
            val prev = reqDao.get(requestId) ?: throw e
            false to replay(prev)
        }
        if (fresh) committed()
        return result
    }

    private fun p(vararg parts: Any?) = parts.joinToString("\u001f") { it?.toString() ?: "\u0000" }

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
            dao.members(), dao.budgets(), dao.recurring(),
            dao.parties(),
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

    suspend fun createAccount(name: String, type: AccountType, currency: CurrencyDef, openingMinor: Long, openingDate: LocalDate, allowNegative: Boolean = false, requestId: String = uuid()): String = once(requestId, "createAccount", p(name.trim(), type, currency.code, openingMinor, openingDate, allowNegative)) {
        if (name.isBlank()) throw LedgerException("اسم الحساب مطلوب")
        dao.txnByRequest(requestId)?.let { t -> return@once dao.entriesOf(t.id).firstOrNull()?.accountId ?: throw RequestConflictException("هذا الطلب استُخدم لعملية مختلفة") }
        if (openingMinor < 0 && !allowNegative) throw LedgerException("الرصيد الافتتاحي سالب؛ فعّل «السماح بالسالب» لهذا الحساب أولاً")
        if (kotlin.math.abs(openingMinor) > MoneyPolicy.MAX_MINOR) throw LedgerException("الرصيد الافتتاحي أكبر من الحد المسموح")
        val id = uuid()
        dao.insertAccount(AccountEntity(id, name.trim(), type.name, currency.code, allowNegative, false, dao.accounts().size + 1, clock()))
        if (openingMinor != 0L) {
            val t = uuid()
            dao.insertTxn(TxnEntity(t, TxnType.OPENING.name, openingDate.toString(), "رصيد افتتاحي", requestId = requestId, createdAtUtc = clock(), updatedAtUtc = clock()))
            dao.insertEntries(listOf(EntryEntity(uuid(), t, id, EntryKind.OPENING.name, openingMinor, currency.code, openingDate.toString())))
        }
        audit("account", id, "create", name)
        id
    }!!

    suspend fun updateAccount(a: AccountEntity) = write { dao.updateAccount(a); audit("account", a.id, "update", a.name) }

    suspend fun addCategory(name: String, kind: String): String = write {
        val id = uuid(); dao.insertCategory(CategoryEntity(id, name.trim(), kind, sort = 100)); id
    }
    suspend fun archiveCategory(c: CategoryEntity, archived: Boolean) = write { dao.updateCategory(c.copy(archived = archived)) }

    // ---------------- الحركات ----------------
    private suspend fun existing(requestId: String): String? = dao.txnByRequest(requestId)?.id

    private suspend fun account(id: String): Account = dao.accounts().firstOrNull { it.id == id }?.toDomain() ?: throw LedgerException("الحساب غير موجود")

    /** مبلغ إدخال: موجب وضمن حد [MoneyPolicy.MAX_MINOR]. */
    private fun checkAmount(minor: Long, what: String = "المبلغ") {
        if (minor <= 0) throw LedgerException("$what يجب أن يكون أكبر من صفر")
        if (minor > MoneyPolicy.MAX_MINOR) throw LedgerException("$what أكبر من الحد المسموح")
    }

    private fun <T : Comparable<T>> cumulative(moves: List<Pair<LocalDate, T>>, dates: Collection<LocalDate>, zero: T, plus: (T, T) -> T): Map<LocalDate, T> {
        val sorted = moves.sortedBy { it.first }
        var i = 0; var run = zero
        return dates.sorted().associateWith { d -> while (i < sorted.size && !sorted[i].first.isAfter(d)) { run = plus(run, sorted[i].second); i++ }; run }
    }

    /**
     * يفحص مسار رصيد الحساب بعد تغيير افتراضي (حذف قيود [removeEntryIds] وإضافة [add])، في نهاية كل يوم
     * من أقدم تاريخ متأثر حتى آخر قيد. السياسة الموثّقة: الترتيب داخل اليوم لا يُعتبر — الرصيد يُفحص في نهاية اليوم.
     * يرفض: تجاوز حد المبالغ، أو (لحساب لا يسمح بالسالب) رصيداً سالباً أسوأ مما كان في أي يوم لاحق.
     */
    private suspend fun guardPath(acc: Account, removeEntryIds: Set<String>, add: List<Pair<LocalDate, Long>>) {
        val all = dao.entries().filter { it.accountId == acc.id }.map { it.id to (LocalDate.parse(it.date) to java.math.BigInteger.valueOf(it.amountMinor)) }
        val removed = all.filter { it.first in removeEntryIds }.map { it.second }
        val addB = add.map { it.first to java.math.BigInteger.valueOf(it.second) }
        val from = (removed.map { it.first } + add.map { it.first }).minOrNull() ?: return
        val before = all.map { it.second }
        val after = all.filter { it.first !in removeEntryIds }.map { it.second } + addB
        val dates = ((before + after).map { it.first }.filter { !it.isBefore(from) } + from).toSet()
        val b = cumulative(before, dates, java.math.BigInteger.ZERO, java.math.BigInteger::add)
        val a = cumulative(after, dates, java.math.BigInteger.ZERO, java.math.BigInteger::add)
        val max = java.math.BigInteger.valueOf(MoneyPolicy.MAX_MINOR)
        for (d in dates.sorted()) {
            val v = a.getValue(d)
            if (v.abs() > max) throw LedgerException("الرصيد في «${acc.name}» سيتجاوز الحد المسموح")
            if (!acc.allowNegative && v.signum() < 0 && v < b.getValue(d))
                throw LedgerException("الرصيد في «${acc.name}» لا يكفي بتاريخ \u2066$d\u2069. فعّل «السماح بالسالب» للحساب إن كان يدعم السحب")
        }
    }

    private suspend fun guardNegative(acc: Account, delta: Long, date: LocalDate) = guardPath(acc, emptySet(), listOf(date to delta))

    /** مثل [guardPath] لوزن قطعة ذهب: لا يصبح الوزن سالباً في أي يوم لاحق. */
    private suspend fun guardGold(holdingId: String, removeMoveIds: Set<String>, add: List<Pair<LocalDate, BigDecimal>>) {
        val all = dao.goldMoves().filter { it.holdingId == holdingId }.map { it.id to (LocalDate.parse(it.date) to BigDecimal(it.grams)) }
        val from = (all.filter { it.first in removeMoveIds }.map { it.second.first } + add.map { it.first }).minOrNull() ?: return
        val before = all.map { it.second }
        val after = all.filter { it.first !in removeMoveIds }.map { it.second } + add
        val dates = ((before + after).map { it.first }.filter { !it.isBefore(from) } + from).toSet()
        val b = cumulative(before, dates, BigDecimal.ZERO, BigDecimal::add)
        val a = cumulative(after, dates, BigDecimal.ZERO, BigDecimal::add)
        for (d in dates.sorted()) {
            val v = a.getValue(d)
            if (v.signum() < 0 && v < b.getValue(d)) throw LedgerException("وزن الذهب سيصبح سالباً بتاريخ \u2066$d\u2069 (بيع لاحق يعتمد على هذه الكمية)")
        }
    }

    private suspend fun cancelledDebts() = app.rasidi.core.data.cancelledDebtIds(dao.txns())

    /** دخل أو مصروف بسيط. يعيد معرّف الحركة. */
    suspend fun addIncomeExpense(income: Boolean, accountId: String, amountMinor: Long, categoryId: String?, date: LocalDate, note: String, party: String, requestId: String, memberId: String? = null): String {
        checkAmount(amountMinor)
        return once(requestId, "incomeExpense", p(income, accountId, amountMinor, categoryId, date, note.trim(), party.trim(), memberId)) {
            existing(requestId)?.let { return@once it }
            val acc = account(accountId)
            val signed = if (income) amountMinor else -amountMinor
            guardNegative(acc, signed, date)
            val t = uuid()
            dao.insertTxn(TxnEntity(t, (if (income) TxnType.INCOME else TxnType.EXPENSE).name, date.toString(), note.trim(), party.trim(), requestId, createdAtUtc = clock(), updatedAtUtc = clock(), memberId = memberId))
            dao.insertEntries(listOf(EntryEntity(uuid(), t, acc.id, (if (income) EntryKind.INCOME else EntryKind.EXPENSE).name, signed, acc.currency.code, date.toString(), categoryId)))
            t
        }!!
    }

    /**
     * تحويل برجلين: المبلغان الفعليان بعملتيهما، والسعر الناتج يُحفظ. الرسوم قيد مصروف مرتبط بحسابه وعملته.
     * التحويل الداخلي لا يدخل في الدخل والمصروف.
     */
    suspend fun transfer(fromId: String, toId: String, fromMinor: Long, toMinor: Long, feeMinor: Long, feeAccountId: String?, date: LocalDate, note: String, requestId: String): String {
        if (fromId == toId) throw LedgerException("اختر حسابين مختلفين")
        if (fromMinor <= 0 || toMinor <= 0) throw LedgerException("أدخل المبلغين")
        checkAmount(fromMinor); checkAmount(toMinor)
        if (feeMinor < 0) throw LedgerException("الرسوم لا تكون سالبة")
        if (feeMinor > 0) checkAmount(feeMinor, "الرسوم")
        return once(requestId, "transfer", p(fromId, toId, fromMinor, toMinor, feeMinor, feeAccountId, date, note.trim())) {
            existing(requestId)?.let { return@once it }
            val from = account(fromId); val to = account(toId)
            if (from.currency.code == to.currency.code && fromMinor != toMinor) throw LedgerException("نفس العملة: المبلغان يجب أن يتساويا")
            val feeAcc = feeAccountId?.let { account(it) } ?: from
            guardNegative(from, -fromMinor - (if (feeAcc.id == from.id) feeMinor else 0), date)
            if (feeMinor > 0 && feeAcc.id != from.id) guardNegative(feeAcc, -feeMinor, date)
            guardNegative(to, toMinor, date)
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
        }!!
    }

    /** تسوية بعد العدّ اليدوي: قيد مستقل بالفرق عند تأكيد المستخدم. */
    suspend fun adjustToCount(accountId: String, countedMinor: Long, date: LocalDate, requestId: String): String? {
        return once(requestId, "adjustToCount", p(accountId, countedMinor, date)) {
            val acc = account(accountId)
            if (countedMinor < 0 && !acc.allowNegative) throw LedgerException("العدّ لا يكون سالباً لحساب لا يسمح بالسالب")
            if (kotlin.math.abs(countedMinor) > MoneyPolicy.MAX_MINOR) throw LedgerException("المبلغ أكبر من الحد المسموح")
            existing(requestId)?.let { return@once it }
            val cur = Balances.balance(acc, dao.entries().map { it.toDomain() }, date)
            val diff = Math.subtractExact(countedMinor, cur.minor)
            if (diff == 0L) return@once null
            guardNegative(acc, diff, date)
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
    suspend fun addDebt(party: String, direction: DebtDirection, principalMinor: Long, currency: CurrencyDef, date: LocalDate, due: LocalDate?, installments: List<Pair<LocalDate, Long>>, note: String, cashAccountId: String?, cashMinor: Long?, requestId: String): String =
        once(requestId, "addDebt", p(app.rasidi.core.backup.PartyIds.normalize(party), direction, principalMinor, currency.code, date, due, installments.joinToString(";") { "${it.first}=${it.second}" }, note.trim(), cashAccountId, cashMinor)) {
            dao.txnByRequest(requestId)?.let { t -> return@once t.debtId ?: t.id }
            addDebtTx(party, direction, principalMinor, currency, date, due, installments, note, cashAccountId, cashMinor, requestId)
        }!!

    private fun validateDebt(party: String, principalMinor: Long, cashMinor: Long?, date: LocalDate, due: LocalDate?, installments: List<Pair<LocalDate, Long>>) {
        if (party.isBlank()) throw LedgerException("اسم الطرف مطلوب")
        checkAmount(principalMinor, "أصل الدين")
        if (cashMinor != null) checkAmount(cashMinor, "مبلغ الحساب")
        if (due != null && due.isBefore(date)) throw LedgerException("تاريخ الاستحقاق قبل تاريخ الدين")
        if (installments.isNotEmpty()) {
            installments.forEach { (d, m) -> checkAmount(m, "القسط"); if (d.isBefore(date)) throw LedgerException("قسط بتاريخ قبل تاريخ الدين") }
            val sum = installments.fold(java.math.BigInteger.ZERO) { a, i -> a + java.math.BigInteger.valueOf(i.second) }
            if (sum != java.math.BigInteger.valueOf(principalMinor)) throw LedgerException("مجموع الأقساط يجب أن يساوي أصل الدين")
        }
    }

    /** جسم إنشاء الدين — يُستدعى داخل معاملة قائمة فقط. */
    private suspend fun addDebtTx(party: String, direction: DebtDirection, principalMinor: Long, currency: CurrencyDef, date: LocalDate, due: LocalDate?, installments: List<Pair<LocalDate, Long>>, note: String, cashAccountId: String?, cashMinor: Long?, requestId: String): String {
        validateDebt(party, principalMinor, cashMinor, date, due, installments)
        run {
            ensureParty(party)
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
            audit("debt", id, "create", "${currency.code}")
            return id
        }
    }

    /**
     * سداد أو تحصيل: [debtMinor] بعملة الدين، و[walletMinor] بعملة الحساب إن اختلفت. يرفض تجاوز المتبقي.
     * يحدّث الدين والحساب معاً في معاملة واحدة.
     */
    suspend fun addDebtPayment(debtId: String, debtMinor: Long, accountId: String?, walletMinor: Long?, date: LocalDate, note: String, requestId: String): String {
        return once(requestId, "debtPayment", p(debtId, debtMinor, accountId, walletMinor, date, note.trim())) {
            existing(requestId)?.let { return@once it }
            val de = dao.debts().firstOrNull { it.id == debtId } ?: throw LedgerException("الدين غير موجود")
            if (walletMinor != null) checkAmount(walletMinor, "مبلغ الحساب")
            val debt = de.toDomain(dao.installments(), cancelledDebts())
            if (date.isBefore(debt.date)) throw LedgerException("تاريخ الدفعة قبل تاريخ الدين (${debt.date})")
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
        }!!
    }

    suspend fun archiveDebt(d: DebtEntity, archived: Boolean) = write { dao.updateDebt(d.copy(archived = archived)); audit("debt", d.id, if (archived) "archive" else "unarchive") }

    // ---------------- الذهب ----------------
    suspend fun addGoldHolding(name: String, karat: Int, kind: GoldKind, openingGrams: BigDecimal, date: LocalDate, note: String = ""): String = write {
        if (name.isBlank()) throw LedgerException("اسم القطعة مطلوب")
        if (karat !in 1..24) throw LedgerException("العيار بين 1 و24")
        checkGrams(openingGrams, allowZero = true)
        val id = uuid()
        dao.insertGoldHolding(GoldHoldingEntity(id, name.trim(), karat, kind.name, note))
        if (openingGrams.signum() != 0) dao.insertGoldMove(GoldMoveEntity(uuid(), id, GoldMoveType.OPENING.name, openingGrams.toPlainString(), date.toString()))
        audit("gold", id, "create", "$name $karat")
        id
    }

    /**
     * شراء أو بيع ذهب: النقد يتغير مرة واحدة (قيد واحد) والوزن يتغير بالكمية الفعلية (حركة وزن واحدة)، في معاملة واحدة.
     */
    /**
     * [accountId] = null: «بلا حركة نقدية» — وزن فقط ويُذكر ذلك في البيان. وإلا يلزم [cashMinor] موجب؛ لا يُتجاهل مبلغ فارغ أو صفر بصمت.
     */
    suspend fun goldTrade(holdingId: String, buy: Boolean, grams: BigDecimal, accountId: String?, cashMinor: Long?, date: LocalDate, note: String, requestId: String): String {
        checkGrams(grams)
        if (accountId != null) { if (cashMinor == null) throw LedgerException("أدخل المبلغ المدفوع أو المقبوض، أو اختر «بلا حركة نقدية»"); checkAmount(cashMinor) }
        else if (cashMinor != null) throw LedgerException("اختر الصندوق الذي دُفع منه المبلغ أو قُبض فيه")
        return once(requestId, "goldTrade", p(holdingId, buy, grams.stripTrailingZeros().toPlainString(), accountId, cashMinor, date, note.trim())) {
            existing(requestId)?.let { return@once it }
            val h = dao.goldHoldings().firstOrNull { it.id == holdingId } ?: throw LedgerException("القطعة غير موجودة")
            if (!buy) {
                val have = GoldCalc.grams(h.toDomain(), dao.goldMoves().map { it.toDomain() }, date)
                if (grams > have) throw LedgerException("الوزن المباع أكبر من الرصيد (${Fmt.plain(have)}غ)")
                guardGold(h.id, emptySet(), listOf(date to grams.negate()))
            }
            val t = uuid()
            dao.insertTxn(TxnEntity(t, (if (buy) TxnType.GOLD_BUY else TxnType.GOLD_SELL).name, date.toString(), note.ifBlank { "${if (buy) "شراء" else "بيع"} ${Fmt.plain(grams)}غ ${h.name}" + if (accountId == null) " (بلا حركة نقدية)" else "" }, "", requestId, createdAtUtc = clock(), updatedAtUtc = clock()))
            if (accountId != null && cashMinor != null) {
                val acc = account(accountId)
                val signed = if (buy) -cashMinor else cashMinor
                guardNegative(acc, signed, date)
                dao.insertEntries(listOf(EntryEntity(uuid(), t, acc.id, EntryKind.GOLD_TRADE.name, signed, acc.currency.code, date.toString())))
            }
            dao.insertGoldMove(GoldMoveEntity(uuid(), holdingId, (if (buy) GoldMoveType.BUY else GoldMoveType.SELL).name, (if (buy) grams else grams.negate()).toPlainString(), date.toString(), t))
            t
        }!!
    }

    /** سياسة الوزن المعلنة: موجب، حتى 3 منازل عشرية (ملّيغرام)، وحتى 1,000,000 غ للحركة الواحدة. */
    private fun checkGrams(g: BigDecimal, allowZero: Boolean = false) {
        if (g.signum() < 0 || (!allowZero && g.signum() == 0)) throw LedgerException("الوزن يجب أن يكون أكبر من صفر")
        if (g.stripTrailingZeros().scale() > 3) throw LedgerException("الوزن بثلاث منازل عشرية كحد أقصى")
        if (g > MAX_GRAMS) throw LedgerException("الوزن أكبر من الحد المسموح")
    }

    // ---------------- التصحيح ----------------
    /**
     * عكس حركة: حركة جديدة بقيود معاكسة، ومعها عكس السجل المرتبط (دفعة الدين، وزن الذهب، دفعة الزكاة) في معاملة واحدة.
     */
    suspend fun reverse(txnId: String, reason: String, requestId: String): String {
        return once(requestId, "reverse", p(txnId, reason.trim())) {
            existing(requestId)?.let { return@once it }
            val t = dao.txn(txnId) ?: throw LedgerException("الحركة غير موجودة")
            if (t.reversedById != null) throw LedgerException("الحركة معكوسة مسبقاً")
            if (t.reversesId != null) throw LedgerException("لا يمكن عكس حركة عكس")
            if (t.type == TxnType.DEBT_NEW.name && t.debtId != null) {
                // إلغاء الدين: لا يُقبل وعليه دفعات حيّة (صافيها غير صفر) — تُعكس الدفعات أولاً
                val net = dao.debtPayments().filter { it.debtId == t.debtId }.sumOf { it.debtAmountMinor }
                if (net != 0L) throw LedgerException("على هذا الدين دفعات مسجّلة؛ اعكس الدفعات أولاً ثم ألغِ الدين")
            }
            val es0 = dao.entriesOf(t.id)
            es0.groupBy { it.accountId }.forEach { (accId, l) -> guardPath(account(accId), emptySet(), l.map { LocalDate.parse(it.date) to -it.amountMinor }) }
            dao.goldMovesOfTxn(t.id).groupBy { it.holdingId }.forEach { (h, l) -> guardGold(h, emptySet(), l.map { LocalDate.parse(it.date) to BigDecimal(it.grams).negate() }) }
            val r = uuid()
            dao.insertTxn(TxnEntity(r, TxnType.REVERSAL.name, t.date, // بتاريخ الأصل حتى تتعادل تقارير الفترة
                "عكس: ${t.note}".take(200) + if (reason.isNotBlank()) " — $reason" else "", t.party, requestId, debtId = t.debtId, reversesId = t.id, createdAtUtc = clock(), updatedAtUtc = clock()))
            dao.insertEntries(es0.map { it.copy(id = uuid(), txnId = r, amountMinor = -it.amountMinor) })
            dao.paymentsOfTxn(t.id).forEach { p -> dao.insertDebtPayment(p.copy(id = uuid(), txnId = r, debtAmountMinor = -p.debtAmountMinor, walletAmountMinor = p.walletAmountMinor?.let { -it })) }
            dao.goldMovesOfTxn(t.id).forEach { m -> dao.insertGoldMove(m.copy(id = uuid(), txnId = r, type = GoldMoveType.ADJUST.name, grams = BigDecimal(m.grams).negate().toPlainString())) }
            dao.zakatPaymentsOfTxn(t.id).forEach { z -> dao.insertZakatPayment(z.copy(id = uuid(), txnId = r, amountMinor = -z.amountMinor, usd = BigDecimal(z.usd).negate().toPlainString())) }
            dao.updateTxn(t.copy(reversedById = r, updatedAtUtc = clock()))
            audit("txn", t.id, "reverse", "by=$r ${if (t.debtId != null) "debt=${t.debtId}" else ""} $reason")
            r
        }!!
    }

    /**
     * تراجع سريع عن حركة جديدة: حذف كامل مع تسجيله، فقط إن:
     * لم تمضِ 10 دقائق (ووقت إنشائها ليس في المستقبل — تغيّر الساعة يمنع التراجع)، وليست حركة عكس ولا معكوسة،
     * ولا دفعة زكاة، ولا يغطيها جرد معتمد أُنشئ بعدها، ولا يعتمد عليها رصيد أو وزن لاحق، ولا دين عليه دفعات.
     * غير ذلك يُصحَّح بـ«عكس الحركة».
     */
    suspend fun undoRecent(txnId: String) = write {
        val t = dao.txn(txnId) ?: return@write
        val age = clock() - t.createdAtUtc
        if (age < 0 || age > UNDO_WINDOW_MS) throw LedgerException("انتهت مهلة التراجع السريع؛ استخدم «عكس الحركة»")
        if (t.reversedById != null || t.reversesId != null) throw LedgerException("حركة معكوسة أو حركة عكس لا تُحذف؛ السجل يبقى كما هو")
        if (dao.zakatPaymentsOfTxn(txnId).isNotEmpty()) throw LedgerException("دفعة زكاة: استخدم «عكس الحركة»")
        if (dao.inventories().any { it.status == "FINAL" && !LocalDate.parse(it.date).isBefore(LocalDate.parse(t.date)) && it.createdAtUtc >= t.createdAtUtc })
            throw LedgerException("اعتُمد جرد يشمل هذه الحركة؛ استخدم «عكس الحركة»")
        val debtToDelete = t.debtId?.takeIf { t.type == TxnType.DEBT_NEW.name }
        if (debtToDelete != null && dao.debtPayments().any { it.debtId == debtToDelete }) throw LedgerException("على هذا الدين دفعات؛ استخدم «عكس الحركة» بعد عكس الدفعات")
        val es = dao.entriesOf(txnId)
        es.groupBy { it.accountId }.forEach { (accId, l) -> guardPath(account(accId), l.map { it.id }.toSet(), emptyList()) }
        dao.goldMovesOfTxn(txnId).groupBy { it.holdingId }.forEach { (h, l) -> guardGold(h, l.map { it.id }.toSet(), emptyList()) }
        dao.deleteTxn(txnId) // القيود والدفعات وحركات الوزن تُحذف بالتتابع (CASCADE)
        if (debtToDelete != null) db.openHelper.writableDatabase.execSQL("DELETE FROM debts WHERE id = ?", arrayOf(debtToDelete))
        audit("txn", txnId, "undo", "${t.type} ${t.date}")
    }

    /**
     * تعديل دخل/مصروف بسيط في مكانه مع سجل تعديل يحفظ القيم السابقة.
     * الحركات المرتبطة بدين أو تحويل تُصحَّح بالعكس ثم إعادة الإدخال.
     */
    suspend fun editSimple(txnId: String, amountMinor: Long, categoryId: String?, date: LocalDate, note: String, party: String, memberId: String? = null) = write {
        val t = dao.txn(txnId) ?: throw LedgerException("الحركة غير موجودة")
        if (t.type != TxnType.INCOME.name && t.type != TxnType.EXPENSE.name) throw LedgerException("هذه الحركة تُصحَّح بالعكس")
        if (t.reversedById != null) throw LedgerException("الحركة معكوسة")
        checkAmount(amountMinor)
        val es = dao.entriesOf(txnId)
        val e = es.single()
        val signed = if (t.type == TxnType.INCOME.name) amountMinor else -amountMinor
        val acc = account(e.accountId)
        // المسار كاملاً: بلا القيد القديم في تاريخه ومع الجديد في تاريخه الجديد
        guardPath(acc, setOf(e.id), listOf(date to signed))
        val inInventory = dao.inventories().any { it.status == "FINAL" && !LocalDate.parse(it.date).isBefore(LocalDate.parse(t.date).coerceAtMost(date)) }
        audit("txn", txnId, "edit", "old=${e.amountMinor} ${t.date} cat=${e.categoryId} note=${t.note}; new=$signed $date cat=$categoryId" + if (inInventory) "; يمسّ جرداً معتمداً" else "")
        dao.updateEntries(listOf(e.copy(amountMinor = signed, date = date.toString(), categoryId = categoryId)))
        dao.updateTxn(t.copy(date = date.toString(), note = note.trim(), party = party.trim(), updatedAtUtc = clock(), memberId = memberId))
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
        checkAmount(amountMinor)
        return once(requestId, "zakatPayment", p(estimateId, accountId, amountMinor, rate.unit, rate.entered.stripTrailingZeros().toPlainString(), rate.direction, rate.date, date, beneficiary.trim())) {
            existing(requestId)?.let { return@once it }
            val est = dao.zakatEstimates().firstOrNull { it.id == estimateId } ?: throw LedgerException("تقدير الزكاة غير موجود")
            if (est.status != app.rasidi.core.zakat.ZakatStatus.DUE.name) throw LedgerException("التقدير ليس «تجب الزكاة»؛ لا تُسجَّل عليه دفعات")
            val inv = dao.inventories().firstOrNull { it.id == est.inventoryId }
            if (inv != null && date.isBefore(LocalDate.parse(inv.date))) throw LedgerException("تاريخ الدفعة قبل تاريخ الجرد (${inv.date})")
            val acc = account(accountId)
            // سعر يوم الدفع بعملة الحساب نفسها (الدولار بسعر الهوية)
            if (rate.unit != acc.currency.code) throw LedgerException("السعر لعملة ${rate.unit} والحساب بعملة ${acc.currency.code}")
            if (rate.date != date) throw LedgerException("السعر بتاريخ ${rate.date} والدفعة بتاريخ $date؛ أدخل سعر يوم الدفع")
            if (acc.currency.code == Currencies.USD.code && rate.usdPerUnit.compareTo(BigDecimal.ONE) != 0) throw LedgerException("الدولار بسعر 1 دائماً")
            guardNegative(acc, -amountMinor, date)
            val usd = rate.toUsd(Money(amountMinor, acc.currency).amount)
            val t = uuid()
            dao.insertTxn(TxnEntity(t, TxnType.ZAKAT.name, date.toString(), "دفعة زكاة", beneficiary.trim(), requestId, createdAtUtc = clock(), updatedAtUtc = clock()))
            dao.insertEntries(listOf(EntryEntity(uuid(), t, acc.id, EntryKind.ZAKAT.name, -amountMinor, acc.currency.code, date.toString(), "cat-zakat".takeIf { dao.categories().any { c -> c.id == it } })))
            dao.insertZakatPayment(ZakatPaymentEntity(uuid(), estimateId, t, date.toString(), amountMinor, acc.currency.code, rate.entered.toPlainString(), rate.direction.name, rate.date.toString(), usd.toPlainString(), acc.id, beneficiary.trim()))
            t
        }!!
    }

    // ---------------- الحسابات (الأشخاص) ----------------
    private suspend fun ensureParty(name: String) {
        val n = name.trim()
        if (dao.parties().none { it.name == n }) dao.upsertParty(PartyEntity(app.rasidi.core.backup.PartyIds.forName(n), n, createdAtUtc = clock()))
    }

    /**
     * حساب جديد (مثل «إضافة حساب جديد»): الاسم والهاتف والملاحظة، ورصيد افتتاحي اختياري
     * يُسجَّل ديناً سابقاً دون حركة نقدية: [owedToMe] = «لي معه» (مدين) وإلا «له معي» (دائن).
     */
    suspend fun addParty(name: String, phone: String, note: String, openingMinor: Long, currency: CurrencyDef, owedToMe: Boolean, date: LocalDate, requestId: String): String {
        val n = app.rasidi.core.backup.PartyIds.normalize(name)
        if (n.isBlank()) throw LedgerException("اسم الحساب مطلوب")
        if (openingMinor < 0) throw LedgerException("الرصيد السابق لا يكون سالباً؛ اختر «لي معه» أو «له معي»")
        if (openingMinor > 0) checkAmount(openingMinor, "الرصيد السابق")
        // الشخص ورصيده السابق وسجل الطلب والتدقيق في معاملة واحدة
        return once(requestId, "addParty", p(n, phone.trim(), note.trim(), openingMinor, currency.code, owedToMe, date)) {
            if (dao.parties().any { it.name == n }) throw LedgerException("يوجد حساب بالاسم «$n»")
            val id = app.rasidi.core.backup.PartyIds.forName(n)
            dao.upsertParty(PartyEntity(id, n, phone.trim(), note.trim(), false, clock()))
            partyHook?.invoke("afterParty")
            if (openingMinor > 0) addDebtTx(n, if (owedToMe) DebtDirection.OWED_TO_ME else DebtDirection.I_OWE, openingMinor, currency, date, null, emptyList(), "رصيد سابق", null, null, requestId)
            audit("party", id, "create")
            partyHook?.invoke("afterDebt")
            n
        }!!
    }

    /** للاختبارات فقط: يُستدعى داخل معاملة إنشاء الشخص («afterParty» ثم «afterDebt») لمحاكاة فشل في منتصفها. */
    internal var partyHook: (suspend (String) -> Unit)? = null

    /** تعديل الحساب؛ تغيير الاسم ينتقل إلى ديونه وحركاته في معاملة واحدة. */
    suspend fun updateParty(old: PartyEntity, name: String, phone: String, note: String, archived: Boolean = old.archived) = write {
        val n = name.trim()
        if (n.isBlank()) throw LedgerException("اسم الحساب مطلوب")
        if (n != old.name && dao.parties().any { it.name == n }) throw LedgerException("يوجد حساب بالاسم «$n»")
        dao.upsertParty(old.copy(name = n, phone = phone.trim(), note = note.trim(), archived = archived))
        if (n != old.name) { dao.renameDebtParty(old.name, n); dao.renameTxnParty(old.name, n) }
        audit("party", old.id, "update", if (n != old.name) "rename ${old.name} → $n" else "edit")
    }

    // ---------------- البيت: الأفراد والميزانيات والمتكررات ----------------
    suspend fun saveMember(m: MemberEntity) = write { require(m.name.isNotBlank()) { "الاسم مطلوب" }; dao.upsertMember(m.copy(name = m.name.trim())) }
    suspend fun saveBudget(categoryId: String, currency: CurrencyDef, limitMinor: Long) = write {
        if (limitMinor <= 0) throw LedgerException("حد الميزانية يجب أن يكون أكبر من صفر")
        val existing = dao.budgets().firstOrNull { it.categoryId == categoryId && it.currency == currency.code }
        dao.upsertBudget(BudgetEntity(existing?.id ?: uuid(), categoryId, currency.code, limitMinor))
    }
    suspend fun deleteBudget(b: BudgetEntity) = write { dao.deleteBudget(b) }
    suspend fun saveRecurring(r: RecurringEntity) = write {
        if (r.name.isBlank()) throw LedgerException("الاسم مطلوب")
        if (r.amountMinor <= 0) throw LedgerException("المبلغ يجب أن يكون أكبر من صفر")
        if (r.dayOfMonth !in 1..31) throw LedgerException("اليوم بين 1 و31")
        dao.upsertRecurring(r)
    }
    suspend fun deleteRecurring(r: RecurringEntity) = write { dao.deleteRecurring(r) }

    /** يسجّل متكرراً مستحقاً لشهر محدد بلمسة تأكيد. معرّف الطلب «rec:id:yyyy-MM» يمنع تسجيله مرتين. */
    suspend fun recordRecurring(r: RecurringEntity, month: java.time.YearMonth, amountMinor: Long = r.amountMinor, date: LocalDate? = null): String =
        addIncomeExpense(r.kind == "INCOME", r.accountId, amountMinor, r.categoryId, date ?: month.atDay(minOf(r.dayOfMonth, month.lengthOfMonth())), r.name, "",
            app.rasidi.core.household.Household.requestId(r, month), r.memberId)

    // ---------------- استيراد CSV ----------------
    data class ImportResult(val imported: Int, val alreadyImported: Int, val failed: List<String>, val possibleDuplicates: List<String>)

    /** يعرض الحركات المتشابهة (التاريخ والمبلغ والحساب) الموجودة مسبقاً لمراجعتها قبل الاستيراد. */
    suspend fun importDuplicatesPreview(p: app.rasidi.core.importer.CsvImport.Preview): List<String> {
        val v = view()
        val existingReq = v.s.txns.map { it.requestId }.toSet()
        return p.rows.filter { it.requestId !in existingReq }.mapNotNull { r ->
            val acc = v.accounts.firstOrNull { it.name == r.account && it.currency.code == r.amount.currency.code } ?: return@mapNotNull null
            val signed = if (r.income) r.amount.minor else -r.amount.minor
            if (v.entries.any { it.accountId == acc.id && it.date == r.date && it.amount.minor == signed }) "سطر ${r.line}: ${r.date} ${Fmt.money(r.amount, false)} في ${r.account}" else null
        }
    }

    /**
     * يستورد صفوف المعاينة: كل صف بمعرّف طلب ثابت، فإعادة الملف نفسه أو الصف نفسه لا تكرّر شيئاً.
     * الحساب يُطابق بالاسم والعملة، وينشأ عند الحاجة إن سمح [createAccounts]. الفئة تنشأ إن لم توجد.
     */
    suspend fun importCsv(p: app.rasidi.core.importer.CsvImport.Preview, createAccounts: Boolean): ImportResult {
        var imported = 0; var already = 0; val failed = mutableListOf<String>()
        val dups = importDuplicatesPreview(p)
        for (r in p.rows) {
            if (dao.txnByRequest(r.requestId) != null) { already++; continue }
            try {
                var acc = dao.accounts().firstOrNull { it.name == r.account && it.currency == r.amount.currency.code }
                if (acc == null) {
                    if (!createAccounts) { failed += "سطر ${r.line}: لا يوجد حساب «${r.account}» بعملة ${r.amount.currency.code}"; continue }
                    createAccount(r.account, AccountType.CASH, r.amount.currency, 0, r.date, allowNegative = true)
                    acc = dao.accounts().first { it.name == r.account && it.currency == r.amount.currency.code }
                }
                val kind = if (r.income) "INCOME" else "EXPENSE"
                val catId = if (r.category.isBlank()) dao.categories().first { it.id == if (r.income) "cat-other-in" else "cat-other-out" }.id
                    else dao.categories().firstOrNull { it.name == r.category && it.kind == kind }?.id ?: addCategory(r.category, kind)
                val t = addIncomeExpense(r.income, acc.id, r.amount.minor, catId, r.date, r.note, r.party, r.requestId)
                imported++
                if (imported == 1) audit("import", p.batchHash, "batch", "rows=${p.rows.size}")
                t
            } catch (e: LedgerException) { failed += "سطر ${r.line}: ${e.message}" }
        }
        return ImportResult(imported, already, failed, dups)
    }

    // ---------------- الاستعادة ----------------
    /** يستبدل الدفتر كاملاً بلقطة في معاملة واحدة: إما السجل السابق كاملاً أو الجديد كاملاً. */
    /** استبدال كامل (للعرض والاختبارات). الاستعادة تستخدم [replaceIfUnchanged]. */
    suspend fun replaceAll(s: Snapshot) = write { clearAndInsert(s) }

    private suspend fun clearAndInsert(s: Snapshot) {
        dao.clearRecurring(); dao.clearBudgets()
        dao.clearZakatPayments(); dao.clearZakatEstimates(); dao.clearInventoryLines(); dao.clearInventories()
        dao.clearDebtPayments(); dao.clearInstallments(); dao.clearGoldMoves(); dao.clearEntries(); dao.clearTxns()
        dao.clearDebts(); dao.clearGoldHoldings(); dao.clearRates(); dao.clearCategories(); dao.clearAccounts(); dao.clearSettings(); dao.clearAudit(); dao.clearMembers(); dao.clearParties(); reqDao.clear()
        dao.insertMembers(s.members); dao.insertParties(s.parties)
        dao.insertAccounts(s.accounts); dao.insertCategories(s.categories); dao.insertTxns(s.txns); dao.insertEntries(s.entries)
        dao.insertDebts(s.debts); dao.insertInstallments(s.installments); dao.insertDebtPayments(s.debtPayments)
        dao.insertGoldHoldings(s.goldHoldings); dao.insertGoldMoves(s.goldMoves); dao.insertRates(s.rates)
        dao.insertInventories(s.inventories); dao.insertInventoryLines(s.inventoryLines); dao.insertZakatEstimates(s.zakatEstimates); dao.insertZakatPayments(s.zakatPayments)
        dao.insertSettings(s.settings); dao.insertAudits(s.audit)
        dao.insertBudgets(s.budgets); dao.insertRecurring(s.recurring)
        db.jobs().clear()
    }

    sealed interface ReplaceOutcome {
        data class Replaced(val fingerprint: String, val previous: Snapshot) : ReplaceOutcome
        data object Stale : ReplaceOutcome
    }

    /** بصمة الاستبدال لا تطابق؛ تُرمى داخل المعاملة فيُلغى كل شيء. */
    class ReplaceVerificationException(message: String) : Exception(message)

    /**
     * بروتوكول الاستعادة في معاملة واحدة (لا كتابة أخرى تتداخل معه):
     * 1) قراءة السجل الحالي ومقارنة بصمته بـ[expectedCurrentFp] — إن تغيّر يُعاد [ReplaceOutcome.Stale] بلا أي كتابة؛
     * 2) [beforeReplace] على اللقطة نفسها (نسخة الأمان) — أي خطأ فيه يلغي العملية؛
     * 3) المسح والإدراج؛ 4) إعادة القراءة ومطابقة [expectedNewFp] — عدم التطابق يُرجع كل شيء.
     */
    suspend fun replaceIfUnchanged(expectedCurrentFp: String, newSnap: Snapshot, expectedNewFp: String, beforeReplace: suspend (Snapshot) -> Unit): ReplaceOutcome {
        val r = db.withTransaction {
            val cur = snapshot()
            if (cur.fingerprint() != expectedCurrentFp) return@withTransaction ReplaceOutcome.Stale
            beforeReplace(cur)
            clearAndInsert(newSnap)
            val fp = snapshot().fingerprint()
            if (fp != expectedNewFp) throw ReplaceVerificationException("بصمة السجل بعد الاستعادة لا تطابق النسخة؛ أُلغيت الاستعادة ولم يتغيّر شيء")
            ReplaceOutcome.Replaced(fp, cur)
        }
        if (r is ReplaceOutcome.Replaced) { _version.value = _version.value + 1; onWrite() }
        return r
    }
}

