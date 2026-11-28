package app.rasidi.core.zakat

import app.rasidi.core.explain.Explanation
import app.rasidi.core.ledger.GoldCalc
import app.rasidi.core.ledger.GoldKind
import app.rasidi.core.money.Fmt
import app.rasidi.core.money.Money
import app.rasidi.core.money.MoneyPolicy
import app.rasidi.core.money.Rate
import app.rasidi.core.money.RateBook
import app.rasidi.core.money.RateLookup
import app.rasidi.core.valuation.InventoryResult
import app.rasidi.core.valuation.LineKind
import app.rasidi.core.valuation.PricedLine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** سياسة النصاب. الوزن بالغرام والنقاء بالعيار (من 24) للذهب أو بالألف للفضة. */
sealed interface NisabPolicy {
    val label: String
    val metalUnit: String
    val pureGrams: BigDecimal

    data object Gold85 : NisabPolicy {
        override val label = "85غ ذهب عيار 24"; override val metalUnit = Rate.GOLD24_GRAM; override val pureGrams = BigDecimal(85)
    }
    data object Silver595 : NisabPolicy {
        override val label = "595غ فضة خالصة"; override val metalUnit = Rate.SILVER_GRAM; override val pureGrams = BigDecimal(595)
    }
    /** إعداد يدوي موثّق: الوزن والعيار والمرجع. */
    data class Manual(val grams: BigDecimal, val karat: Int, val gold: Boolean, val reference: String) : NisabPolicy {
        override val label = "${Fmt.plain(grams)}غ ${if (gold) "ذهب عيار $karat" else "فضة"} — $reference"
        override val metalUnit = if (gold) Rate.GOLD24_GRAM else Rate.SILVER_GRAM
        override val pureGrams: BigDecimal = if (gold) GoldCalc.pureGrams(grams, karat) else grams
    }
}

enum class Decision(val ar: String) { INCLUDE("مدرج"), EXCLUDE("مستبعد"), PARTIAL("مدرج جزئياً"), REVIEW("يحتاج مراجعة") }

/** قرار الإدراج لكل بند مع المبلغ (للجزئي) والسبب. */
data class LineDecision(val decision: Decision, val partialUsd: BigDecimal? = null, val reason: String = "")

enum class ZakatStatus(val ar: String) { DUE("تجب الزكاة"), BELOW_NISAB("أقل من النصاب"), NEEDS_REVIEW("يحتاج مراجعة") }

data class ZakatInput(
    val inventory: InventoryResult,
    val decisions: Map<String, LineDecision>,
    val nisab: NisabPolicy,
    val rates: List<Rate>,
    val hawlHijri: String,
    val hawlMethod: String,
    val hawlComplete: Boolean,
    val percent: BigDecimal = BigDecimal("0.025"),
)

data class ZakatLine(val line: PricedLine, val decision: LineDecision, val countedUsd: BigDecimal)

data class ZakatResult(
    val input: ZakatInput,
    val lines: List<ZakatLine>,
    val assetsUsd: BigDecimal,
    val receivablesUsd: BigDecimal,
    val deductionsUsd: BigDecimal,
    val baseUsd: BigDecimal,
    val nisabRate: Rate?,
    val nisabUsd: BigDecimal?,
    val status: ZakatStatus,
    val reasons: List<String>,
    /** التقدير المقرّب إلى السنت؛ صفر عندما لا تجب. */
    val zakatUsd: BigDecimal,
) {
    fun explain(): Explanation = Explanation(
        title = "تقدير الزكاة",
        result = Fmt.usd(zakatUsd) + " — " + status.ar,
        formula = "الوعاء = الأموال والأصول المدرجة (${Fmt.usd(assetsUsd)}) + الديون التي لي المدرجة (${Fmt.usd(receivablesUsd)}) − الالتزامات المعتمدة (${Fmt.usd(deductionsUsd)}) = ${Fmt.usd(baseUsd)}؛ " +
            "الزكاة = الوعاء × ${input.percent.multiply(BigDecimal(100)).stripTrailingZeros().toPlainString()}% إذا بلغ النصاب (${nisabUsd?.let { Fmt.usd(it) } ?: "غير مسعّر"}) واكتمل الحول",
        items = lines.map { z ->
            Explanation.Item(
                label = "${z.line.item.kind.ar}: ${z.line.item.label}",
                original = z.line.item.original,
                rate = z.line.rate?.label(), rateSource = z.line.rate?.source, rateDate = z.line.rate?.date?.toString(),
                value = Fmt.usd(z.countedUsd),
                note = z.decision.decision.ar + (z.decision.reason.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                refIds = listOfNotNull(z.line.item.refId),
            )
        } + Explanation.Item("النصاب: ${input.nisab.label}", "${Fmt.plain(input.nisab.pureGrams.setScale(3, RoundingMode.HALF_UP))}غ خالص",
            nisabRate?.label(), nisabRate?.source, nisabRate?.date?.toString(), nisabUsd?.let { Fmt.usd(it) }),
        pending = reasons,
        asOf = input.inventory.date.toString(),
    )
}

object ZakatCalc {
    /** القرار الافتراضي المقترح لكل بند (يُعرض للمستخدم ليؤكّده). */
    fun defaultDecision(l: PricedLine): LineDecision = when (l.item.kind) {
        LineKind.CASH -> if (l.item.quantity.signum() < 0) LineDecision(Decision.REVIEW, reason = "رصيد سالب: قرّر إدراجه كالتزام مرة واحدة") else LineDecision(Decision.INCLUDE)
        LineKind.GOLD -> if (l.item.goldKind == GoldKind.JEWELRY) LineDecision(Decision.REVIEW, reason = "حلي: يختلف الحكم بحسب الاستعمال") else LineDecision(Decision.INCLUDE)
        LineKind.RECEIVABLE -> LineDecision(Decision.REVIEW, reason = "دين مرجوّ أم متعثر؟")
        LineKind.PAYABLE -> LineDecision(Decision.REVIEW, reason = "التزام قد يُخصم")
    }

    fun compute(input: ZakatInput): ZakatResult {
        val reasons = mutableListOf<String>()
        var assets = BigDecimal.ZERO; var recv = BigDecimal.ZERO; var ded = BigDecimal.ZERO
        val zl = input.inventory.lines.map { l ->
            val d = input.decisions[l.item.id] ?: defaultDecision(l)
            val usd = l.usd
            var counted = BigDecimal.ZERO
            if (usd == null && d.decision != Decision.EXCLUDE) reasons += "${l.item.label}: ${l.state.ar}"
            else if (usd != null) when (d.decision) {
                Decision.REVIEW -> reasons += "${l.item.label}: ${d.reason.ifBlank { "يحتاج قراراً" }}"
                Decision.EXCLUDE -> {}
                Decision.INCLUDE -> counted = usd
                Decision.PARTIAL -> {
                    val p = d.partialUsd
                    if (p == null || p.signum() < 0) reasons += "${l.item.label}: أدخل المبلغ الجزئي"
                    else if (p > usd) reasons += "${l.item.label}: الخصم الجزئي يتجاوز المتبقي من الأصل"
                    else counted = p
                }
            }
            val negativeCash = l.item.kind == LineKind.CASH && l.item.quantity.signum() < 0
            when {
                l.item.kind == LineKind.PAYABLE || negativeCash -> ded += counted
                l.item.kind == LineKind.RECEIVABLE -> recv += counted
                else -> assets += counted
            }
            ZakatLine(l, d, counted)
        }
        val base = assets + recv - ded
        val book = RateBook(input.rates)
        val nisabRate = when (val r = book.lookup(input.nisab.metalUnit, input.inventory.date)) {
            is RateLookup.Exact -> r.rate
            else -> { reasons += "أدخل سعر ${Rate.unitLabel(input.nisab.metalUnit)} بتاريخ ${input.inventory.date} لحساب النصاب"; null }
        }
        val nisabUsd = nisabRate?.toUsd(input.nisab.pureGrams)
        if (!input.hawlComplete) reasons += "أكّد اكتمال الحول (${input.hawlHijri})"
        val status = when {
            reasons.isNotEmpty() -> ZakatStatus.NEEDS_REVIEW
            base < nisabUsd!! -> ZakatStatus.BELOW_NISAB // مقارنة دقيقة قبل التقريب
            else -> ZakatStatus.DUE
        }
        val zakat = if (status == ZakatStatus.DUE) base.multiply(input.percent).setScale(2, MoneyPolicy.ROUNDING) else BigDecimal.ZERO.setScale(2)
        return ZakatResult(input, zl, assets, recv, ded, base, nisabRate, nisabUsd, status, reasons, zakat)
    }
}

/** دفعة زكاة منفّذة: بعملة الدفع وسعر يوم الدفع. */
data class ZakatPayment(
    val id: String,
    val amount: Money,
    val rate: Rate,
    val date: LocalDate,
    val accountId: String?,
    val beneficiary: String? = null,
) {
    val usd: BigDecimal get() = rate.toUsd(amount.amount)
}

data class PlannedPayment(val amount: Money, val rate: Rate) { val usd get() = rate.toUsd(amount.amount) }

object Distribution {
    data class Progress(val dueUsd: BigDecimal, val plannedUsd: BigDecimal, val paidUsd: BigDecimal) {
        val remainingUsd: BigDecimal get() = (dueUsd - paidUsd).setScale(2, MoneyPolicy.ROUNDING)
        val overpaidUsd: BigDecimal get() = if (remainingUsd.signum() < 0) remainingUsd.negate() else BigDecimal.ZERO.setScale(2)
        val remainingLabel get() = "المتبقي " + Fmt.number(remainingUsd.max(BigDecimal.ZERO), 2)
    }

    fun progress(dueUsd: BigDecimal, planned: List<PlannedPayment>, paid: List<ZakatPayment>) = Progress(
        dueUsd,
        planned.fold(BigDecimal.ZERO) { a, p -> a + p.usd },
        paid.fold(BigDecimal.ZERO) { a, p -> a + p.usd },
    )

    /**
     * يحوّل حصّة بالدولار إلى مبلغ بعملة الدفع مقرّباً لأصغر وحدة، ويعيد الفرق المتبقي بالدولار.
     */
    fun shareIn(usd: BigDecimal, rate: Rate, currency: app.rasidi.core.money.CurrencyDef): Pair<Money, BigDecimal> {
        val m = Money.of(rate.fromUsd(usd), currency)
        return m to (usd - rate.toUsd(m.amount))
    }

    /**
     * يقسم المتبقي بين عملات وفق حصص غير سالبة مجموعها 1، ويقرّب الدفعة الأخيرة للأعلى لتغطي فرق التقريب.
     * لا ينتج دفعة سالبة أو صفرية: المتبقي ≤ 0 أو قائمة فارغة ⇒ لا خطة؛ وإن غطّت الدفعات السابقة المتبقي بالتقريب لا تُضاف دفعة أخيرة.
     */
    fun planByShares(remainingUsd: BigDecimal, shares: List<Triple<BigDecimal, Rate, app.rasidi.core.money.CurrencyDef>>): List<PlannedPayment> {
        if (remainingUsd.signum() <= 0 || shares.isEmpty()) return emptyList()
        require(shares.all { it.first.signum() >= 0 }) { "الحصص لا تكون سالبة" }
        val sum = shares.fold(BigDecimal.ZERO) { a, t -> a + t.first }
        require((sum - BigDecimal.ONE).abs() <= BigDecimal("1E-9")) { "مجموع الحصص يجب أن يساوي 1" }
        shares.forEach { (_, rate, cur) -> require(rate.unit == cur.code) { "سعر ${rate.unit} لا يطابق العملة ${cur.code}" } }
        var left = remainingUsd
        val out = mutableListOf<PlannedPayment>()
        shares.forEachIndexed { i, (share, rate, cur) ->
            val last = i == shares.lastIndex
            val target = if (last) left else remainingUsd.multiply(share)
            if (target.signum() <= 0) return@forEachIndexed
            val raw = rate.fromUsd(target)
            val amt = if (last) raw.setScale(cur.minorDigits, RoundingMode.CEILING) else raw.setScale(cur.minorDigits, MoneyPolicy.ROUNDING)
            if (amt.signum() <= 0) return@forEachIndexed
            val p = PlannedPayment(Money.of(amt, cur), rate)
            left -= p.usd
            out += p
        }
        return out
    }
}
