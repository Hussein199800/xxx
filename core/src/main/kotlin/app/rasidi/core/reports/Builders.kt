package app.rasidi.core.reports

import app.rasidi.core.data.LedgerView
import app.rasidi.core.data.TxnEntity
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.core.valuation.InventoryResult
import app.rasidi.core.valuation.LineKind
import app.rasidi.core.zakat.ZakatPayment
import app.rasidi.core.zakat.ZakatResult
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** خيارات الخصوصية قبل التصدير. */
data class PrivacyOptions(val hideNames: Boolean = false, val hideNotes: Boolean = false)

/** يبني نماذج التقارير من الدفتر. كل منشئ يعيد [ReportDoc] واحداً تستهلكه الشاشة والصيغ الثلاث. */
object ReportBuilders {
    private val arMonths = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    fun arDate(d: LocalDate) = "${d.dayOfMonth} ${arMonths[d.monthValue - 1]} ${d.year}"
    fun period(from: LocalDate, to: LocalDate) = if (from == to) arDate(from) else "${arDate(from)} – ${arDate(to)}"
    private fun stamp(from: LocalDate, to: LocalDate) = when {
        from.withDayOfMonth(1) == from && to == from.withDayOfMonth(from.lengthOfMonth()) -> from.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        from.dayOfYear == 1 && to == from.withDayOfYear(from.lengthOfYear()) -> from.year.toString()
        else -> "${from}_${to}"
    }
    private fun name(s: String, p: PrivacyOptions, mask: String = "—") = if (p.hideNames && s.isNotBlank()) mask else s
    private fun note(s: String, p: PrivacyOptions) = if (p.hideNotes) "" else s
    private fun money(m: Money) = Cell.Num(m.amount, m.currency.minorDigits)
    private fun typeAr(t: TxnEntity?) = t?.let { runCatching { TxnType.valueOf(it.type).ar }.getOrDefault(it.type) } ?: ""

    /** كشف حساب بعملته الأصلية: الرصيد قبل الفترة، الحركات، الرصيد في النهاية. */
    fun accountStatement(v: LedgerView, accountId: String, from: LocalDate, to: LocalDate, generatedAt: String, p: PrivacyOptions = PrivacyOptions()): ReportDoc {
        val acc = v.accountById.getValue(accountId)
        val st = Balances.statement(acc, v.entries, from, to)
        val cur = acc.currency
        var inSum = Money.zero(cur); var outSum = Money.zero(cur)
        val rows = st.lines.map { (e, run) ->
            val t = v.txnById[e.txnId]
            if (e.amount.minor >= 0) inSum += e.amount else outSum += -e.amount
            listOf(
                Cell.d(e.date), Cell.t(typeAr(t)), Cell.t(e.categoryId?.let { v.categoryNames[it] } ?: ""),
                Cell.t(note(t?.note ?: "", p)), Cell.t(name(t?.party ?: "", p)),
                if (e.amount.minor >= 0) money(e.amount) else Cell.Empty,
                if (e.amount.minor < 0) money(-e.amount) else Cell.Empty,
                money(run), Cell.Text(cur.code), Cell.t(e.txnId.takeLast(8)),
            )
        }
        return ReportDoc(
            ReportDoc.Kind.REPORT, "كشف الحساب: ${acc.name}", period(from, to), generatedAt,
            filters = listOf("الحساب" to "${acc.name} (${acc.type.ar})", "العملة" to "${cur.nameAr} ${cur.code}"),
            meta = listOf("أساس الحساب" to "القيود المؤرخة بتاريخ العملية، بالعملة الأصلية دون تحويل") + privacyMeta(p),
            summary = listOf(
                "الرصيد قبل الفترة (${cur.code})" to money(st.opening),
                "مجموع الوارد (${cur.code})" to money(inSum),
                "مجموع الصادر (${cur.code})" to money(outSum),
                "الرصيد في نهاية الفترة (${cur.code})" to money(st.closing),
                "عدد الحركات" to Cell.Num(BigDecimal(rows.size), 0),
            ),
            tables = listOf(ReportTable("حركات الفترة", "التفاصيل", STATEMENT_COLS, rows,
                totals = listOf(listOf(Cell.Empty, Cell.Text("الإجمالي"), Cell.Empty, Cell.Empty, Cell.Empty, money(inSum), money(outSum), money(st.closing), Cell.Text(cur.code), Cell.Empty)))),
            fileBase = FileNames.safe("رصيدي-كشف-الحساب-${acc.name}-${stamp(from, to)}"),
        )
    }

    private val STATEMENT_COLS = listOf(
        Column("التاريخ", ColType.DATE), Column("النوع", ColType.TEXT, 0.8f), Column("الفئة", ColType.TEXT, 0.8f),
        Column("البيان", ColType.TEXT, 1.6f), Column("الطرف", ColType.TEXT, 0.9f),
        Column("وارد", ColType.NUMBER), Column("صادر", ColType.NUMBER), Column("الرصيد", ColType.NUMBER),
        Column("العملة", ColType.CODE), Column("المرجع", ColType.CODE),
    )

    private fun privacyMeta(p: PrivacyOptions) = buildList {
        if (p.hideNames) add("الخصوصية" to "الأسماء مخفية")
        if (p.hideNotes) add("الملاحظات" to "مخفية")
    }

    /** تقرير فترة: دخل، مصروف حسب الفئة، صافي تدفق، ودين سُدّد أو حُصّل — بالعملات الأصلية. */
    fun periodReport(v: LedgerView, from: LocalDate, to: LocalDate, generatedAt: String, label: String, p: PrivacyOptions = PrivacyOptions(), detailed: Boolean = true): ReportDoc {
        val totals = PeriodTotals.of(v.entries, from, to)
        val codes = (totals.income.keys + totals.expense.keys + totals.debtSettled.keys).toSortedSet()
        val flowRows = codes.map { c ->
            val cur = Currencies.of(c)
            val inc = totals.income[c] ?: Money.zero(cur); val exp = totals.expense[c] ?: Money.zero(cur)
            listOf(Cell.Text(cur.nameAr), money(inc), money(exp), money(inc - exp), money(totals.debtSettled[c] ?: Money.zero(cur)), Cell.Text(c))
        }
        val catRows = totals.expenseByCategory.entries.sortedWith(compareBy({ it.key.second }, { -it.value.minor }))
            .map { (k, m) -> listOf(Cell.Text(k.first?.let { v.categoryNames[it] } ?: "بلا فئة"), money(m), Cell.Text(k.second)) }
        val inRange = v.entries.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedWith(compareBy({ it.date }, { it.txnId }))
        val detail = inRange.map { e ->
            val t = v.txnById[e.txnId]
            listOf(Cell.d(e.date), Cell.t(typeAr(t)), Cell.t(v.accountById[e.accountId]?.name ?: ""), Cell.t(e.categoryId?.let { v.categoryNames[it] } ?: ""),
                Cell.t(note(t?.note ?: "", p)), Cell.t(name(t?.party ?: "", p)), money(e.amount), Cell.Text(e.amount.currency.code))
        }
        val tables = mutableListOf(
            ReportTable("صافي التدفق حسب العملة", "صافي التدفق", listOf(Column("العملة", ColType.TEXT), Column("الدخل", ColType.NUMBER), Column("المصروف", ColType.NUMBER), Column("الصافي", ColType.NUMBER), Column("ديون سُدّدت/حُصّلت", ColType.NUMBER), Column("الرمز", ColType.CODE)), flowRows, keepInSummary = true),
            ReportTable("المصروف حسب الفئة", "حسب الفئة", listOf(Column("الفئة", ColType.TEXT, 1.5f), Column("المبلغ", ColType.NUMBER), Column("العملة", ColType.CODE)), catRows, keepInSummary = true),
        )
        // المصروف حسب فرد البيت (إن وُجد أفراد)
        if (v.s.members.isNotEmpty()) {
            val byMember = app.rasidi.core.household.Household.spendByMember(v, from, to).flatMap { m ->
                m.byCurrency.values.map { listOf(Cell.Text(if (p.hideNames && m.memberId != null) "فرد" else m.name), money(it), Cell.Text(it.currency.code)) }
            }
            tables += ReportTable("المصروف حسب الفرد", "حسب الفرد", listOf(Column("الفرد", ColType.TEXT, 1.5f), Column("المبلغ", ColType.NUMBER), Column("العملة", ColType.CODE)), byMember, keepInSummary = true)
        }
        if (detailed) tables += ReportTable("تفاصيل الحركات", "التفاصيل", listOf(Column("التاريخ", ColType.DATE), Column("النوع", ColType.TEXT, 0.8f), Column("الحساب", ColType.TEXT, 0.9f), Column("الفئة", ColType.TEXT, 0.8f), Column("البيان", ColType.TEXT, 1.6f), Column("الطرف", ColType.TEXT, 0.8f), Column("المبلغ", ColType.NUMBER), Column("العملة", ColType.CODE)), detail)
        return ReportDoc(
            ReportDoc.Kind.REPORT, "تقرير $label", period(from, to), generatedAt,
            filters = listOf("الفترة" to period(from, to), "النوع" to if (detailed) "تفصيلي" else "ملخّص"),
            meta = listOf("قاعدة" to "التحويل الداخلي والرصيد الافتتاحي مستبعدان من الدخل والمصروف؛ القيم بعملتها الأصلية") + privacyMeta(p),
            summary = codes.flatMap { c ->
                val cur = Currencies.of(c)
                listOf("الدخل ($c)" to money(totals.income[c] ?: Money.zero(cur)), "المصروف ($c)" to money(totals.expense[c] ?: Money.zero(cur)))
            } + ("عدد القيود" to Cell.Num(BigDecimal(inRange.size), 0)),
            tables = tables,
            fileBase = FileNames.safe("رصيدي-تقرير-$label-${stamp(from, to)}"),
        )
    }

    /** تقرير دين: الأصل، الدفعات بعملتيها، الأقساط، المتبقي والحالة. */
    fun debtReport(v: LedgerView, debtId: String, today: LocalDate, generatedAt: String, p: PrivacyOptions = PrivacyOptions()): ReportDoc {
        val d = v.debts.first { it.id == debtId }
        val pays = v.payments.filter { it.debtId == debtId }.sortedBy { it.date }
        val rem = DebtCalc.remaining(d, pays)
        var cum = Money.zero(d.currency)
        val rows = pays.map { pm ->
            cum += pm.debtAmount
            listOf(Cell.d(pm.date), money(pm.debtAmount), Cell.Text(d.currency.code), pm.walletAmount?.let { money(it.abs()) } ?: Cell.Empty, Cell.t(pm.walletAmount?.currency?.code),
                Cell.t(pm.accountId?.let { v.accountById[it]?.name } ?: "بلا حركة نقدية"), money(d.effectivePrincipal - cum))
        }
        val inst = d.installments.map { listOf(Cell.d(it.dueDate), money(it.amount), Cell.Text(d.currency.code)) }
        return ReportDoc(
            ReportDoc.Kind.REPORT, "كشف دين: ${name(d.party, p, "طرف")}", "منذ ${arDate(d.date)}", generatedAt,
            filters = listOf("الاتجاه" to (if (d.direction == DebtDirection.OWED_TO_ME) "لي عند الناس" else "عليّ للناس")),
            meta = listOf("الحالة" to DebtCalc.status(d, pays, today).ar) + (d.dueDate?.let { listOf("الاستحقاق" to it.toString()) } ?: emptyList()) + privacyMeta(p),
            summary = listOf("أصل الدين (${d.currency.code})" to money(d.principal), "المدفوع (${d.currency.code})" to money(DebtCalc.paid(d, pays)), "المتبقي (${d.currency.code})" to money(rem)) +
                (if (d.cancelled) listOf("ملغى بعكس حركة الإنشاء" to Cell.Text("الأصل لا يُحتسب")) else emptyList()),
            tables = listOfNotNull(
                ReportTable("الدفعات", "الدفعات", listOf(Column("التاريخ", ColType.DATE), Column("مبلغ الدين", ColType.NUMBER), Column("عملة الدين", ColType.CODE), Column("مبلغ المحفظة", ColType.NUMBER), Column("عملة المحفظة", ColType.CODE), Column("الحساب", ColType.TEXT), Column("المتبقي بعدها", ColType.NUMBER)), rows),
                if (inst.isNotEmpty()) ReportTable("جدول الأقساط", "الأقساط", listOf(Column("الاستحقاق", ColType.DATE), Column("المبلغ", ColType.NUMBER), Column("العملة", ColType.CODE)), inst) else null,
            ),
            fileBase = FileNames.safe("رصيدي-دين-${name(d.party, p, "طرف")}-$today"),
        )
    }

    /** تقرير الجرد: كل بند بعملته وقيمته بالدولار، مع الأسعار ومصادرها والبنود الناقصة. */
    fun inventoryReport(inv: InventoryResult, generatedAt: String, revisionLabel: String, status: String): ReportDoc {
        val rows = inv.lines.map { l ->
            listOf(Cell.Text(l.item.kind.ar), Cell.Text(l.item.label), Cell.Text(l.item.original),
                Cell.t(l.rate?.label()), Cell.t(l.rate?.source), Cell.d(l.rate?.date),
                l.signedUsd?.let { Cell.Num(it.setScale(2, MoneyPolicy.ROUNDING)) } ?: Cell.Empty, Cell.Text(l.state.ar))
        }
        val kinds = LineKind.values().map { k -> listOf(Cell.Text(k.ar), Cell.Num(inv.total(k).let { if (k.isLiability) it.negate() else it }.setScale(2, MoneyPolicy.ROUNDING)), Cell.Text("USD")) }
        val rates = inv.lines.mapNotNull { it.rate }.distinctBy { it.id }.map { r -> listOf(Cell.Text(r.label()), Cell.Text(r.source), Cell.d(r.date), Cell.Num(r.usdPerUnit.stripTrailingZeros().let { if (it.scale() > 8) it.setScale(8, MoneyPolicy.ROUNDING) else it }, 8)) }
        return ReportDoc(
            ReportDoc.Kind.REPORT, "الجرد بتاريخ ${arDate(inv.date)}", "تاريخ التقييم: ${inv.date}", generatedAt,
            meta = listOf("تاريخ التقييم" to inv.date.toString(), "حالة التقرير" to status, "معرّف مراجعة الجرد" to revisionLabel,
                "مصدر الأسعار" to inv.lines.mapNotNull { it.rate?.source }.distinct().joinToString("، ").ifEmpty { "—" }),
            summary = listOf((if (inv.isComplete) "صافي الأموال (USD)" else "إجمالي البنود المسعّرة (USD) — جزئي") to Cell.Num(inv.pricedNet.setScale(2, MoneyPolicy.ROUNDING)),
                "عدد البنود" to Cell.Num(BigDecimal(inv.lines.size), 0), "بنود بلا سعر معتمد" to Cell.Num(BigDecimal(inv.missingCount), 0)),
            warnings = if (inv.isComplete) emptyList() else listOf("${inv.missingCount} بند بلا سعر معتمد؛ الإجمالي المعروض جزئي والجرد مسودة") + inv.lines.filter { !it.counts }.map { "${it.item.label}: ${it.note ?: it.state.ar}" },
            tables = listOf(
                ReportTable("الإجمالي حسب النوع", "حسب النوع", listOf(Column("النوع", ColType.TEXT), Column("القيمة", ColType.NUMBER), Column("العملة", ColType.CODE)), kinds, keepInSummary = true),
                ReportTable("بنود الجرد", "البنود", listOf(Column("النوع", ColType.TEXT, 0.6f), Column("البند", ColType.TEXT, 1.1f), Column("القيمة الأصلية", ColType.TEXT, 1f), Column("السعر", ColType.TEXT, 1.1f), Column("المصدر", ColType.TEXT, 0.8f), Column("تاريخ السعر", ColType.DATE), Column("بالدولار", ColType.NUMBER), Column("الحالة", ColType.TEXT, 0.7f)), rows,
                    totals = listOf(listOf(Cell.Empty, Cell.Text(if (inv.isComplete) "صافي الأموال" else "إجمالي جزئي"), Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Num(inv.pricedNet.setScale(2, MoneyPolicy.ROUNDING)), Cell.Empty))),
                ReportTable("الأسعار المستخدمة", "الأسعار", listOf(Column("السعر", ColType.TEXT, 1.2f), Column("المصدر", ColType.TEXT), Column("التاريخ", ColType.DATE), Column("دولار لكل وحدة", ColType.NUMBER)), rates),
            ),
            fileBase = FileNames.safe("رصيدي-الجرد-${inv.date}"),
        )
    }

    /** تقرير الزكاة: البنود وقراراتها، النصاب، الحول، التقدير، والتوزيع. */
    fun zakatReport(z: ZakatResult, payments: List<ZakatPayment>, generatedAt: String, revisionLabel: String, accountNames: Map<String, String>, showBeneficiaries: Boolean = false): ReportDoc {
        val r2 = { b: BigDecimal -> b.setScale(2, MoneyPolicy.ROUNDING) }
        val prog = app.rasidi.core.zakat.Distribution.progress(z.zakatUsd, emptyList(), payments)
        val lines = z.lines.map { l -> listOf(Cell.Text(l.line.item.kind.ar), Cell.Text(l.line.item.label), Cell.Text(l.line.item.original), l.line.usd?.let { Cell.Num(r2(it)) } ?: Cell.Empty, Cell.Text(l.decision.decision.ar), Cell.Num(r2(l.countedUsd)), Cell.t(l.decision.reason)) }
        val pays = payments.sortedBy { it.date }.map { p ->
            listOf(Cell.d(p.date), money(p.amount), Cell.Text(p.amount.currency.code), Cell.Text(p.rate.label()), Cell.Num(r2(p.usd)), Cell.t(p.accountId?.let { accountNames[it] }), Cell.t(if (showBeneficiaries) p.beneficiary else if (p.beneficiary.isNullOrBlank()) "" else "مخفي"))
        }
        return ReportDoc(
            ReportDoc.Kind.REPORT, "تقدير الزكاة", "جرد ${arDate(z.input.inventory.date)}", generatedAt,
            meta = listOf(
                "تاريخ التقييم" to z.input.inventory.date.toString(), "معرّف مراجعة الجرد" to revisionLabel,
                "الحول الهجري" to "${z.input.hawlHijri} — ${if (z.input.hawlComplete) "مكتمل (بتأكيد المستخدم)" else "غير مؤكّد"} — ${z.input.hawlMethod}",
                "سياسة النصاب" to z.input.nisab.label, "سعر المعدن" to (z.nisabRate?.let { "${it.label()} — ${it.source} — ${it.date}" } ?: "غير متوفر"),
                "النسبة" to "${z.input.percent.multiply(BigDecimal(100)).stripTrailingZeros().toPlainString()}%", "حالة التقرير" to z.status.ar,
                "تنبيه" to "تقدير مبني على الخيارات التي أكّدها المستخدم، وليس فتوى",
            ),
            summary = listOf(
                "الأموال والأصول المدرجة (USD)" to Cell.Num(r2(z.assetsUsd)), "الديون التي لي المدرجة (USD)" to Cell.Num(r2(z.receivablesUsd)),
                "الالتزامات المخصومة (USD)" to Cell.Num(r2(z.deductionsUsd)), "الوعاء (USD)" to Cell.Num(r2(z.baseUsd)),
                "قيمة النصاب (USD)" to (z.nisabUsd?.let { Cell.Num(r2(it)) } ?: Cell.Text("غير مسعّر")),
                "الزكاة المقدّرة (USD)" to Cell.Num(z.zakatUsd), "المدفوع (USD)" to Cell.Num(r2(prog.paidUsd)),
                "المتبقي (USD)" to Cell.Num(prog.remainingUsd.max(BigDecimal.ZERO)),
            ) + (if (prog.overpaidUsd.signum() > 0) listOf("مدفوع زائد (USD)" to Cell.Num(prog.overpaidUsd)) else emptyList()),
            warnings = z.reasons,
            tables = listOf(
                ReportTable("بنود الوعاء وقرارات الإدراج", "البنود", listOf(Column("النوع", ColType.TEXT, 0.6f), Column("البند", ColType.TEXT, 1f), Column("القيمة الأصلية", ColType.TEXT), Column("بالدولار", ColType.NUMBER), Column("القرار", ColType.TEXT, 0.7f), Column("المحتسب", ColType.NUMBER), Column("السبب", ColType.TEXT, 1.1f)), lines,
                    totals = listOf(listOf(Cell.Empty, Cell.Text("الوعاء"), Cell.Empty, Cell.Empty, Cell.Empty, Cell.Num(r2(z.baseUsd)), Cell.Empty))),
                ReportTable("دفعات التوزيع", "التوزيع", listOf(Column("التاريخ", ColType.DATE), Column("المبلغ", ColType.NUMBER), Column("العملة", ColType.CODE), Column("سعر يوم الدفع", ColType.TEXT, 1.1f), Column("بالدولار", ColType.NUMBER), Column("المحفظة", ColType.TEXT, 0.9f), Column("المستفيد", ColType.TEXT, 0.8f)), pays,
                    totals = listOf(listOf(Cell.Empty, Cell.Empty, Cell.Empty, Cell.Text("المدفوع"), Cell.Num(r2(prog.paidUsd)), Cell.Text(prog.remainingLabel), Cell.Empty))),
            ),
            fileBase = FileNames.safe("رصيدي-الزكاة-${z.input.inventory.date}"),
        )
    }

    /** سند مستخرج من حركة موجودة (قبض، صرف، صرف عملات، تحويل، إشعار مدين/دائن) بقالب السندات. */
    fun voucher(v: LedgerView, txnId: String, generatedAt: String, p: PrivacyOptions = PrivacyOptions(),
                opt: app.rasidi.core.voucher.VoucherOptions = app.rasidi.core.voucher.VoucherOptions(), asNotice: Boolean = false): ReportDoc {
        val vc = app.rasidi.core.voucher.VoucherBuilder.build(v, txnId, opt.copy(hideParty = p.hideNames || opt.hideParty), generatedAt, asNotice)
        val t = v.txnById.getValue(txnId)
        return ReportDoc(
            ReportDoc.Kind.VOUCHER, vc.kind.title, "رقم ${vc.number} · ${vc.dateLine}", generatedAt,
            meta = listOf("" to vc.sentence),
            tables = listOf(ReportTable("تفاصيل السند", "السند", listOf(Column("البند", ColType.TEXT, 0.8f), Column("القيمة", ColType.TEXT, 2f)),
                (listOfNotNull(
                    ("الأخ" to "${vc.party} المحترم").takeIf { vc.party.isNotBlank() },
                    "المبلغ" to Fmt.money(vc.amount, false),
                    "كتابةً" to vc.words,
                    ("البيان" to vc.statement).takeIf { vc.statement.isNotBlank() && !p.hideNotes },
                ) + vc.details).map { (k, x) -> listOf(Cell.Text(k), Cell.Text(x)) })),
            fileBase = FileNames.safe("رصيدي-${vc.kind.title}-${vc.number}-${t.date}"),
            voucher = if (p.hideNotes) vc.copy(statement = "") else vc,
        )
    }

    /** كشف حساب شخص: النوع (عليه/له)، المبلغ، التفاصيل، الرصيد، مع رصيد سابق اختياري. */
    fun partyStatement(v: LedgerView, party: String, currency: String, from: LocalDate, to: LocalDate, includePrior: Boolean, generatedAt: String, footerNote: String = "", p: PrivacyOptions = PrivacyOptions()): ReportDoc {
        val st = PartyLedger.statement(v, party, currency, from, to, includePrior)
        val cur = Currencies.of(currency)
        val rows = mutableListOf<List<Cell>>()
        if (includePrior) rows += listOf(Cell.d(from), Cell.Text("رصيد سابق"), Cell.Empty, Cell.Empty, money(st.prior), Cell.Text(currency))
        st.lines.forEach { l ->
            rows += listOf(Cell.d(l.date), Cell.Text(if (l.onHim != null) "عليه" else "له"), money(l.onHim ?: l.forHim!!), Cell.t(note(l.details, p)), money(l.balance), Cell.Text(currency))
        }
        val name = if (p.hideNames) "طرف" else party
        return ReportDoc(
            ReportDoc.Kind.REPORT, "كشف حساب: $name", period(from, to), generatedAt,
            filters = listOf("العملة" to cur.nameAr, "رصيد سابق" to if (includePrior) "مُدرج" else "غير مُدرج") +
                (if (st.lines.any { it.date.isAfter(LocalDate.now()) }) listOf("تنبيه" to "يشمل حركات بتاريخ مستقبلي") else emptyList()),
            summary = listOf("عليه ($currency)" to money(st.totalOn), "له ($currency)" to money(st.totalFor), "${st.closingLabel} ($currency)" to money(st.closing.abs())),
            tables = listOf(ReportTable("الحركات", "الكشف", listOf(Column("التاريخ", ColType.DATE), Column("النوع", ColType.TEXT, 0.5f), Column("المبلغ", ColType.NUMBER),
                Column("التفاصيل", ColType.TEXT, 1.6f), Column("الرصيد", ColType.NUMBER), Column("العملة", ColType.CODE)), rows,
                totals = listOf(
                    listOf(Cell.Empty, Cell.Text("عليه"), money(st.totalOn), Cell.Empty, Cell.Empty, Cell.Text(currency)),
                    listOf(Cell.Empty, Cell.Text("له"), money(st.totalFor), Cell.Empty, Cell.Empty, Cell.Text(currency)),
                    listOf(Cell.Empty, Cell.Text(st.closingLabel), Cell.Empty, Cell.Empty, money(st.closing.abs()), Cell.Text(currency))))),
            fileBase = FileNames.safe("رصيدي-كشف-$name-$currency-${stamp(from, to)}"),
            footerNote = footerNote,
        )
    }

    fun kindAr(k: EntryKind) = when (k) {
        EntryKind.OPENING -> "افتتاحي"; EntryKind.INCOME -> "دخل"; EntryKind.EXPENSE -> "مصروف"
        EntryKind.TRANSFER_OUT -> "تحويل صادر"; EntryKind.TRANSFER_IN -> "تحويل وارد"; EntryKind.FEE -> "رسوم"
        EntryKind.DEBT_PRINCIPAL -> "أصل دين"; EntryKind.DEBT_SETTLEMENT -> "تسوية دين"; EntryKind.GOLD_TRADE -> "ذهب"
        EntryKind.ADJUSTMENT -> "تسوية"; EntryKind.ZAKAT -> "زكاة"
    }

    /** CSV لتبادل البيانات (UTF-8 مع BOM). */
    fun csv(t: ReportTable): ByteArray {
        fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' } || s.startsWith("=") || s.startsWith("+") || s.startsWith("-") && s.toBigDecimalOrNull() == null || s.startsWith("@")) "\"" + s.replace("\"", "\"\"").let { if (it.startsWith("=") || it.startsWith("+") || it.startsWith("@")) "'$it" else it } + "\"" else s
        val sb = StringBuilder("﻿")
        sb.append(t.columns.joinToString(",") { q(it.header) }).append("\r\n")
        t.rows.forEach { r -> sb.append(r.joinToString(",") { c -> when (c) { is Cell.Num -> c.v.toPlainString(); else -> q(c.display()) } }).append("\r\n") }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
