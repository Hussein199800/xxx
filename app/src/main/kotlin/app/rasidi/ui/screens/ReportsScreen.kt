package app.rasidi.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.rasidi.core.reports.PrivacyOptions
import app.rasidi.core.reports.ReportBuilders
import app.rasidi.core.reports.display
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp

enum class ReportKind(val ar: String) { DAY("يومي"), MONTH("شهري"), YEAR("سنوي"), CUSTOM("فترة مخصصة"), STATEMENT("كشف حساب") }

@Composable
fun ReportsScreen(vm: MainViewModel, nav: NavController, kindArg: String, ref: String) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    val today = vm.today()
    var kind by rememberSaveable { mutableStateOf(when (kindArg) { "month" -> ReportKind.MONTH; "year" -> ReportKind.YEAR; "statement" -> ReportKind.STATEMENT; else -> ReportKind.MONTH }) }
    var anchor by remember { mutableStateOf(today) }
    var from by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var to by remember { mutableStateOf(today) }
    var account by rememberSaveable { mutableStateOf(ref.ifBlank { view.accounts.firstOrNull()?.id }) }
    var detailed by rememberSaveable { mutableStateOf(true) }
    var hideNames by remember { mutableStateOf(false) }; var hideNotes by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    val (f, t) = when (kind) {
        ReportKind.DAY -> anchor to anchor
        ReportKind.MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
        ReportKind.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
        ReportKind.CUSTOM, ReportKind.STATEMENT -> from to to
    }
    val doc = remember(view, kind, f, t, account, detailed, hideNames, hideNotes) {
        val p = PrivacyOptions(hideNames, hideNotes)
        if (kind == ReportKind.STATEMENT) account?.let { ReportBuilders.accountStatement(view, it, f, t, nowStamp(), p) }
        else ReportBuilders.periodReport(view, f, t, nowStamp(), kind.ar, p, detailed)
    }
    ScreenScaffold("التقارير والتصدير", { nav.popBackStack() }, sub = "تاريخ العملية وسعرها أساس التقارير التاريخية") {
        ChipRow(ReportKind.values().toList(), kind, { it.ar }, { kind = it })
        Spacer(Modifier.height(Sp.sm))
        when (kind) {
            ReportKind.DAY, ReportKind.MONTH, ReportKind.YEAR -> DateField(anchor, { anchor = it }, "ضمن")
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DateField(from, { from = it }, "من", Modifier.weight(1f)); DateField(to, { to = it }, "إلى", Modifier.weight(1f)) }
        }
        if (kind == ReportKind.STATEMENT) { Spacer(Modifier.height(Sp.xs)); ChipRow(view.accounts.map { it.id }, account, { id -> view.accountById[id]!!.let { "${it.name} · ${it.currency.code}" } }, { account = it }) }
        else ChipRow(listOf(true, false), detailed, { if (it) "تفصيلي" else "ملخّص" }, { detailed = it })
        Spacer(Modifier.height(Sp.sm))
        doc?.let { d ->
            RCard {
                Text(d.title, style = MaterialTheme.typography.titleLarge, color = c.pri)
                Text(d.period, color = c.mute)
                d.summary.forEach { (k, v) -> KeyValue(k, v.display()) }
                HowLink { explain = true }
                d.tables.filter { it.keepInSummary }.forEach { tb ->
                    Text(tb.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Sp.xs))
                    tb.rows.forEach { r -> KeyValue(r.first().display(), r.drop(1).joinToString("  ") { it.display() }) }
                }
            }
            Spacer(Modifier.height(Sp.sm))
        }
        ExportPanel(vm, doc, true, hideNames, hideNotes) { a, b -> hideNames = a; hideNotes = b }
    }
    if (explain) {
        // كيف انحسب تقرير الفترة: كل قيد دخل/مصروف بعملته الأصلية مع رابط حركته
        val es = view.entries.filter { !it.date.isBefore(f) && !it.date.isAfter(t) && (it.kind.countsAsIncome || it.kind.countsAsExpense) && (kind != ReportKind.STATEMENT || it.accountId == account) }
        val e = app.rasidi.core.explain.Explanation(
            title = "كيف انحسب ${doc?.title ?: "التقرير"}",
            result = doc?.summary?.joinToString(" · ") { "${it.first}: ${it.second.display()}" } ?: "",
            formula = "الدخل = مجموع قيود الدخل؛ المصروف = قيود المصروف والرسوم والزكاة؛ الصافي = الدخل − المصروف لكل عملة على حدة. التحويل الداخلي والرصيد الافتتاحي وتسوية الديون مستبعدة. لا تحويل بين العملات في تقرير الفترة.",
            items = es.sortedBy { it.date }.take(300).map { x -> app.rasidi.core.explain.Explanation.Item("${x.date} · ${ReportBuilders.kindAr(x.kind)} · ${view.accountById[x.accountId]?.name}", app.rasidi.core.money.Fmt.money(x.amount), note = x.categoryId?.let { view.categoryNames[it] }, refIds = listOf(x.txnId)) },
            pending = if (es.size > 300) listOf("يظهر أول 300 قيد من ${es.size}؛ التصدير التفصيلي يشمل الكل") else emptyList(),
            asOf = "$f – $t",
        )
        ExplanationDialog(e, { explain = false }) { id -> explain = false; nav.navigate(app.rasidi.ui.Routes.txn(id)) }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
