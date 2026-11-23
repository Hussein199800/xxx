package app.rasidi.core.reports

import java.nio.ByteBuffer

/** قارئ TrueType بسيط: cmap (4 و12) والعروض والمقاييس اللازمة لتضمين الخط في PDF. */
class TrueTypeFont(val data: ByteArray, val psName: String) {
    private val buf = ByteBuffer.wrap(data)
    private val tables = HashMap<String, Pair<Int, Int>>()
    val unitsPerEm: Int
    val ascent: Int; val descent: Int; val capHeight: Int
    val bbox: IntArray
    private val advances: IntArray
    private val cmap = HashMap<Int, Int>()

    init {
        val num = u16(4)
        for (i in 0 until num) {
            val o = 12 + i * 16
            val tag = String(data, o, 4, Charsets.ISO_8859_1)
            tables[tag] = u32(o + 8).toInt() to u32(o + 12).toInt()
        }
        val head = t("head")
        unitsPerEm = u16(head + 18)
        bbox = intArrayOf(s16(head + 36), s16(head + 38), s16(head + 40), s16(head + 42))
        val hhea = t("hhea")
        ascent = s16(hhea + 4); descent = s16(hhea + 6)
        val numH = u16(hhea + 34)
        val numGlyphs = u16(t("maxp") + 4)
        val hmtx = t("hmtx")
        advances = IntArray(numGlyphs) { g -> if (g < numH) u16(hmtx + g * 4) else u16(hmtx + (numH - 1) * 4) }
        capHeight = tables["OS/2"]?.let { (o, _) -> if (u16(o) >= 2) s16(o + 88) else ascent } ?: ascent
        parseCmap()
    }

    private fun t(tag: String) = tables[tag]?.first ?: throw IllegalStateException("missing table $tag")
    private fun u16(o: Int) = buf.getShort(o).toInt() and 0xffff
    private fun s16(o: Int) = buf.getShort(o).toInt()
    private fun u32(o: Int) = buf.getInt(o).toLong() and 0xffffffffL

    private fun parseCmap() {
        val base = t("cmap")
        val n = u16(base + 2)
        var f4 = -1; var f12 = -1
        for (i in 0 until n) {
            val pid = u16(base + 4 + i * 8); val eid = u16(base + 6 + i * 8); val off = u32(base + 8 + i * 8).toInt()
            val fmt = u16(base + off)
            if (pid == 3 && eid == 10 && fmt == 12) f12 = base + off
            if (pid == 3 && eid == 1 && fmt == 4) f4 = base + off
            if (pid == 0 && fmt == 4 && f4 < 0) f4 = base + off
            if (pid == 0 && fmt == 12 && f12 < 0) f12 = base + off
        }
        if (f12 >= 0) {
            val groups = u32(f12 + 12).toInt()
            for (g in 0 until groups) {
                val o = f12 + 16 + g * 12
                val start = u32(o).toInt(); val end = u32(o + 4).toInt(); val gid = u32(o + 8).toInt()
                for (c in start..end) cmap[c] = gid + (c - start)
            }
        } else if (f4 >= 0) {
            val segX2 = u16(f4 + 6); val segs = segX2 / 2
            val ends = f4 + 14; val starts = ends + segX2 + 2; val deltas = starts + segX2; val ranges = deltas + segX2
            for (s in 0 until segs) {
                val end = u16(ends + s * 2); val start = u16(starts + s * 2); val delta = s16(deltas + s * 2); val ro = u16(ranges + s * 2)
                if (start == 0xFFFF) continue
                for (c in start..end) {
                    val g = if (ro == 0) (c + delta) and 0xffff else {
                        val gi = u16(ranges + s * 2 + ro + (c - start) * 2)
                        if (gi == 0) 0 else (gi + delta) and 0xffff
                    }
                    if (g != 0) cmap[c] = g
                }
            }
        }
    }

    fun glyph(cp: Int): Int = cmap[cp] ?: 0
    fun has(cp: Int) = cmap.containsKey(cp)
    /** عرض الحرف بوحدات PDF (1/1000 من حجم الخط). */
    fun width(gid: Int): Int = (advances.getOrElse(gid) { advances[0] } * 1000L / unitsPerEm).toInt()
    fun scaled(v: Int) = (v * 1000L / unitsPerEm).toInt()
}
