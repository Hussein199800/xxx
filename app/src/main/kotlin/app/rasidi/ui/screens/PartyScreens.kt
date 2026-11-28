package app.rasidi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.rasidi.core.reports.AccountsReport as AR
import androidx.compose.foundation.rememberScrollState
import app.rasidi.core.ledger.DebtDirection
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.rasidi.core.ledger.PartyLedger
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Fmt
import app.rasidi.core.reports.ReportBuilders
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.Routes
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import java.time.LocalDate

enum class AccFilter(val ar: String) { ALL("الكل"), DEBTOR("مدينة · لي معها"), CREDITOR("دائنة · لها معي"), SETTLED("مسدّدة"), WALLETS("الصناديق") }

/**
 * تبويب «الحسابات»: كل الحسابات حتى ذات الرصيد صفر، مقسّمة إلى
 * مدينة (لي معها) ودائنة (لها معي)، ومعها الصناديق (المحافظ).
 */
@Composable
fun AccountsTabScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v = vm.view.collectAsStateWithLifecycle().value ?: return
    val all = remember(v) { PartyLedger.parties(v) }
    val (deb, cred) = remember(all) { PartyLedger.totals(all) }
    var filter by rememberSaveable { mutableStateOf(AccFilter.ALL) }
    var q by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val list = all.filter { p ->
        (q.isBlank() || p.party.contains(q.trim(), true) || p.phone.contains(q.trim())) && when (filter) {
            AccFilter.ALL -> true; AccFilter.DEBTOR -> p.debtor; AccFilter.CREDITOR -> p.creditor; AccFilter.SETTLED -> p.settled; AccFilter.WALLETS -> false
        }
    }
    var printing by rememberSaveable { mutableStateOf(false) }
    val wallets = v.accounts.filter { !it.archived && (q.isBlank() || it.name.contains(q.trim(), true)) }
    // عرض كسول للأعداد الكبيرة (F08)؛ الرأس والفلاتر عناصر في القائمة نفسها
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(horizontal = Sp.md), contentPadding = PaddingValues(bottom = 170.dp)) {
      item {
        BigTitle("الحسابات", sub = "${all.size} حساب · ${v.accounts.count { !it.archived }} صناديق")
        // بطاقتا المدين والدائن — إجماليات كل الحسابات، لا تتأثر بالبحث
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.sm)) {
            RCard(Modifier.weight(1f), onClick = { filter = AccFilter.DEBTOR }, tint = if (filter == AccFilter.DEBTOR) c.priSoft else null) {
                Text("مدينة · لي معها", style = MaterialTheme.typography.labelLarge, color = c.mute)
                Text("${all.count { it.debtor }} حساب", style = MaterialTheme.typography.bodySmall, color = c.mute)
                if (deb.isEmpty()) Text("—", style = MaterialTheme.typography.titleMedium, color = c.mute)
                deb.values.forEach { AmountText(it, color = c.pri) }
            }
            RCard(Modifier.weight(1f), onClick = { filter = AccFilter.CREDITOR }, tint = if (filter == AccFilter.CREDITOR) c.dangerSoft else null) {
                Text("دائنة · لها معي", style = MaterialTheme.typography.labelLarge, color = c.mute)
                Text("${all.count { it.creditor }} حساب", style = MaterialTheme.typography.bodySmall, color = c.mute)
                if (cred.isEmpty()) Text("—", style = MaterialTheme.typography.titleMedium, color = c.mute)
                cred.values.forEach { AmountText(it, color = c.danger) }
            }
        }
        Text("الإجماليات لكل الحسابات بكل عملة على حدة، ولا تتأثر بالبحث.", style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(Sp.sm))
        OutlinedTextField(q, { q = it }, placeholder = { Text(if (filter == AccFilter.WALLETS) "ابحث باسم الصندوق" else "ابحث بالاسم أو الهاتف") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Sp.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
            SoftButton("طباعة وتصدير", { printing = !printing }, Modifier.weight(1f), icon = Icons.Filled.Print)
            SoftButton("إضافة حساب", { adding = true }, Modifier.weight(1f), icon = Icons.Filled.PersonAdd)
        }
        Spacer(Modifier.height(Sp.xs))
        ChipRow(AccFilter.values().toList(), filter, { f -> when (f) {
            AccFilter.ALL -> "الكل ${all.size}"; AccFilter.DEBTOR -> "مدينة · لي معها ${all.count { it.debtor }}"
            AccFilter.CREDITOR -> "دائنة · لها معي ${all.count { it.creditor }}"; AccFilter.SETTLED -> "مسدّدة ${all.count { it.settled }}"
            AccFilter.WALLETS -> "الصناديق ${v.accounts.count { !it.archived }}" } }, { filter = it })
        Spacer(Modifier.height(Sp.sm))
        if (printing) AccountsPrintPanel(vm, v, filter, list.map { it.party }.toSet())
      }
        if (filter == AccFilter.WALLETS) {
            items(wallets.size, key = { "w" + wallets[it].id }) { idx ->
                val a = wallets[idx]
                val b = v.balance(a)
                RCard(onClick = { nav.navigate(Routes.account(a.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.AccountBalanceWallet, c.pri)
                        Spacer(Modifier.width(Sp.sm))
                        Column(Modifier.weight(1f)) { Text(a.name, style = MaterialTheme.typography.titleMedium); Text("${a.type.ar} · ${a.currency.nameAr}", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                        AmountText(b, signColor = b.isNegative)
                    }
                }
                Spacer(Modifier.height(Sp.xs))
            }
            item { SoftButton("إدارة الصناديق وإضافة صندوق", { nav.navigate(Routes.ACCOUNTS) }, Modifier.fillMaxWidth()) }
        } else {
            if (list.isEmpty()) item { EmptyState(Icons.Filled.Groups, if (all.isEmpty()) "لا حسابات بعد" else "لا نتائج", if (all.isEmpty()) "أضف حساباً لكل شخص تتعامل معه" else "غيّر البحث أو التصنيف", "إضافة حساب") { adding = true } }
            items(list.size, key = { "p" + list[it].party }) { idx -> val p = list[idx]; PartyRow(p) { nav.navigate(Routes.party(p.party)) }; Spacer(Modifier.height(Sp.xs)) }
            item { TextButton(onClick = { nav.navigate(Routes.DEBTS) }, modifier = Modifier.fillMaxWidth()) { Text("الديون والأقساط بالتفصيل") } }
        }
    }
    if (adding) AddPartyDialog(vm) { adding = false }
}

@Composable
private fun PartyRow(p: PartyLedger.PartySummary, onClick: () -> Unit) {
    val c = LocalR.current
    RCard(onClick = onClick, contentDesc = p.party) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (p.debtor) c.priSoft else if (p.creditor) c.dangerSoft else c.soft), contentAlignment = Alignment.Center) {
                Text(p.party.trim().take(1), style = MaterialTheme.typography.titleLarge, color = if (p.debtor) c.pri else if (p.creditor) c.danger else c.mute)
            }
            Spacer(Modifier.width(Sp.sm))
            Column(Modifier.weight(1f)) {
                Text(p.party, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(p.phone.takeIf { it.isNotBlank() }?.let { Fmt.LRI + it + Fmt.PDI }, p.lastDate?.let { "آخر حركة ${ReportDate.ar(it)}" } ?: "بلا حركات").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                val nz = p.balances.values.filter { !it.isZero }
                if (nz.isEmpty()) StatusChip("مسدّد", Tone.OK)
                nz.forEach { b ->
                    Text(if (b.minor > 0) "مدين · لي معه" else "دائن · له معي", style = MaterialTheme.typography.labelSmall, color = if (b.minor > 0) c.pri else c.danger)
                    AmountText(b.abs(), style = MaterialTheme.typography.titleSmall, color = if (b.minor > 0) c.pri else c.danger)
                }
            }
        }
    }
}

/** «إضافة حساب جديد»: الاسم والهاتف والملاحظة ورصيد سابق اختياري (لي معه / له معي). */
@Composable
fun AddPartyDialog(vm: MainViewModel, edit: app.rasidi.core.data.PartyEntity? = null, onDone: () -> Unit) {
    val c = LocalR.current
    var name by rememberSaveable { mutableStateOf(edit?.name ?: "") }
    var phone by rememberSaveable { mutableStateOf(edit?.phone ?: "") }
    var note by rememberSaveable { mutableStateOf(edit?.note ?: "") }
    var withOpening by rememberSaveable { mutableStateOf(false) }
    var owedToMe by rememberSaveable { mutableStateOf(true) }
    var cur by rememberSaveable { mutableStateOf("USD") }
    val amount = remember { AmountState() }
    var date by remember { mutableStateOf(vm.today()) }
    val rid = rememberSaveable { vm.newRequestId() }
    AlertDialog(onDismissRequest = onDone, title = { Text(if (edit == null) "إضافة حساب جديد" else "تعديل الحساب") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                TextIn(name, { name = it }, "الاسم")
                Spacer(Modifier.height(6.dp))
                TextIn(phone, { phone = it }, "رقم الهاتف (اختياري)", keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
                Spacer(Modifier.height(6.dp))
                TextIn(note, { note = it }, "ملاحظة (اختياري)")
                if (edit == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Switch(withOpening, { withOpening = it }); Spacer(Modifier.width(8.dp)); Text("إضافة رصيد سابق")
                    }
                    if (withOpening) {
                        ChipRow(listOf(true, false), owedToMe, { if (it) "لي معه (مدين)" else "له معي (دائن)" }, { owedToMe = it })
                        ChipRow(listOf("USD", "TRY", "SYP", "EUR", "SYP_OLD"), cur, { Currencies.of(it).nameAr }, { cur = it })
                        AmountField(amount, Currencies.of(cur), "الرصيد السابق")
                        DateField(date, { date = it }, "بتاريخ")
                    }
                }
            }
        },
        confirmButton = {
            val m = if (withOpening) amount.parse(Currencies.of(cur)) else null
            TextButton(enabled = name.isNotBlank() && (!withOpening || (m != null && m.minor > 0)), onClick = {
                vm.launchSafe(if (edit == null) "أُضيف الحساب" else "حُفظ الحساب") {
                    if (edit == null) vm.repo.addParty(name, phone, note, m?.minor ?: 0, Currencies.of(cur), owedToMe, date, rid)
                    else vm.repo.updateParty(edit, name, phone, note)
                    onDone()
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("إلغاء", color = c.mute) } })
}

enum class StPeriod(val ar: String) { DAY("يومي"), MONTH("شهري"), YEAR("سنوي"), ALL("الكل") }

/** كشف حساب شخص: فترة، من/إلى بأسهم، عملة، رصيد سابق، جدول النوع/المبلغ/التفاصيل/الرصيد، وشريط عليه/له/الرصيد. */
@Composable
fun PartyStatementScreen(vm: MainViewModel, nav: NavController, party: String) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val view = v ?: return
    val currencies = remember(view, party) { PartyLedger.currencies(view, party) }
    var cur by rememberSaveable { mutableStateOf(currencies.firstOrNull() ?: "USD") }
    var period by rememberSaveable { mutableStateOf(StPeriod.ALL) }
    var anchor by remember { mutableStateOf(vm.today()) }
    var prior by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf(false) }
    val (from, to) = when (period) {
        StPeriod.DAY -> anchor to anchor
        StPeriod.MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
        StPeriod.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
        StPeriod.ALL -> PartyLedger.allRange(view, party, cur, vm.today()) // الحدود نفسها للعرض والتصدير
    }
    fun shift(n: Long) { anchor = when (period) { StPeriod.DAY -> anchor.plusDays(n); StPeriod.MONTH -> anchor.plusMonths(n); StPeriod.YEAR -> anchor.plusYears(n); StPeriod.ALL -> anchor } }
    val st = remember(view, party, cur, from, to, prior) { PartyLedger.statement(view, party, cur, from, to, prior && period != StPeriod.ALL) }
    val currency = Currencies.of(cur)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val entity = view.s.parties.firstOrNull { it.name == party.trim() }
        var editing by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var openingFix by remember { mutableStateOf(false) }
        PageTopBar(party, { nav.popBackStack() }, actions = {
            IconButton(onClick = { export = !export }, Modifier.size(48.dp)) { Icon(Icons.Filled.Print, "طباعة وتصدير الكشف", tint = c.txt) }
            Box {
                IconButton(onClick = { menu = true }, Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, "خيارات الحساب", tint = c.txt) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("دين جديد لهذا الحساب") }, { menu = false; nav.navigate(Routes.add("debt", party)) }, leadingIcon = { Icon(Icons.Filled.Handshake, null) })
                    val open = view.debts.filter { d -> d.party.trim() == party.trim() }.firstOrNull { d -> app.rasidi.core.ledger.DebtCalc.remaining(d, view.payments).minor > 0 }
                    if (open != null) DropdownMenuItem({ Text("سند قبض أو سداد") }, { menu = false; nav.navigate(Routes.add("payment", open.id)) }, leadingIcon = { Icon(Icons.Filled.TaskAlt, null) })
                    if (entity != null) DropdownMenuItem({ Text("تعديل الحساب") }, { menu = false; editing = true }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    DropdownMenuItem({ Text("الرصيد السابق وتصحيحه") }, { menu = false; openingFix = true }, leadingIcon = { Icon(Icons.Filled.Restore, null) })
                    if (!entity?.phone.isNullOrBlank()) {
                        val digits = entity!!.phone.filter { it.isDigit() }
                        fun launch(i: android.content.Intent, missing: String) = try { ctx.startActivity(i) } catch (e: android.content.ActivityNotFoundException) { vm.error(missing) }
                        DropdownMenuItem({ Text("اتصال") }, { menu = false
                            if (digits.length < 6) vm.error("رقم الهاتف غير صالح؛ عدّله من «تعديل الحساب»")
                            else launch(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + entity.phone.filter { it.isDigit() || it == '+' })), "لا يوجد تطبيق اتصال على هذا الجهاز")
                        }, leadingIcon = { Icon(Icons.Filled.Call, null) })
                        DropdownMenuItem({ Text("رسالة واتساب بالرصيد") }, {
                            menu = false
                            val txt = "${s.get("profile.name").ifBlank { "رصيدي" }}\nكشف حساب: $party\n${st.closingLabel}: ${Fmt.number(st.closing.amount.abs(), currency.minorDigits)} $cur"
                            if (digits.length < 8) vm.error("واتساب يحتاج الرقم بالمفتاح الدولي (مثل 963…)؛ عدّله من «تعديل الحساب»")
                            else launch(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/$digits?text=" + android.net.Uri.encode(txt))), "تعذّر فتح واتساب أو المتصفح")
                        }, leadingIcon = { Icon(Icons.Filled.Chat, null) })
                    }
                    if (entity != null) DropdownMenuItem({ Text(if (entity.archived) "إلغاء الأرشفة" else "أرشفة الحساب") }, { menu = false; vm.launchSafe { vm.repo.updateParty(entity, entity.name, entity.phone, entity.note, !entity.archived) } }, leadingIcon = { Icon(Icons.Filled.Archive, null) })
                }
            }
        })
        if (editing && entity != null) AddPartyDialog(vm, entity) { editing = false; nav.popBackStack() }
        if (openingFix) PartyOpeningDialog(vm, view, party) { openingFix = false }
        // بطاقة الفلاتر
        RCard(Modifier.padding(horizontal = Sp.md)) {
            ChipRow(StPeriod.values().toList(), period, { it.ar }, { period = it })
            if (currencies.size > 1) { Spacer(Modifier.height(6.dp)); ChipRow(currencies, cur, { Currencies.of(it).nameAr }, { cur = it }) }
            if (period != StPeriod.ALL) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    IconButton(onClick = { shift(-1) }) { Icon(Icons.Filled.ChevronRight, "السابق", tint = c.pri) }
                    Text("من ${Fmt.LRI}$from${Fmt.PDI}  إلى ${Fmt.LRI}$to${Fmt.PDI}", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { shift(1) }) { Icon(Icons.Filled.ChevronLeft, "التالي", tint = c.pri) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(prior, { prior = it }); Text("رصيد سابق") }
            }
        }
        if (export) Column(Modifier.padding(horizontal = Sp.md, vertical = 6.dp)) {
            val doc = remember(view, party, cur, period, from, to, prior) { ReportBuilders.partyStatement(view, party, cur, from, to, prior && period != StPeriod.ALL, nowStamp(), s.get("statement.footer")) }
            ExportPanel(vm, doc, false, false, false) { _, _ -> }
        }
        // رأس الجدول
        Row(Modifier.fillMaxWidth().padding(top = Sp.sm).background(c.pri).padding(vertical = 10.dp, horizontal = Sp.md)) {
            listOf("النوع" to 0.8f, "المبلغ" to 1.2f, "التفاصيل" to 1.6f, "الرصيد" to 1.2f).forEach { (h, w) -> Text(h, Modifier.weight(w), color = c.priInk, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (prior && period != StPeriod.ALL) item {
                StatementRow("رصيد سابق", null, "قبل ${Fmt.LRI}$from${Fmt.PDI}", st.prior, currency.minorDigits, c.mute) {}
            }
            items(st.lines) { l ->
                val on = l.onHim != null
                Column {
                    StatementRow(if (on) "عليه" else "له", l.onHim ?: l.forHim, "${l.details}\n${Fmt.LRI}${l.date}${Fmt.PDI}", l.balance, currency.minorDigits, if (on) c.danger else c.pri) { selected = if (selected == l.txnId) null else l.txnId }
                    if (selected != null && selected == l.txnId) Box(Modifier.padding(horizontal = Sp.md, vertical = 6.dp)) { VoucherActions(vm, l.txnId!!) }
                    HorizontalDivider(color = c.line)
                }
            }
            if (st.lines.isEmpty()) item { EmptyState(Icons.Filled.ReceiptLong, "لا حركات في هذه الفترة", "غيّر الفترة أو اختر «الكل»") }
        }
        // شريط الملخّص السفلي
        Row(Modifier.fillMaxWidth().background(c.pri).navigationBarsPadding().padding(horizontal = Sp.md, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("عليه: ${Fmt.LRI}${Fmt.number(st.totalOn.amount, currency.minorDigits)}${Fmt.PDI}", color = c.priInk, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("له: ${Fmt.LRI}${Fmt.number(st.totalFor.amount, currency.minorDigits)}${Fmt.PDI}", color = c.priInk, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${st.closingLabel}: ${Fmt.LRI}${Fmt.number(st.closing.amount.abs(), currency.minorDigits)} $cur${Fmt.PDI}", color = c.priInk, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1.6f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun StatementRow(kind: String, amount: app.rasidi.core.money.Money?, details: String, balance: app.rasidi.core.money.Money, digits: Int, kindColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = LocalR.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = Sp.md, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(kind, Modifier.weight(0.8f), color = kindColor, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(amount?.let { Fmt.number(it.amount, digits) } ?: "", Modifier.weight(1.2f), color = kindColor, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(details, Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall, color = c.txt, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(Fmt.LRI + Fmt.number(balance.amount.abs(), digits) + Fmt.PDI + "\n" + (if (balance.minor > 0) "عليه" else if (balance.minor < 0) "له" else ""), Modifier.weight(1.2f), color = c.sky(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

private fun app.rasidi.ui.theme.RColors.sky() = if (dark) androidx.compose.ui.graphics.Color(0xFF7CC4F5) else androidx.compose.ui.graphics.Color(0xFF1E6FB8)

/**
 * F03: الرصيد السابق لحساب شخص — كل الديون المسجّلة دون حركة نقدية لهذا الشخص (بكل عملة واتجاه، لا يُفترض دين واحد).
 * التصحيح لا يُنشئ حركة صندوق، ولا ينزل عن صافي المسدَّد. الإلغاء أو تبديل الاتجاه/العملة: «إلغاء الدين» من حركة إنشائه.
 */
@Composable
private fun PartyOpeningDialog(vm: MainViewModel, view: app.rasidi.core.data.LedgerView, party: String, onDone: () -> Unit) {
    val c = LocalR.current
    val list = view.debts.filter { it.party.trim() == party.trim() && !it.cancelled && view.s.debts.firstOrNull { e -> e.id == it.id }?.txnId == null }
    var pick by remember { mutableStateOf<app.rasidi.core.ledger.Debt?>(null) }
    val amount = remember { AmountState() }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val rid = remember(pick) { vm.newRequestId() }
    AlertDialog(onDismissRequest = onDone, title = { Text("الرصيد السابق وتصحيحه") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (list.isEmpty()) Text("لا رصيد سابق مسجّل دون حركة نقدية لهذا الحساب. أضفه بـ«دين جديد لهذا الحساب» دون صندوق.", color = c.mute)
            list.forEach { d ->
                val paid = app.rasidi.core.ledger.DebtCalc.paid(d, view.payments)
                RCard(onClick = { pick = d; amount.text = d.principal.amount.toPlainString() }, tint = if (pick?.id == d.id) c.soft else null) {
                    Text("${if (d.direction == DebtDirection.OWED_TO_ME) "لي معه" else "له معي"} · ${Fmt.money(d.principal)}", style = MaterialTheme.typography.titleSmall)
                    Text("بتاريخ \u2066${d.date}\u2069 · المسدَّد ${Fmt.money(paid)}" + if (d.installments.isNotEmpty()) " · بأقساط (لا يُعدَّل هنا)" else "", style = MaterialTheme.typography.bodySmall, color = c.mute)
                }
            }
            pick?.let { d ->
                AmountField(amount, d.currency, "الأصل الصحيح")
                TextIn(reason, { reason = it }, "سبب التصحيح (مطلوب)")
                Text("لا تُنشأ حركة صندوق. لإلغاء الدين أو تغيير اتجاهه أو عملته: اعكس دفعاته ثم «إلغاء الدين» من حركة إنشائه وأضف رصيداً جديداً.", color = c.mute, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = {
        val d = pick; val m = d?.let { amount.parse(it.currency) }
        TextButton(enabled = !busy && d != null && m != null && m.minor > 0 && reason.isNotBlank() && d.installments.isEmpty(), onClick = {
            busy = true
            vm.launchSafe("صُحّح الرصيد السابق") { vm.repo.correctDebtPrincipal(d!!.id, m!!.minor, reason, rid); onDone() }.invokeOnCompletion { busy = false }
        }) { Text("صحّح") }
    }, dismissButton = { TextButton(onClick = onDone) { Text("إغلاق", color = c.mute) } })
}

/**
 * F04: «طباعة وتصدير» من تبويب الحسابات: النطاق، «كما في»، المؤرشفة والصفرية، الترتيب، والخصوصية،
 * ثم معاينة وطباعة A4 أو مشاركة أو حفظ بالصيغ الثلاث. يُبنى التقرير خارج خيط الواجهة من لقطة الشاشة نفسها.
 */
@Composable
private fun AccountsPrintPanel(vm: MainViewModel, v: app.rasidi.core.data.LedgerView, filter: AccFilter, visible: Set<String>) {
    val c = LocalR.current
    var scope by rememberSaveable { mutableStateOf(AR.Scope.VISIBLE) }
    var asOf by remember { mutableStateOf(vm.today()) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var zero by rememberSaveable { mutableStateOf(true) }
    var sort by rememberSaveable { mutableStateOf(AR.Sort.NAME) }
    var phone by rememberSaveable { mutableStateOf(false) }
    var statements by rememberSaveable { mutableStateOf(false) }
    var hideNames by remember { mutableStateOf(false) }; var hideNotes by remember { mutableStateOf(false) }
    val effScope = if (scope == AR.Scope.VISIBLE && filter == AccFilter.WALLETS) AR.Scope.WALLETS else scope
    val doc by produceState<app.rasidi.core.reports.ReportDoc?>(null, v, effScope, asOf, archived, zero, sort, phone, statements, hideNames, hideNotes, visible) {
        value = null
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            AR.build(v, effScope, AR.Options(asOf, archived, zero, sort, phone, false, visible.takeIf { effScope == AR.Scope.VISIBLE }, statements), nowStamp(),
                app.rasidi.core.reports.PrivacyOptions(hideNames, hideNotes))
        }
    }
    RCard {
        Text("طباعة وتصدير الحسابات", style = MaterialTheme.typography.titleMedium)
        ChipRow(AR.Scope.values().toList(), scope, { it.ar }, { scope = it })
        DateField(asOf, { asOf = it }, "كما في تاريخ")
        ChipRow(AR.Sort.values().toList(), sort, { it.ar }, { sort = it })
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(archived, { archived = it }); Text("تضمين المؤرشفة") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(zero, { zero = it }); Text("تضمين الأرصدة الصفرية") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(phone, { phone = it }); Text("إظهار الهاتف") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(statements, { statements = it }); Text("إلحاق كشف تفصيلي لكل حساب") }
        if (doc == null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("يُجهَّز التقرير…", color = c.mute) }
        else Text("${doc!!.rowCount} صف في التقرير", color = c.mute, style = MaterialTheme.typography.bodySmall)
    }
    ExportPanel(vm, doc, true, hideNames, hideNotes) { a, b -> hideNames = a; hideNotes = b }
    Spacer(Modifier.height(Sp.sm))
}
