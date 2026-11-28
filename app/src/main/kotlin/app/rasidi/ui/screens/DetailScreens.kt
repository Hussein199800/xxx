package app.rasidi.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.rasidi.core.data.toDomain
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.core.reports.PrivacyOptions
import app.rasidi.core.reports.ReportBuilders
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.Routes
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import java.time.LocalDate
import java.time.LocalDateTime

fun nowStamp(): String = LocalDateTime.now().toString().take(16).replace('T', ' ')

@Composable
fun TxnDetailScreen(vm: MainViewModel, nav: NavController, id: String) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    val t = view.txnById[id] ?: run { ScreenScaffold("الحركة", { nav.popBackStack() }) { Text("الحركة غير موجودة (ربما تراجعت عنها)", color = c.mute) }; return }
    val es = view.entries.filter { it.txnId == id }
    var confirmReverse by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var voucher by remember { mutableStateOf(false) }
    var hideNames by remember { mutableStateOf(false) }; var hideNotes by remember { mutableStateOf(false) }
    val rid = rememberSaveable { vm.newRequestId() }
    val type = TxnType.valueOf(t.type)
    ScreenScaffold(type.ar, { nav.popBackStack() }, sub = ReportDate.ar(LocalDate.parse(t.date))) {
        RCard {
            if (t.note.isNotBlank()) KeyValue("البيان", t.note)
            if (t.party.isNotBlank()) KeyValue("الطرف", t.party)
            t.fxRate?.let { KeyValue("السعر الفعلي", it) }
            KeyValue("المرجع", t.id.takeLast(8).uppercase())
            if (t.reversedById != null) StatusChip("معكوسة", Tone.INFO)
            if (t.reversesId != null) TextButton(onClick = { nav.navigate(Routes.txn(t.reversesId!!)) }) { Text("افتح الحركة الأصلية") }
            t.reversedById?.let { r -> TextButton(onClick = { nav.navigate(Routes.txn(r)) }) { Text("افتح حركة العكس") } }
        }
        SectionHeader("القيود")
        RCard {
            if (es.isEmpty()) Text("بلا قيود نقدية (دين مسجّل دون حركة)", color = c.mute)
            es.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(view.accountById[e.accountId]?.name ?: "", style = MaterialTheme.typography.titleSmall)
                        Text(ReportBuilders.kindAr(e.kind) + (e.categoryId?.let { " · ${view.categoryNames[it]}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.mute)
                    }
                    AmountText(e.amount, signColor = true)
                }
            }
        }
        t.debtId?.let { d -> SoftButton("افتح الدين", { nav.navigate(Routes.debt(d)) }, Modifier.padding(top = Sp.sm)) }
        Spacer(Modifier.height(Sp.md))
        // السند: طباعة ومشاركة (مثل «طباعة سند / مشاركة سند / إشعار»)
        RCard {
            Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(Icons.Filled.Receipt, CardTint.green); Spacer(Modifier.width(Sp.sm)); Text("السند", style = MaterialTheme.typography.titleMedium) }
            VoucherActions(vm, id)
            TextButton(onClick = { voucher = !voucher }) { Text(if (voucher) "إخفاء الصيغ الأخرى" else "حفظ كملف (PDF أو Word أو Excel)") }
        }
        Spacer(Modifier.height(Sp.xs))
        if ((type == TxnType.INCOME || type == TxnType.EXPENSE) && t.reversedById == null)
            SoftButton("تعديل", { nav.navigate(Routes.add(if (type == TxnType.INCOME) "income" else "expense", id)) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Edit)
        val isDebtNew = type == TxnType.DEBT_NEW && t.debtId != null
        // الدين القديم بلا حركة نقدية يُلغى أيضاً بعكس حركة إنشائه
        if (t.reversedById == null && t.reversesId == null && (es.isNotEmpty() || isDebtNew)) {
            Spacer(Modifier.height(Sp.xs))
            OutlinedButton(onClick = { confirmReverse = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Filled.Undo, null); Spacer(Modifier.width(6.dp)); Text(if (isDebtNew) "إلغاء الدين (عكس)" else "عكس الحركة") }
        }
        if (voucher) {
            Spacer(Modifier.height(Sp.sm))
            val st by vm.settings.collectAsStateWithLifecycle()
            val doc = remember(t, hideNames, hideNotes, st) { ReportBuilders.voucher(view, id, nowStamp(), PrivacyOptions(hideNames, hideNotes), st.voucherOptions()) }
            ExportPanel(vm, doc, true, hideNames, hideNotes) { a, b -> hideNames = a; hideNotes = b }
        }
    }
    if (confirmReverse) AlertDialog(onDismissRequest = { confirmReverse = false },
        title = { Text("عكس الحركة؟") },
        text = { Column {
            Text(if (type == TxnType.DEBT_NEW) "سيُلغى الدين: لا يُحتسب أصله بعد الآن، وتُعاد أي مبالغ نقدية لحساباتها، ويبقى الدين وحركته في السجل. إن كانت عليه دفعات فاعكسها أولاً."
                else "ستُنشأ حركة عكس بنفس التاريخ تعيد أرصدة الحسابات" + (if (t.debtId != null) " والدين" else "") + " معاً، ويبقى الأصل في السجل.")
            es.forEach { e -> Text("• ${view.accountById[e.accountId]?.name}: ${Fmt.money(-e.amount)}", color = c.mute) }
            TextIn(reason, { reason = it }, "السبب (اختياري)")
        } },
        confirmButton = { TextButton(onClick = { confirmReverse = false; vm.launchSafe("تم العكس") { vm.repo.reverse(id, reason, rid) } }) { Text("اعكس") } },
        dismissButton = { TextButton(onClick = { confirmReverse = false }) { Text("إلغاء") } })
}

@Composable
fun DebtDetailScreen(vm: MainViewModel, nav: NavController, id: String) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val rows by vm.debts.collectAsStateWithLifecycle()
    val view = v ?: return
    val r = rows.firstOrNull { it.debt.id == id } ?: return
    val d = r.debt
    var export by remember { mutableStateOf(false) }
    var hideNames by remember { mutableStateOf(false) }
    val pays = view.payments.filter { it.debtId == id }.sortedByDescending { it.date }
    val payTxn = view.s.debtPayments.filter { it.debtId == id }.associate { it.id to it.txnId }
    ScreenScaffold(d.party, { nav.popBackStack() }, sub = if (d.direction == DebtDirection.OWED_TO_ME) "لي عند الناس" else "عليّ للناس") {
        RCard(tint = if (r.status == DebtStatus.OVERDUE) c.dangerSoft else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("المتبقي", color = c.mute, style = MaterialTheme.typography.labelLarge)
                    AmountText(r.remaining, style = MaterialTheme.typography.headlineMedium, color = if (d.direction == DebtDirection.I_OWE) c.danger else c.pri)
                }
                StatusChip(r.status.ar, when (r.status) { DebtStatus.SETTLED -> Tone.OK; DebtStatus.OVERDUE -> Tone.DANGER; DebtStatus.PARTIAL -> Tone.GOLD; DebtStatus.OPEN -> Tone.INFO; DebtStatus.CANCELLED -> Tone.INFO })
            }
            KeyValue("أصل الدين", Fmt.money(d.principal))
            KeyValue("المدفوع", Fmt.money(r.paid))
            KeyValue("تاريخ الدين", d.date.toString())
            d.dueDate?.let { KeyValue("الاستحقاق", it.toString()) }
            r.nextDue?.let { KeyValue("الاستحقاق القادم", it.toString()) }
            if (!d.note.isNullOrBlank()) KeyValue("ملاحظة", d.note!!)
        }
        Spacer(Modifier.height(Sp.sm))
        if (d.cancelled) Text("هذا الدين ملغى (عُكست حركة إنشائه)؛ يبقى في السجل ولا يُحتسب.", color = c.mute)
        if (r.remaining.minor > 0) PrimaryButton(if (d.direction == DebtDirection.I_OWE) "سجّل دفعة سداد" else "سجّل دفعة تحصيل", { nav.navigate(Routes.add("payment", id)) }, Modifier.fillMaxWidth(), icon = Icons.Filled.TaskAlt)
        if (d.installments.isNotEmpty()) {
            SectionHeader("جدول الأقساط")
            RCard {
                var cum = Money.zero(d.currency)
                d.installments.forEach { i ->
                    cum += i.amount
                    val covered = r.paid >= cum
                    val late = !covered && i.dueDate.isBefore(vm.today())
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(i.dueDate.toString(), modifier = Modifier.weight(1f))
                        AmountText(i.amount, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.width(8.dp))
                        StatusChip(if (covered) "مغطّى" else if (late) "متأخر" else "قادم", if (covered) Tone.OK else if (late) Tone.DANGER else Tone.INFO)
                    }
                }
            }
        }
        SectionHeader("الدفعات")
        RCard {
            if (pays.isEmpty()) Text("لا دفعات بعد", color = c.mute)
            pays.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.date.toString(), style = MaterialTheme.typography.titleSmall)
                        Text(p.accountId?.let { view.accountById[it]?.name } ?: "بلا حركة نقدية", style = MaterialTheme.typography.bodySmall, color = c.mute)
                        p.walletAmount?.takeIf { it.currency.code != d.currency.code }?.let { Text("بالحساب: ${Fmt.money(it.abs())}", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                    }
                    AmountText(p.debtAmount, color = if (p.debtAmount.minor < 0) c.mute else c.txt)
                    payTxn[p.id]?.let { tid -> IconButton(onClick = { nav.navigate(Routes.txn(tid)) }) { Icon(Icons.Filled.ChevronLeft, "تفاصيل الدفعة") } }
                }
            }
        }
        Spacer(Modifier.height(Sp.sm))
        SoftButton("كشف حساب ${d.party}", { nav.navigate(Routes.party(d.party)) }, Modifier.fillMaxWidth(), icon = Icons.Filled.ReceiptLong)
        view.s.txns.firstOrNull { it.debtId == id && it.type == TxnType.DEBT_NEW.name }?.let { nt ->
            TextButton(onClick = { nav.navigate(Routes.txn(nt.id)) }, modifier = Modifier.fillMaxWidth()) { Text(if (d.cancelled) "حركة الإنشاء والإلغاء" else "حركة إنشاء الدين (للتصحيح أو الإلغاء)") }
        }
        Spacer(Modifier.height(Sp.xs))
        SoftButton("تصدير كشف الدين", { export = !export }, Modifier.fillMaxWidth(), icon = Icons.Filled.IosShare)
        if (export) {
            val doc = remember(view, hideNames) { ReportBuilders.debtReport(view, id, vm.today(), nowStamp(), PrivacyOptions(hideNames)) }
            ExportPanel(vm, doc, true, hideNames, false) { a, _ -> hideNames = a }
        }
        val de = view.s.debts.first { it.id == id }
        TextButton(onClick = { vm.launchSafe(if (de.archived) "أُعيد الدين" else "أُرشف الدين") { vm.repo.archiveDebt(de, !de.archived) } }) { Text(if (de.archived) "إلغاء الأرشفة" else "أرشفة الدين") }
    }
}

@Composable
private fun StepHeader(step: Int, total: Int, title: String) {
    val c = LocalR.current
    Text("الخطوة $step من $total: $title", style = MaterialTheme.typography.titleLarge, color = c.pri)
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(progress = { step / total.toFloat() }, modifier = Modifier.fillMaxWidth().height(5.dp), color = c.pri, trackColor = c.soft)
    Spacer(Modifier.height(Sp.md))
}

/** أول تشغيل بخطوات: البداية (محلياً أو استرجاع)، ثم البيانات الشخصية، ثم أول محفظة، ثم حماية البيانات. */
@Composable
fun OnboardingScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    var mode by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(1) }
    var pname by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("نقد") }
    var type by rememberSaveable { mutableStateOf(AccountType.CASH) }
    var cur by rememberSaveable { mutableStateOf("USD") }
    val opening = remember { AmountState() }
    var date by remember { mutableStateOf(vm.today()) }
    fun done(to: String = Routes.HOME) { vm.device.onboarded = true; nav.navigate(to) { popUpTo(Routes.ONBOARD) { inclusive = true } } }
    ScreenScaffold("أهلاً في رصيدي", null, sub = if (mode.isEmpty()) "دفترك المالي على جهازك. يعمل بلا إنترنت." else null) {
        if (mode.isEmpty()) {
            RCard(onClick = { mode = "local" }) {
                IconBadge(Icons.Filled.PhoneAndroid, c.pri)
                Text("ابدأ محلياً", style = MaterialTheme.typography.titleLarge, color = c.txt)
                Text("ثلاث خطوات قصيرة: اسمك، أول محفظة، حماية بياناتك.", color = c.mute)
            }
            Spacer(Modifier.height(Sp.sm))
            RCard(onClick = { done(Routes.BACKUP) }) {
                IconBadge(Icons.Filled.Restore, c.gold)
                Text("استرجع سجلي", style = MaterialTheme.typography.titleLarge, color = c.txt)
                Text("من ملف .rasidi أو من Google Drive، برمز الاستعادة.", color = c.mute)
            }
            Spacer(Modifier.height(Sp.sm))
            TextButton(onClick = { vm.device.onboarded = true; vm.switchDemo(true) }) { Text("جرّب دفتراً تجريبياً منفصلاً") }
            return@ScreenScaffold
        }
        when (step) {
            1 -> {
                StepHeader(1, 3, "بياناتك في السندات")
                TextIn(pname, { pname = it }, "الاسم أو اسم البيت (اختياري)")
                Spacer(Modifier.height(Sp.xs))
                TextIn(city, { city = it }, "المدينة والبلد (اختياري)")
                Text("تظهر في رأس السندات عند الطباعة والمشاركة. يمكنك تعديلها لاحقاً من الإعدادات.", style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    PrimaryButton("التالي", { if (pname.isNotBlank()) vm.setSetting("profile.name", pname.trim()); if (city.isNotBlank()) vm.setSetting("profile.city", city.trim()); step = 2 }, Modifier.weight(1f))
                    TextButton(onClick = { step = 2 }) { Text("تخطي") }
                }
            }
            2 -> {
                StepHeader(2, 3, "أول محفظة")
                TextIn(name, { name = it }, "اسم المحفظة")
                Spacer(Modifier.height(Sp.xs))
                ChipRow(AccountType.values().toList(), type, { it.ar }, { type = it })
                Spacer(Modifier.height(Sp.xs))
                Text("العملة", style = MaterialTheme.typography.titleSmall, color = c.mute)
                ChipRow(listOf("USD", "TRY", "SYP", "EUR", "SYP_OLD"), cur, { Currencies.of(it).nameAr }, { cur = it })
                AmountField(opening, Currencies.of(cur), "الرصيد الافتتاحي (اختياري)")
                DateField(date, { date = it }, "تاريخ الرصيد")
                Spacer(Modifier.height(Sp.md))
                val m = if (opening.text.isBlank()) Money.zero(Currencies.of(cur)) else opening.parse(Currencies.of(cur))
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    PrimaryButton("التالي", enabled = name.isNotBlank() && m != null, modifier = Modifier.weight(1f), onClick = {
                        vm.launchSafe("أُضيفت المحفظة") { vm.repo.createAccount(name, type, Currencies.of(cur), m!!.minor, date); step = 3 }
                    })
                    TextButton(onClick = { step = 1 }) { Text("السابق") }
                }
            }
            else -> {
                StepHeader(3, 3, "احمِ بياناتك")
                RCard(tint = c.goldSoft) {
                    IconBadge(Icons.Filled.Key, c.gold)
                    Text("رمز الاستعادة", style = MaterialTheme.typography.titleMedium)
                    Text("أنشئه الآن واكتبه خارج الهاتف. به وحده تُفتح نسخك الاحتياطية من ملف أو من Google Drive إن ضاع الهاتف.", color = c.mute)
                }
                Spacer(Modifier.height(Sp.md))
                PrimaryButton("أنشئ رمز الاستعادة الآن", { done(Routes.BACKUP) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Key)
                TextButton(onClick = { done() }, modifier = Modifier.fillMaxWidth()) { Text("لاحقاً، ابدأ الاستخدام") }
            }
        }
    }
}
