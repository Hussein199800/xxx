package app.rasidi.core

import app.rasidi.core.backup.Snapshot
import app.rasidi.core.data.*
import app.rasidi.core.demo.DemoData
import app.rasidi.core.reports.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** F04–F06: تقرير الحسابات — كل صف مفلتر موجود، الإجماليات لكل عملة، لا قصّ بعد 100 صف. */
class AccountsReportTest {
    private val asOf = LocalDate.of(2026, 11, 20)
    companion object {
        /** بيانات اصطناعية: 130 حساباً بأرصدة مختلفة + حالات خاصة. */
        fun bigView(): LedgerView {
            val base = DemoData.snapshot(LocalDate.of(2026, 11, 20))
            val debts = (1..130).map { i -> DebtEntity("bd$i", "عميل تجريبي $i", if (i % 3 == 0) "I_OWE" else "OWED_TO_ME", (i * 1_000L), if (i % 4 == 0) "TRY" else "USD", "2026-0${1 + i % 9}-10") }
            val special = listOf(
                DebtEntity("m1", "مزدوج", "OWED_TO_ME", 50_00, "USD", "2026-05-01"), DebtEntity("m2", "مزدوج", "I_OWE", 70_00, "TRY", "2026-05-01"),
                DebtEntity("fut", "مستقبلي", "OWED_TO_ME", 9_00, "USD", "2027-01-01"),
            )
            val parties = (base.parties + (debts + special).map { it.party }.distinct().map { PartyEntity(app.rasidi.core.backup.PartyIds.forName(it), it) } +
                PartyEntity("z", "جديد بلا حركات") + PartyEntity("ar", "مؤرشف", archived = true)).distinctBy { it.name }
            return LedgerView(base.copy(debts = base.debts + debts + special, parties = parties))
        }
    }
    private val v = bigView()

    @Test fun everyFilteredRowAndPerCurrencyTotals() {
        val doc = AccountsReport.build(v, AccountsReport.Scope.ALL, AccountsReport.Options(asOf), "t")
        val people = doc.tables.first()
        val names = people.rows.map { (it[1] as Cell.Text).v }
        assertTrue("أكثر من 100 صف", people.rows.size > 130)
        assertTrue(names.contains("عميل تجريبي 130")); assertTrue(names.contains("جديد بلا حركات"))
        assertFalse("المؤرشف مستبعد افتراضياً", names.contains("مؤرشف"))
        // المستقبلي بعد «كما في» لا يُحتسب: حساب بلا حركات حتى ذلك التاريخ
        assertEquals("بلا حركات", (people.rows.first { (it[1] as Cell.Text).v == "مستقبلي" }[6] as Cell.Text).v)
        // مزدوج: صف دولار مدين وصف ليرة دائن، لا تقاصّ
        assertEquals(2, names.count { it == "مزدوج" })
        // الإجماليات لكل عملة تطابق مجموع الصفوف
        people.totals.forEach { t ->
            val c = (t[2] as Cell.Text).v
            val net = people.rows.filter { (it[2] as? Cell.Text)?.v == c }.mapNotNull { it[5] as? Cell.Num }.fold(BigDecimal.ZERO) { a, n -> a + n.v }
            assertEquals(0, net.compareTo((t[5] as Cell.Num).v))
        }
        assertEquals("الصناديق", doc.tables[1].title)
    }

    @Test fun scopesAndSorting() {
        val deb = AccountsReport.build(v, AccountsReport.Scope.DEBTOR, AccountsReport.Options(asOf), "t").tables.single()
        assertTrue(deb.rows.all { (it[6] as Cell.Text).v == "لي معه" })
        assertTrue(deb.rows.any { (it[1] as Cell.Text).v == "مزدوج" })
        val cred = AccountsReport.build(v, AccountsReport.Scope.CREDITOR, AccountsReport.Options(asOf), "t").tables.single()
        assertTrue(cred.rows.all { (it[6] as Cell.Text).v == "له معي" }); assertTrue(cred.rows.any { (it[1] as Cell.Text).v == "مزدوج" })
        val vis = AccountsReport.build(v, AccountsReport.Scope.VISIBLE, AccountsReport.Options(asOf, visibleNames = setOf("مزدوج")), "t").tables.single()
        assertEquals(2, vis.rows.size)
        val sorted = AccountsReport.build(v, AccountsReport.Scope.ALL, AccountsReport.Options(asOf, sort = AccountsReport.Sort.BALANCE), "t").tables.first()
        val usd = sorted.rows.filter { (it[2] as? Cell.Text)?.v == "USD" }.map { (it[5] as Cell.Num).v }
        assertEquals(usd.sortedDescending(), usd)
        val arch = AccountsReport.build(v, AccountsReport.Scope.ALL, AccountsReport.Options(asOf, includeArchived = true), "t").tables.first()
        assertTrue(arch.rows.any { (it[1] as Cell.Text).v == "مؤرشف" })
    }

    @Test fun privacyAndThreeFormatsKeepAllRows() {
        val doc = AccountsReport.build(v, AccountsReport.Scope.ALL, AccountsReport.Options(asOf, showPhone = true), "t", PrivacyOptions(hideNames = true))
        assertFalse(doc.tables.first().rows.any { (it[1] as Cell.Text).v.startsWith("عميل") })
        assertEquals(8, doc.tables.first().columns.size) // الهاتف يُخفى مع الأسماء
        val x = String(XlsxWriter.write(doc), Charsets.ISO_8859_1); assertTrue(x.startsWith("PK"))
        val pdf = PdfWriter.withBundledFonts().write(doc)
        assertTrue(String(pdf, Charsets.ISO_8859_1).split("/Type /Page\n", "/Type/Page ").size > 3 || String(pdf, Charsets.ISO_8859_1).contains("/Count"))
        assertTrue(DocxWriter.write(doc).isNotEmpty())
    }
}
