package app.rasidi.core.reports

import java.text.Bidi

/**
 * تشكيل الحروف العربية إلى أشكال العرض (Presentation Forms) وترتيب ثنائي الاتجاه للعرض البصري.
 * يُستخدم في مولّد PDF الخاص لأن الخط يُرسم كمعرّفات حروف مباشرة دون محرّك تشكيل.
 */
object ArabicShaper {
    private enum class J { DUAL, RIGHT, NONE, CAUSING }

    /** حرف أساسي → (معزول، نهائي، ابتدائي، وسطي). */
    private val forms: Map<Char, IntArray> = buildMap {
        fun d(c: Int, base: Int) = put(c.toChar(), intArrayOf(base, base + 1, base + 2, base + 3))
        fun r(c: Int, base: Int) = put(c.toChar(), intArrayOf(base, base + 1, 0, 0))
        put('ء', intArrayOf(0xFE80, 0, 0, 0))
        r(0x0622, 0xFE81); r(0x0623, 0xFE83); r(0x0624, 0xFE85); r(0x0625, 0xFE87); d(0x0626, 0xFE89)
        r(0x0627, 0xFE8D); d(0x0628, 0xFE8F); r(0x0629, 0xFE93); d(0x062A, 0xFE95); d(0x062B, 0xFE99)
        d(0x062C, 0xFE9D); d(0x062D, 0xFEA1); d(0x062E, 0xFEA5); r(0x062F, 0xFEA9); r(0x0630, 0xFEAB)
        r(0x0631, 0xFEAD); r(0x0632, 0xFEAF); d(0x0633, 0xFEB1); d(0x0634, 0xFEB5); d(0x0635, 0xFEB9)
        d(0x0636, 0xFEBD); d(0x0637, 0xFEC1); d(0x0638, 0xFEC5); d(0x0639, 0xFEC9); d(0x063A, 0xFECD)
        d(0x0641, 0xFED1); d(0x0642, 0xFED5); d(0x0643, 0xFED9); d(0x0644, 0xFEDD); d(0x0645, 0xFEE1)
        d(0x0646, 0xFEE5); d(0x0647, 0xFEE9); r(0x0648, 0xFEED); r(0x0649, 0xFEEF); d(0x064A, 0xFEF1)
        d(0x067E, 0xFB56); d(0x0686, 0xFB7A); r(0x0698, 0xFB8A); d(0x06A9, 0xFB8E); d(0x06AF, 0xFB92); d(0x06CC, 0xFBFC)
    }

    private fun joining(c: Char): J = when {
        c == 'ـ' || c == '‍' -> J.CAUSING
        else -> forms[c]?.let { if (it[2] != 0) J.DUAL else if (it[1] != 0) J.RIGHT else J.NONE } ?: J.NONE
    }

    private fun transparent(c: Char) = c in 'ً'..'ٟ' || c == 'ٰ'

    /** لام-ألف: ألف → (معزول، نهائي). */
    private val lamAlef = mapOf('آ' to intArrayOf(0xFEF5, 0xFEF6), 'أ' to intArrayOf(0xFEF7, 0xFEF8), 'إ' to intArrayOf(0xFEF9, 0xFEFA), 'ا' to intArrayOf(0xFEFB, 0xFEFC))

    private val mirror = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    /** وحدة عرض: رمز الشكل المرسوم والنص المنطقي الذي يمثّله (لـ ToUnicode). */
    data class Cluster(val cp: Int, val source: String, val index: Int)

    fun clean(s: String): String = s.replace(Regex("[\u2066-\u2069\u200E\u200F\u202A-\u202E\u061C]"), "").filterNot(::transparent)

    /** يشكّل نصاً منطقياً إلى وحدات بترتيبها المنطقي. */
    fun shape(text: String): List<Cluster> {
        val s = clean(text)
        val out = ArrayList<Cluster>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val f = forms[c]
            if (f == null) { out += Cluster(c.code, c.toString(), i); i++; continue }
            val prev = (i - 1 downTo 0).map { s[it] }.firstOrNull { !transparent(it) }
            val prevJoins = prev != null && (joining(prev) == J.DUAL || joining(prev) == J.CAUSING)
            if (c == 'ل' && i + 1 < s.length && lamAlef.containsKey(s[i + 1])) {
                val la = lamAlef.getValue(s[i + 1])
                out += Cluster(if (prevJoins) la[1] else la[0], s.substring(i, i + 2), i); i += 2; continue
            }
            val next = s.getOrNull(i + 1)
            val nextJoins = next != null && joining(next) != J.NONE && joining(c) == J.DUAL
            val cp = when {
                prevJoins && nextJoins && f[3] != 0 -> f[3]
                prevJoins && f[1] != 0 -> f[1]
                nextJoins && f[2] != 0 -> f[2]
                else -> f[0]
            }
            out += Cluster(cp, c.toString(), i); i++
        }
        return out
    }

    fun hasRtl(s: String) = s.any { it in '֐'..'ࣿ' || it in 'יִ'..'ﻼ' }

    /** مقاطع لاتينية/رقمية متصلة تُعزل كوحدة LTR داخل فقرة عربية (مثل «1,250.00 USD»). */
    private val ltrRun = Regex("[+A-Za-z0-9$€£][A-Za-z0-9$€£.,:+\\-−%/#@_=]*(?:[ ][A-Za-z0-9$€£.,:+\\-−%/#@_=]+)*")

    private fun isEmbedding(c: Char) = c in '\u202A'..'\u202E' || c == '\u200F'

    /** يشكّل ثم يرتّب للعرض البصري من اليسار إلى اليمين. [rtlBase] اتجاه الفقرة. */
    fun visual(text: String, rtlBase: Boolean = true): List<Cluster> {
        // عزل الاتجاه (LRI/PDI) يُحوَّل إلى تضمين (LRE/PDF) المدعوم في java.text.Bidi
        var s = text.replace('\u2066', '\u202A').replace('\u2067', '\u202B').replace('\u2069', '\u202C')
            .replace(Regex("[\u2068\u200E\u200F\u061C]"), "").filterNot(::transparent)
        if (s.isEmpty()) return emptyList()
        if (!hasRtl(s) && !rtlBase) return shape(s)
        if (rtlBase && s.none { it == '\u202A' }) s = ltrRun.replace(s) { "\u202A" + it.value + "\u202C\u200F" }
        val clusters = shapeKeepingIndex(s)
        val bidi = Bidi(s, if (rtlBase) Bidi.DIRECTION_RIGHT_TO_LEFT else Bidi.DIRECTION_LEFT_TO_RIGHT)
        val levels = ByteArray(clusters.size) { bidi.getLevelAt(clusters[it].index).toByte() }
        val arr: Array<Any> = clusters.toTypedArray()
        Bidi.reorderVisually(levels, 0, arr, 0, arr.size)
        return arr.map { it as Cluster }.map { cl ->
            if (bidi.getLevelAt(cl.index) % 2 == 1 && cl.cp < 0x10000) mirror[cl.cp.toChar()]?.let { cl.copy(cp = it.code) } ?: cl else cl
        }
    }

    /** مثل [shape] لكن على نص قد يحتوي محارف تضمين: تُتخطّى دون وحدة عرض وتبقى الفهارس مطابقة. */
    private fun shapeKeepingIndex(s: String): List<Cluster> {
        val visible = StringBuilder(); val map = ArrayList<Int>()
        s.forEachIndexed { i, c -> if (!isEmbedding(c)) { visible.append(c); map += i } }
        return shape(visible.toString()).map { it.copy(index = map[it.index]) }
    }
}
