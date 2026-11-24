package app.rasidi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

/** قائمة الأشخاص (أطراف الديون) برصيد كل منهم: عليه أو له. */
@Composable
fun PartiesList(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v = vm.view.collectAsStateWithLifecycle().value ?: return
    val parties = remember(v) { PartyLedger.parties(v) }
    if (parties.isEmpty()) { EmptyState(Icons.Filled.Groups, "لا أشخاص بعد", "يظهر هنا كل من له أو عليه دين", "دين جديد") { nav.navigate(Routes.add("debt")) }; return }
    parties.forEach { p ->
        RCard(onClick = { nav.navigate(Routes.party(p.party)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Person, CardTint.sky)
                Spacer(Modifier.width(Sp.sm))
                Column(Modifier.weight(1f)) {
                    Text(p.party, style = MaterialTheme.typography.titleMedium)
                    Text("آخر حركة ${ReportDate.ar(p.lastDate)}", style = MaterialTheme.typography.bodySmall, color = c.mute)
                }
                Column(horizontalAlignment = Alignment.End) {
                    p.balances.values.filter { !it.isZero }.ifEmpty { p.balances.values.take(1) }.forEach { b ->
                        Text(if (b.minor > 0) "عليه" else if (b.minor < 0) "له" else "مسدّد", style = MaterialTheme.typography.labelSmall, color = if (b.minor > 0) c.danger else c.pri)
                        AmountText(b.abs(), style = MaterialTheme.typography.titleSmall, color = if (b.minor > 0) c.danger else c.pri)
                    }
                }
            }
        }
        Spacer(Modifier.height(Sp.xs))
    }
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
        StPeriod.ALL -> LocalDate.of(1900, 1, 1) to LocalDate.of(2999, 12, 31)
    }
    fun shift(n: Long) { anchor = when (period) { StPeriod.DAY -> anchor.plusDays(n); StPeriod.MONTH -> anchor.plusMonths(n); StPeriod.YEAR -> anchor.plusYears(n); StPeriod.ALL -> anchor } }
    val st = remember(view, party, cur, from, to, prior) { PartyLedger.statement(view, party, cur, from, to, prior && period != StPeriod.ALL) }
    val currency = Currencies.of(cur)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageTopBar(party, { nav.popBackStack() }, actions = {
            IconButton(onClick = { export = !export }, Modifier.size(48.dp)) { Icon(Icons.Filled.Print, "طباعة وتصدير الكشف", tint = c.txt) }
        })
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
            val doc = remember(view, party, cur, from, to, prior) { ReportBuilders.partyStatement(view, party, cur, if (period == StPeriod.ALL) st.lines.firstOrNull()?.date ?: vm.today() else from, if (period == StPeriod.ALL) vm.today() else to, prior && period != StPeriod.ALL, nowStamp(), s.get("statement.footer")) }
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
