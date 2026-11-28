package app.rasidi.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
    // معرّف ثابت لنية الحفظ الحالية، يتجدد بعد نجاحها (الشاشة تبقى مفتوحة لعدّ لاحق)
    var rid by rememberSaveable { mutableStateOf(vm.newRequestId()) }
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
                    PrimaryButton("سجّل التسوية", enabled = !diff.isZero, onClick = { vm.launchSafe("سُجّلت التسوية") { vm.repo.adjustToCount(id, m.minor, today, rid); rid = vm.newRequestId(); count = false } })
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

/** الإعدادات: فئات واضحة، كل فئة بأيقونة ملوّنة وعنوان وسطر يصف محتواها، وتفتح صفحتها. */
@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavController) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val opt = s.voucherOptions()
    ScreenScaffold("الإعدادات", { nav.popBackStack() }) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
            ListCard("البيانات الشخصية", opt.profile.name.ifBlank { "اسمك أو اسم البيت، المدينة، الهاتف — تظهر في رأس السندات" }, Icons.Filled.Badge, CardTint.green) { nav.navigate(Routes.settings("profile")) }
            ListCard("إعدادات الطباعة", "مقاس الورق: ${opt.paper.ar} · A4 أو A5 أو حراري 80/58 مم", Icons.Filled.Print, CardTint.pink) { nav.navigate(Routes.settings("print")) }
            ListCard("إعدادات السندات", "التواقيع (${opt.signatures.size})، الرصيد المتبقي في السند، تذييل السند وكشف الحساب", Icons.Filled.Receipt, CardTint.sky) { nav.navigate(Routes.settings("vouchers")) }
            ListCard("المظهر والخطوط", "${when (s.theme) { "light" -> "نهاري"; "dark" -> "ليلي"; else -> "تلقائي" }} · الخط ${(s.fontScale * 100).toInt()}% · إخفاء المبالغ", Icons.Filled.Contrast, CardTint.violet) { nav.navigate(Routes.settings("appearance")) }
            ListCard("تخصيص أزرار الواجهة الرئيسية", "${s.homeButtons.size} أزرار: ${s.homeButtons.take(3).joinToString("، ") { app.rasidi.ui.HomeButtons.ALL[it] ?: it }}…", Icons.Filled.Apps, CardTint.amber) { nav.navigate(Routes.settings("home")) }
            ListCard("العملات وأسعار الصرف", "الأسعار اليدوية، غرام الذهب، قاعدة الليرة السورية", Icons.Filled.CurrencyExchange, CardTint.teal) { nav.navigate(Routes.settings("currency")) }
            ListCard("النسخ الاحتياطي و\u2066Google Drive\u2069", status.sub, Icons.Filled.CloudUpload, CardTint.green) { nav.navigate(Routes.BACKUP) }
            ListCard("الأمان والقفل", if (vm.device.appLock) "مقفل بالبصمة أو رمز الجهاز" else "قفل التطبيق غير مفعّل", Icons.Filled.Lock, CardTint.slate) { nav.navigate(Routes.settings("security")) }
            ListCard("الدفتر التجريبي", if (demo) "مفعّل الآن: بيانات اصطناعية" else "جرّب التطبيق دون المساس بدفترك", Icons.Filled.Science, CardTint.amber, badge = if (demo) "مفعّل" else null) { nav.navigate(Routes.settings("demo")) }
            ListCard("عن التطبيق", "رصيدي ${app.rasidi.BuildConfig.VERSION_NAME} · الخصوصية", Icons.Filled.Info, CardTint.slate) { nav.navigate(Routes.settings("about")) }
        }
    }
}

@Composable
fun SettingsSectionScreen(vm: MainViewModel, nav: NavController, section: String) {
    val c = LocalR.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    fun save(k: String, v: String) = vm.setSetting(k, v)
    val titles = mapOf("profile" to "البيانات الشخصية", "print" to "إعدادات الطباعة", "vouchers" to "إعدادات السندات", "appearance" to "المظهر والخطوط",
        "home" to "تخصيص أزرار الرئيسية", "currency" to "العملات وأسعار الصرف", "security" to "الأمان والقفل", "demo" to "الدفتر التجريبي", "about" to "عن التطبيق")
    ScreenScaffold(titles[section] ?: "الإعدادات", { nav.popBackStack() }) {
        when (section) {
            "profile" -> {
                var name by remember { mutableStateOf(s.get("profile.name")) }
                var city by remember { mutableStateOf(s.get("profile.city")) }
                var phone by remember { mutableStateOf(s.get("profile.phone")) }
                RCard {
                    Text("تظهر في رأس كل سند وفي المشاركة النصية.", color = c.mute, style = MaterialTheme.typography.bodySmall)
                    TextIn(name, { name = it }, "الاسم أو اسم البيت")
                    TextIn(city, { city = it }, "المدينة والبلد")
                    TextIn(phone, { phone = it }, "رقم الهاتف", keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
                    PrimaryButton("احفظ", { save("profile.name", name.trim()); save("profile.city", city.trim()); save("profile.phone", phone.trim()); vm.message("حُفظت البيانات الشخصية") }, Modifier.fillMaxWidth())
                }
            }
            "print" -> {
                val paper = s.voucherOptions().paper
                RCard {
                    Text("مقاس الورق للسندات", style = MaterialTheme.typography.titleSmall)
                    app.rasidi.core.voucher.Paper.values().forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { save("print.paper", p.name) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(p == paper, { save("print.paper", p.name) }); Text(p.ar + when (p) { app.rasidi.core.voucher.Paper.A4 -> " — طابعة عادية"; app.rasidi.core.voucher.Paper.A5 -> " — نصف A4"; else -> " — طابعة حرارية" })
                        }
                    }
                    Text("الطباعة تتم عبر خدمة الطباعة في أندرويد: طابعة شبكة، أو تطبيق الطابعة الحرارية، أو «حفظ كـ PDF». التقارير تُطبع دائماً على A4.", style = MaterialTheme.typography.bodySmall, color = c.mute)
                }
            }
            "vouchers" -> {
                var showBal by remember { mutableStateOf(s.get("voucher.showBalance", "1") == "1") }
                val sigs = remember { s.get("voucher.signatures", "المستلم|المحاسب").split('|').let { l -> MutableList(4) { i -> l.getOrElse(i) { "" } }.toMutableStateList() } }
                var footer by remember { mutableStateOf(s.get("voucher.footer")) }
                var stFooter by remember { mutableStateOf(s.get("statement.footer")) }
                RCard {
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(showBal, { showBal = it; save("voucher.showBalance", if (it) "1" else "0") }); Spacer(Modifier.width(8.dp)); Text("إضافة الرصيد المتبقي في سندات الديون") }
                }
                SectionHeader("توقيع السندات")
                RCard {
                    listOf("الأول", "الثاني", "الثالث", "الرابع").forEachIndexed { i, l -> TextIn(sigs[i], { sigs[i] = it }, "توقيع السند $l") }
                }
                SectionHeader("التذييل")
                RCard {
                    TextIn(footer, { footer = it }, "ملاحظة أسفل السند (مثل: شكراً لتعاملكم)", singleLine = false)
                    TextIn(stFooter, { stFooter = it }, "ملاحظة أسفل كشف الحساب عند الطباعة", singleLine = false)
                }
                Spacer(Modifier.height(Sp.sm))
                PrimaryButton("احفظ", {
                    save("voucher.signatures", sigs.map { it.replace("|", " ").trim() }.filter { it.isNotBlank() }.joinToString("|"))
                    save("voucher.footer", footer.trim()); save("statement.footer", stFooter.trim()); vm.message("حُفظت إعدادات السندات")
                }, Modifier.fillMaxWidth())
            }
            "appearance" -> {
                RCard {
                    Text("الوضع", style = MaterialTheme.typography.titleSmall)
                    ChipRow(listOf("system", "light", "dark"), s.theme, { when (it) { "light" -> "نهاري"; "dark" -> "ليلي"; else -> "تلقائي" } }, { save("ui.theme", it) })
                    Spacer(Modifier.height(Sp.sm))
                    Text("حجم الخط", style = MaterialTheme.typography.titleSmall, color = c.txt)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.85f to "أصغر", 0.92f to "صغير", 1f to "عادي", 1.12f to "كبير", 1.25f to "أكبر").forEach { (v, l) ->
                            val on = kotlin.math.abs(s.fontScale - v) < 0.01f
                            Column(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(if (on) c.priSoft else c.soft)
                                .clickable { save("ui.fontScale", v.toString()) }.heightIn(min = 64.dp).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("أ", fontSize = (15 * v).sp, color = if (on) c.pri else c.txt, style = MaterialTheme.typography.titleMedium)
                                Text(l, style = MaterialTheme.typography.labelSmall, color = if (on) c.pri else c.mute)
                            }
                        }
                    }
                    Spacer(Modifier.height(Sp.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.hideAmounts, { save("ui.hideAmounts", if (it) "1" else "0") }); Spacer(Modifier.width(8.dp)); Text("إخفاء المبالغ (يمكن التبديل من زر العين في الأعلى)") }
                }
            }
            "home" -> {
                val chosen = remember { s.homeButtons.toMutableStateList() }
                Text("اختر حتى 9 أزرار ورتّبها. تظهر أعلى الرئيسية ثلاثة في كل صف.", color = c.mute, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Sp.xs))
                RCard {
                    chosen.toList().forEachIndexed { i, id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HomeButton(id, Modifier.weight(1f)) {}
                            IconButton(onClick = { if (i > 0) { chosen.removeAt(i); chosen.add(i - 1, id) } }) { Icon(Icons.Filled.KeyboardArrowUp, "للأعلى") }
                            IconButton(onClick = { if (i < chosen.lastIndex) { chosen.removeAt(i); chosen.add(i + 1, id) } }) { Icon(Icons.Filled.KeyboardArrowDown, "للأسفل") }
                            IconButton(onClick = { chosen.remove(id) }) { Icon(Icons.Filled.RemoveCircleOutline, "إزالة", tint = c.danger) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (chosen.isEmpty()) Text("لا أزرار: لن يظهر الصف في الرئيسية", color = c.mute)
                }
                SectionHeader("أزرار متاحة")
                RCard {
                    app.rasidi.ui.HomeButtons.ALL.keys.filter { it !in chosen }.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HomeButton(id, Modifier.weight(1f)) {}
                            IconButton(onClick = { if (chosen.size < 9) chosen.add(id) }, enabled = chosen.size < 9) { Icon(Icons.Filled.AddCircleOutline, "إضافة", tint = c.pri) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(Sp.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    PrimaryButton("احفظ", { save("home.buttons", chosen.joinToString(",")); vm.message("حُفظت أزرار الرئيسية") }, Modifier.weight(1f))
                    SoftButton("الافتراضي", { chosen.clear(); chosen.addAll(app.rasidi.ui.HomeButtons.DEFAULT.split(',')) }, Modifier.weight(1f))
                }
            }
            "currency" -> {
                ListCard("الأسعار", "أدخل «1 USD = 40 TRY» أو سعر غرام الذهب بأي عيار", Icons.Filled.CurrencyExchange, CardTint.sky) { nav.navigate(Routes.RATES) }
                Spacer(Modifier.height(Sp.sm))
                RCard {
                    Text("الليرة السورية", style = MaterialTheme.typography.titleSmall)
                    Text("قاعدة: 100 ليرة قديمة = 1 ليرة جديدة. القديمة والجديدة وحدتان منفصلتان ولا يُطبّق المعامل مرتين. طابق تاريخ النفاذ مع إعلان المصرف المركزي.", style = MaterialTheme.typography.bodySmall, color = c.mute)
                    KeyValue("تاريخ النفاذ (الافتراضي)", SyrianRedenomination.DEFAULT.effectiveDate.toString())
                    KeyValue("نهاية التداول", SyrianRedenomination.DEFAULT.withdrawalEnd?.toString() ?: "غير محددة")
                }
            }
            "security" -> {
                var lock by remember { mutableStateOf(vm.device.appLock) }
                RCard {
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(lock, { lock = it; vm.device.appLock = it }); Spacer(Modifier.width(8.dp)); Text("قفل التطبيق بالبصمة أو رمز الجهاز") }
                    Text("دفترك محفوظ على الجهاز فقط ومستبعد من نسخ أندرويد التلقائي. النسخ عبر ملف .rasidi المشفّر.", style = MaterialTheme.typography.bodySmall, color = c.mute)
                }
            }
            "demo" -> RCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(demo, { vm.switchDemo(it); nav.navigate(Routes.HOME) { popUpTo(0) } }); Spacer(Modifier.width(8.dp))
                    Column { Text("الدفتر التجريبي"); Text("قاعدة منفصلة تماماً عن دفترك الفعلي", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                }
                if (demo) SoftButton("املأ الدفتر التجريبي ببيانات اصطناعية", { vm.launchSafe("تم") { vm.repo.replaceAll(app.rasidi.core.demo.DemoData.snapshot(vm.today())) } })
            }
            "about" -> RCard {
                Text("رصيدي ${app.rasidi.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text("دفتر مالي شخصي للبيت: مصروف ودخل وديون وذهب وجرد وزكاة، بالعربية وبلا إنترنت.", color = c.mute)
                Spacer(Modifier.height(Sp.xs))
                Text("الخصوصية", style = MaterialTheme.typography.titleSmall)
                Text("لا حساب ولا خوادم ولا إعلانات ولا تحليلات. بياناتك على جهازك فقط، وتُنسخ إلى Google Drive مشفّرة إن ربطته بنفسك.", style = MaterialTheme.typography.bodySmall, color = c.mute)
            }
        }
    }
}
