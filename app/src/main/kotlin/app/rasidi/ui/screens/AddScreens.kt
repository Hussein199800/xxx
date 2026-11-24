package app.rasidi.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import java.time.LocalDate

/** آخر اختيار مناسب (حساب/فئة) محفوظ على الجهاز. */
private fun prefs(ctx: Context) = ctx.getSharedPreferences("rasidi.ui", Context.MODE_PRIVATE)

@Composable
fun AddScreen(vm: MainViewModel, nav: NavController, type: String, ref: String) {
    when (type) {
        "expense", "income" -> IncomeExpenseForm(vm, nav, type == "income", ref)
        "transfer" -> TransferForm(vm, nav)
        "debt" -> DebtForm(vm, nav, ref)
        "payment" -> DebtPaymentForm(vm, nav, ref)
        else -> IncomeExpenseForm(vm, nav, false, ref)
    }
}

@Composable
private fun AccountPicker(vm: MainViewModel, selected: String?, label: String = "الحساب", onSelect: (String) -> Unit) {
    val v by vm.view.collectAsStateWithLifecycle()
    val accs = v?.accounts?.filter { !it.archived }.orEmpty()
    val c = LocalR.current
    Text(label, style = MaterialTheme.typography.titleSmall, color = c.mute, modifier = Modifier.padding(top = Sp.sm, bottom = 4.dp))
    if (accs.isEmpty()) Text("أضف حساباً أولاً من «المزيد ← الحسابات»", color = c.warn)
    ChipRow(accs.map { it.id }, selected, { id -> accs.first { it.id == id }.let { "${it.name} · ${it.currency.code}" } }, onSelect)
}

@Composable
fun IncomeExpenseForm(vm: MainViewModel, nav: NavController, income: Boolean, editTxnId: String) {
    val ctx = LocalContext.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    val p = prefs(ctx)
    val editing = editTxnId.isNotBlank()
    val editTxn = if (editing) view.txnById[editTxnId] else null
    val editEntry = if (editing) view.entries.firstOrNull { it.txnId == editTxnId } else null
    var accountId by rememberSaveable { mutableStateOf(editEntry?.accountId ?: p.getString(if (income) "lastIncomeAccount" else "lastExpenseAccount", null)?.takeIf { id -> view.accounts.any { it.id == id } } ?: view.accounts.firstOrNull { !it.archived }?.id) }
    var categoryId by rememberSaveable { mutableStateOf(editEntry?.categoryId ?: p.getString(if (income) "lastIncomeCategory" else "lastExpenseCategory", null)) }
    val amount = remember { AmountState(editEntry?.amount?.abs()?.amount?.toPlainString() ?: "") }
    var date by remember { mutableStateOf(editTxn?.date?.let(LocalDate::parse) ?: vm.today()) }
    var note by rememberSaveable { mutableStateOf(editTxn?.note ?: "") }
    var party by rememberSaveable { mutableStateOf(editTxn?.party ?: "") }
    var member by rememberSaveable { mutableStateOf(editTxn?.memberId ?: p.getString("lastMember", null)?.takeIf { id -> view.s.members.any { it.id == id } }) }
    val requestId = rememberSaveable { vm.newRequestId() }
    var saving by remember { mutableStateOf(false) }
    val acc = view.accounts.firstOrNull { it.id == accountId }
    val cats = view.s.categories.filter { it.kind == (if (income) "INCOME" else "EXPENSE") && !it.archived && it.id != "cat-zakat" && it.id != "cat-fees" }
    ScreenScaffold(if (editing) "تعديل الحركة" else if (income) "دخل" else "مصروف", onBack = { nav.popBackStack() }) {
        AmountField(amount, acc?.currency)
        AccountPicker(vm, accountId) { accountId = it }
        Text("الفئة", style = MaterialTheme.typography.titleSmall, color = LocalR.current.mute, modifier = Modifier.padding(top = Sp.sm, bottom = 4.dp))
        ChipRow(cats.map { it.id }, categoryId, { id -> cats.first { it.id == id }.name }, { categoryId = it })
        Spacer(Modifier.height(Sp.sm))
        if (view.s.members.any { !it.archived }) {
            Text("مَن؟ (اختياري)", style = MaterialTheme.typography.titleSmall, color = LocalR.current.mute, modifier = Modifier.padding(top = Sp.sm, bottom = 4.dp))
            ChipRow(listOf<String?>(null) + view.s.members.filter { !it.archived }.map { it.id }, member, { id -> id?.let { i -> view.s.members.first { it.id == i }.name } ?: "مشترك" }, { member = it })
        }
        DateField(date, { date = it })
        Spacer(Modifier.height(Sp.sm))
        TextIn(note, { note = it }, "ملاحظة (اختياري)")
        Spacer(Modifier.height(Sp.xs))
        TextIn(party, { party = it }, "الطرف (اختياري)")
        Spacer(Modifier.height(Sp.md))
        val m = amount.parse(acc?.currency)
        PrimaryButton(if (editing) "احفظ التعديل" else "احفظ", enabled = !saving && m != null && m.minor > 0 && acc != null && categoryId != null && !amount.needsConfirm, icon = Icons.Filled.Check, modifier = Modifier.fillMaxWidth(), onClick = {
            saving = true
            p.edit().putString("lastMember", member).putString(if (income) "lastIncomeAccount" else "lastExpenseAccount", accountId).putString(if (income) "lastIncomeCategory" else "lastExpenseCategory", categoryId).apply()
            if (editing) vm.launchSafe("تم حفظ التعديل وتسجيله في سجل التعديلات") { vm.repo.editSimple(editTxnId, m!!.minor, categoryId, date, note, party, member); nav.popBackStack() }
            else vm.launchSafe("تم الحفظ", undoable = true) { vm.repo.addIncomeExpense(income, acc!!.id, m!!.minor, categoryId, date, note, party, requestId, member).also { nav.popBackStack() } }
        })
        if (editing && editEntry != null && m != null) Text("الأثر: ${Fmt.money(editEntry.amount)} ← ${Fmt.money(if (income) m else -m)}", color = LocalR.current.mute, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun TransferForm(vm: MainViewModel, nav: NavController) {
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var from by rememberSaveable { mutableStateOf(view.accounts.firstOrNull()?.id) }
    var to by rememberSaveable { mutableStateOf(view.accounts.getOrNull(1)?.id) }
    val a1 = remember { AmountState() }; val a2 = remember { AmountState() }; val fee = remember { AmountState() }
    var date by remember { mutableStateOf(vm.today()) }
    var note by rememberSaveable { mutableStateOf("") }
    val rid = rememberSaveable { vm.newRequestId() }
    var saving by remember { mutableStateOf(false) }
    val fa = view.accountById[from]; val ta = view.accountById[to]
    val same = fa != null && ta != null && fa.currency.code == ta.currency.code
    ScreenScaffold("تحويل", onBack = { nav.popBackStack() }, sub = "حركة واحدة برجلين؛ لا تُحسب دخلاً ولا مصروفاً") {
        AccountPicker(vm, from, "من") { from = it }
        AccountPicker(vm, to, "إلى") { to = it }
        Spacer(Modifier.height(Sp.sm))
        AmountField(a1, fa?.currency, if (same) "المبلغ" else "المبلغ المرسَل")
        if (!same) {
            AmountField(a2, ta?.currency, "المبلغ المستلَم")
            val m1 = a1.parse(fa?.currency); val m2 = a2.parse(ta?.currency)
            if (m1 != null && m2 != null && m1.minor > 0) Text("السعر الناتج: 1 ${fa!!.currency.code} = ${Fmt.plain(m2.amount.divide(m1.amount, 6, java.math.RoundingMode.HALF_EVEN))} ${ta!!.currency.code}", color = LocalR.current.mute)
        }
        AmountField(fee, fa?.currency, "رسوم (اختياري، مصروف من حساب المصدر)")
        DateField(date, { date = it })
        Spacer(Modifier.height(Sp.xs))
        TextIn(note, { note = it }, "ملاحظة (اختياري)")
        Spacer(Modifier.height(Sp.md))
        val m1 = a1.parse(fa?.currency); val m2 = if (same) m1 else a2.parse(ta?.currency); val mf = if (fee.text.isBlank()) Money.zero(fa?.currency ?: Currencies.USD) else fee.parse(fa?.currency)
        PrimaryButton("احفظ التحويل", enabled = !saving && m1 != null && m2 != null && mf != null && from != to, modifier = Modifier.fillMaxWidth(), onClick = {
            saving = true
            vm.launchSafe("تم التحويل", undoable = true) { vm.repo.transfer(from!!, to!!, m1!!.minor, m2!!.minor, mf!!.minor, null, date, note, rid).also { nav.popBackStack() } }.invokeOnCompletion { saving = false }
        })
    }
}

@Composable
fun DebtForm(vm: MainViewModel, nav: NavController, partyRef: String = "") {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var party by rememberSaveable { mutableStateOf(partyRef) }
    var dir by rememberSaveable { mutableStateOf(DebtDirection.I_OWE) }
    var cur by rememberSaveable { mutableStateOf("USD") }
    val principal = remember { AmountState() }
    var date by remember { mutableStateOf(vm.today()) }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var installmentsCount by rememberSaveable { mutableStateOf("") }
    var withCash by rememberSaveable { mutableStateOf(false) }
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    val cash = remember { AmountState() }
    var note by rememberSaveable { mutableStateOf("") }
    val rid = rememberSaveable { vm.newRequestId() }
    val currency = Currencies.of(cur)
    ScreenScaffold("دين جديد", onBack = { nav.popBackStack() }) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(dir == DebtDirection.I_OWE, { dir = DebtDirection.I_OWE }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("عليّ") }
            SegmentedButton(dir == DebtDirection.OWED_TO_ME, { dir = DebtDirection.OWED_TO_ME }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("لي") }
        }
        Spacer(Modifier.height(Sp.sm))
        TextIn(party, { party = it }, "الحساب (الشخص)")
        val names = view.s.parties.filter { !it.archived }.map { it.name }.filter { party.isBlank() || (it.contains(party.trim()) && it != party.trim()) }.take(8)
        if (names.isNotEmpty()) ChipRow(names, null, { it }, { party = it })
        Spacer(Modifier.height(Sp.xs))
        Text("عملة الدين", style = MaterialTheme.typography.titleSmall, color = c.mute)
        ChipRow(listOf("USD", "TRY", "SYP", "EUR", "SYP_OLD") + view.accounts.map { it.currency.code }.distinct().filter { it !in listOf("USD", "TRY", "SYP", "EUR", "SYP_OLD") }, cur, { Currencies.of(it).nameAr }, { cur = it })
        AmountField(principal, currency, "أصل الدين")
        DateField(date, { date = it }, "تاريخ الدين")
        Spacer(Modifier.height(Sp.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateField(due ?: vm.today().plusMonths(1), { due = it }, if (due == null) "استحقاق (اختياري)" else "الاستحقاق")
            if (due != null) TextButton(onClick = { due = null }) { Text("بلا") }
        }
        TextIn(installmentsCount, { installmentsCount = it.filter(Char::isDigit).take(3) }, "عدد الأقساط الشهرية (اختياري، من تاريخ الاستحقاق)", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
        Spacer(Modifier.height(Sp.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(withCash, { withCash = it }); Spacer(Modifier.width(8.dp))
            Text(if (withCash) "قرض نقدي جديد مرتبط بحساب" else "دين قديم دون حركة نقدية", color = c.txt)
        }
        if (withCash) {
            AccountPicker(vm, account) { account = it }
            val acc = view.accountById[account]
            if (acc != null && acc.currency.code != cur) AmountField(cash, acc.currency, "المبلغ بعملة الحساب")
        }
        TextIn(note, { note = it }, "ملاحظة (اختياري)")
        Spacer(Modifier.height(Sp.md))
        val pm = principal.parse(currency)
        PrimaryButton("احفظ الدين", enabled = party.isNotBlank() && pm != null && pm.minor > 0 && (!withCash || account != null), modifier = Modifier.fillMaxWidth(), onClick = {
            val n = installmentsCount.toIntOrNull() ?: 0
            val inst = if (n > 1) {
                val first = due ?: date.plusMonths(1)
                val base = pm!!.minor / n; val rem = pm.minor - base * n
                (0 until n).map { i -> first.plusMonths(i.toLong()) to (base + if (i == n - 1) rem else 0) }
            } else emptyList()
            val acc = view.accountById[account]
            vm.launchSafe("تم حفظ الدين") {
                vm.repo.addDebt(party, dir, pm!!.minor, currency, date, if (n > 1) null else due, inst, note, if (withCash) account else null,
                    if (withCash && acc != null && acc.currency.code != cur) cash.parse(acc.currency)?.minor else null, rid).also { nav.popBackStack() }
            }
        })
    }
}

@Composable
fun DebtPaymentForm(vm: MainViewModel, nav: NavController, debtRef: String) {
    val c = LocalR.current
    val rows by vm.debts.collectAsStateWithLifecycle()
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    val open = rows.filter { it.remaining.minor > 0 && !it.archived }
    var debtId by rememberSaveable { mutableStateOf(debtRef.ifBlank { open.firstOrNull()?.debt?.id }) }
    val row = rows.firstOrNull { it.debt.id == debtId }
    val amount = remember { AmountState() }
    var account by rememberSaveable { mutableStateOf<String?>(view.accounts.firstOrNull { it.currency.code == row?.debt?.currency?.code }?.id) }
    val wallet = remember { AmountState() }
    var date by remember { mutableStateOf(vm.today()) }
    var note by rememberSaveable { mutableStateOf("") }
    val rid = rememberSaveable { vm.newRequestId() }
    var saving by remember { mutableStateOf(false) }
    ScreenScaffold("سجّل دفعة", onBack = { nav.popBackStack() }) {
        if (open.isEmpty()) { EmptyState(Icons.Filled.Check, "لا ديون مفتوحة", "كل الديون مسدّدة"); return@ScreenScaffold }
        Text("الدين", style = MaterialTheme.typography.titleSmall, color = c.mute)
        ChipRow(open.map { it.debt.id }, debtId, { id -> open.first { it.debt.id == id }.let { "${it.debt.party} · ${it.debt.direction.ar}" } }, { debtId = it })
        if (row != null) {
            Text("المتبقي: ${Fmt.money(row.remaining)}", color = c.mute, modifier = Modifier.padding(vertical = 6.dp))
            AmountField(amount, row.debt.currency, if (row.debt.direction == DebtDirection.I_OWE) "المبلغ المسدَّد" else "المبلغ المحصَّل")
            val m = amount.parse(row.debt.currency)
            if (m != null && m > row.remaining) Text("يتجاوز المتبقي (${Fmt.money(row.remaining)})", color = c.danger)
            AccountPicker(vm, account, if (row.debt.direction == DebtDirection.I_OWE) "دُفع من" else "استُلم في") { account = it }
            TextButton(onClick = { account = null }) { Text("بلا حركة نقدية") }
            val acc = view.accountById[account]
            val differs = acc != null && acc.currency.code != row.debt.currency.code
            if (differs) AmountField(wallet, acc!!.currency, "المبلغ بعملة الحساب")
            DateField(date, { date = it })
            TextIn(note, { note = it }, "ملاحظة (اختياري)")
            Spacer(Modifier.height(Sp.md))
            val wm = if (differs) wallet.parse(acc!!.currency) else null
            PrimaryButton("احفظ الدفعة", enabled = !saving && m != null && m.minor > 0 && m <= row.remaining && (!differs || wm != null), modifier = Modifier.fillMaxWidth(), onClick = {
                saving = true
                vm.launchSafe("تم تسجيل الدفعة", undoable = true) { vm.repo.addDebtPayment(row.debt.id, m!!.minor, account, wm?.minor, date, note, rid).also { nav.popBackStack() } }.invokeOnCompletion { saving = false }
            })
        }
    }
}
