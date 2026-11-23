package app.rasidi.core.money

import java.math.BigDecimal

/**
 * يقبل الأرقام العربية (٠-٩) والفارسية (۰-۹) والغربية مع فواصلها.
 * - «٫» فاصل عشري عربي و«٬» فاصل آلاف عربي.
 * - عند وجود «.» و«,» معاً، الأخير هو العشري.
 * - فاصل وحيد يتبعه ثلاث خانات بالضبط (مثل 1,234 أو 1.234) ملتبس: يُعاد [ParseResult.ambiguous]=true
 *   ويُعرض للمستخدم بصيغة موحّدة قبل الحفظ. التفسير الافتراضي: فاصل آلاف.
 */
object AmountParser {
    data class ParseResult(val value: BigDecimal, val ambiguous: Boolean, val normalized: String)

    fun normalizeDigits(s: String): String = buildString {
        for (ch in s) append(
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                '٫' -> '.'   // ٫
                '٬' -> '\u0000' // ٬ thousands
                '،' -> ','   // ، comma
                '−', '–' -> '-'
                ' ', ' ', ' ', '\u200F', '\u200E', '\u061C', '\'' -> '\u0000'
                else -> ch
            }
        )
    }.replace("\u0000", "")

    fun parse(input: String): ParseResult? {
        var s = normalizeDigits(input.trim())
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("-")) { negative = true; s = s.substring(1) }
        else if (s.endsWith("-")) { negative = true; s = s.dropLast(1) }
        if (s.isEmpty() || s.any { it !in "0123456789.," }) return null
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        var ambiguous = false
        val plain: String = when {
            lastDot >= 0 && lastComma >= 0 -> {
                val dec = if (lastDot > lastComma) '.' else ','
                val thou = if (dec == '.') ',' else '.'
                if (s.count { it == dec } > 1) return null
                s.replace(thou.toString(), "").replace(dec, '.')
            }
            lastDot >= 0 || lastComma >= 0 -> {
                val sep = if (lastDot >= 0) '.' else ','
                val parts = s.split(sep)
                if (parts.size > 2) {
                    // 1,234,567 → آلاف
                    if (parts.drop(1).all { it.length == 3 } && parts[0].isNotEmpty()) s.replace(sep.toString(), "") else return null
                } else {
                    val frac = parts[1]
                    if (frac.length == 3 && parts[0].isNotEmpty() && parts[0] != "0") {
                        ambiguous = true
                        s.replace(sep.toString(), "")
                    } else s.replace(sep, '.')
                }
            }
            else -> s
        }
        if (plain.isEmpty() || plain == ".") return null
        val v = try { BigDecimal(plain) } catch (e: NumberFormatException) { return null }
        val value = if (negative) v.negate() else v
        return ParseResult(value, ambiguous, value.stripTrailingZeros().toPlainString().let { if (it.contains('E')) value.toPlainString() else it })
    }

    /** يحلّل مبلغاً لعملة محددة ويرفض الكسور الزائدة عن خانات الوحدة الصغرى. */
    fun parseMoney(input: String, c: CurrencyDef): Money? {
        val r = parse(input) ?: return null
        if (r.value.stripTrailingZeros().scale() > c.minorDigits) return null
        return try { Money.of(r.value, c) } catch (e: Exception) { null }
    }
}
