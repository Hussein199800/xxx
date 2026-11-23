package app.rasidi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.ui.*
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import java.time.LocalDate

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val c = LocalR.current
    Column(Modifier.fillMaxSize().background(c.bg).padding(Sp.lg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(Icons.Filled.Lock, c.pri, 72.dp)
        Spacer(Modifier.height(Sp.md))
        Text("رصيدي مقفل", style = MaterialTheme.typography.headlineSmall, color = c.txt)
        Spacer(Modifier.height(Sp.md))
        PrimaryButton("افتح بالبصمة أو رمز الجهاز", onUnlock, icon = Icons.Filled.Fingerprint)
    }
}

@Composable
fun TxnRowItem(r: TxnRow, onClick: () -> Unit) {
    val c = LocalR.current
    val (icon, tint) = when (r.type) {
        TxnType.INCOME -> Icons.Filled.Payments to c.pri
        TxnType.EXPENSE -> Icons.Filled.ShoppingBag to c.danger
        TxnType.TRANSFER -> Icons.Filled.SwapHoriz to c.mute
        TxnType.DEBT_NEW, TxnType.DEBT_PAYMENT -> Icons.Filled.Handshake to c.gold
        TxnType.GOLD_BUY, TxnType.GOLD_SELL -> Icons.Filled.Diamond to c.gold
        TxnType.ZAKAT -> Icons.Filled.VolunteerActivism to c.gold
        TxnType.REVERSAL -> Icons.Filled.Undo to c.mute
        else -> Icons.Filled.Tune to c.mute
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint, 40.dp)
        Spacer(Modifier.width(Sp.sm))
        Column(Modifier.weight(1f)) {
            Text(r.note.ifBlank { r.categoryName ?: r.type.ar }, style = MaterialTheme.typography.titleSmall, color = c.txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(r.categoryName?.takeIf { r.note.isNotBlank() }, r.accountNames.joinToString(" ← "), r.party.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.reversed) StatusChip("معكوسة", Tone.INFO)
        }
        Column(horizontalAlignment = Alignment.End) {
            val main = if (r.type == TxnType.TRANSFER) r.amounts.filter { it.minor < 0 }.take(1) + r.amounts.filter { it.minor > 0 }.take(1) else r.amounts.take(2)
            main.forEach { AmountText(it, style = MaterialTheme.typography.titleSmall, signColor = r.type != TxnType.TRANSFER) }
        }
    }
}

@Composable
fun HomeScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val home by vm.home.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    var explain by remember { mutableStateOf(false) }
    val h = home ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = Sp.md).padding(bottom = 170.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = Sp.xs), verticalAlignment = Alignment.CenterVertically) {
            Text("رصيدي", style = MaterialTheme.typography.titleMedium, color = c.pri, modifier = Modifier.weight(1f))
            if (demo) StatusChip("دفتر تجريبي", Tone.GOLD)
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Settings, "الإعدادات", tint = c.mute) }
        }
        BigTitle("أموالي اليوم", sub = ReportDate.ar(vm.today()))
        if (vm.device.driveRelinkNeeded) {
            RCard(tint = c.warnSoft, onClick = { nav.navigate(Routes.BACKUP) }) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.CloudOff, null, tint = c.warn); Spacer(Modifier.width(8.dp)); Text("أعد ربط Google لاستئناف النسخ التلقائي", color = c.warn, style = MaterialTheme.typography.titleSmall) }
            }
            Spacer(Modifier.height(Sp.sm))
        }
        // صافي الأموال
        val inv = h.netWorth.inventory
        RCard(onClick = { explain = true }, contentDesc = "صافي الأموال التقديري") {
            Text("صافي الأموال التقديري", style = MaterialTheme.typography.labelLarge, color = c.mute)
            UsdText(inv.pricedNet, style = MaterialTheme.typography.displaySmall, color = c.pri)
            if (!inv.isComplete) StatusChip("إجمالي جزئي: ${inv.missingCount} بند بلا سعر", Tone.WARN)
            Spacer(Modifier.height(Sp.xs))
            h.netWorth.byCurrency.toSortedMap().forEach { (_, m) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.currency.nameAr, color = c.mute, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    AmountText(m, style = MaterialTheme.typography.titleSmall)
                }
            }
            HowLink { explain = true }
        }
        Spacer(Modifier.height(Sp.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.sm)) {
            RCard(Modifier.weight(1f), onClick = { nav.navigate(Routes.reports("month")) }) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.TrendingUp, null, tint = c.pri); Spacer(Modifier.width(6.dp)); Text("دخل الشهر", style = MaterialTheme.typography.labelLarge, color = c.mute) }
                if (h.monthIncome.isEmpty()) Text("—", style = MaterialTheme.typography.titleMedium, color = c.mute)
                h.monthIncome.values.forEach { AmountText(it, color = c.pri) }
            }
            RCard(Modifier.weight(1f), onClick = { nav.navigate(Routes.reports("month")) }) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.TrendingDown, null, tint = c.danger); Spacer(Modifier.width(6.dp)); Text("مصروف الشهر", style = MaterialTheme.typography.labelLarge, color = c.mute) }
                if (h.monthExpense.isEmpty()) Text("—", style = MaterialTheme.typography.titleMedium, color = c.mute)
                h.monthExpense.values.forEach { AmountText(it, color = c.danger) }
            }
        }
        Spacer(Modifier.height(Sp.sm))
        h.nearestDue?.let { (d, due, rem) ->
            val late = due.isBefore(vm.today())
            RCard(onClick = { nav.navigate(Routes.debt(d.id)) }, tint = if (late) c.dangerSoft else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Event, if (late) c.danger else c.gold)
                    Spacer(Modifier.width(Sp.sm))
                    Column(Modifier.weight(1f)) {
                        Text("أقرب استحقاق", style = MaterialTheme.typography.labelLarge, color = c.mute)
                        Text("${d.party} — ${if (d.direction == DebtDirection.I_OWE) "عليّ" else "لي"}", style = MaterialTheme.typography.titleSmall, color = c.txt)
                        Text(due.toString(), style = MaterialTheme.typography.bodySmall, color = if (late) c.danger else c.mute)
                    }
                    Column(horizontalAlignment = Alignment.End) { AmountText(rem); if (late) StatusChip("متأخر", Tone.DANGER) }
                }
            }
        }
        SectionHeader("آخر الحركات", "الكل") { nav.navigate(Routes.TXNS) }
        if (h.recent.isEmpty()) EmptyState(Icons.Filled.ReceiptLong, "لا حركات بعد", "سجّل أول مصروف من زر الإضافة", "سجّل مصروفاً") { nav.navigate(Routes.add("expense")) }
        else RCard { h.recent.forEach { TxnRowItem(it) { nav.navigate(Routes.txn(it.id)) } } }
    }
    if (explain) ExplanationDialog(h.netWorth.inventory.explain().copy(title = "صافي الأموال التقديري"), { explain = false }) { ref -> explain = false; vm.view.value?.accountById?.get(ref)?.let { nav.navigate(Routes.account(it.id)) } ?: nav.navigate(Routes.debt(ref)) }
}

object ReportDate { fun ar(d: LocalDate) = app.rasidi.core.reports.ReportBuilders.arDate(d) }

enum class PeriodF(val ar: String) { MONTH("هذا الشهر"), LAST_MONTH("الشهر الماضي"), YEAR("هذه السنة"), ALL("الكل") }

@Composable
fun TransactionsScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var q by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(PeriodF.MONTH) }
    var type by remember { mutableStateOf<TxnType?>(null) }
    var account by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var currency by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableIntStateOf(200) }
    val today = vm.today()
    val (from, to) = when (period) {
        PeriodF.MONTH -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
        PeriodF.LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1) to today.minusMonths(1).let { it.withDayOfMonth(it.lengthOfMonth()) }
        PeriodF.YEAR -> today.withDayOfYear(1) to today.withDayOfYear(today.lengthOfYear())
        PeriodF.ALL -> LocalDate.of(1900, 1, 1) to LocalDate.of(2999, 1, 1)
    }
    val filtered = remember(view, q, period, type, account, category, currency) {
        val byTxn = view.entries.groupBy { it.txnId }
        view.s.txns.filter { t ->
            val d = LocalDate.parse(t.date)
            val es = byTxn[t.id].orEmpty()
            !d.isBefore(from) && !d.isAfter(to) &&
                (type == null || t.type == type!!.name) &&
                (account == null || es.any { it.accountId == account }) &&
                (category == null || es.any { it.categoryId == category }) &&
                (currency == null || es.any { it.amount.currency.code == currency }) &&
                (q.isBlank() || t.note.contains(q, true) || t.party.contains(q, true) || es.any { e -> view.categoryNames[e.categoryId]?.contains(q, true) == true })
        }.sortedWith(compareByDescending<app.rasidi.core.data.TxnEntity> { it.date }.thenByDescending { it.createdAtUtc })
    }
    // ملخص يطابق الفلاتر: دخل ومصروف بالعملات الأصلية
    val ids = filtered.map { it.id }.toSet()
    val totals = remember(filtered, account, currency) { PeriodTotals.of(view.entries.filter { it.txnId in ids && (account == null || it.accountId == account) && (currency == null || it.amount.currency.code == currency) }, LocalDate.of(1900, 1, 1), LocalDate.of(2999, 1, 1)) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = Sp.md, end = Sp.md, bottom = 170.dp)) {
        item { BigTitle("الحركات") }
        item {
            OutlinedTextField(q, { q = it }, placeholder = { Text("ابحث في البيان أو الطرف أو الفئة") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Sp.xs))
            ChipRow(PeriodF.values().toList(), period, { it.ar }, { period = it })
            Spacer(Modifier.height(6.dp))
            ChipRow(listOf<TxnType?>(null, TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER, TxnType.DEBT_PAYMENT, TxnType.DEBT_NEW, TxnType.ZAKAT, TxnType.GOLD_BUY), type, { it?.ar ?: "كل الأنواع" }, { type = it })
            Spacer(Modifier.height(6.dp))
            ChipRow(listOf<String?>(null) + view.accounts.map { it.id }, account, { id -> id?.let { view.accountById[it]?.name } ?: "كل الحسابات" }, { account = it })
            Spacer(Modifier.height(6.dp))
            ChipRow(listOf<String?>(null) + view.s.categories.filter { !it.archived }.map { it.id }, category, { id -> id?.let { view.categoryNames[it] } ?: "كل الفئات" }, { category = it })
            Spacer(Modifier.height(6.dp))
            ChipRow(listOf<String?>(null) + view.accounts.map { it.currency.code }.distinct(), currency, { it ?: "كل العملات" }, { currency = it })
        }
        item {
            RCard(Modifier.padding(vertical = Sp.sm)) {
                Text("${filtered.size} حركة ضمن الفلاتر", style = MaterialTheme.typography.labelLarge, color = c.mute)
                (totals.income.keys + totals.expense.keys).toSortedSet().forEach { code ->
                    val cur = Currencies.of(code)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(code, color = c.mute, modifier = Modifier.width(56.dp))
                        Text("دخل ", color = c.mute, style = MaterialTheme.typography.bodySmall); AmountText(totals.income[code] ?: Money.zero(cur), style = MaterialTheme.typography.bodyMedium, color = c.pri)
                        Spacer(Modifier.width(12.dp))
                        Text("مصروف ", color = c.mute, style = MaterialTheme.typography.bodySmall); AmountText(totals.expense[code] ?: Money.zero(cur), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                    }
                }
            }
        }
        val shown = filtered.take(limit)
        val groups = shown.groupBy { it.date }
        groups.forEach { (day, list) ->
            item(key = "h$day") { Text(ReportDate.ar(LocalDate.parse(day)), style = MaterialTheme.typography.titleSmall, color = c.mute, modifier = Modifier.padding(top = Sp.sm, bottom = 4.dp)) }
            items(vm.rows(view, list), key = { it.id }) { r -> TxnRowItem(r) { nav.navigate(Routes.txn(r.id)) } }
        }
        if (filtered.isEmpty()) item { EmptyState(Icons.Filled.SearchOff, "لا نتائج", "غيّر الفترة أو الفلاتر") }
        if (filtered.size > limit) item { SoftButton("اعرض المزيد (${filtered.size - limit})", { limit += 200 }, Modifier.fillMaxWidth().padding(top = Sp.sm)) }
    }
}

@Composable
fun DebtsScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val rows by vm.debts.collectAsStateWithLifecycle()
    var dir by remember { mutableStateOf(DebtDirection.OWED_TO_ME) }
    var showSettled by remember { mutableStateOf(false) }
    val list = rows.filter { it.debt.direction == dir && !it.archived && (showSettled || it.status != DebtStatus.SETTLED) }.sortedWith(compareBy({ it.status == DebtStatus.SETTLED }, { it.nextDue ?: LocalDate.MAX }))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = Sp.md).padding(bottom = 170.dp)) {
        BigTitle("الديون")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(dir == DebtDirection.OWED_TO_ME, { dir = DebtDirection.OWED_TO_ME }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("لي عند الناس") }
            SegmentedButton(dir == DebtDirection.I_OWE, { dir = DebtDirection.I_OWE }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("عليّ للناس") }
        }
        Spacer(Modifier.height(Sp.sm))
        val totals = rows.filter { it.debt.direction == dir && !it.archived }.groupBy { it.remaining.currency.code }.mapValues { (_, l) -> l.map { it.remaining }.reduce { a, b -> a + b } }
        RCard(tint = if (dir == DebtDirection.I_OWE) c.dangerSoft else c.priSoft) {
            Text(if (dir == DebtDirection.OWED_TO_ME) "مجموع ما لي" else "مجموع ما عليّ", style = MaterialTheme.typography.labelLarge, color = c.mute)
            if (totals.isEmpty()) Text("لا شيء", style = MaterialTheme.typography.titleLarge, color = c.mute)
            totals.values.forEach { AmountText(it, style = MaterialTheme.typography.headlineSmall, color = if (dir == DebtDirection.I_OWE) c.danger else c.pri) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Sp.xs)) {
            Switch(showSettled, { showSettled = it }); Spacer(Modifier.width(8.dp)); Text("أظهر المسدّد", color = c.mute)
        }
        if (list.isEmpty()) EmptyState(Icons.Filled.Handshake, "لا ديون هنا", "سجّل ديناً جديداً أو قديماً دون حركة نقدية", "دين جديد") { nav.navigate(Routes.add("debt")) }
        list.forEach { r ->
            Spacer(Modifier.height(Sp.xs))
            RCard(onClick = { nav.navigate(Routes.debt(r.debt.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.debt.party, style = MaterialTheme.typography.titleMedium, color = c.txt)
                        r.nextDue?.let { Text("الاستحقاق: \u2066$it\u2069", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        AmountText(r.remaining)
                        StatusChip(r.status.ar, when (r.status) { DebtStatus.SETTLED -> Tone.OK; DebtStatus.OVERDUE -> Tone.DANGER; DebtStatus.PARTIAL -> Tone.GOLD; DebtStatus.OPEN -> Tone.INFO })
                    }
                }
                if (r.debt.principal.minor > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { (r.paid.minor.toFloat() / r.debt.principal.minor).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = c.pri, trackColor = c.soft)
                    Text("سُدّد ${Fmt.money(r.paid)} من ${Fmt.money(r.debt.principal)}", style = MaterialTheme.typography.bodySmall, color = c.mute)
                }
            }
        }
    }
}

@Composable
fun MoreScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val items: List<Triple<String, ImageVector, Pair<String, String>>> = listOf(
        Triple(Routes.ACCOUNTS, Icons.Filled.AccountBalanceWallet, "الحسابات" to "المحافظ وكشوف الحساب"),
        Triple(Routes.GOLD, Icons.Filled.Diamond, "الذهب" to "القطع والوزن الخالص"),
        Triple(Routes.reports(), Icons.Filled.Description, "التقارير والتصدير" to "Excel وWord وPDF"),
        Triple(Routes.INVENTORY, Icons.Filled.Inventory2, "الجرد والزكاة" to "صافي الأموال بالدولار"),
        Triple(Routes.RATES, Icons.Filled.CurrencyExchange, "الأسعار" to "العملات وغرام الذهب"),
        Triple(Routes.BACKUP, Icons.Filled.CloudUpload, "النسخ الاحتياطي" to "ملف مشفّر وGoogle Drive"),
        Triple(Routes.CATEGORIES, Icons.Filled.Category, "الفئات" to "إضافة وأرشفة"),
        Triple(Routes.SETTINGS, Icons.Filled.Settings, "الإعدادات" to "المظهر والقفل والخصوصية"),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = Sp.md).padding(bottom = 170.dp)) {
        BigTitle("المزيد")
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.sm), modifier = Modifier.padding(bottom = Sp.sm)) {
                row.forEach { (route, icon, t) ->
                    val tint = if (route == Routes.GOLD || route == Routes.INVENTORY) c.gold else c.pri
                    RCard(Modifier.weight(1f).heightIn(min = 128.dp), onClick = { nav.navigate(route) }) {
                        IconBadge(icon, tint)
                        Spacer(Modifier.height(Sp.xs))
                        Text(t.first, style = MaterialTheme.typography.titleMedium, color = c.txt)
                        Text(t.second, style = MaterialTheme.typography.bodySmall, color = c.mute)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
