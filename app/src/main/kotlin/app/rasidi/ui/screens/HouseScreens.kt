package app.rasidi.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.rasidi.core.data.MemberEntity
import app.rasidi.core.data.RecurringEntity
import app.rasidi.core.household.Household
import app.rasidi.core.ledger.GoldCalc
import app.rasidi.core.money.*
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.Routes
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.UUID

// ============================== المزيد (أسلوب طاقتي) ==============================
@Composable
fun MoreScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val home by vm.home.collectAsStateWithLifecycle()
    val v by vm.view.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val view = v ?: return
    val month = YearMonth.now()
    val budgets = remember(view) { Household.budgets(view, month) }
    val due = remember(view) { Household.recurringDue(view, month) }
    @Composable fun group(title: String, content: @Composable ColumnScope.() -> Unit) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.txt, modifier = Modifier.padding(top = Sp.lg, bottom = Sp.xs, start = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs), content = content)
    }
    var tab by rememberSaveable { mutableStateOf("ledger") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Sp.md).padding(bottom = 170.dp)) {
        BigTitle("المزيد")
        // ثلاثة أقسام على طريقة سَيْل: دفتري، المساعدة، عن رصيدي
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = Sp.sm)) {
            listOf("ledger" to "دفتري", "help" to "المساعدة", "about" to "عن رصيدي").forEachIndexed { i, (id, l) ->
                SegmentedButton(tab == id, { tab = id }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
            }
        }
        if (tab == "help") { HelpTab(vm, nav); return@Column }
        if (tab == "about") { AboutTab(vm, nav); return@Column }
        // 1. بطاقة البطل: حالة الدفتر
        RCard(onClick = { nav.navigate(Routes.BACKUP) }, tint = c.priSoft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.MenuBook, c.pri, 56.dp)
                Spacer(Modifier.width(Sp.sm))
                Column(Modifier.weight(1f)) {
                    Text(status.name, style = MaterialTheme.typography.titleLarge, color = c.txt)
                    Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(status.tone); Spacer(Modifier.width(6.dp)); Text(status.sub, style = MaterialTheme.typography.bodySmall, color = c.mute) }
                    Text("${view.accounts.count { !it.archived }} حسابات · ${view.s.txns.size} حركة · ${view.accounts.map { it.currency.code }.distinct().size} عملات", style = MaterialTheme.typography.bodySmall, color = c.mute)
                }
                home?.let { UsdText(it.netWorth.inventory.pricedNet, style = MaterialTheme.typography.titleMedium, color = c.pri) }
            }
        }
        // 2. صف الاختصارات
        Spacer(Modifier.height(Sp.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
            Shortcut("مصروف", Icons.Filled.ShoppingBag, CardTint.pink, Modifier.weight(1f)) { nav.navigate(Routes.add("expense")) }
            Shortcut("تحويل", Icons.Filled.SwapHoriz, CardTint.sky, Modifier.weight(1f)) { nav.navigate(Routes.add("transfer")) }
            Shortcut("الجرد", Icons.Filled.Inventory2, CardTint.amber, Modifier.weight(1f)) { nav.navigate(Routes.INVENTORY) }
            Shortcut("تقرير وتصدير", Icons.Filled.Download, CardTint.green, Modifier.weight(1f)) { nav.navigate(Routes.reports()) }
        }
        // 3. المجموعات
        group("دفتري") {
            ListCard("الحسابات", "${view.accounts.count { !it.archived }} حسابات · كشوف الحساب والعدّ اليدوي", Icons.Filled.AccountBalanceWallet, CardTint.green) { nav.navigate(Routes.ACCOUNTS) }
            ListCard("أفراد البيت", if (view.s.members.isEmpty()) "اعرف من صرف ماذا" else view.s.members.filter { !it.archived }.joinToString("، ") { it.name }, Icons.Filled.Groups, CardTint.sky) { nav.navigate(Routes.MEMBERS) }
            ListCard("الفئات", "${view.s.categories.count { !it.archived }} فئة · إضافة وأرشفة", Icons.Filled.Category, CardTint.slate) { nav.navigate(Routes.CATEGORIES) }
            ListCard("الذهب", "${view.gold.count { !it.archived }} قطع · الوزن الخالص والقيمة", Icons.Filled.Diamond, CardTint.amber) { nav.navigate(Routes.GOLD) }
        }
        group("تخطيط البيت") {
            val over = budgets.count { it.over }; val near = budgets.count { it.near }
            ListCard("الميزانية الشهرية", if (budgets.isEmpty()) "حدّ لكل فئة: أكل، فواتير، نقل…" else "${budgets.size} ميزانيات" + (if (over > 0) " · $over متجاوزة" else if (near > 0) " · $near قاربت الحد" else " · ضمن الحد"),
                Icons.Filled.PieChart, CardTint.violet, badge = if (over > 0) "تجاوز" else null, badgeTone = Tone.DANGER) { nav.navigate(Routes.BUDGETS) }
            val pending = due.count { !it.done }
            ListCard("الفواتير والمتكررات", if (due.isEmpty()) "إيجار، إنترنت، راتب… تُسجَّل بلمسة" else "${due.size} شهرياً · $pending بانتظار التسجيل هذا الشهر",
                Icons.Filled.EventRepeat, CardTint.teal, badge = if (pending > 0) "$pending" else null) { nav.navigate(Routes.RECURRING) }
        }
        group("التقارير والجرد") {
            ListCard("التقارير والتصدير", "يومي، شهري، سنوي، كشف حساب · Excel وWord وPDF", Icons.Filled.Description, CardTint.green) { nav.navigate(Routes.reports()) }
            ListCard("الجرد والزكاة", "${view.s.inventories.map { it.groupId }.distinct().size} جرد محفوظ · صافي الأموال بالدولار", Icons.Filled.VolunteerActivism, CardTint.amber) { nav.navigate(Routes.INVENTORY) }
            val lastRate = view.s.rates.maxByOrNull { it.date }
            ListCard("الأسعار", lastRate?.let { "آخر سعر بتاريخ \u2066${it.date}\u2069" } ?: "أدخل أسعار العملات وغرام الذهب", Icons.Filled.CurrencyExchange, CardTint.sky) { nav.navigate(Routes.RATES) }
        }
        group("الأمان والإعدادات") {
            ListCard("النسخ الاحتياطي", status.sub + if (vm.device.drivePermissionId != null) " · Drive مربوط" else "", Icons.Filled.CloudUpload, CardTint.green,
                badge = if (status.tone == Tone.WARN || status.tone == Tone.DANGER) "مهم" else null, badgeTone = Tone.WARN) { nav.navigate(Routes.BACKUP) }
            ListCard("الإعدادات", "${when (s.theme) { "light" -> "نهاري"; "dark" -> "ليلي"; else -> "تلقائي" }} · الخط ${(s.fontScale * 100).toInt()}%${if (vm.device.appLock) " · مقفل بالبصمة" else ""}", Icons.Filled.Tune, CardTint.violet) { nav.navigate(Routes.SETTINGS) }
        }
        Spacer(Modifier.height(Sp.lg))
        if (demo) OutlinedButton(onClick = { vm.switchDemo(false); nav.navigate(Routes.HOME) { popUpTo(0) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.danger)) { Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("الخروج من الدفتر التجريبي") }
        Text("رصيدي · الإصدار ${app.rasidi.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.fillMaxWidth().padding(top = Sp.md), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** المساعدة: الجولة، ما الجديد، ثم الشرح كاملاً. */
@Composable
fun HelpTab(vm: MainViewModel, nav: NavController) {
    var tour by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
        ListCard("جولة في التطبيق", "كيف تستخدم رصيدي في دقيقة", Icons.Filled.MenuBook, CardTint.sky) { tour = true }
        ListCard("ما الجديد", "ما أضيف في كل إصدار", Icons.Filled.NewReleases, CardTint.amber) { nav.navigate(Routes.CHANGELOG) }
    }
    app.rasidi.ui.help.GuideList()
    if (tour) app.rasidi.ui.help.TourDialog { tour = false; vm.device.tourSeen = true }
}

@Composable
fun AboutTab(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    RCard(tint = c.priSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.AccountBalanceWallet, c.pri, 56.dp)
            Spacer(Modifier.width(Sp.sm))
            Column { Text("رصيدي", style = MaterialTheme.typography.headlineSmall, color = c.pri); Text("الإصدار ${app.rasidi.BuildConfig.VERSION_NAME}", color = c.mute) }
        }
        Spacer(Modifier.height(Sp.xs))
        Text("دفتر مالي شخصي للبيت: مصروف ودخل وديون وذهب وجرد وزكاة، بالعربية وبلا إنترنت.", color = c.txt)
    }
    Spacer(Modifier.height(Sp.sm))
    Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
        ListCard("ما الجديد", "الإصدار ${app.rasidi.ui.help.CHANGELOG.first().version} وما قبله", Icons.Filled.NewReleases, CardTint.amber) { nav.navigate(Routes.CHANGELOG) }
        ListCard("الخصوصية", "لا حساب ولا خوادم ولا إعلانات ولا تحليلات؛ بياناتك على هاتفك", Icons.Filled.Shield, CardTint.green) { nav.navigate(Routes.settings("about")) }
        ListCard("الإعدادات", "البيانات الشخصية، الطباعة، السندات، المظهر", Icons.Filled.Tune, CardTint.violet) { nav.navigate(Routes.SETTINGS) }
    }
    Text("رصيدي · الإصدار ${app.rasidi.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.fillMaxWidth().padding(top = Sp.lg), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
fun HelpScreen(vm: MainViewModel, nav: NavController) {
    ScreenScaffold("المساعدة", { nav.popBackStack() }, sub = "اضغط أي بند ليُفتح الشرح") { HelpTab(vm, nav) }
}

@Composable
fun ChangelogScreen(nav: NavController) {
    ScreenScaffold("ما الجديد", { nav.popBackStack() }) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
            app.rasidi.ui.help.CHANGELOG.forEachIndexed { i, r -> app.rasidi.ui.help.ReleaseCard(r, i == 0) }
        }
    }
}

// ============================== الحاسبة ==============================
/** حاسبة سريعة: تقبل الأرقام العربية والفارسية و + − × ÷ والأقواس. */
object QuickCalc {
    fun eval(input: String): BigDecimal? {
        val s = AmountParser.normalizeDigits(input).replace('×', '*').replace('÷', '/').replace('x', '*').replace(",", "").replace(" ", "")
        if (s.isEmpty()) return null
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        lateinit var expr: () -> BigDecimal
        fun factor(): BigDecimal {
            if (peek() == '-') { i++; return factor().negate() }
            if (peek() == '(') { i++; val v = expr(); if (peek() == ')') i++; return v }
            val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return BigDecimal(s.substring(st, i))
        }
        fun term(): BigDecimal { var v = factor(); while (peek() == '*' || peek() == '/') { val op = s[i++]; val r = factor(); v = if (op == '*') v.multiply(r) else v.divide(r, 10, RoundingMode.HALF_UP) }; return v }
        expr = { var v = term(); while (peek() == '+' || peek() == '-') { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }; v }
        return try { val v = expr(); if (i != s.length) null else v.stripTrailingZeros() } catch (e: Exception) { null }
    }
}

@Composable
fun CalculatorScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    val book = remember(view) { RateBook(view.rates) }
    val today = vm.today()
    fun latest(unit: String): Rate? = when (val l = book.lookup(unit, today)) { is RateLookup.Exact -> l.rate; is RateLookup.OlderSuggestion -> l.rate; RateLookup.Missing -> null }
    val codes = (listOf("USD", "TRY", "SYP", "EUR") + view.accounts.map { it.currency.code }).distinct()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Sp.md).padding(bottom = 170.dp)) {
        BigTitle("الحاسبة", sub = "تعمل بلا إنترنت بالأسعار المحفوظة")
        // تحويل العملات
        var amt by rememberSaveable { mutableStateOf("100") }
        var from by rememberSaveable { mutableStateOf("USD") }
        var to by rememberSaveable { mutableStateOf("TRY") }
        RCard {
            Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(Icons.Filled.CurrencyExchange, CardTint.sky); Spacer(Modifier.width(Sp.sm)); Text("تحويل العملات", style = MaterialTheme.typography.titleMedium) }
            TextIn(amt, { amt = it }, "المبلغ", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            Text("من", style = MaterialTheme.typography.labelLarge, color = c.mute); ChipRow(codes, from, { it }, { from = it })
            Text("إلى", style = MaterialTheme.typography.labelLarge, color = c.mute); ChipRow(codes, to, { it }, { to = it })
            val a = QuickCalc.eval(amt)
            val rf = latest(from); val rt = latest(to)
            if (a != null && rf != null && rt != null) {
                val res = rt.fromUsd(rf.toUsd(a))
                Text(Fmt.LRI + "${Fmt.number(a, 2)} $from = ${Fmt.number(res, Currencies.find(to)?.minorDigits ?: 2)} $to" + Fmt.PDI, style = MaterialTheme.typography.headlineSmall, color = c.pri, modifier = Modifier.padding(vertical = 6.dp))
                listOf(rf, rt).filter { it.unit != "USD" }.forEach { r -> Text("${r.label()} · ${r.source} · \u2066${r.date}\u2069" + if (r.date != today) " (أقدم من اليوم)" else "", style = MaterialTheme.typography.bodySmall, color = if (r.date != today) c.warn else c.mute) }
            } else if (a != null) Text("لا سعر محفوظ لـ ${listOfNotNull(from.takeIf { rf == null }, to.takeIf { rt == null }).joinToString(" و")}", color = c.warn)
            TextButton(onClick = { nav.navigate(Routes.RATES) }) { Text("الأسعار") }
        }
        Spacer(Modifier.height(Sp.sm))
        // حاسبة سريعة
        var expr by rememberSaveable { mutableStateOf("") }
        RCard {
            Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(Icons.Filled.Calculate, CardTint.green); Spacer(Modifier.width(Sp.sm)); Text("حاسبة سريعة", style = MaterialTheme.typography.titleMedium) }
            OutlinedTextField(expr, { expr = it }, placeholder = { Text("مثال: ١٢٥٠ + 300 × 2") }, singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("+", "−", "×", "÷", "(", ")", "⌫").forEach { k ->
                    FilledTonalButton(onClick = { expr = if (k == "⌫") expr.dropLast(1) else expr + (if (k == "−") "-" else k) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text(k) }
                }
            }
            QuickCalc.eval(expr)?.let { Text("= " + Fmt.LRI + Fmt.number(it, if (it.scale() > 0) minOf(it.scale(), 4) else 0) + Fmt.PDI, style = MaterialTheme.typography.headlineMedium, color = c.pri) }
        }
        Spacer(Modifier.height(Sp.sm))
        // قيمة الذهب
        var grams by rememberSaveable { mutableStateOf("") }
        var karat by rememberSaveable { mutableStateOf(21) }
        var goldCur by rememberSaveable { mutableStateOf("USD") }
        RCard(tint = c.goldSoft) {
            Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(Icons.Filled.Diamond, c.gold); Spacer(Modifier.width(Sp.sm)); Text("قيمة الذهب", style = MaterialTheme.typography.titleMedium) }
            TextIn(grams, { grams = it }, "الوزن بالغرام", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            ChipRow(listOf(24, 22, 21, 18, 14), karat, { "عيار $it" }, { karat = it })
            ChipRow(codes, goldCur, { it }, { goldCur = it })
            val g = QuickCalc.eval(grams); val gr = latest(Rate.GOLD24_GRAM); val cr = latest(goldCur)
            if (g != null) {
                val pure = GoldCalc.pureGrams(g, karat)
                Text("الخالص: ${Fmt.number(pure, 3)} غ عيار 24", color = c.txt)
                if (gr != null && cr != null) {
                    val usd = gr.toUsd(pure)
                    Text(Fmt.LRI + "${Fmt.number(cr.fromUsd(usd), 2)} $goldCur" + Fmt.PDI, style = MaterialTheme.typography.headlineSmall, color = c.gold)
                    Text("${gr.label()} · ${gr.source} · ${gr.date}", style = MaterialTheme.typography.bodySmall, color = c.mute)
                } else Text("أدخل سعر غرام الذهب من «الأسعار»", color = c.warn)
            }
        }
        Spacer(Modifier.height(Sp.sm))
        // تقسيم مبلغ
        var total by rememberSaveable { mutableStateOf("") }
        var people by rememberSaveable { mutableStateOf("2") }
        RCard {
            Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(Icons.Filled.Groups, CardTint.violet); Spacer(Modifier.width(Sp.sm)); Text("تقسيم مبلغ", style = MaterialTheme.typography.titleMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                TextIn(total, { total = it }, "المبلغ", Modifier.weight(2f), keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
                TextIn(people, { people = it.filter(Char::isDigit).take(3) }, "عدد الأشخاص", Modifier.weight(1f), keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            }
            val t = QuickCalc.eval(total); val n = people.toIntOrNull()
            if (t != null && n != null && n > 0) {
                val each = t.divide(BigDecimal(n), 2, RoundingMode.DOWN)
                val rest = t - each.multiply(BigDecimal(n))
                Text("لكل شخص: " + Fmt.LRI + Fmt.number(each, 2) + Fmt.PDI, style = MaterialTheme.typography.headlineSmall, color = c.pri)
                if (rest.signum() > 0) Text("ويبقى ${Fmt.number(rest, 2)} يُضاف لأحدهم", style = MaterialTheme.typography.bodySmall, color = c.mute)
            }
        }
    }
}

// ============================== أفراد البيت ==============================
@Composable
fun MembersScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var name by rememberSaveable { mutableStateOf("") }
    val today = vm.today()
    val spend = remember(view) { Household.spendByMember(view, today.withDayOfMonth(1), today) }
    ScreenScaffold("أفراد البيت", { nav.popBackStack() }, sub = "اختيار الفرد في المصروف اختياري؛ يفيد في معرفة من صرف ماذا") {
        RCard {
            TextIn(name, { name = it }, "اسم الفرد")
            PrimaryButton("أضف", enabled = name.isNotBlank(), onClick = { vm.launchSafe("أُضيف") { vm.repo.saveMember(MemberEntity(UUID.randomUUID().toString(), name, sort = view.s.members.size + 1)); name = "" } })
        }
        SectionHeader("مصروف هذا الشهر حسب الفرد")
        RCard {
            if (spend.isEmpty()) Text("لا مصروف هذا الشهر", color = c.mute)
            spend.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) { m.byCurrency.values.forEach { AmountText(it, style = MaterialTheme.typography.bodyMedium, color = c.danger) } }
                }
            }
        }
        SectionHeader("الأفراد")
        view.s.members.forEach { m ->
            RCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Person, CardTint.sky)
                    Spacer(Modifier.width(Sp.sm))
                    Text(m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), color = if (m.archived) c.mute else c.txt)
                    TextButton(onClick = { vm.launchSafe { vm.repo.saveMember(m.copy(archived = !m.archived)) } }) { Text(if (m.archived) "استعادة" else "أرشفة") }
                }
            }
            Spacer(Modifier.height(Sp.xs))
        }
    }
}

// ============================== الميزانية الشهرية ==============================
@Composable
fun BudgetRow(b: Household.BudgetLine) {
    val c = LocalR.current
    val col = if (b.over) c.danger else if (b.near) c.warn else c.pri
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.categoryName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (b.over) StatusChip("متجاوزة", Tone.DANGER) else if (b.near) StatusChip("قاربت الحد", Tone.WARN)
        }
        LinearProgressIndicator(progress = { b.ratio.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = col, trackColor = c.soft)
        Text("صُرف ${Fmt.money(b.spent)} من ${Fmt.money(b.limit)} · " + if (b.over) "زيادة ${Fmt.money(-b.remaining)}" else "يبقى ${Fmt.money(b.remaining)}",
            style = MaterialTheme.typography.bodySmall, color = if (b.over) c.danger else c.mute)
    }
}

@Composable
fun BudgetsScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    var month by remember { mutableStateOf(YearMonth.now()) }
    val lines = remember(view, month) { Household.budgets(view, month) }
    val cats = view.s.categories.filter { it.kind == "EXPENSE" && !it.archived && it.id != "cat-zakat" }
    var cat by rememberSaveable { mutableStateOf(cats.firstOrNull()?.id) }
    var cur by rememberSaveable { mutableStateOf(view.accounts.firstOrNull()?.currency?.code ?: "USD") }
    val limit = remember { AmountState() }
    ScreenScaffold("الميزانية الشهرية", { nav.popBackStack() }, sub = "حدّ شهري لكل فئة بعملتها. لا تحويل بين العملات.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.ChevronRight, "الشهر السابق") }
            Text("${ReportDate.ar(month.atDay(1)).substringAfter(' ')}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Filled.ChevronLeft, "الشهر التالي") }
        }
        RCard {
            if (lines.isEmpty()) Text("لا ميزانيات بعد. أضف حداً لفئة مثل «أكل».", color = c.mute)
            lines.forEach { b ->
                BudgetRow(b)
                TextButton(onClick = { vm.launchSafe("حُذفت الميزانية") { vm.repo.deleteBudget(view.s.budgets.first { it.id == b.budgetId }) } }) { Text("حذف", color = c.mute) }
            }
        }
        SectionHeader("أضف أو عدّل ميزانية")
        RCard {
            ChipRow(cats.map { it.id }, cat, { id -> cats.first { it.id == id }.name }, { cat = it })
            ChipRow(view.accounts.map { it.currency.code }.distinct(), cur, { it }, { cur = it })
            AmountField(limit, Currencies.of(cur), "الحد الشهري")
            val m = limit.parse(Currencies.of(cur))
            PrimaryButton("احفظ", enabled = cat != null && m != null && m.minor > 0, modifier = Modifier.fillMaxWidth(), onClick = { vm.launchSafe("حُفظت الميزانية") { vm.repo.saveBudget(cat!!, Currencies.of(cur), m!!.minor); limit.text = "" } })
        }
    }
}

// ============================== الفواتير والمتكررات ==============================
@Composable
fun RecurringDueCard(vm: MainViewModel, items: List<Household.DueItem>, month: YearMonth, onOpen: () -> Unit) {
    val c = LocalR.current
    val v = vm.view.collectAsStateWithLifecycle().value ?: return
    RCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مستحق هذا الشهر", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpen) { Text("الكل") }
        }
        items.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.rec.name, style = MaterialTheme.typography.titleSmall)
                    Text("${if (d.rec.kind == "INCOME") "دخل" else "مصروف"} · يوم \u2066${d.dueDate.dayOfMonth}\u2069 · ${v.accountById[d.rec.accountId]?.name ?: ""}", style = MaterialTheme.typography.bodySmall, color = if (!d.done && d.dueDate.isBefore(vm.today())) c.warn else c.mute)
                }
                val acc = v.accountById[d.rec.accountId]
                acc?.let { AmountText(Money(d.rec.amountMinor, it.currency), style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.width(6.dp))
                if (d.done) StatusChip("سُجّل", Tone.OK)
                else FilledTonalButton(onClick = { vm.launchSafe("سُجّل ${d.rec.name}", undoable = true) { vm.repo.recordRecurring(d.rec, month) } }, modifier = Modifier.heightIn(min = 44.dp)) { Text("سجّل") }
            }
        }
    }
}

@Composable
fun RecurringScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val v by vm.view.collectAsStateWithLifecycle()
    val view = v ?: return
    val month = YearMonth.now()
    val due = remember(view) { Household.recurringDue(view, month) }
    var name by rememberSaveable { mutableStateOf("") }
    var income by rememberSaveable { mutableStateOf(false) }
    var account by rememberSaveable { mutableStateOf(view.accounts.firstOrNull()?.id) }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var day by rememberSaveable { mutableStateOf("1") }
    var member by rememberSaveable { mutableStateOf<String?>(null) }
    val amount = remember { AmountState() }
    val acc = view.accountById[account]
    val cats = view.s.categories.filter { it.kind == (if (income) "INCOME" else "EXPENSE") && !it.archived && it.id != "cat-zakat" }
    ScreenScaffold("الفواتير والمتكررات", { nav.popBackStack() }, sub = "لا يُسجَّل شيء تلقائياً: يظهر المستحق وتؤكّده بلمسة، ولا يتكرر في الشهر نفسه.") {
        if (due.isNotEmpty()) RecurringDueCard(vm, due, month) {}
        SectionHeader("متكرر جديد")
        RCard {
            TextIn(name, { name = it }, "الاسم (إيجار، إنترنت، راتب…)")
            ChipRow(listOf(false, true), income, { if (it) "دخل" else "مصروف" }, { income = it; cat = null })
            ChipRow(view.accounts.map { it.id }, account, { id -> view.accountById[id]!!.let { "${it.name} · ${it.currency.code}" } }, { account = it })
            ChipRow(cats.map { it.id }, cat, { id -> cats.first { it.id == id }.name }, { cat = it })
            AmountField(amount, acc?.currency)
            TextIn(day, { day = it.filter(Char::isDigit).take(2) }, "يوم الاستحقاق في الشهر (1–31)", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            if (view.s.members.isNotEmpty()) ChipRow(listOf<String?>(null) + view.s.members.map { it.id }, member, { id -> id?.let { i -> view.s.members.first { it.id == i }.name } ?: "مشترك" }, { member = it })
            val m = amount.parse(acc?.currency)
            PrimaryButton("احفظ", enabled = name.isNotBlank() && acc != null && cat != null && m != null && (day.toIntOrNull() ?: 0) in 1..31, modifier = Modifier.fillMaxWidth(), onClick = {
                vm.launchSafe("حُفظ") { vm.repo.saveRecurring(RecurringEntity(UUID.randomUUID().toString(), name, if (income) "INCOME" else "EXPENSE", acc!!.id, cat!!, m!!.minor, day.toInt(), member)); name = ""; amount.text = "" }
            })
        }
        SectionHeader("كل المتكررات")
        view.s.recurring.forEach { r ->
            RCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleSmall, color = if (r.active) c.txt else c.mute)
                        Text("يوم ${r.dayOfMonth} · ${view.categoryNames[r.categoryId] ?: ""}", style = MaterialTheme.typography.bodySmall, color = c.mute)
                    }
                    view.accountById[r.accountId]?.let { AmountText(Money(r.amountMinor, it.currency), style = MaterialTheme.typography.bodyMedium) }
                    Switch(r.active, { vm.launchSafe { vm.repo.saveRecurring(r.copy(active = it)) } })
                }
            }
            Spacer(Modifier.height(Sp.xs))
        }
    }
}
