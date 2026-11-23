package app.rasidi.core.reports

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * مصنف Excel حقيقي (SpreadsheetML) من اليمين لليسار:
 * - ورقة «الملخّص» ثم ورقة لكل جدول.
 * - صف العناوين مثبّت ومفلتر، والمبالغ خلايا رقمية والتواريخ خلايا تاريخية قابلة للفرز.
 * - العملة في عمود مستقل، والأسماء والملاحظات نصوص صريحة (inlineStr) لا تُفسَّر كصيغ.
 * - الأرقام التي تتجاوز 15 خانة معنوية تُضاف قيمتها الدقيقة في عمود نصّي مجاور.
 */
object XlsxWriter {
    // أنماط: 0 افتراضي، 1 عنوان عريض، 2 رأس جدول، 3 تاريخ، 4 رقم #,##0.00، 5 رقم #,##0، 6 رقم #,##0.000، 7 نص ملفوف، 8 إجمالي عريض رقم، 9 تحذير
    private val digitsStyle = mapOf(0 to 5, 2 to 4, 3 to 6)

    fun write(doc: ReportDoc): ByteArray {
        val sheets = mutableListOf<Pair<String, String>>() // name, xml
        sheets += uniqueName("الملخّص", sheets) to summarySheet(doc)
        doc.tables.forEach { t -> sheets += uniqueName(t.sheetName, sheets) to tableSheet(t) }

        val z = Zip()
        z.put("[Content_Types].xml", XML_HEAD + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
            sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
        z.put("_rels/.rels", XML_HEAD + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""")
        z.put("docProps/core.xml", XML_HEAD + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(doc.title)}</dc:title><dc:creator>رصيدي</dc:creator><dc:language>ar</dc:language></cp:coreProperties>""")
        val defined = StringBuilder()
        doc.tables.forEachIndexed { i, t ->
            val cols = expandedColumnCount(t)
            if (t.rows.isNotEmpty()) defined.append("""<definedName name="_xlnm._FilterDatabase" localSheetId="${i + 1}" hidden="1">'${esc(sheets[i + 1].first)}'!${'$'}A${'$'}1:${'$'}${col(cols - 1)}${'$'}${t.rows.size + 1}</definedName>""")
        }
        z.put("xl/workbook.xml", XML_HEAD + """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets>""" +
            sheets.mapIndexed { i, s -> """<sheet name="${esc(s.first)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets>" +
            (if (defined.isNotEmpty()) "<definedNames>$defined</definedNames>" else "") + "</workbook>")
        z.put("xl/_rels/workbook.xml.rels", XML_HEAD + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
            """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        z.put("xl/styles.xml", STYLES)
        sheets.forEachIndexed { i, s -> z.put("xl/worksheets/sheet${i + 1}.xml", s.second) }
        return z.bytes()
    }

    private fun uniqueName(raw: String, existing: List<Pair<String, String>>): String {
        var base = raw.replace(Regex("[\\[\\]:*?/\\\\']"), " ").trim().take(31).ifEmpty { "ورقة" }
        var n = 2; val names = existing.map { it.first }.toSet(); var name = base
        while (name in names) { val suf = " $n"; name = base.take(31 - suf.length) + suf; n++ }
        return name
    }

    private fun summarySheet(doc: ReportDoc): String {
        val rows = mutableListOf<String>(); var r = 0
        fun row(vararg cells: String) { r++; rows += "<row r=\"$r\">" + cells.mapIndexed { i, c -> c.replace("{ref}", "${col(i)}$r") }.joinToString("") + "</row>" }
        row(str(doc.title, 1)); row(str(doc.period, 0))
        row(str("تاريخ التوليد", 2), str(doc.generatedAt, 0))
        doc.filters.forEach { (k, v) -> row(str(k, 2), str(v, 7)) }
        doc.meta.forEach { (k, v) -> row(str(k, 2), str(v, 7)) }
        if (doc.warnings.isNotEmpty()) { row(); doc.warnings.forEach { row(str("⚠ تنبيه", 9), str(it, 9)) } }
        row()
        doc.summary.forEach { (k, v) -> row(str(k, 2), cell(v, bold = true)) }
        // جداول الإجماليات المطلوبة في الملخّص
        doc.tables.filter { it.totals.isNotEmpty() }.forEach { t ->
            row(); row(str(t.title, 1))
            row(*t.columns.map { str(it.header, 2) }.toTypedArray())
            t.totals.forEach { tr -> row(*tr.map { cell(it, bold = true) }.toTypedArray()) }
        }
        return sheetXml(rows, cols = listOf(28.0, 46.0, 18.0, 18.0, 18.0, 18.0), frozen = 0, autoFilter = null)
    }

    private fun needsExact(v: Cell) = v is Cell.Num && v.v.stripTrailingZeros().precision() > 15

    private fun expandedColumnCount(t: ReportTable): Int = t.columns.size + t.columns.indices.count { ci -> t.rows.any { needsExact(it.getOrElse(ci) { Cell.Empty }) } }

    private fun tableSheet(t: ReportTable): String {
        val exactCols = t.columns.indices.filter { ci -> (t.rows + t.totals).any { needsExact(it.getOrElse(ci) { Cell.Empty }) } }.toSet()
        val headers = mutableListOf<String>(); val widths = mutableListOf<Double>()
        t.columns.forEachIndexed { i, c ->
            headers += c.header; widths += when (c.type) { ColType.DATE -> 13.0; ColType.CODE -> 10.0; ColType.NUMBER -> 17.0; ColType.TEXT -> 14.0 + 10 * c.weight }
            if (i in exactCols) { headers += "${c.header} (قيمة دقيقة نصّية)"; widths += 26.0 }
        }
        val rows = mutableListOf<String>(); var r = 0
        fun row(cells: List<String>) { r++; rows += "<row r=\"$r\">" + cells.mapIndexed { i, c -> c.replace("{ref}", "${col(i)}$r") }.joinToString("") + "</row>" }
        row(headers.map { str(it, 2) })
        fun expand(cells: List<Cell>, bold: Boolean): List<String> = buildList {
            t.columns.indices.forEach { i ->
                val c = cells.getOrElse(i) { Cell.Empty }
                add(cell(c, bold))
                if (i in exactCols) add(if (c is Cell.Num) str(c.v.toPlainString(), 0) else "<c r=\"{ref}\"/>")
            }
        }
        t.rows.forEach { row(expand(it, false)) }
        val lastDataRow = r
        if (t.totals.isNotEmpty()) { row(emptyList()); t.totals.forEach { row(expand(it, true)) } }
        val ref = "A1:${col(headers.size - 1)}${maxOf(lastDataRow, 1)}"
        return sheetXml(rows, widths, frozen = 1, autoFilter = if (t.rows.isNotEmpty()) ref else null)
    }

    private fun sheetXml(rows: List<String>, cols: List<Double>, frozen: Int, autoFilter: String?): String {
        val pane = if (frozen > 0) """<pane ySplit="$frozen" topLeftCell="A${frozen + 1}" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft"/>""" else ""
        return XML_HEAD + """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><sheetViews><sheetView rightToLeft="1" workbookViewId="0">$pane</sheetView></sheetViews><sheetFormatPr defaultRowHeight="18"/><cols>""" +
            cols.mapIndexed { i, w -> """<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""" }.joinToString("") + "</cols><sheetData>" +
            rows.joinToString("") + "</sheetData>" + (autoFilter?.let { "<autoFilter ref=\"$it\"/>" } ?: "") +
            """<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/><pageSetup paperSize="9" orientation="portrait" fitToWidth="1" fitToHeight="0"/><headerFooter><oddFooter>&amp;C&amp;P / &amp;N</oddFooter></headerFooter>""" +
            (if (frozen > 0) "" else "") + "</worksheet>"
    }

    private fun str(s: String, style: Int) = "<c r=\"{ref}\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${esc(s)}</t></is></c>"

    private fun cell(c: Cell, bold: Boolean): String = when (c) {
        is Cell.Text -> str(c.v, if (bold) 1 else 7)
        is Cell.Num -> "<c r=\"{ref}\" s=\"${if (bold) (if (c.digits == 0) 10 else 8) else digitsStyle[c.digits] ?: 4}\"><v>${c.v.toPlainString()}</v></c>"
        is Cell.Date -> "<c r=\"{ref}\" s=\"3\"><v>${excelSerial(c.v)}</v></c>"
        Cell.Empty -> "<c r=\"{ref}\"/>"
    }

    fun excelSerial(d: LocalDate): Long = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d)

    fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }; return sb.toString() }

    private val STYLES = XML_HEAD + """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/><numFmt numFmtId="165" formatCode="#,##0.000"/></numFmts>
<fonts count="4"><font><sz val="11"/><name val="Arial"/><family val="2"/></font><font><b/><sz val="13"/><color rgb="FF0F5F3E"/><name val="Arial"/><family val="2"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Arial"/><family val="2"/></font><font><b/><sz val="11"/><color rgb="FF9A5B00"/><name val="Arial"/><family val="2"/></font></fonts>
<fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0F5F3E"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF4D6"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD9D5C7"/></left><right style="thin"><color rgb="FFD9D5C7"/></right><top style="thin"><color rgb="FFD9D5C7"/></top><bottom style="thin"><color rgb="FFD9D5C7"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="11">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"><alignment readingOrder="2"/></xf>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"><alignment readingOrder="2"/></xf>
<xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"><alignment horizontal="center" vertical="center" wrapText="1" readingOrder="2"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"><alignment horizontal="center"/></xf>
<xf numFmtId="4" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
<xf numFmtId="3" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
<xf numFmtId="49" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"><alignment vertical="top" wrapText="1" readingOrder="2"/></xf>
<xf numFmtId="4" fontId="1" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1"/>
<xf numFmtId="49" fontId="3" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"><alignment wrapText="1" readingOrder="2"/></xf>
<xf numFmtId="3" fontId="1" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
