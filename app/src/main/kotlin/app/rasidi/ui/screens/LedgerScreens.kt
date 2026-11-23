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
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.core.reports.PrivacyOptions
import app.rasidi.core.reports.ReportBuilders
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.Routes
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun AccountsScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var adding by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    ScreenScaffold("الحسابات", { nav.popBackStack() }, actions = { IconButton(onClick = { adding = !adding }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Add, "حساب جديد", tint = c.pri) } }) {
        if (adding) { NewAccountCard(vm) { adding = false }; Spacer(Modifier.height(Sp.sm)) }
        view.accounts.filter { showArchived || !it.archived }.forEach { a ->
            val b = view.balance(a)
            RCard(onClick = { nav.navigate(Routes.account(a.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(when (a.type) { AccountType.CASH -> Icons.Filled.Payments; AccountType.BANK -> Icons.Filled.AccountBalance; AccountType.EWALLET -> Icons.Filled.PhoneAndroid; AccountType.SAVINGS -> Icons.Filled.Savings }, c.pri)
                    Spacer(Modifier.width(Sp.sm))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium)
                        Text("${a.type.ar} · ${a.currency.nameAr}", style = MaterialTheme.typography.bodySmall, color = c.mute)
                        if (a.archived) StatusChip("مؤرشف", Tone.INFO)
                    }
                    AmountText(b, signColor = b.isNegative)
                }
            }
            Spacer(Modifier.height(Sp.xs))
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(showArchived, { showArchived = it }); Spacer(Modifier.width(8.dp)); Text("أظهر المؤرشفة") }
        Text("لكل حساب عملة واحدة. إذا كان البنك نفسه يحتفظ بعملتين افتح حسابين.", style = MaterialTheme.typography.bodySmall, color = c.mute)
    }
}

@Composable
fun NewAccountCard(vm: MainViewModel, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(AccountType.CASH) }
    var cur by rememberSaveable { mutableStateOf("USD") }
    var neg by rememberSaveable { mutableStateOf(false) }
    val opening = remember { AmountState() }
    var date by remember { mutableStateOf(vm.today()) }
    val rid = rememberSaveable { vm.newRequestId() }
    RCard {
        Text("حساب جديد", style = MaterialTheme.typography.titleMedium)
        TextIn(name, { name = it }, "الاسم")
        Spacer(Modifier.height(6.dp))
        ChipRow(AccountType.values().toList(), type, { it.ar }, { type = it })
        Spacer(Modifier.height(6.dp))
        ChipRow(Currencies.catalog.map { it.code }, cur, { Currencies.of(it).nameAr }, { cur = it })
        AmountField(opening, Currencies.of(cur), "الرصيد الافتتاحي")
        DateField(date, { date = it }, "تاريخ الرصيد")
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(neg, { neg = it }); Spacer(Modifier.width(8.dp)); Text("يسمح بالرصيد السالب (سحب على المكشوف)") }
        val m = if (opening.text.isBlank()) Money.zero(Currencies.of(cur)) else opening.parse(Currencies.of(cur))
        PrimaryButton("أضف", enabled = name.isNotBlank() && m != null, modifier = Modifier.fillMaxWidth(), onClick = {
            vm.launchSafe("أُضيف الحساب") { vm.repo.createAccount(name, type, Currencies.of(cur), m!!.minor, date, neg, rid); onDone() }
        })
    }
}

@Composable
fun AccountDetailScreen(vm: MainViewModel, nav: NavController, id: String) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    val a = view.accountById[id] ?: return
    val today = vm.today()
    var from by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var to by remember { mutableStateOf(today.withDayOfMonth(today.lengthOfMonth())) }
    var count by remember { mutableStateOf(false) }
    val counted = remember { AmountState() }
    var export by remember { mutableStateOf(false) }
    var hideNames by remember { mutableStateOf(false) }; var hideNotes by remember { mutableStateOf(false) }
    val rid = rememberSaveable { vm.newRequestId() }
    val st = remember(view, from, to) { Balances.statement(a, view.entries, from, to) }
    ScreenScaffold(a.name, { nav.popBackStack() }, sub = "${a.type.ar} · ${a.currency.nameAr}") {
        RCard {
            Text("الرصيد الحالي", color = c.mute, style = MaterialTheme.typography.labelLarge)
            AmountText(view.balance(a), style = MaterialTheme.typography.headlineMedium, color = c.pri)
        }
        SectionHeader("كشف الحساب")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DateField(from, { from = it }, "من", Modifier.weight(1f)); DateField(to, { to = it }, "إلى", Modifier.weight(1f)) }
        Spacer(Modifier.height(Sp.xs))
        RCard {
            KeyValue("الرصيد قبل الفترة", Fmt.money(st.opening))
            st.lines.reversed().take(100).forEach { (e, run) ->
                HorizontalDivider(color = c.line)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(view.txnById[e.txnId]?.note?.ifBlank { null } ?: ReportBuilders.kindAr(e.kind), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(e.date.toString(), style = MaterialTheme.typography.bodySmall, color = c.mute)
                    }
                    Column(horizontalAlignment = Alignment.End) { AmountText(e.amount, style = MaterialTheme.typography.bodyMedium, signColor = true); AmountText(run, style = MaterialTheme.typography.bodySmall, color = c.mute) }
                }
            }
            if (st.lines.size > 100) Text("يظهر آخر 100 قيد؛ التصدير يشمل الكل (${st.lines.size})", color = c.mute, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = c.line)
            KeyValue("الرصيد في نهاية الفترة", Fmt.money(st.closing))
        }
        Spacer(Modifier.height(Sp.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
            SoftButton("تصدير الكشف", { export = !export }, Modifier.weight(1f), icon = Icons.Filled.IosShare)
            SoftButton("عدّ يدوي", { count = !count }, Modifier.weight(1f), icon = Icons.Filled.Calculate)
        }
        if (export) {
            val doc = remember(view, from, to, hideNames, hideNotes) { ReportBuilders.accountStatement(view, id, from, to, nowStamp(), PrivacyOptions(hideNames, hideNotes)) }
            ExportPanel(vm, doc, true, hideNames, hideNotes) { x, y -> hideNames = x; hideNotes = y }
        }
        if (count) {
            RCard {
                Text("أدخل ما عددته فعلياً اليوم. يظهر الفرق ويُسجَّل بتسوية مستقلة عند تأكيدك.", color = c.mute)
                AmountField(counted, a.currency, "المبلغ المعدود")
                counted.parse(a.currency)?.let { m ->
                    val diff = m - view.balance(a, today)
                    Text("الفرق: ${Fmt.money(diff)}", color = if (diff.isZero) c.pri else c.warn, style = MaterialTheme.typography.titleMedium)
                    PrimaryButton("سجّل التسوية", enabled = !diff.isZero, onClick = { vm.launchSafe("سُجّلت التسوية") { vm.repo.adjustToCount(id, m.minor, today, rid); count = false } })
                }
            }
        }
        val e = view.s.accounts.first { it.id == id }
        Spacer(Modifier.height(Sp.sm))
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(e.allowNegative, { vm.launchSafe { vm.repo.updateAccount(e.copy(allowNegative = it)) } }); Spacer(Modifier.width(8.dp)); Text("يسمح بالرصيد السالب") }
        TextButton(onClick = { vm.launchSafe(if (e.archived) "أُعيد الحساب" else "أُرشف الحساب مع حفظ سجله") { vm.repo.updateAccount(e.copy(archived = !e.archived)) } }) { Text(if (e.archived) "إلغاء الأرشفة" else "أرشفة الحساب") }
    }
}

@Composable
fun GoldScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var adding by remember { mutableStateOf(false) }
    var trade by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val rate = (app.rasidi.core.money.RateBook(view.rates).lookup(Rate.GOLD24_GRAM, vm.today()) as? app.rasidi.core.money.RateLookup.Exact)?.rate
        ?: view.rates.filter { it.unit == Rate.GOLD24_GRAM && !it.date.isAfter(vm.today()) }.maxByOrNull { it.date }
    ScreenScaffold("الذهب", { nav.popBackStack() }, actions = { IconButton(onClick = { adding = !adding }, Modifier.size(48.dp)) { Icon(Icons.Filled.Add, "قطعة جديدة", tint = c.gold) } },
        sub = rate?.let { "سعر غرام عيار 24: ${Fmt.plain(it.entered)} USD (\u2066\u2066${it.date}\u2069\u2069)" } ?: "لا يوجد سعر لغرام الذهب؛ أضفه من «الأسعار»") {
        if (adding) NewGoldCard(vm) { adding = false }
        view.gold.filter { !it.archived }.forEach { h ->
            val g = GoldCalc.grams(h, view.goldMoves)
            val pure = GoldCalc.pureGrams(g, h.karat)
            Spacer(Modifier.height(Sp.xs))
            RCard(tint = c.goldSoft) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Diamond, c.gold)
                    Spacer(Modifier.width(Sp.sm))
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.titleMedium)
                        Text("${h.kind.ar} · عيار ${h.karat}", style = MaterialTheme.typography.bodySmall, color = c.mute)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${Fmt.number(g, 3)} غ", style = MaterialTheme.typography.titleMedium)
                        Text("خالص ${Fmt.number(pure, 3)} غ", style = MaterialTheme.typography.bodySmall, color = c.mute)
                        rate?.let { UsdText(it.toUsd(pure), style = MaterialTheme.typography.bodySmall, color = c.gold, prefix = "≈ ") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    SoftButton("شراء", { trade = h.id to true }, Modifier.weight(1f)); SoftButton("بيع", { trade = h.id to false }, Modifier.weight(1f))
                }
                if (trade?.first == h.id) GoldTradeCard(vm, h.id, trade!!.second) { trade = null }
            }
        }
        if (view.gold.isEmpty()) EmptyState(Icons.Filled.Diamond, "لا ذهب مسجّل", "سجّل كل قطعة أو دفعة بوزنها الصافي من الأحجار وعيارها", "أضف قطعة") { adding = true }
        Text("الوزن الخالص = الوزن × العيار ÷ 24. القيمة = الخالص × سعر غرام عيار 24 بتاريخ الجرد.", style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.padding(top = Sp.sm))
    }
}

@Composable
private fun NewGoldCard(vm: MainViewModel, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var karat by rememberSaveable { mutableStateOf(21) }
    var kind by rememberSaveable { mutableStateOf(GoldKind.SAVINGS) }
    var grams by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf(vm.today()) }
    RCard {
        TextIn(name, { name = it }, "اسم القطعة أو الدفعة")
        ChipRow(listOf(24, 22, 21, 18, 14), karat, { "عيار $it" }, { karat = it })
        ChipRow(GoldKind.values().toList(), kind, { it.ar }, { kind = it })
        TextIn(grams, { grams = it }, "الوزن الصافي الافتتاحي بالغرام", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
        DateField(date, { date = it })
        val g = AmountParser.parse(grams)?.value
        PrimaryButton("أضف", enabled = name.isNotBlank() && (grams.isBlank() || g != null), modifier = Modifier.fillMaxWidth(), onClick = {
            vm.launchSafe("أُضيفت القطعة") { vm.repo.addGoldHolding(name, karat, kind, g ?: BigDecimal.ZERO, date); onDone() }
        })
    }
}

@Composable
private fun GoldTradeCard(vm: MainViewModel, holdingId: String, buy: Boolean, onDone: () -> Unit) {
    val v by vm.view.collectAsStateWithLifecycle()
    var grams by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    val cash = remember { AmountState() }
    var date by remember { mutableStateOf(vm.today()) }
    val rid = rememberSaveable { vm.newRequestId() }
    Column {
        TextIn(grams, { grams = it }, "الوزن بالغرام", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
        Text(if (buy) "دُفع من (اختياري)" else "استُلم في (اختياري)", style = MaterialTheme.typography.titleSmall)
        ChipRow(listOf<String?>(null) + v!!.accounts.map { it.id }, account, { id -> id?.let { v!!.accountById[it]?.name } ?: "بلا حركة نقدية" }, { account = it })
        val acc = v!!.accountById[account]
        if (acc != null) AmountField(cash, acc.currency, "المبلغ")
        DateField(date, { date = it })
        val g = AmountParser.parse(grams)?.value
        PrimaryButton(if (buy) "سجّل الشراء" else "سجّل البيع", enabled = g != null && g.signum() > 0, modifier = Modifier.fillMaxWidth(), onClick = {
            vm.launchSafe("تم التسجيل", undoable = true) { vm.repo.goldTrade(holdingId, buy, g!!, account, acc?.let { cash.parse(it.currency)?.minor }, date, "", rid).also { onDone() } }
        })
    }
}

@Composable
fun RatesScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var unit by rememberSaveable { mutableStateOf("TRY") }
    var value by rememberSaveable { mutableStateOf("") }
    var dir by rememberSaveable { mutableStateOf(RateDirection.UNITS_PER_USD) }
    var karat by rememberSaveable { mutableStateOf(24) }
    var kind by rememberSaveable { mutableStateOf(RateKind.MANUAL) }
    var source by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf(vm.today()) }
    val metal = unit == Rate.GOLD24_GRAM || unit == Rate.SILVER_GRAM
    val units = (view.accounts.map { it.currency.code } + listOf("TRY", "SYP", "EUR", "SYP_OLD")).distinct().filter { it != "USD" } + listOf(Rate.GOLD24_GRAM, Rate.SILVER_GRAM)
    ScreenScaffold("الأسعار", { nav.popBackStack() }, sub = "الإدخال اليدوي يعمل بلا إنترنت ويكفي لإتمام الجرد") {
        RCard {
            Text("سعر جديد", style = MaterialTheme.typography.titleMedium)
            ChipRow(units, unit, { Rate.unitLabel(it).let { l -> Currencies.find(it)?.nameAr ?: l } }, { unit = it; if (it == Rate.GOLD24_GRAM || it == Rate.SILVER_GRAM) dir = RateDirection.USD_PER_UNIT })
            if (!metal) {
                ChipRow(RateDirection.values().toList(), dir, { if (it == RateDirection.UNITS_PER_USD) "1 USD = ? $unit" else "1 $unit = ? USD" }, { dir = it })
            } else if (unit == Rate.GOLD24_GRAM) {
                Text("سعر غرام لأي عيار يُطبَّع إلى عيار 24 مرة واحدة", style = MaterialTheme.typography.bodySmall, color = c.mute)
                ChipRow(listOf(24, 22, 21, 18), karat, { "عيار $it" }, { karat = it })
            }
            TextIn(value, { value = it }, if (metal) "دولار للغرام" else if (dir == RateDirection.UNITS_PER_USD) "كم $unit مقابل 1 USD" else "كم USD مقابل 1 $unit", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            val p = AmountParser.parse(value)?.value
            if (p != null && p.signum() > 0) {
                val preview = if (unit == Rate.GOLD24_GRAM && karat != 24) Rate.goldGramFromKarat("p", p, karat, date, "").let { "عيار $karat بسعر ${Fmt.plain(p)} = ${Fmt.plain(it.entered.setScale(4, java.math.RoundingMode.HALF_UP))} USD لغرام عيار 24" }
                else Rate("p", unit, p, if (metal) RateDirection.USD_PER_UNIT else dir, date).label()
                Text(preview, color = c.pri, style = MaterialTheme.typography.titleSmall)
            }
            ChipRow(RateKind.values().toList(), kind, { when (it) { RateKind.OFFICIAL -> "رسمي"; RateKind.LOCAL -> "محلي"; RateKind.MANUAL -> "يدوي" } }, { kind = it })
            TextIn(source, { source = it }, "المصدر (صرّاف، صائغ، نشرة…)")
            DateField(date, { date = it }, "تاريخ السعر")
            PrimaryButton("احفظ السعر", enabled = p != null && p.signum() > 0, modifier = Modifier.fillMaxWidth(), onClick = {
                vm.launchSafe("حُفظ السعر") {
                    if (unit == Rate.GOLD24_GRAM && karat != 24) { val r = Rate.goldGramFromKarat("x", p!!, karat, date, source.ifBlank { "يدوي" }); vm.repo.addRate(r.unit, r.entered, r.direction, date, r.source, kind) }
                    else vm.repo.addRate(unit, p!!, if (metal) RateDirection.USD_PER_UNIT else dir, date, source, kind)
                    value = ""
                }
            })
        }
        SectionHeader("الأسعار المحفوظة")
        view.s.rates.groupBy { it.unit }.forEach { (u, list) ->
            RCard {
                Text(Currencies.find(u)?.nameAr ?: Rate.unitLabel(u), style = MaterialTheme.typography.titleSmall, color = c.txt)
                list.sortedByDescending { it.date }.take(8).forEach { r ->
                    val d = r.toDomain()
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.date, style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.width(96.dp))
                        Text(d.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(r.source, style = MaterialTheme.typography.bodySmall, color = c.mute)
                        IconButton(onClick = { vm.launchSafe("حُذف السعر") { vm.repo.deleteRate(r) } }, Modifier.size(40.dp)) { Icon(Icons.Filled.Delete, "حذف السعر", tint = c.mute) }
                    }
                }
            }
            Spacer(Modifier.height(Sp.xs))
        }
        Text("حذف سعر لا يغيّر جرداً محفوظاً: كل جرد يثبّت أسعاره في لقطته.", style = MaterialTheme.typography.bodySmall, color = c.mute)
    }
}

@Composable
fun CategoriesScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("EXPENSE") }
    ScreenScaffold("الفئات", { nav.popBackStack() }) {
        RCard {
            ChipRow(listOf("EXPENSE", "INCOME"), kind, { if (it == "EXPENSE") "مصروف" else "دخل" }, { kind = it })
            TextIn(name, { name = it }, "اسم الفئة")
            PrimaryButton("أضف", enabled = name.isNotBlank(), onClick = { vm.launchSafe("أُضيفت الفئة") { vm.repo.addCategory(name, kind); name = "" } })
        }
        listOf("EXPENSE" to "فئات المصروف", "INCOME" to "فئات الدخل").forEach { (k, t) ->
            SectionHeader(t)
            RCard {
                view.s.categories.filter { it.kind == k }.forEach { cat ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(cat.name, modifier = Modifier.weight(1f), color = if (cat.archived) c.mute else c.txt)
                        if (cat.id != "cat-zakat" && cat.id != "cat-fees") TextButton(onClick = { vm.launchSafe { vm.repo.archiveCategory(cat, !cat.archived) } }) { Text(if (cat.archived) "استعادة" else "أرشفة") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    var lock by remember { mutableStateOf(vm.device.appLock) }
    ScreenScaffold("الإعدادات", { nav.popBackStack() }) {
        SectionHeader("المظهر")
        RCard {
            ChipRow(listOf("system", "light", "dark"), s.theme, { when (it) { "light" -> "نهاري"; "dark" -> "ليلي"; else -> "حسب النظام" } }, { vm.setSetting("ui.theme", it) })
            Spacer(Modifier.height(Sp.xs))
            Text("حجم النص: ${(s.fontScale * 100).toInt()}%", color = c.txt)
            Slider(s.fontScale, { vm.setSetting("ui.fontScale", ((it * 20).toInt() / 20f).toString()) }, valueRange = 0.85f..1.4f, steps = 10)
        }
        SectionHeader("الخصوصية والأمان")
        RCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.hideAmounts, { vm.setSetting("ui.hideAmounts", if (it) "1" else "0") }); Spacer(Modifier.width(8.dp)); Text("إخفاء المبالغ") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(lock, { lock = it; vm.device.appLock = it }); Spacer(Modifier.width(8.dp)); Text("قفل التطبيق بالبصمة أو رمز الجهاز") }
        }
        SectionHeader("الليرة السورية")
        RCard {
            Text("قاعدة: 100 ليرة قديمة = 1 ليرة جديدة. طابق تاريخ النفاذ ونهاية التداول مع إعلان المصرف المركزي.", style = MaterialTheme.typography.bodySmall, color = c.mute)
            KeyValue("تاريخ النفاذ (الافتراضي)", SyrianRedenomination.DEFAULT.effectiveDate.toString())
            KeyValue("نهاية التداول", SyrianRedenomination.DEFAULT.withdrawalEnd?.toString() ?: "غير محددة")
        }
        SectionHeader("الدفتر")
        RCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(demo, { vm.switchDemo(it); nav.navigate(Routes.HOME) { popUpTo(0) } }); Spacer(Modifier.width(8.dp))
                Column { Text("الدفتر التجريبي"); Text("قاعدة منفصلة تماماً عن دفترك الفعلي", style = MaterialTheme.typography.bodySmall, color = c.mute) }
            }
            if (demo) SoftButton("املأ الدفتر التجريبي ببيانات اصطناعية", { vm.launchSafe("تم") { vm.repo.replaceAll(app.rasidi.core.demo.DemoData.snapshot(vm.today())) } })
        }
        Text("رصيدي ${app.rasidi.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.padding(top = Sp.md))
    }
}
