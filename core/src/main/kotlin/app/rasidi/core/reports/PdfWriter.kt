package app.rasidi.core.reports

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * مولّد PDF خاص بلا تبعيات: A4 جاهز للطباعة، خط Cairo مضمَّن (CIDFontType2 / Identity-H)
 * مع ToUnicode فيبقى النص قابلاً للتحديد والنسخ، وترويسة وتذييل وترقيم صفحات في كل صفحة،
 * وتكرار رأس الجدول بعد كل فاصل صفحة، ولفّ النص داخل الخلايا. السند المفرد بقالب A5 مختصر.
 */
class PdfWriter(private val regular: TrueTypeFont, private val bold: TrueTypeFont) {

    companion object {
        fun withBundledFonts(): PdfWriter {
            fun load(n: String) = PdfWriter::class.java.getResourceAsStream("/rasidi-fonts/$n")?.use { it.readBytes() }
                ?: throw IllegalStateException("الخط $n غير موجود")
            return PdfWriter(TrueTypeFont(load("cairo_regular.ttf"), "Cairo-Regular"), TrueTypeFont(load("cairo_bold.ttf"), "Cairo-Bold"))
        }
        private val GREEN = floatArrayOf(0.059f, 0.373f, 0.243f)
        private val INK = floatArrayOf(0.063f, 0.141f, 0.102f)
        private val MUTE = floatArrayOf(0.42f, 0.455f, 0.41f)
        private val LINE = floatArrayOf(0.85f, 0.835f, 0.78f)
        private val ZEBRA = floatArrayOf(0.969f, 0.961f, 0.933f)
        private val TOTAL = floatArrayOf(0.918f, 0.949f, 0.925f)
        private val WARN_BG = floatArrayOf(1f, 0.957f, 0.839f)
        private val WARN_INK = floatArrayOf(0.604f, 0.357f, 0f)
        private val WHITE = floatArrayOf(1f, 1f, 1f)
    }

    private class FontUse(val font: TrueTypeFont, val key: String) {
        val used = HashMap<Int, String>() // gid → unicode
    }
    private lateinit var fr: FontUse
    private lateinit var fb: FontUse

    // ---------- النص ----------
    private data class Shaped(val gids: IntArray, val width: Float)

    private fun shapeLine(text: String, f: FontUse, size: Float, rtl: Boolean = true): Shaped {
        val cl = ArabicShaper.visual(text, rtl)
        val g = IntArray(cl.size); var w = 0
        cl.forEachIndexed { i, c ->
            var gid = f.font.glyph(c.cp)
            if (gid == 0 && c.source.isNotEmpty()) gid = f.font.glyph(c.source[0].code) // الشكل المعزول = الحرف الأساسي
            if (gid == 0) gid = f.font.glyph('?'.code)
            g[i] = gid; w += f.font.width(gid)
            f.used.putIfAbsent(gid, c.source)
        }
        return Shaped(g, w * size / 1000f)
    }

    private fun width(text: String, f: FontUse, size: Float): Float {
        var w = 0
        for (c in ArabicShaper.shape(text)) {
            var gid = f.font.glyph(c.cp); if (gid == 0 && c.source.isNotEmpty()) gid = f.font.glyph(c.source[0].code)
            w += f.font.width(gid)
        }
        return w * size / 1000f
    }

    /** لفّ النص منطقياً على الكلمات ضمن عرض [max]. */
    private fun wrap(text: String, f: FontUse, size: Float, max: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split('\n')) {
            val words = para.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) { out += ""; continue }
            var line = ""
            for (w in words) {
                val cand = if (line.isEmpty()) w else "$line $w"
                if (width(cand, f, size) <= max || line.isEmpty()) {
                    if (line.isEmpty() && width(w, f, size) > max) {
                        // كلمة أطول من الخلية: تُقسم على المحارف
                        var chunk = ""
                        for (ch in w) { if (width(chunk + ch, f, size) > max && chunk.isNotEmpty()) { out += chunk; chunk = "" }; chunk += ch }
                        line = chunk
                    } else line = cand
                } else { out += line; line = w }
            }
            out += line
        }
        return out
    }

    // ---------- الصفحات ----------
    private inner class Page(val w: Float, val h: Float) {
        val sb = StringBuilder()
        fun color(c: FloatArray, stroke: Boolean = false) = sb.append("%.3f %.3f %.3f %s\n".format(java.util.Locale.US, c[0], c[1], c[2], if (stroke) "RG" else "rg"))
        fun rect(x: Float, y: Float, rw: Float, rh: Float, c: FloatArray) { color(c); sb.append("%.2f %.2f %.2f %.2f re f\n".format(java.util.Locale.US, x, y, rw, rh)) }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, c: FloatArray, lw: Float = 0.5f) {
            color(c, true); sb.append("%.2f w %.2f %.2f m %.2f %.2f l S\n".format(java.util.Locale.US, lw, x1, y1, x2, y2))
        }
        /** يرسم نصاً؛ [align] 1 يمين، 0 وسط، -1 يسار، مرجعه [x] (الحافة المناسبة). */
        fun text(t: String, x: Float, y: Float, size: Float, boldFont: Boolean = false, c: FloatArray = INK, align: Int = 1, rtl: Boolean = true) {
            if (t.isEmpty()) return
            val f = if (boldFont) fb else fr
            val s = shapeLine(t, f, size, rtl)
            val sx = when (align) { 1 -> x - s.width; 0 -> x - s.width / 2; else -> x }
            color(c)
            sb.append("BT /${f.key} %.2f Tf %.2f %.2f Td <".format(java.util.Locale.US, size, sx, y))
            s.gids.forEach { sb.append("%04X".format(it)) }
            sb.append("> Tj ET\n")
        }
    }

    private val pages = mutableListOf<Page>()
    private var pw = 595.28f; private var ph = 841.89f
    private val m = 36f
    private var y = 0f
    private var cur: Page? = null
    private lateinit var doc: ReportDoc

    private val right get() = pw - m
    private val contentW get() = pw - 2 * m

    private fun newPage(): Page {
        val p = Page(pw, ph); pages += p; cur = p
        // ترويسة كل صفحة
        p.text(doc.title, right, ph - m + 4, 10f, true, GREEN)
        p.text("رصيدي", m, ph - m + 4, 10f, true, GREEN, align = -1)
        p.line(m, ph - m - 2, right, ph - m - 2, LINE, 0.8f)
        y = ph - m - 18
        return p
    }

    private fun ensure(hgt: Float): Page {
        val p = cur ?: newPage()
        if (y - hgt < m + 22) return newPage()
        return p
    }

    fun write(d: ReportDoc): ByteArray {
        doc = d
        pages.clear(); cur = null
        fr = FontUse(regular, "F1"); fb = FontUse(bold, "F2")
        if (d.kind == ReportDoc.Kind.VOUCHER) { pw = 419.53f; ph = 595.28f } else { pw = 595.28f; ph = 841.89f }

        var p = newPage()
        // العنوان والفترة
        p.text(d.title, right, y - 8, if (d.kind == ReportDoc.Kind.VOUCHER) 18f else 20f, true, GREEN); y -= 30
        p.text(d.period, right, y, 12f); y -= 18
        p.text("تاريخ التوليد: ${d.generatedAt}", right, y, 9f, c = MUTE); y -= 14
        for ((k, v) in d.filters + d.meta) {
            val lines = wrap("$k: $v", fr, 9f, contentW)
            for (l in lines) { p = ensure(13f); p.text(l, right, y, 9f, c = MUTE); y -= 13 }
        }
        y -= 4
        for (w in d.warnings) {
            val lines = wrap("⚠ $w".replace("⚠", "!"), fb, 9.5f, contentW - 16)
            val hh = lines.size * 14f + 8
            p = ensure(hh)
            p.rect(m, y - hh + 10, contentW, hh, WARN_BG)
            var yy = y
            lines.forEach { l -> p.text(l, right - 8, yy - 2, 9.5f, true, WARN_INK); yy -= 14 }
            y -= hh + 4
        }
        if (d.summary.isNotEmpty()) {
            heading("الملخّص")
            table(listOf(Column("البند", ColType.TEXT, 2f), Column("القيمة", ColType.NUMBER, 1f)), d.summary.map { listOf(Cell.Text(it.first), it.second) }, emptyList())
        }
        for (t in d.tables) {
            heading(t.title)
            if (t.rows.isEmpty() && t.totals.isEmpty()) { val pg = ensure(16f); pg.text("لا توجد بنود ضمن هذه الفلاتر.", right, y, 10f, c = MUTE); y -= 18 }
            else table(t.columns, t.rows, t.totals)
        }
        if (d.kind == ReportDoc.Kind.VOUCHER) {
            p = ensure(60f); y -= 30
            p.text("التوقيع: ________________", right, y, 10f)
            p.text("المستلم: ________________", m, y, 10f, align = -1)
        }
        // تذييل وترقيم
        pages.forEachIndexed { i, pg ->
            pg.line(m, m + 6, right, m + 6, LINE, 0.6f)
            pg.text("صفحة ${i + 1} من ${pages.size}", pw / 2, m - 6, 8.5f, c = MUTE, align = 0)
            pg.text(d.generatedAt, m, m - 6, 8f, c = MUTE, align = -1, rtl = false)
        }
        return assemble()
    }

    private fun heading(t: String) {
        val p = ensure(40f)
        y -= 8
        p.text(t, right, y - 4, 13f, true, GREEN)
        y -= 20
    }

    private fun colWeight(c: Column) = when (c.type) { ColType.DATE -> 1.1f; ColType.CODE -> 0.75f; ColType.NUMBER -> 1.3f; ColType.TEXT -> 1.2f * c.weight }

    private fun table(cols: List<Column>, rows: List<List<Cell>>, totals: List<List<Cell>>) {
        val size = 8.5f; val lh = 11.5f; val pad = 4f
        val sum = cols.sumOf { colWeight(it).toDouble() }.toFloat()
        val widths = cols.map { colWeight(it) / sum * contentW }
        // الأعمدة من اليمين إلى اليسار
        val xRight = FloatArray(cols.size); run { var x = right; cols.indices.forEach { i -> xRight[i] = x; x -= widths[i] } }

        fun cellLines(c: Cell, i: Int, bold: Boolean): List<String> = when (c) {
            is Cell.Num, is Cell.Date -> listOf(c.display())
            else -> wrap(c.display(), if (bold) fb else fr, size, widths[i] - 2 * pad)
        }
        fun header(): Float {
            val hl = cols.mapIndexed { i, c -> wrap(c.header, fb, size, widths[i] - 2 * pad) }
            val hh = hl.maxOf { it.size } * lh + 2 * pad
            val p = cur!!
            p.rect(m, y - hh, contentW, hh, GREEN)
            hl.forEachIndexed { i, ls -> var yy = y - pad - size; ls.forEach { l -> p.text(l, xRight[i] - widths[i] / 2, yy, size, true, WHITE, align = 0); yy -= lh } }
            y -= hh
            return hh
        }
        ensure(60f); header()
        fun row(cells: List<Cell>, idx: Int, bold: Boolean, bg: FloatArray?) {
            val ls = cols.indices.map { i -> cellLines(cells.getOrElse(i) { Cell.Empty }, i, bold) }
            val rh = ls.maxOf { maxOf(1, it.size) } * lh + 2 * pad
            if (y - rh < m + 22) { newPage(); header() }
            val p = cur!!
            bg?.let { p.rect(m, y - rh, contentW, rh, it) }
            cols.forEachIndexed { i, c ->
                val cell = cells.getOrElse(i) { Cell.Empty }
                var yy = y - pad - size
                ls[i].forEach { l ->
                    when {
                        cell is Cell.Num -> p.text(l, xRight[i] - widths[i] + pad, yy, size, bold, align = -1, rtl = false)
                        cell is Cell.Date || c.type == ColType.CODE -> p.text(l, xRight[i] - widths[i] / 2, yy, size, bold, align = 0, rtl = false)
                        else -> p.text(l, xRight[i] - pad, yy, size, bold)
                    }
                    yy -= lh
                }
            }
            p.line(m, y - rh, right, y - rh, LINE, 0.4f)
            y -= rh
        }
        rows.forEachIndexed { i, r -> row(r, i, false, if (i % 2 == 1) ZEBRA else null) }
        totals.forEach { row(it, 0, true, TOTAL) }
        y -= 10
    }

    // ---------- تجميع ملف PDF ----------
    private fun assemble(): ByteArray {
        val objs = mutableListOf<ByteArray>()
        fun add(b: ByteArray): Int { objs += b; return objs.size }
        fun add(s: String) = add(s.toByteArray(Charsets.ISO_8859_1))
        fun stream(dict: String, data: ByteArray, compress: Boolean = true): Int {
            val body = if (compress) deflate(data) else data
            val head = "<< $dict /Length ${body.size}${if (compress) " /Filter /FlateDecode" else ""} >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
            return add(head + body + "\nendstream".toByteArray(Charsets.ISO_8859_1))
        }
        fun fontObjs(u: FontUse): Int {
            val f = u.font
            val ff = stream("/Length1 ${f.data.size}", f.data)
            val desc = add("<< /Type /FontDescriptor /FontName /${f.psName} /Flags 32 /FontBBox [${f.bbox.joinToString(" ") { f.scaled(it).toString() }}] /ItalicAngle 0 /Ascent ${f.scaled(f.ascent)} /Descent ${f.scaled(f.descent)} /CapHeight ${f.scaled(f.capHeight)} /StemV 80 /FontFile2 $ff 0 R >>")
            val gids = u.used.keys.sorted()
            val w = gids.joinToString(" ") { "$it [${f.width(it)}]" }
            val cid = add("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /${f.psName} /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor $desc 0 R /DW 500 /W [$w] /CIDToGIDMap /Identity >>")
            val cmap = StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
            gids.chunked(100).forEach { ch ->
                cmap.append("${ch.size} beginbfchar\n")
                ch.forEach { g -> cmap.append("<%04X> <%s>\n".format(g, u.used[g]!!.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) })) }
                cmap.append("endbfchar\n")
            }
            cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
            val tu = stream("", cmap.toString().toByteArray(Charsets.ISO_8859_1))
            return add("<< /Type /Font /Subtype /Type0 /BaseFont /${f.psName} /Encoding /Identity-H /DescendantFonts [$cid 0 R] /ToUnicode $tu 0 R >>")
        }
        // ترتيب ثابت: 1 catalog، 2 pages، ثم الباقي
        add("CATALOG"); add("PAGES")
        val f1 = fontObjs(fr); val f2 = fontObjs(fb)
        val pageIds = pages.map { p ->
            val c = stream("", p.sb.toString().toByteArray(Charsets.ISO_8859_1))
            add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Resources << /Font << /F1 $f1 0 R /F2 $f2 0 R >> >> /Contents $c 0 R >>".format(java.util.Locale.US, p.w, p.h))
        }
        val info = add("<< /Title <FEFF${doc.title.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) }}> /Producer (Rasidi) /Creator (Rasidi) >>")
        objs[0] = "<< /Type /Catalog /Pages 2 0 R /Lang (ar) /ViewerPreferences << /Direction /R2L /DisplayDocTitle true >> >>".toByteArray(Charsets.ISO_8859_1)
        objs[1] = "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>".toByteArray(Charsets.ISO_8859_1)

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = IntArray(objs.size)
        objs.forEachIndexed { i, b ->
            offsets[i] = out.size()
            out.write("${i + 1} 0 obj\n".toByteArray(Charsets.ISO_8859_1)); out.write(b); out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append("%010d 00000 n \n".format(it)) }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION); d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end(); return out.toByteArray()
    }
}
