package app.rasidi.core

import app.rasidi.core.importer.CsvImport
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import org.junit.Assert.*
import org.junit.Test

class CsvImportTest {
    private val csv = """date;type;amount;currency;account;category;note;party;id
01/11/2026;مصروف;"1.250,50";TRY;بنك;أكل;"سوق; خضار";;a1
2026-11-02;income;٩٠٠;USD;نقد;راتب;;;a2
2026-11-03;expense;15000;SYP;نقد سوري;نقل;;;a3
bad-date;expense;1;USD;نقد;;;;a4
2026-11-04;expense;1;XYZ;نقد;;;;a5
2026-11-05;expense;5;USD;نقد;;;;a2
""".toByteArray()

    @Test fun parsesWithPreviewAndInvalidRows() {
        val p = CsvImport.parse(csv)
        assertEquals(';', p.delimiter)
        assertEquals(3, p.rows.size)
        assertEquals(3, p.invalid.size)
        assertEquals(Money.of("1250.50", Currencies.TRY), p.rows[0].amount)
        assertEquals("سوق; خضار", p.rows[0].note)
        assertEquals(Money.of("900", Currencies.USD), p.rows[1].amount)
        assertTrue(p.invalid.any { it.reason.contains("مكرر") })
    }

    @Test fun sypOldNewChoiceAppliedOnce() {
        assertEquals(Currencies.SYP, CsvImport.parse(csv).rows[2].amount.currency)
        val old = CsvImport.parse(csv, CsvImport.Options(sypMeansOld = true)).rows[2].amount
        assertEquals(Money.of("15000", Currencies.SYP_OLD), old) // لا قسمة على 100 عند الاستيراد
    }

    @Test fun stableRequestIdsPreventDuplicates() {
        val a = CsvImport.parse(csv).rows.map { it.requestId }
        val b = CsvImport.parse(csv).rows.map { it.requestId }
        assertEquals(a, b)
        val noIds = "date,type,amount,currency,account\n2026-11-01,expense,5,USD,نقد\n".toByteArray()
        assertEquals(CsvImport.parse(noIds).rows[0].requestId, CsvImport.parse(noIds).rows[0].requestId)
    }

    @Test fun templateParses() { assertEquals(2, CsvImport.parse(CsvImport.TEMPLATE.toByteArray()).rows.size) }
}
