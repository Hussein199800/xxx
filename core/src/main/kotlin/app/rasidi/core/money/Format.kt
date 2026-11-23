package app.rasidi.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** تنسيق موحّد للعرض: أرقام غربية بفاصل آلاف، والسالب يُعرض بإشارة ناقص صريحة داخل عزل اتجاه. */
object Fmt {
    const val LRI = "⁦"; const val PDI = "⁩"

    private fun df(digits: Int) = DecimalFormat(
        if (digits == 0) "#,##0" else "#,##0." + "0".repeat(digits),
        DecimalFormatSymbols(Locale.US)
    ).apply { roundingMode = RoundingMode.HALF_UP }

    fun number(v: BigDecimal, digits: Int): String = df(digits).format(v.setScale(digits, MoneyPolicy.ROUNDING))

    /** المبلغ مع رمز العملة، معزولاً باتجاه LTR حتى لا ينقلب السالب أو الرمز داخل سطر عربي. */
    fun money(m: Money, isolate: Boolean = true): String {
        val s = number(m.amount, m.currency.minorDigits) + " " + m.currency.code
        return if (isolate) LRI + s + PDI else s
    }

    fun usd(v: BigDecimal, isolate: Boolean = true): String {
        val s = number(v, 2) + " USD"
        return if (isolate) LRI + s + PDI else s
    }

    fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()
}
