package app.rasidi.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * سياسة التقريب الموثّقة:
 * - المبالغ تُخزَّن كوحدات صغرى صحيحة (Long) ضمن ±[MAX_MINOR].
 * - الحساب الوسيط بـ BigDecimal بدقة [CALC_SCALE] خانة.
 * - التقريب [ROUNDING] (نصف للأعلى) يحدث فقط عند العرض وعند إنشاء قيد نقدي فعلي.
 * - مقارنة بلوغ النصاب تتم على القيم غير المقرَّبة.
 */
object MoneyPolicy {
    const val MAX_MINOR: Long = 999_999_999_999_999L // 10^15 - 1
    const val CALC_SCALE = 18
    val ROUNDING: RoundingMode = RoundingMode.HALF_UP
}

data class Money(val minor: Long, val currency: CurrencyDef) : Comparable<Money> {
    init {
        require(minor in -MoneyPolicy.MAX_MINOR..MoneyPolicy.MAX_MINOR) { "المبلغ خارج الحد المسموح" }
    }

    val amount: BigDecimal get() = BigDecimal.valueOf(minor, currency.minorDigits)

    operator fun plus(o: Money): Money { same(o); return Money(Math.addExact(minor, o.minor), currency) }
    operator fun minus(o: Money): Money { same(o); return Money(Math.subtractExact(minor, o.minor), currency) }
    operator fun unaryMinus(): Money = Money(-minor, currency)
    val isZero get() = minor == 0L
    val isNegative get() = minor < 0L
    fun abs() = if (minor < 0) -this else this

    override fun compareTo(other: Money): Int { same(other); return minor.compareTo(other.minor) }
    private fun same(o: Money) = require(o.currency.code == currency.code) { "عملتان مختلفتان: ${currency.code} و ${o.currency.code}" }

    override fun toString() = "${amount.toPlainString()} ${currency.code}"

    companion object {
        fun zero(c: CurrencyDef) = Money(0, c)

        /** يحوّل قيمة عشرية إلى وحدات صغرى مع التقريب الموثّق. */
        fun of(value: BigDecimal, c: CurrencyDef): Money =
            Money(value.setScale(c.minorDigits, MoneyPolicy.ROUNDING).unscaledValue().longValueExact(), c)

        fun of(value: String, c: CurrencyDef) = of(BigDecimal(value), c)
    }
}

fun Iterable<Money>.sumIn(c: CurrencyDef): Money = fold(Money.zero(c)) { a, b -> a + b }
