package app.rasidi.core.voucher

import app.rasidi.core.money.Money
import java.math.BigDecimal

/**
 * التفقيط: كتابة المبلغ بالحروف العربية كما في السندات («خمسمائة دولار أمريكي فقط لا غير»).
 * يستخدم صيغة المذكّر واسم العملة بصيغة المفرد بعد العدد (الصيغة الشائعة في السندات).
 */
object Tafqit {
    private val ones = listOf("", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة", "عشرة",
        "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر")
    private val tens = listOf("", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون")
    private val hundreds = listOf("", "مائة", "مائتان", "ثلاثمائة", "أربعمائة", "خمسمائة", "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة")

    /** اسم الوحدة الصغرى لكل عملة. */
    private val subunit = mapOf("USD" to "سنت", "EUR" to "سنت", "TRY" to "قرش", "SYP" to "قرش", "SAR" to "هللة", "AED" to "فلس",
        "JOD" to "فلس", "KWD" to "فلس", "QAR" to "درهم", "EGP" to "قرش", "GBP" to "بنس")

    /** عدد صحيح (0 – 999,999,999,999) بالحروف. */
    fun number(n: Long): String {
        require(n >= 0) { "عدد سالب" }
        if (n == 0L) return "صفر"
        val parts = mutableListOf<String>()
        val scales = listOf(
            Triple(1_000_000_000L, listOf("مليار", "ملياران", "مليارات"), "مليار"),
            Triple(1_000_000L, listOf("مليون", "مليونان", "ملايين"), "مليون"),
            Triple(1_000L, listOf("ألف", "ألفان", "آلاف"), "ألف"),
        )
        var rest = n
        for ((value, forms, many) in scales) {
            val q = rest / value; rest %= value
            if (q == 0L) continue
            parts += when {
                q == 1L -> forms[0]
                q == 2L -> forms[1]
                q in 3..10 -> under1000(q.toInt()) + " " + forms[2]
                else -> under1000(q.toInt()) + " " + many
            }
        }
        if (rest > 0) parts += under1000(rest.toInt())
        return parts.joinToString(" و")
    }

    private fun under1000(n: Int): String {
        val h = n / 100; val r = n % 100
        val p = mutableListOf<String>()
        if (h > 0) p += hundreds[h]
        if (r > 0) p += if (r < 20) ones[r] else if (r % 10 == 0) tens[r / 10] else "${ones[r % 10]} و${tens[r / 10]}"
        return p.joinToString(" و")
    }

    /** المبلغ مع العملة: «ألف ومائتان وخمسون دولار أمريكي وخمسون سنت فقط لا غير». */
    fun money(m: Money): String {
        val a = m.amount.abs()
        val whole = a.toBigInteger().toLong()
        val frac = a.subtract(BigDecimal(whole)).movePointRight(m.currency.minorDigits).toLong()
        val sb = StringBuilder()
        if (m.isNegative) sb.append("سالب ")
        sb.append(number(whole)).append(' ').append(m.currency.nameAr)
        if (frac > 0) sb.append(" و").append(number(frac)).append(' ').append(subunit[m.currency.code] ?: "جزء")
        sb.append(" فقط لا غير")
        return sb.toString()
    }
}
