package app.rasidi.core

import app.rasidi.core.backup.Snapshot
import app.rasidi.core.data.*
import app.rasidi.core.ledger.PartyLedger
import app.rasidi.core.reports.ReportBuilders
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** F07: «الكل» في العرض والتصدير بالحدود نفسها، ويشمل الحركات المؤرخة مستقبلاً. */
class StatementRangeTest {
    private val today = LocalDate.of(2026, 11, 28)
    private val v = LedgerView(Snapshot(
        debts = listOf(
            DebtEntity("d1", "خالد", "OWED_TO_ME", 100_00, "USD", "2026-01-05"),
            DebtEntity("d2", "خالد", "OWED_TO_ME", 40_00, "USD", "2027-02-01"), // مستقبلي
            DebtEntity("d3", "خالد", "I_OWE", 9_00, "TRY", "2026-03-01"),
        ),
        parties = listOf(PartyEntity("p", "خالد")),
    ))

    @Test fun allRangeCoversFutureAndMatchesExport() {
        val (from, to) = PartyLedger.allRange(v, "خالد", "USD", today)
        assertEquals(LocalDate.of(2026, 1, 5), from); assertEquals(LocalDate.of(2027, 2, 1), to)
        val shown = PartyLedger.statement(v, "خالد", "USD", from, to, false)
        assertEquals(2, shown.lines.size)
        val doc = ReportBuilders.partyStatement(v, "خالد", "USD", from, to, false, "t")
        assertEquals(shown.lines.size, doc.tables.first().rows.size)
        assertTrue(doc.filters.any { it.second.contains("مستقبل") })
        // عملة بلا حركات: النطاق اليوم فقط، بلا انهيار
        assertEquals(today to today, PartyLedger.allRange(v, "خالد", "EUR", today))
    }
}
