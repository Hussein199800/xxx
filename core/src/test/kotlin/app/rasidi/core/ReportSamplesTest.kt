package app.rasidi.core

import app.rasidi.core.data.LedgerView
import app.rasidi.core.demo.DemoData
import app.rasidi.core.ledger.GoldCalc
import app.rasidi.core.ledger.GoldKind
import app.rasidi.core.money.*
import app.rasidi.core.reports.*
import app.rasidi.core.valuation.*
import app.rasidi.core.zakat.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** يولّد عينات التقارير ببيانات اصطناعية ويتحقق من بنيتها. الملفات تُفتح لاحقاً بـ LibreOffice في scripts/verify-samples.sh. */
class ReportSamplesTest {
    private val outDir = File(System.getProperty("rasidi.samples.dir") ?: "build/samples").also { it.mkdirs() }
    private val gen = "2026-11-23 10:30"
    private val today = LocalDate.of(2026, 11, 20)
    private val view = LedgerView(DemoData.snapshot(today, extraTxns = 140))

    private fun writeAll(d0: ReportDoc, dir: File = outDir): List<File> {
        dir.mkdirs()
        // ترويسة الجهة المُصدِرة كما تأتي من «البيانات الشخصية» (بيانات اصطناعية)
        val doc = d0.copy(issuerName = "بيت أبو محمد", issuerLine = "حلب، سوريا · +963 900 000 000")
        val x = File(dir, doc.fileBase + ".xlsx").apply { writeBytes(XlsxWriter.write(doc)) }
        val d = File(dir, doc.fileBase + ".docx").apply { writeBytes(DocxWriter.write(doc)) }
        val p = File(dir, doc.fileBase + ".pdf").apply { writeBytes(PdfWriter.withBundledFonts().write(doc)) }
        return listOf(x, d, p)
    }

    private fun zipEntries(b: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(b)).use { z -> var e = z.nextEntry; while (e != null) { m[e.name] = z.readBytes().toString(Charsets.UTF_8); e = z.nextEntry } }
        return m
    }

    @Test fun mainSampleSameReportThreeFormats() {
        val doc = ReportBuilders.periodReport(view, today.withDayOfYear(1), today.withDayOfYear(1).plusYears(1).minusDays(1), gen, "سنوي")
        assertTrue("التقرير يجب أن يكون متعدد الصفحات", doc.tables.last().rows.size > 150)
        val files = writeAll(doc)
        val xlsx = zipEntries(files[0].readBytes())
        assertTrue(xlsx.keys.contains("xl/workbook.xml"))
        val detail = xlsx.filterKeys { it.startsWith("xl/worksheets/") }.values.last()
        assertTrue(detail.contains("rightToLeft=\"1\"")); assertTrue(detail.contains("state=\"frozen\"")); assertTrue(detail.contains("<autoFilter"))
        val docx = zipEntries(files[1].readBytes())
        assertTrue(docx.getValue("word/document.xml").contains("<w:tblHeader/>")); assertTrue(docx.getValue("word/document.xml").contains("<w:bidiVisual/>"))
        assertTrue(docx.getValue("word/footer1.xml").contains("NUMPAGES"))
        val pdf = files[2].readBytes()
        assertTrue(String(pdf, 0, 8, Charsets.ISO_8859_1).startsWith("%PDF-1.7"))
        assertTrue(String(pdf, Charsets.ISO_8859_1).contains("/ToUnicode"))
    }

    /** F06: تقرير جميع الحسابات متعدد الصفحات بكل صيغه (بيانات اصطناعية). */
    @Test fun accountsReportSamples() {
        val big = AccountsReportTest.bigView()
        val dir = File(outDir, "accounts")
        writeAll(AccountsReport.build(big, AccountsReport.Scope.ALL, AccountsReport.Options(today, showPhone = true), gen), dir)
        writeAll(AccountsReport.build(big, AccountsReport.Scope.DEBTOR, AccountsReport.Options(today), gen), dir)
        writeAll(AccountsReport.build(big, AccountsReport.Scope.CREDITOR, AccountsReport.Options(today, sort = AccountsReport.Sort.BALANCE), gen), dir)
    }

    @Test fun otherReportSamples() {
        val extra = File(outDir, "extra")
        writeAll(ReportBuilders.accountStatement(view, "acc-try", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), gen), extra)
        writeAll(ReportBuilders.debtReport(view, "debt-3", today, gen), extra)
        writeAll(ReportBuilders.voucher(view, view.s.txns.first { it.type == "TRANSFER" }.id, gen), extra)
        // مثال القبول للزكاة
        val d = LocalDate.of(2026, 3, 20)
        val rates = listOf(
            Rate("r-try", "TRY", BigDecimal(40), RateDirection.UNITS_PER_USD, d, "صرّاف"),
            Rate("r-syp", "SYP", BigDecimal(120), RateDirection.UNITS_PER_USD, d, "صرّاف"),
            Rate("r-g", Rate.GOLD24_GRAM, BigDecimal(100), RateDirection.USD_PER_UNIT, d, "صائغ"),
        )
        val items = listOf(
            InventoryItem("c1", LineKind.CASH, "نقد دولار", "USD", BigDecimal(4000), "4,000.00 USD"),
            InventoryItem("c2", LineKind.CASH, "بنك تركي", "TRY", BigDecimal(8000), "8,000.00 TRY"),
            InventoryItem("c3", LineKind.CASH, "نقد سوري جديد", "SYP", BigDecimal(12000), "12,000.00 SYP"),
            InventoryItem("g1", LineKind.GOLD, "ليرات ذهب عيار 21", Rate.GOLD24_GRAM, GoldCalc.pureGrams(BigDecimal(80), 21), "80غ عيار 21 = 70غ خالص", GoldKind.SAVINGS),
            InventoryItem("r1", LineKind.RECEIVABLE, "دين لي على أبي سامر", "USD", BigDecimal(400), "400.00 USD"),
            InventoryItem("p1", LineKind.PAYABLE, "قسط مستحق للمحل", "USD", BigDecimal(200), "200.00 USD"),
        )
        val inv = InventoryCalc.price(d, items, rates)
        writeAll(ReportBuilders.inventoryReport(inv, gen, "INV-2026-03-20 / مراجعة 1", "معتمد"), extra)
        val z = ZakatCalc.compute(ZakatInput(inv, mapOf("r1" to LineDecision(Decision.INCLUDE, reason = "دين مرجوّ"), "p1" to LineDecision(Decision.INCLUDE, reason = "قسط مستحق خلال الحول")),
            NisabPolicy.Manual(BigDecimal(85), 24, true, "إعداد المستخدم"), rates, "1447/09/01", "تاريخ اختاره المستخدم", true))
        assertEquals(BigDecimal("287.50"), z.zakatUsd)
        val pays = listOf(
            ZakatPayment("z1", Money.of("120", Currencies.USD), RateBook.USD_IDENTITY.copy(date = d), d, "acc-usd", "جمعية"),
            ZakatPayment("z2", Money.of("2000", Currencies.TRY), rates[0], d, "acc-try", "جار"),
            ZakatPayment("z3", Money.of("14100", Currencies.SYP), rates[1], d, "acc-syp", "قريب"),
        )
        val zdoc = ReportBuilders.zakatReport(z, pays, gen, "INV-2026-03-20 / مراجعة 1", mapOf("acc-usd" to "نقد دولار", "acc-try" to "بنك تركي", "acc-syp" to "نقد سوري"))
        assertFalse("أسماء المستفيدين مخفية افتراضياً", DocxWriter.write(zdoc).let { b -> zipEntries(b).getValue("word/document.xml").contains("جمعية") })
        writeAll(zdoc, extra)
        // جرد بسعر ناقص: الإجمالي الجزئي والتنبيه يظهران
        val partial = InventoryCalc.price(d, items, rates.drop(1))
        val pdoc = ReportBuilders.inventoryReport(partial, gen, "INV-2026-03-20 / مسودة", "مسودة")
        assertTrue(pdoc.warnings.isNotEmpty())
        writeAll(pdoc.copy(fileBase = pdoc.fileBase + "-مسودة-سعر-ناقص"), extra)
    }

    @Test fun longNumbersKeepExactTextColumn() {
        val doc = ReportDoc(ReportDoc.Kind.REPORT, "اختبار", "x", gen, fileBase = "t", tables = listOf(
            ReportTable("t", "t", listOf(Column("مبلغ", ColType.NUMBER)), listOf(listOf(Cell.Num(BigDecimal("9999999999999.99"))), listOf(Cell.Num(BigDecimal("1234567890123456.78")))))))
        val sheet = zipEntries(XlsxWriter.write(doc)).getValue("xl/worksheets/sheet2.xml")
        assertTrue(sheet.contains("قيمة دقيقة نصّية")); assertTrue(sheet.contains(">1234567890123456.78<"))
    }

    @Test fun arabicShapingProducesPresentationForms() {
        val v = ArabicShaper.visual("رصيدي")
        // بصرياً من اليسار: ي(معزولة بعد الدال) د ي(وسطي) ص(وسطي) ر
        assertEquals(listOf(0xFEF1, 0xFEAA, 0xFEF4, 0xFEBB, 0xFEAD), v.map { it.cp })
        assertEquals(0xFEFB, ArabicShaper.shape("لا")[0].cp)
        val mixed = ArabicShaper.visual("المبلغ 1,250.00 USD")
        val range = ArabicShaper.visual("1 يناير 2026 – 31 ديسمبر 2026")
        val digitRuns = Regex("[0-9]+").findAll(range.joinToString("") { if (it.cp < 0x600) it.source else " " }).map { it.value }.toList()
        assertEquals(listOf("2026", "31", "2026", "1"), digitRuns)
        assertEquals("1,250.00 USD", mixed.takeWhile { it.cp < 0x600 }.joinToString("") { it.source }.trim())
    }
}
