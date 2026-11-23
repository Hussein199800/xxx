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
import app.rasidi.core.data.*
import app.rasidi.core.money.*
import app.rasidi.core.reports.ReportBuilders
import app.rasidi.core.valuation.*
import app.rasidi.core.zakat.*
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.Routes
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun InventoryScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var date by remember { mutableStateOf(vm.today()) }
    var confirmed by remember { mutableStateOf(setOf<String>()) }
    var oldSyp by remember { mutableStateOf(mapOf<String, BigDecimal?>()) }
    var explain by remember { mutableStateOf<app.rasidi.core.explain.Explanation?>(null) }
    var yearClose by rememberSaveable { mutableStateOf(false) }
    var revisionOf by rememberSaveable { mutableStateOf<String?>(null) }
    var openSaved by rememberSaveable { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf<InventoryResult?>(null) }
    var exportLabel by remember { mutableStateOf("") }
    val inv = remember(view, date, confirmed, oldSyp) { InventoryCalc.price(date, view.inventoryItems(date), view.rates, confirmed, SyrianRedenomination.DEFAULT, oldSyp) }
    ScreenScaffold("الجرد والزكاة", { nav.popBackStack() }, sub = "الأموال كما كانت في نهاية اليوم المختار، بنداً بنداً بالدولار") {
        // الخطوة 1
        Text("1. اختر التاريخ", style = MaterialTheme.typography.titleMedium, color = c.txt)
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs), verticalAlignment = Alignment.CenterVertically) {
            DateField(date, { date = it; confirmed = emptySet() }, "تاريخ الجرد")
            if (date != vm.today()) TextButton(onClick = { date = vm.today() }) { Text("لو جردت اليوم") }
        }
        if (revisionOf != null) StatusChip("مراجعة جديدة لجرد محفوظ", Tone.GOLD)
        // الخطوة 2
        Text("2. راجع الأسعار", style = MaterialTheme.typography.titleMedium, color = c.txt, modifier = Modifier.padding(top = Sp.md))
        RCard {
            inv.lines.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${l.item.kind.ar}: ${l.item.label}", style = MaterialTheme.typography.titleSmall)
                        Text(l.item.original, style = MaterialTheme.typography.bodySmall, color = c.mute)
                        l.rate?.let { Text("${it.label()} · ${it.source} · ${it.date}", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                        l.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.warn) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        l.signedUsd?.let { UsdText(it, style = MaterialTheme.typography.titleSmall, color = if (it.signum() < 0) c.danger else c.txt) }
                        StatusChip(l.state.ar, when (l.state) { PriceState.PRICED -> Tone.OK; PriceState.CONFIRMED_OLDER -> Tone.GOLD; else -> Tone.WARN })
                    }
                }
                when (l.state) {
                    PriceState.SUGGESTED -> TextButton(onClick = { confirmed = confirmed + l.item.id }) { Text("اعتمد السعر الأقدم مع ملاحظة") }
                    PriceState.MISSING -> TextButton(onClick = { nav.navigate(Routes.RATES) }) { Text("أضف سعراً بتاريخ $date") }
                    PriceState.NEEDS_DECISION -> Row { TextButton(onClick = { oldSyp = oldSyp + (l.item.id to null) }) { Text("قابل للاستبدال") } }
                    else -> {}
                }
                HorizontalDivider(color = c.line)
            }
            if (inv.lines.isEmpty()) Text("لا أموال مسجّلة بهذا التاريخ", color = c.mute)
        }
        // الخطوة 3
        Text("3. راجع صافي أموالك", style = MaterialTheme.typography.titleMedium, color = c.txt, modifier = Modifier.padding(top = Sp.md))
        RCard(onClick = { explain = inv.explain() }) {
            LineKind.values().forEach { k ->
                val t = inv.total(k)
                if (t.signum() != 0) KeyValue(k.ar, Fmt.usd(if (k.isLiability) t.negate() else t, false))
            }
            HorizontalDivider(color = c.line)
            Text(if (inv.isComplete) "صافي الأموال" else "إجمالي البنود المسعّرة (جزئي)", color = c.mute)
            UsdText(inv.pricedNet, style = MaterialTheme.typography.headlineMedium, color = c.pri)
            if (!inv.isComplete) StatusChip("${inv.missingCount} بند يحتاج سعراً أو قراراً — يُحفظ مسودة", Tone.WARN)
            HowLink { explain = inv.explain() }
        }
        // الخطوة 4
        Text("4. احفظ الجرد", style = MaterialTheme.typography.titleMedium, color = c.txt, modifier = Modifier.padding(top = Sp.md))
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(yearClose, { yearClose = it }); Text("إقفال السنة (يحفظ كشف نهاية الفترة مع الجرد)") }
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
            SoftButton("احفظ مسودة", { vm.launchSafe("حُفظت المسودة") { vm.repo.saveInventory(inv, false, yearClose, revisionOf, "") } }, Modifier.weight(1f))
            PrimaryButton("اعتمد الجرد", { vm.launchSafe("اعتُمد الجرد") { vm.repo.saveInventory(inv, true, yearClose, revisionOf, ""); revisionOf = null } }, Modifier.weight(1f), enabled = inv.isComplete)
        }
        SoftButton("صدّر هذا الجرد", { export = inv; exportLabel = "مسودة غير محفوظة" }, Modifier.fillMaxWidth().padding(top = Sp.xs))

        SectionHeader("الجرود المحفوظة")
        val groups = view.s.inventories.groupBy { it.groupId }
        if (groups.isEmpty()) Text("لا جرد محفوظ بعد", color = c.mute)
        groups.values.sortedByDescending { g -> g.maxOf { it.date } }.forEach { g ->
            val latest = g.maxBy { it.revision }
            RCard(onClick = { openSaved = if (openSaved == latest.id) null else latest.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("جرد ${latest.date}", style = MaterialTheme.typography.titleMedium)
                        Text("مراجعة ${latest.revision}${if (latest.yearClose) " · إقفال سنة" else ""}", style = MaterialTheme.typography.bodySmall, color = c.mute)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        UsdText(BigDecimal(latest.pricedNetUsd), style = MaterialTheme.typography.titleSmall)
                        StatusChip(if (latest.status == "FINAL") "معتمد" else "مسودة", if (latest.status == "FINAL") Tone.OK else Tone.WARN)
                    }
                }
                if (openSaved == latest.id) {
                    val saved = savedInventory(latest, view.s.inventoryLines)
                    g.sortedByDescending { it.revision }.forEach { r -> Text("• مراجعة ${r.revision}: ${Fmt.usd(BigDecimal(r.pricedNetUsd), false)} — ${if (r.status == "FINAL") "معتمد" else "مسودة"}", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                        if (latest.status == "FINAL") PrimaryButton("الزكاة", { nav.navigate(Routes.zakat(latest.id)) }, Modifier.weight(1f), icon = Icons.Filled.VolunteerActivism)
                        SoftButton("مراجعة جديدة", { date = LocalDate.parse(latest.date); revisionOf = latest.groupId; confirmed = emptySet() }, Modifier.weight(1f))
                    }
                    SoftButton("تصدير", { export = saved; exportLabel = "INV-${latest.date} / مراجعة ${latest.revision}" }, Modifier.fillMaxWidth().padding(top = 6.dp))
                    HowLink { explain = saved.explain() }
                }
            }
            Spacer(Modifier.height(Sp.xs))
        }
        export?.let { e ->
            val doc = remember(e, exportLabel) { ReportBuilders.inventoryReport(e, nowStamp(), exportLabel, if (e.isComplete) "مكتمل" else "مسودة") }
            ExportPanel(vm, doc, false, false, false) { _, _ -> }
        }
    }
    explain?.let { ExplanationDialog(it, { explain = null }) }
}

@Composable
fun ZakatScreen(vm: MainViewModel, nav: NavController, inventoryId: String) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    val invE = view.s.inventories.firstOrNull { it.id == inventoryId } ?: return
    val inv = remember(view, inventoryId) { savedInventory(invE, view.s.inventoryLines) }
    var decisions by remember { mutableStateOf(inv.lines.associate { it.item.id to ZakatCalc.defaultDecision(it) }) }
    var nisabKind by rememberSaveable { mutableStateOf("gold") }
    var hawl by rememberSaveable { mutableStateOf("") }
    var hawlMethod by rememberSaveable { mutableStateOf("تاريخ اختاره المستخدم") }
    var hawlDone by rememberSaveable { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    var estimateId by rememberSaveable { mutableStateOf(view.s.zakatEstimates.firstOrNull { it.inventoryId == inventoryId }?.id) }
    var showNames by remember { mutableStateOf(false) }
    val nisab = if (nisabKind == "gold") NisabPolicy.Gold85 else NisabPolicy.Silver595
    val res = remember(decisions, nisabKind, hawl, hawlDone, hawlMethod, view) {
        ZakatCalc.compute(ZakatInput(inv, decisions, nisab, view.rates, hawl.ifBlank { "غير محدد" }, hawlMethod, hawlDone))
    }
    ScreenScaffold("الزكاة", { nav.popBackStack() }, sub = "من جرد ${invE.date} (مراجعة ${invE.revision}). تقدير مبني على خياراتك، وليس فتوى.") {
        RCard {
            Text("الحول الهجري", style = MaterialTheme.typography.titleMedium)
            TextIn(hawl, { hawl = it }, "تاريخ الحول (مثال 1447/09/01)")
            TextIn(hawlMethod, { hawlMethod = it }, "طريقة تحديده")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(hawlDone, { hawlDone = it }); Text("أؤكّد اكتمال الحول") }
            Text("النسبة الافتراضية للحول الهجري 2.5%", style = MaterialTheme.typography.bodySmall, color = c.mute)
        }
        Spacer(Modifier.height(Sp.sm))
        RCard {
            Text("النصاب", style = MaterialTheme.typography.titleMedium)
            ChipRow(listOf("gold", "silver"), nisabKind, { if (it == "gold") "85غ ذهب عيار 24" else "595غ فضة خالصة" }, { nisabKind = it })
            Text(res.nisabUsd?.let { "قيمة النصاب: ${Fmt.usd(it, false)} (${res.nisabRate?.label()} · ${res.nisabRate?.date})" } ?: "أدخل سعر ${Rate.unitLabel(nisab.metalUnit)} بتاريخ ${invE.date}", color = if (res.nisabUsd == null) c.warn else c.mute)
            if (res.nisabUsd == null) TextButton(onClick = { nav.navigate(Routes.RATES) }) { Text("افتح الأسعار") }
        }
        SectionHeader("البنود: الإدراج والسبب")
        inv.lines.forEach { l ->
            val d = decisions[l.item.id]!!
            RCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("${l.item.kind.ar}: ${l.item.label}", style = MaterialTheme.typography.titleSmall); Text(l.item.original, style = MaterialTheme.typography.bodySmall, color = c.mute) }
                    l.usd?.let { UsdText(it, style = MaterialTheme.typography.bodyMedium) }
                }
                ChipRow(Decision.values().toList(), d.decision, { it.ar }, { decisions = decisions + (l.item.id to d.copy(decision = it)) })
                if (d.decision == Decision.PARTIAL) TextIn(d.partialUsd?.toPlainString() ?: "", { s -> decisions = decisions + (l.item.id to d.copy(partialUsd = AmountParser.parse(s)?.value)) }, "المبلغ المحتسب بالدولار (لا يتجاوز المتبقي)")
                TextIn(d.reason, { s -> decisions = decisions + (l.item.id to d.copy(reason = s)) }, "السبب")
            }
            Spacer(Modifier.height(Sp.xs))
        }
        RCard(onClick = { explain = true }, tint = c.goldSoft) {
            KeyValue("الأموال والأصول المدرجة", Fmt.usd(res.assetsUsd, false))
            KeyValue("الديون التي لي المدرجة", Fmt.usd(res.receivablesUsd, false))
            KeyValue("الالتزامات المخصومة", Fmt.usd(res.deductionsUsd, false))
            KeyValue("الوعاء", Fmt.usd(res.baseUsd, false))
            HorizontalDivider(color = c.line)
            Text("الزكاة المقدّرة", color = c.mute)
            UsdText(res.zakatUsd, style = MaterialTheme.typography.headlineMedium, color = c.gold)
            StatusChip(res.status.ar, when (res.status) { ZakatStatus.DUE -> Tone.GOLD; ZakatStatus.BELOW_NISAB -> Tone.INFO; ZakatStatus.NEEDS_REVIEW -> Tone.WARN })
            res.reasons.forEach { Text("• $it", color = c.warn, style = MaterialTheme.typography.bodySmall) }
            HowLink { explain = true }
        }
        Spacer(Modifier.height(Sp.xs))
        PrimaryButton("ثبّت التقدير", { vm.launchSafe("ثُبّت تقدير الزكاة") {
            val dj = buildJsonObject { decisions.forEach { (k, d) -> putJsonObject(k) { put("decision", d.decision.name); put("partialUsd", d.partialUsd?.toPlainString()); put("reason", d.reason) } } }.toString()
            estimateId = vm.repo.saveZakat(inventoryId, res, dj)
        } }, Modifier.fillMaxWidth(), enabled = res.status != ZakatStatus.NEEDS_REVIEW)

        val est = view.s.zakatEstimates.firstOrNull { it.id == estimateId }
        if (est != null) ZakatDistribution(vm, nav, est, res, showNames) { showNames = it }
    }
    if (explain) ExplanationDialog(res.explain(), { explain = false })
}

@Composable
private fun ZakatDistribution(vm: MainViewModel, nav: NavController, est: ZakatEstimateEntity, res: ZakatResult, showNames: Boolean, onShowNames: (Boolean) -> Unit) {
    val c = LocalR.current
    val view = vm.view.collectAsStateWithLifecycle().value ?: return
    val due = BigDecimal(est.zakatUsd)
    val paidEntities = view.s.zakatPayments.filter { it.estimateId == est.id }
    val paid = paidEntities.map { p -> ZakatPayment(p.id, Money(p.amountMinor, Currencies.of(p.currency)), Rate("z", p.currency, BigDecimal(p.rateEntered), RateDirection.valueOf(p.rateDirection), LocalDate.parse(p.rateDate)), LocalDate.parse(p.date), p.accountId, p.beneficiary) }
    val prog = Distribution.progress(due, emptyList(), paid)
    var account by rememberSaveable { mutableStateOf(view.accounts.firstOrNull()?.id) }
    val amount = remember { AmountState() }
    var date by remember { mutableStateOf(vm.today()) }
    var beneficiary by rememberSaveable { mutableStateOf("") }
    val manualRate = remember { AmountState() }
    val rid = remember(paidEntities.size) { vm.newRequestId() }
    SectionHeader("جهّز التوزيع")
    RCard {
        KeyValue("المستحق المثبّت", Fmt.usd(due, false)); KeyValue("المدفوع", Fmt.usd(prog.paidUsd, false))
        Text(prog.remainingLabel + " USD", style = MaterialTheme.typography.headlineSmall, color = if (prog.remainingUsd.signum() <= 0) c.pri else c.gold)
        if (prog.overpaidUsd.signum() > 0) StatusChip("مدفوع زائد ${Fmt.usd(prog.overpaidUsd, false)}", Tone.INFO)
        LinearProgressIndicator(progress = { if (due.signum() == 0) 1f else (prog.paidUsd.toFloat() / due.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = c.gold, trackColor = c.soft)
    }
    // اقتراح بحصص متساوية بين عملات الحسابات
    val remaining = prog.remainingUsd
    if (remaining.signum() > 0) {
        val book = RateBook(view.rates)
        val curs = view.accounts.map { it.currency }.distinctBy { it.code }
        val rates = curs.mapNotNull { cur -> when (val l = book.lookup(cur.code, vm.today())) { is RateLookup.Exact -> Triple(BigDecimal.ONE.divide(BigDecimal(curs.size), 12, java.math.RoundingMode.HALF_EVEN), l.rate, cur); else -> null } }
        if (rates.isNotEmpty()) RCard {
            Text("خطة مقترحة بحصص متساوية (أسعار اليوم)", style = MaterialTheme.typography.titleSmall)
            val shares = rates.mapIndexed { i, t -> if (i == rates.lastIndex) t.copy(first = BigDecimal.ONE - rates.dropLast(1).fold(BigDecimal.ZERO) { a, x -> a + x.first }) else t }
            val plan = Distribution.planByShares(remaining, shares)
            plan.forEach { p -> KeyValue(Fmt.money(p.amount, false), "≈ ${Fmt.usd(p.usd, false)}") }
            val tot = plan.fold(BigDecimal.ZERO) { a, p -> a + p.usd }
            Text("فرق التقريب: ${Fmt.usd(tot - remaining, false)} (عُدّلت الدفعة الأخيرة لتغطيته)", style = MaterialTheme.typography.bodySmall, color = c.mute)
        }
    }
    SectionHeader("سجّل دفعة منفّذة")
    RCard {
        ChipRow(view.accounts.map { it.id }, account, { id -> view.accountById[id]!!.let { "${it.name} · ${it.currency.code}" } }, { account = it })
        val acc = view.accountById[account]
        AmountField(amount, acc?.currency)
        DateField(date, { date = it }, "تاريخ الدفع")
        val lookup = acc?.let { RateBook(view.rates).lookup(it.currency.code, date) }
        val rate: Rate? = when {
            acc == null -> null
            lookup is RateLookup.Exact -> lookup.rate
            else -> AmountParser.parse(manualRate.text)?.value?.takeIf { it.signum() > 0 }?.let { Rate("m", acc.currency.code, it, RateDirection.UNITS_PER_USD, date, "يدوي عند الدفع") }
        }
        if (acc != null && lookup !is RateLookup.Exact) { Text("لا سعر ${acc.currency.code} بتاريخ $date؛ أدخل سعر يوم الدفع", color = c.warn); AmountField(manualRate, null, "1 USD = ? ${acc.currency.code}") }
        val m = amount.parse(acc?.currency)
        if (m != null && rate != null) Text("= ${Fmt.usd(rate.toUsd(m.amount), false)} بسعر ${rate.label()}", color = c.mute)
        TextIn(beneficiary, { beneficiary = it }, "المستفيد أو الجهة (اختياري، مختصر)")
        PrimaryButton("سجّل الدفعة (مصروف «زكاة»)", enabled = m != null && m.minor > 0 && rate != null, modifier = Modifier.fillMaxWidth(), onClick = {
            vm.launchSafe("سُجّلت الدفعة") { vm.repo.addZakatPayment(est.id, acc!!.id, m!!.minor, rate!!, date, beneficiary, rid); amount.text = "" }
        })
    }
    if (paid.isNotEmpty()) {
        SectionHeader("الدفعات")
        RCard {
            paid.sortedByDescending { it.date }.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) { Text(p.date.toString()); Text(p.rate.label(), style = MaterialTheme.typography.bodySmall, color = c.mute) }
                    Column(horizontalAlignment = Alignment.End) { AmountText(p.amount, style = MaterialTheme.typography.bodyMedium); UsdText(p.usd, style = MaterialTheme.typography.bodySmall, color = c.mute) }
                }
            }
        }
    }
    Spacer(Modifier.height(Sp.sm))
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(showNames, onShowNames); Text("أظهر أسماء المستفيدين في التصدير") }
    val doc = remember(res, paid, showNames) { ReportBuilders.zakatReport(res, paid, nowStamp(), "INV-${res.input.inventory.date}", view.accounts.associate { it.id to it.name }, showNames) }
    ExportPanel(vm, doc, false, false, false) { _, _ -> }
}
