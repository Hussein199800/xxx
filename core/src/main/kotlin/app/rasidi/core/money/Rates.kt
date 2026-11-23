package app.rasidi.core.money

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** اتجاه السعر كما أدخله المستخدم. */
enum class RateDirection {
    /** «1 USD = value وحدة» مثل 1 USD = 40 TRY. */
    UNITS_PER_USD,
    /** «1 وحدة = value USD» مثل 1 EUR = 1.08 USD. */
    USD_PER_UNIT,
}

enum class RateKind { OFFICIAL, LOCAL, MANUAL }

/**
 * سعر مؤرخ. يُحفظ [entered] واتجاهه كما أُدخل، وتُشتقّ النسبة عند الحاجة،
 * فيبقى مقلوب 120 دقيقاً (القسمة تتم مرة واحدة على المبلغ لا على السعر).
 * للذهب: [unit] = "XAU_G24" أي غرام ذهب عيار 24، و[direction] = USD_PER_UNIT.
 */
data class Rate(
    val id: String,
    val unit: String,
    val entered: BigDecimal,
    val direction: RateDirection,
    val date: LocalDate,
    val source: String = "يدوي",
    val kind: RateKind = RateKind.MANUAL,
    val observedAtUtc: Long = 0,
) {
    init { require(entered.signum() > 0) { "السعر يجب أن يكون موجباً" } }

    /** يحوّل كمية من الوحدة إلى دولار دون تقريب نهائي. */
    fun toUsd(quantity: BigDecimal): BigDecimal = when (direction) {
        RateDirection.USD_PER_UNIT -> quantity.multiply(entered)
        RateDirection.UNITS_PER_USD -> quantity.divide(entered, MoneyPolicy.CALC_SCALE, RoundingMode.HALF_EVEN)
    }

    /** يحوّل دولاراً إلى كمية من الوحدة دون تقريب نهائي. */
    fun fromUsd(usd: BigDecimal): BigDecimal = when (direction) {
        RateDirection.USD_PER_UNIT -> usd.divide(entered, MoneyPolicy.CALC_SCALE, RoundingMode.HALF_EVEN)
        RateDirection.UNITS_PER_USD -> usd.multiply(entered)
    }

    /** دولار لكل وحدة، للعرض فقط. */
    val usdPerUnit: BigDecimal get() = toUsd(BigDecimal.ONE)

    /** نص مقروء مثل «1 USD = 40 TRY». */
    fun label(): String = when (direction) {
        RateDirection.UNITS_PER_USD -> "1 USD = ${Fmt.plain(entered)} ${unitLabel(unit)}"
        RateDirection.USD_PER_UNIT -> "1 ${unitLabel(unit)} = ${Fmt.plain(entered)} USD"
    }

    companion object {
        const val GOLD24_GRAM = "XAU_G24"
        const val SILVER_GRAM = "XAG_G"
        fun unitLabel(u: String) = when (u) {
            GOLD24_GRAM -> "غ ذهب 24"
            SILVER_GRAM -> "غ فضة"
            else -> u
        }

        /** سعر غرام لعيار غير 24 يُطبَّع مرة واحدة إلى عيار 24. */
        fun goldGramFromKarat(id: String, pricePerGramUsd: BigDecimal, karat: Int, date: LocalDate, source: String): Rate {
            require(karat in 1..24)
            val g24 = pricePerGramUsd.multiply(BigDecimal(24)).divide(BigDecimal(karat), MathContext.DECIMAL128)
            return Rate(id, GOLD24_GRAM, g24.stripTrailingZeros(), RateDirection.USD_PER_UNIT, date, "$source (مطبّع من عيار $karat)")
        }
    }
}

/** نتيجة البحث عن سعر بتاريخ معيّن. */
sealed interface RateLookup {
    /** سعر بنفس تاريخ التقييم. */
    data class Exact(val rate: Rate) : RateLookup
    /** سعر أقدم: اقتراح يحتاج تأكيداً وملاحظة. */
    data class OlderSuggestion(val rate: Rate, val daysOld: Long) : RateLookup
    /** لا يوجد سعر صالح (أسعار الأيام التالية مستبعدة). */
    data object Missing : RateLookup
}

class RateBook(rates: List<Rate>) {
    private val byUnit = rates.groupBy { it.unit }.mapValues { e -> e.value.sortedWith(compareBy<Rate> { it.date }.thenBy { it.observedAtUtc }) }

    fun lookup(unit: String, date: LocalDate): RateLookup {
        if (unit == Currencies.USD.code) return RateLookup.Exact(USD_IDENTITY.copy(date = date))
        val list = byUnit[unit] ?: return RateLookup.Missing
        val notAfter = list.filter { !it.date.isAfter(date) }
        val best = notAfter.lastOrNull() ?: return RateLookup.Missing
        return if (best.date == date) RateLookup.Exact(best)
        else RateLookup.OlderSuggestion(best, java.time.temporal.ChronoUnit.DAYS.between(best.date, date))
    }

    companion object {
        val USD_IDENTITY = Rate("usd", "USD", BigDecimal.ONE, RateDirection.USD_PER_UNIT, LocalDate.of(2000, 1, 1), "ثابت")
    }
}
