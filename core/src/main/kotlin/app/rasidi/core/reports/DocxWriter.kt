package app.rasidi.core.reports

/**
 * مستند Word عربي قابل للتحرير (WordprocessingML):
 * عنوان وفترة وملخص ثم جداول RTL (bidiVisual) مع تكرار رأس الجدول في كل صفحة،
 * وتذييل بترقيم «صفحة X من Y»، وورق A4.
 * المبالغ في خلايا LTR مستقلة حتى لا تنقلب الإشارة السالبة، والعملة في عمود مستقل.
 */
object DocxWriter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val FONT = "Arial"

    fun write(doc: ReportDoc): ByteArray {
        val body = StringBuilder()
        body.append(para(doc.title, style = "Title"))
        body.append(para(doc.period, style = "Subtitle"))
        body.append(para("تاريخ التوليد: ${doc.generatedAt}", style = "Meta"))
        (doc.filters + doc.meta).forEach { (k, v) -> body.append(kvPara(k, v)) }
        doc.warnings.forEach { body.append(para("⚠ $it", style = "Warn")) }
        if (doc.summary.isNotEmpty()) {
            body.append(para("الملخّص", style = "Heading1"))
            body.append(table(listOf(Column("البند", ColType.TEXT, 2f), Column("القيمة", ColType.NUMBER, 1f)), doc.summary.map { listOf(Cell.Text(it.first), it.second) }, emptyList(), repeatHeader = false))
        }
        doc.tables.forEach { t ->
            body.append(para(t.title, style = "Heading1"))
            if (t.rows.isEmpty() && t.totals.isEmpty()) body.append(para("لا توجد بنود ضمن هذه الفلاتر.", style = "Meta"))
            else body.append(table(t.columns, t.rows, t.totals, repeatHeader = true))
        }
        if (doc.kind == ReportDoc.Kind.VOUCHER) {
            body.append(para("", null)); body.append(para("التوقيع: ____________________        المستلم: ____________________", null))
        }
        body.append("""<w:sectPr><w:footerReference w:type="default" r:id="rIdFooter"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1021" w:bottom="1134" w:left="1021" w:header="567" w:footer="567" w:gutter="0"/><w:bidi/></w:sectPr>""")

        val z = Zip()
        z.put("[Content_Types].xml", XML_HEAD + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
        z.put("_rels/.rels", XML_HEAD + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""")
        z.put("docProps/core.xml", XML_HEAD + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(doc.title)}</dc:title><dc:creator>رصيدي</dc:creator><dc:language>ar-SY</dc:language></cp:coreProperties>""")
        z.put("word/_rels/document.xml.rels", XML_HEAD + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/><Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/></Relationships>""")
        z.put("word/document.xml", XML_HEAD + """<w:document xmlns:w="$W" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$body</w:body></w:document>""")
        z.put("word/settings.xml", XML_HEAD + """<w:settings xmlns:w="$W"><w:defaultTabStop w:val="720"/><w:themeFontLang w:val="en-US" w:bidi="ar-SY"/><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>""")
        z.put("word/footer1.xml", XML_HEAD + """<w:ftr xmlns:w="$W"><w:p><w:pPr><w:bidi/><w:jc w:val="center"/></w:pPr>${run("رصيدي — ${doc.title} — صفحة ")}${field("PAGE")}${run(" من ")}${field("NUMPAGES")}</w:p></w:ftr>""")
        z.put("word/styles.xml", STYLES)
        return z.bytes()
    }

    private fun rPr(bold: Boolean = false, rtl: Boolean = true, color: String? = null, size: Int? = null) =
        "<w:rPr><w:rFonts w:ascii=\"$FONT\" w:hAnsi=\"$FONT\" w:cs=\"$FONT\"/>" + (if (bold) "<w:b/><w:bCs/>" else "") +
            (color?.let { "<w:color w:val=\"$it\"/>" } ?: "") + (size?.let { "<w:sz w:val=\"$it\"/><w:szCs w:val=\"$it\"/>" } ?: "") +
            (if (rtl) "<w:rtl/>" else "") + "<w:lang w:bidi=\"ar-SY\"/></w:rPr>"

    /** يقسم النص إلى مقاطع عربية ومقاطع لاتينية/رقمية حتى تأخذ كل منها اتجاهها الصحيح. */
    private fun runs(text: String, bold: Boolean = false): String {
        if (text.isEmpty()) return ""
        val clean = text.replace(Regex("[\u2066-\u2069\u200E\u200F]"), "")
        val sb = StringBuilder()
        val re = Regex("[A-Za-z0-9$€£.,:+\\-−%/()\\[\\]#@_=]+(?:[ ][A-Za-z0-9$€£.,:+\\-−%/()#@_=]+)*")
        var last = 0
        for (m in re.findAll(clean)) {
            if (m.range.first > last) sb.append(run(clean.substring(last, m.range.first), bold, true))
            sb.append(run(m.value, bold, false))
            last = m.range.last + 1
        }
        if (last < clean.length) sb.append(run(clean.substring(last), bold, true))
        return sb.toString()
    }

    private fun run(t: String, bold: Boolean = false, rtl: Boolean = true) = "<w:r>${rPr(bold, rtl)}<w:t xml:space=\"preserve\">${esc(t)}</w:t></w:r>"

    private fun field(name: String) = "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> $name </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r>${rPr(rtl = false)}<w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"

    private fun para(text: String, style: String?, ltr: Boolean = false, jc: String? = null, bold: Boolean = false): String =
        "<w:p><w:pPr>" + (style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: "") + (if (ltr) "<w:bidi w:val=\"0\"/>" else "<w:bidi/>") + (jc?.let { "<w:jc w:val=\"$it\"/>" } ?: "") +
            "<w:spacing w:before=\"0\" w:after=\"${if (style == null) 0 else 80}\"/></w:pPr>" + runs(text, bold) + "</w:p>"

    private fun kvPara(k: String, v: String) = "<w:p><w:pPr><w:pStyle w:val=\"Meta\"/><w:bidi/></w:pPr>${run("$k: ", true)}${runs(v)}</w:p>"

    private fun table(cols: List<Column>, rows: List<List<Cell>>, totals: List<List<Cell>>, repeatHeader: Boolean): String {
        val totalW = 9864 // عرض النص بين الهوامش (twips)
        val wsum = cols.sumOf { w(it).toDouble() }
        val widths = cols.map { (w(it) / wsum * totalW).toInt() }
        val sb = StringBuilder("<w:tbl><w:tblPr><w:bidiVisual/><w:tblW w:w=\"$totalW\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/>")
        sb.append("<w:tblBorders>" + listOf("top", "start", "bottom", "end", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D9D5C7\"/>" } + "</w:tblBorders>")
        sb.append("<w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:start w:w=\"80\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:end w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        widths.forEach { sb.append("<w:gridCol w:w=\"$it\"/>") }
        sb.append("</w:tblGrid>")
        // رأس الجدول يتكرر في كل صفحة
        sb.append("<w:tr><w:trPr>" + (if (repeatHeader) "<w:tblHeader/>" else "") + "<w:cantSplit/></w:trPr>")
        cols.forEachIndexed { i, c -> sb.append(tc(widths[i], "<w:p><w:pPr><w:bidi/><w:jc w:val=\"center\"/></w:pPr><w:r>${rPr(true, true, "FFFFFF")}<w:t xml:space=\"preserve\">${esc(c.header)}</w:t></w:r></w:p>", "0F5F3E")) }
        sb.append("</w:tr>")
        fun row(cells: List<Cell>, bold: Boolean, shade: String?) {
            sb.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
            cols.forEachIndexed { i, c ->
                val cell = cells.getOrElse(i) { Cell.Empty }
                val p = when (cell) {
                    is Cell.Num -> para(cell.display(), null, ltr = true, jc = "right", bold = bold)
                    is Cell.Date -> para(cell.display(), null, ltr = true, jc = "center", bold = bold)
                    else -> if (c.type == ColType.CODE) para(cell.display(), null, ltr = true, jc = "center", bold = bold) else para(cell.display(), null, bold = bold)
                }
                sb.append(tc(widths[i], p, shade))
            }
            sb.append("</w:tr>")
        }
        rows.forEachIndexed { idx, r -> row(r, false, if (idx % 2 == 1) "F7F5EE" else null) }
        totals.forEach { row(it, true, "EAF2EC") }
        sb.append("</w:tbl>")
        sb.append("<w:p><w:pPr><w:bidi/><w:spacing w:after=\"120\"/></w:pPr></w:p>")
        return sb.toString()
    }

    private fun w(c: Column) = when (c.type) { ColType.DATE -> 1.1f; ColType.CODE -> 0.8f; ColType.NUMBER -> 1.3f; ColType.TEXT -> 1.2f * c.weight }

    private fun tc(width: Int, content: String, shade: String?) =
        "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>" + (shade?.let { "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$it\"/>" } ?: "") + "</w:tcPr>$content</w:tc>"

    private val STYLES = XML_HEAD + """<w:styles xmlns:w="$W">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$FONT" w:hAnsi="$FONT" w:cs="$FONT" w:eastAsia="$FONT"/><w:sz w:val="20"/><w:szCs w:val="20"/><w:lang w:val="en-US" w:bidi="ar-SY"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:bidi/><w:spacing w:after="60" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:bidi/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:bidi/><w:spacing w:after="60"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val="0F5F3E"/><w:sz w:val="40"/><w:szCs w:val="40"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:qFormat/><w:rPr><w:color w:val="10241A"/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:bidi/><w:spacing w:before="240" w:after="100"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val="0F5F3E"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Meta"><w:name w:val="Meta"/><w:basedOn w:val="Normal"/><w:rPr><w:color w:val="6B7469"/><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Warn"><w:name w:val="Warning"/><w:basedOn w:val="Normal"/><w:pPr><w:shd w:val="clear" w:color="auto" w:fill="FFF4D6"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val="9A5B00"/></w:rPr></w:style>
<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>
</w:styles>"""
}
