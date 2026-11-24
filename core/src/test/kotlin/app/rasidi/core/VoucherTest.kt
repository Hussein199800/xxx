package app.rasidi.core

import app.rasidi.core.data.LedgerView
import app.rasidi.core.demo.DemoData
import app.rasidi.core.ledger.PartyLedger
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import app.rasidi.core.reports.*
import app.rasidi.core.voucher.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class VoucherTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val v = LedgerView(DemoData.snapshot(today))
    private val opt = VoucherOptions(Profile("بيت أبو محمد", "حلب، سوريا", "+963 900 000 000"), signatures = listOf("المستلم", "المحاسب"), footer = "شكراً لتعاملكم")
    private val out = File(System.getProperty("rasidi.samples.dir") ?: "build/samples", "vouchers").also { it.mkdirs() }

    private fun txn(type: String, debt: String? = null) = v.s.txns.first { it.type == type && (debt == null || it.debtId == debt) }.id

    @Test fun kindsAndWords() {
        val exp = VoucherBuilder.build(v, txn("EXPENSE"), opt, "2026-11-24 08:11")
        assertEquals(VoucherKind.PAYMENT, exp.kind)
        assertEquals(Tafqit.money(exp.amount), exp.words)
        val inc = VoucherBuilder.build(v, txn("INCOME"), opt, "x")
        assertEquals(VoucherKind.RECEIPT, inc.kind)
        val ex = VoucherBuilder.build(v, txn("TRANSFER"), opt, "x")
        assertEquals(VoucherKind.EXCHANGE, ex.kind)
        assertTrue(ex.details.any { it.first == "المبلغ المستلم" })
        // دفعة دين عليّ (سداد لخالد): سند صرف أو إشعار مدين، مع الرصيد المتبقي له
        val pay = txn("DEBT_PAYMENT", "debt-3")
        assertEquals(VoucherKind.PAYMENT, VoucherBuilder.build(v, pay, opt, "x").kind)
        val notice = VoucherBuilder.build(v, pay, opt, "x", asNotice = true)
        assertEquals(VoucherKind.DEBIT_NOTE, notice.kind)
        assertEquals("خالد", notice.party)
        assertEquals(Money.of("100", Currencies.USD), notice.amount)
        assertTrue(notice.details.any { it.first == "الرصيد المتبقي له" && it.second.contains("100.00 USD") })
        assertTrue(notice.shareText().contains("سند إشعار مدين"))
        // رقم السند ثابت ومتزايد
        assertTrue(VoucherBuilder.number(v, pay) > VoucherBuilder.number(v, txn("EXPENSE")))
    }

    @Test fun samplesAllPapers() {
        val pay = txn("DEBT_PAYMENT", "debt-3")
        for (paper in Paper.values()) {
            val doc = ReportBuilders.voucher(v, txn("EXPENSE"), "2026-11-24 08:11", opt = opt.copy(paper = paper))
            File(out, "سند-صرف-${paper.name}.pdf").writeBytes(PdfWriter.withBundledFonts().write(doc))
        }
        val n = ReportBuilders.voucher(v, pay, "2026-11-24 08:12", opt = opt, asNotice = true)
        File(out, "سند-إشعار-مدين-A4.pdf").writeBytes(PdfWriter.withBundledFonts().write(n))
        File(out, "سند-إشعار-مدين.docx").writeBytes(DocxWriter.write(n))
        File(out, "سند-صرف-عملات-A5.pdf").writeBytes(PdfWriter.withBundledFonts().write(ReportBuilders.voucher(v, txn("TRANSFER"), "2026-11-24 08:13", opt = opt.copy(paper = Paper.A5))))
    }

    @Test fun partyStatement() {
        val parties = PartyLedger.parties(v)
        assertTrue(parties.any { it.party == "خالد" })
        val st = PartyLedger.statement(v, "خالد", "USD", LocalDate.of(2020, 1, 1), today, includePrior = false)
        // دين عليّ لخالد 200 (له) وسددت 100 (عليه) ⇒ الرصيد له 100
        assertEquals(Money.of("100", Currencies.USD), st.totalOn)
        assertEquals(Money.of("200", Currencies.USD), st.totalFor)
        assertEquals(Money.of("-100", Currencies.USD), st.closing)
        assertEquals("الرصيد له", st.closingLabel)
        val prior = PartyLedger.statement(v, "خالد", "USD", LocalDate.of(2026, 6, 1), today, includePrior = true)
        assertEquals(Money.of("-200", Currencies.USD), prior.prior)
        val doc = ReportBuilders.partyStatement(v, "خالد", "USD", LocalDate.of(2025, 1, 1), today, true, "2026-11-24 08:20", "يرجى مراجعتنا عند أي ملاحظة")
        File(out, "كشف-حساب-خالد.pdf").writeBytes(PdfWriter.withBundledFonts().write(doc))
        File(out, "كشف-حساب-خالد.xlsx").writeBytes(XlsxWriter.write(doc))
    }
}
