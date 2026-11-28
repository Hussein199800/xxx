package app.rasidi.core.valuation

import app.rasidi.core.explain.Explanation
import app.rasidi.core.ledger.GoldKind
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Fmt
import app.rasidi.core.money.Money
import app.rasidi.core.money.MoneyPolicy
import app.rasidi.core.money.Rate
import app.rasidi.core.money.RateBook
import app.rasidi.core.money.RateLookup
import app.rasidi.core.money.SyrianRedenomination
import java.math.BigDecimal
import java.time.LocalDate

enum class LineKind(val ar: String, val isLiability: Boolean = false) {
    CASH("نقد"), GOLD("ذهب"), RECEIVABLE("دين لي"), PAYABLE("دين عليّ", true),
}

/**
 * بند جرد خام قبل التسعير.
 * [unit] رمز العملة أو [Rate.GOLD24_GRAM]، و[quantity] المبلغ الأصلي أو غرامات الخالص.
 */
data class InventoryItem(
    val id: String,
    val kind: LineKind,
    val label: String,
    val unit: String,
    val quantity: BigDecimal,
    val original: String,
    val goldKind: GoldKind? = null,
    val refId: String? = null,
)

enum class PriceState(val ar: String) { PRICED("مسعّر"), SUGGESTED("سعر أقدم يحتاج تأكيداً"), CONFIRMED_OLDER("سعر أقدم مؤكَّد"), MISSING("بلا سعر"), NEEDS_DECISION("يحتاج قراراً") }

data class PricedLine(
    val item: InventoryItem,
    val state: PriceState,
    val rate: Rate?,
    /** القيمة بالدولار غير المقرّبة؛ null إذا لم يُعتمد السعر. موجبة دائماً، والاتجاه من [LineKind]. */
    val usd: BigDecimal?,
    val note: String? = null,
) {
    val counts get() = usd != null
    /** البند ينقص الصافي: التزام، أو نقد رصيده سالب. الإشارة تُحدَّد مرة واحدة هنا فقط. */
    val reducesNet: Boolean get() = item.kind.isLiability || (item.kind == LineKind.CASH && item.quantity.signum() < 0)
    /** القيمة الموقّعة: الالتزامات والنقد السالب بالسالب ([usd] يبقى مطلقاً للتسعير). */
    val signedUsd: BigDecimal? get() = usd?.let { if (reducesNet) it.negate() else it }
}

data class InventoryResult(
    val date: LocalDate,
    val lines: List<PricedLine>,
) {
    val missingCount get() = lines.count { !it.counts }
    val isComplete get() = missingCount == 0
    /** مجموع نوع بالإشارة (النقد بعد طرح السالب منه، والالتزامات بالسالب). */
    fun total(kind: LineKind): BigDecimal = lines.filter { it.item.kind == kind && it.counts }.fold(BigDecimal.ZERO) { a, l -> a + l.signedUsd!! }
    /** صافي البنود المسعّرة: الموجودات − الالتزامات (الإجمالي الجزئي عند النقص). */
    val pricedNet: BigDecimal get() = lines.filter { it.counts }.fold(BigDecimal.ZERO) { a, l -> a + l.signedUsd!! }

    fun explain(): Explanation = Explanation(
        title = "صافي الأموال بتاريخ $date",
        result = Fmt.usd(pricedNet),
        formula = "النقد + الذهب + الديون التي لي − الديون التي عليّ (المدرجة)" + if (!isComplete) " — إجمالي جزئي: $missingCount بند بلا سعر" else "",
        items = lines.map { l ->
            Explanation.Item(
                label = "${l.item.kind.ar}: ${l.item.label}",
                original = l.item.original,
                rate = l.rate?.label(), rateSource = l.rate?.source, rateDate = l.rate?.date?.toString(),
                value = l.signedUsd?.let { Fmt.usd(it) } ?: l.state.ar,
                note = l.note,
                refIds = listOfNotNull(l.item.refId),
            )
        },
        pending = lines.filter { !it.counts }.map { "${it.item.label}: ${it.state.ar}${it.note?.let { n -> " — $n" } ?: ""}" },
        asOf = date.toString(),
    )
}

object InventoryCalc {
    /**
     * يسعّر البنود بتاريخ [date].
     * - سعر بنفس التاريخ يُعتمد.
     * - سعر أقدم اقتراح لا يُعتمد إلا إذا كان معرّف البند ضمن [confirmedOlder].
     * - أسعار الأيام التالية لا تُستخدم.
     * - النقد بالسوري القديم بعد نهاية التداول يحتاج قراراً ([oldSypDecisions]: id → قيمة يدوية بالدولار أو null للتأكيد بسعر الاستبدال).
     */
    fun price(
        date: LocalDate,
        items: List<InventoryItem>,
        rates: List<Rate>,
        confirmedOlder: Set<String> = emptySet(),
        redenom: SyrianRedenomination = SyrianRedenomination.DEFAULT,
        oldSypDecisions: Map<String, BigDecimal?> = emptyMap(),
    ): InventoryResult {
        val book = RateBook(rates)
        val lines = items.map { item ->
            if (item.quantity.signum() == 0) return@map PricedLine(item, PriceState.PRICED, null, BigDecimal.ZERO)
            var lookup = book.lookup(item.unit, date)
            var note: String? = null
            if (item.unit == Currencies.SYP_OLD.code) {
                val st = redenom.status(date)
                if (st == SyrianRedenomination.OldCashStatus.SUBJECT_TO_WITHDRAWAL) {
                    if (!oldSypDecisions.containsKey(item.id))
                        return@map PricedLine(item, PriceState.NEEDS_DECISION, null, null, "خاضع للسحب: أكّد قابلية الاستبدال أو أدخل تقييماً يدوياً")
                    val manual = oldSypDecisions[item.id]
                    if (manual != null) return@map PricedLine(item, PriceState.PRICED, null, manual.abs(), "تقييم يدوي مبرّر")
                    note = "مؤكّد قابل للاستبدال"
                }
                if (st != SyrianRedenomination.OldCashStatus.CURRENT && lookup !is RateLookup.Exact) {
                    // بعد النفاذ: اشتق سعر القديم من سعر الجديد (المعامل مرة واحدة)
                    when (val n = book.lookup(Currencies.SYP.code, date)) {
                        is RateLookup.Exact -> { lookup = RateLookup.Exact(redenom.deriveOldRate(n.rate)); note = listOfNotNull(note, "سعر مشتق من الليرة الجديدة ÷100").joinToString(" — ") }
                        is RateLookup.OlderSuggestion -> if (lookup is RateLookup.Missing) lookup = RateLookup.OlderSuggestion(redenom.deriveOldRate(n.rate), n.daysOld)
                        RateLookup.Missing -> {}
                    }
                }
            }
            val absQty = item.quantity.abs()
            when (val l = lookup) {
                is RateLookup.Exact -> PricedLine(item, PriceState.PRICED, l.rate, l.rate.toUsd(absQty), note)
                is RateLookup.OlderSuggestion ->
                    if (item.id in confirmedOlder) PricedLine(item, PriceState.CONFIRMED_OLDER, l.rate, l.rate.toUsd(absQty), "سعر أقدم بـ ${l.daysOld} يوم، أكّده المستخدم")
                    else PricedLine(item, PriceState.SUGGESTED, l.rate, null, "أقرب سعر بتاريخ ${l.rate.date} (أقدم بـ ${l.daysOld} يوم)")
                RateLookup.Missing -> PricedLine(item, PriceState.MISSING, null, null, "أدخل سعر ${Rate.unitLabel(item.unit)} بتاريخ $date")
            }
        }
        return InventoryResult(date, lines)
    }

    fun round2(v: BigDecimal): BigDecimal = v.setScale(2, MoneyPolicy.ROUNDING)

    /** يحوّل قيمة بالدولار إلى Money بالدولار مع التقريب الموثّق. */
    fun usdMoney(v: BigDecimal) = Money.of(v, Currencies.USD)
}
