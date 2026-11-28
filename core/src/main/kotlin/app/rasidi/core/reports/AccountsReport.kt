package app.rasidi.core.reports

import app.rasidi.core.data.LedgerView
import app.rasidi.core.ledger.PartyLedger
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import java.math.BigDecimal
import java.time.LocalDate

/**
 * F04/F05: تقرير «جميع الحسابات» من نموذج التقارير نفسه (لا لقطة شاشة).
 * - صف لكل شخص ولكل عملة؛ لا تقاصّ بين العملات (الشخص المدين بعملة والدائن بأخرى يظهر في التصنيفين).
 * - الإجماليات لكل عملة مستقلة، مع عدد الحسابات الفريد وعدد صفوف العملات.
 * - «كما في تاريخ» يُطبَّق على الحساب والترويسة معاً.
 */
object AccountsReport {
    enum class Scope(val ar: String) { VISIBLE("النتائج المعروضة"), ALL("جميع الحسابات"), DEBTOR("المدينة · لي معها"), CREDITOR("الدائنة · لها معي"), SETTLED("المسدّدة"), WALLETS("الصناديق") }
    enum class Sort(val ar: String) { NAME("الاسم"), BALANCE("الرصيد ضمن العملة"), LAST("آخر حركة") }

    data class Options(
        val asOf: LocalDate,
        val includeArchived: Boolean = false,
        val includeZero: Boolean = true,
        val sort: Sort = Sort.NAME,
        val showPhone: Boolean = false,
        val showNote: Boolean = false,
        /** للنطاق VISIBLE: أسماء الحسابات المعروضة حالياً (البحث والتصنيف). */
        val visibleNames: Set<String>? = null,
        /** إلحاق كشف تفصيلي لكل شخص وعملة. */
        val withStatements: Boolean = false,
    )

    private fun money(m: Money) = Cell.Num(m.amount, m.currency.minorDigits)

    fun build(v: LedgerView, scope: Scope, o: Options, generatedAt: String, p: PrivacyOptions = PrivacyOptions()): ReportDoc {
        val tables = mutableListOf<ReportTable>()
        val summary = mutableListOf<Pair<String, Cell>>()
        val meta = mutableListOf("كما في" to ReportBuilders.arDate(o.asOf), "أساس الحساب" to "أرصدة بالعملة الأصلية دون تحويل ولا تقاصّ بين العملات")
        var mask = 0
        val masks = HashMap<String, String>()
        fun nm(n: String) = if (p.hideNames) masks.getOrPut(n) { "حساب ${++mask}" } else n

        if (scope != Scope.WALLETS) {
            val all = PartyLedger.parties(v, o.includeArchived, o.asOf)
            data class R(val party: PartyLedger.PartySummary, val cur: String?, val bal: Money?)
            var rows = all.flatMap { ps ->
                val nonZero = ps.balances.filterValues { !it.isZero }
                when {
                    ps.balances.isEmpty() -> listOf(R(ps, null, null)) // حساب جديد بلا حركات
                    nonZero.isEmpty() -> ps.balances.keys.sorted().map { R(ps, it, ps.balances[it]) }
                    else -> (if (o.includeZero) ps.balances else nonZero).toSortedMap().map { (c, m) -> R(ps, c, m) }
                }
            }
            rows = when (scope) {
                Scope.DEBTOR -> rows.filter { (it.bal?.minor ?: 0) > 0 }
                Scope.CREDITOR -> rows.filter { (it.bal?.minor ?: 0) < 0 }
                Scope.SETTLED -> rows.filter { it.party.settled }
                Scope.VISIBLE -> rows.filter { o.visibleNames == null || it.party.party in o.visibleNames }
                else -> rows
            }
            if (!o.includeZero && scope != Scope.SETTLED) rows = rows.filter { it.bal != null && !it.bal.isZero }
            rows = when (o.sort) {
                Sort.NAME -> rows.sortedWith(compareBy<R> { it.party.party }.thenBy { it.cur ?: "" })
                // ترتيب الرصيد داخل كل عملة فقط (لا مقارنة أرقام بين عملات مختلفة)
                Sort.BALANCE -> rows.sortedWith(compareBy<R> { it.cur ?: "~" }.thenByDescending { it.bal?.minor ?: 0 }.thenBy { it.party.party })
                Sort.LAST -> rows.sortedWith(compareByDescending<R> { it.party.lastDate ?: LocalDate.MIN }.thenBy { it.party.party })
            }
            val cols = mutableListOf(Column("م", ColType.CODE, 0.4f), Column("الحساب", ColType.TEXT, 1.4f), Column("العملة", ColType.CODE, 0.6f),
                Column("عليه (مدين)", ColType.NUMBER), Column("له (دائن)", ColType.NUMBER), Column("الصافي", ColType.NUMBER), Column("الاتجاه", ColType.TEXT, 0.7f), Column("آخر حركة", ColType.DATE))
            if (o.showPhone && !p.hideNames) cols += Column("الهاتف", ColType.CODE)
            if (o.showNote && !p.hideNotes) cols += Column("ملاحظة", ColType.TEXT, 1.2f)
            val body = rows.mapIndexed { i, r ->
                val b = r.bal
                val dir = when { r.cur == null -> "بلا حركات"; b!!.minor > 0 -> "لي معه"; b.minor < 0 -> "له معي"; else -> "مسدّد" }
                buildList {
                    add(Cell.Text("${i + 1}")); add(Cell.Text(nm(r.party.party))); add(Cell.t(r.cur))
                    add(if (b != null && b.minor > 0) money(b) else Cell.Empty)
                    add(if (b != null && b.minor < 0) money(-b) else Cell.Empty)
                    add(if (b != null) money(b) else Cell.Empty); add(Cell.Text(dir)); add(Cell.d(r.party.lastDate))
                    if (o.showPhone && !p.hideNames) add(Cell.t(r.party.phone))
                    if (o.showNote && !p.hideNotes) add(Cell.t(r.party.note))
                }
            }
            val pad = cols.size - 8
            val totals = rows.mapNotNull { it.cur }.distinct().sorted().map { c ->
                val cur = Currencies.of(c)
                val l = rows.filter { it.cur == c }.mapNotNull { it.bal }
                val deb = l.filter { it.minor > 0 }.fold(Money.zero(cur)) { a, m -> a + m }
                val cred = l.filter { it.minor < 0 }.fold(Money.zero(cur)) { a, m -> a + (-m) }
                listOf(Cell.Empty, Cell.Text("الإجمالي"), Cell.Text(c), money(deb), money(cred), money(deb - cred), Cell.Empty, Cell.Empty) + List(pad) { Cell.Empty }
            }
            tables += ReportTable("الحسابات — ${scope.ar}", "الحسابات", cols, body, totals, keepInSummary = false)
            val unique = rows.map { it.party.party }.distinct().size
            summary += "عدد الحسابات" to Cell.Num(BigDecimal(unique), 0)
            if (rows.size != unique) summary += "صفوف العملات" to Cell.Num(BigDecimal(rows.size), 0)
            totals.forEach { t -> summary += "صافي ${(t[2] as Cell.Text).v}" to t[5] }
            if (o.withStatements) rows.filter { it.cur != null }.map { it.party.party to it.cur!! }.distinct().forEach { (party, c) ->
                val (from, to) = PartyLedger.allRange(v, party, c, o.asOf).let { (f, t) -> f to minOf(t, o.asOf) }
                val st = ReportBuilders.partyStatement(v, party, c, from, to, false, generatedAt, p = p)
                st.tables.forEach { tb -> tables += tb.copy(title = "كشف: ${nm(party)} (${c})", sheetName = "كشف ${tables.size}") }
            }
        }

        if (scope == Scope.ALL || scope == Scope.WALLETS) {
            val accs = v.accounts.filter { o.includeArchived || !it.archived }
            val wrows = accs.map { it to v.balance(it, o.asOf) }.filter { o.includeZero || !it.second.isZero }
                .let { l -> if (o.sort == Sort.BALANCE) l.sortedWith(compareBy<Pair<app.rasidi.core.ledger.Account, Money>> { it.first.currency.code }.thenByDescending { it.second.minor }) else l.sortedBy { it.first.name } }
            val body = wrows.mapIndexed { i, (a, b) -> listOf(Cell.Text("${i + 1}"), Cell.Text(a.name), Cell.Text(a.type.ar), Cell.Text(a.currency.code), money(b),
                Cell.Text(when { a.archived -> "مؤرشف"; b.minor < 0 -> "سالب"; else -> "نشط" })) }
            val totals = wrows.groupBy { it.first.currency.code }.toSortedMap().map { (c, l) ->
                listOf(Cell.Empty, Cell.Text("الإجمالي"), Cell.Empty, Cell.Text(c), money(l.fold(Money.zero(Currencies.of(c))) { a, x -> a + x.second }), Cell.Empty) }
            tables += ReportTable("الصناديق", "الصناديق", listOf(Column("م", ColType.CODE, 0.4f), Column("الصندوق", ColType.TEXT, 1.4f), Column("النوع", ColType.TEXT, 0.7f),
                Column("العملة", ColType.CODE, 0.6f), Column("الرصيد", ColType.NUMBER), Column("الحالة", ColType.TEXT, 0.7f)), body, totals)
            totals.forEach { t -> summary += "الصناديق ${(t[3] as Cell.Text).v}" to t[4] }
        }
        val filters = listOf("النطاق" to scope.ar, "المؤرشفة" to if (o.includeArchived) "مُدرجة" else "مستبعدة",
            "الصفرية" to if (o.includeZero) "مُدرجة" else "مستبعدة", "الترتيب" to o.sort.ar)
        return ReportDoc(ReportDoc.Kind.REPORT, "تقرير الحسابات — ${scope.ar}", "كما في ${ReportBuilders.arDate(o.asOf)}", generatedAt,
            filters = filters, meta = meta + (if (p.hideNames) listOf("الخصوصية" to "الأسماء مخفية") else emptyList()), summary = summary, tables = tables,
            fileBase = FileNames.safe("رصيدي-الحسابات-${scope.name.lowercase()}-${o.asOf}"))
    }
}
