package app.rasidi.core

import app.rasidi.core.backup.*
import app.rasidi.core.data.LedgerView
import app.rasidi.core.demo.DemoData
import app.rasidi.core.ledger.PartyLedger
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PartiesTest {
    private val v = LedgerView(DemoData.snapshot(LocalDate.of(2026, 11, 20)))

    @Test fun allAccountsIncludingZeroBalanceAndDebtorCreditorSplit() {
        val ps = PartyLedger.parties(v)
        assertEquals(4, ps.size)
        val omAli = ps.first { it.party == "أم علي" }
        assertTrue(omAli.settled); assertNull(omAli.lastDate)
        // أبو سامر عليه 400 دولار: مدين (لي معه)
        assertTrue(ps.first { it.party == "أبو سامر" }.debtor)
        // خالد له 100 دولار: دائن (له معي)
        assertTrue(ps.first { it.party == "خالد" }.creditor)
        val (deb, cred) = PartyLedger.totals(ps)
        assertEquals(Money.of("400", Currencies.USD), deb["USD"])
        assertEquals(Money.of("100", Currencies.USD), cred["USD"])
        assertEquals(Money.of("20000", Currencies.TRY), cred["TRY"]) // محل الأجهزة: 30,000 − قسطان 10,000
        // الحسابات بلا رصيد في آخر القائمة
        assertEquals("أم علي", ps.last().party)
    }

    @Test fun v2BackupGetsPartiesFromDebtNames() {
        val snap = DemoData.snapshot(LocalDate.of(2026, 11, 20)).copy(parties = emptyList())
        val full = BackupJson.encodeToJsonElement(BackupFormat.Payload.serializer(), BackupFormat.Payload("x", emptyMap(), snap)).jsonObject
        val v2 = JsonObject(full + ("snapshot" to JsonObject(full["snapshot"]!!.jsonObject - "parties"))).toString()
        val up = BackupJson.decodeFromString(BackupFormat.Payload.serializer(), BackupMigrations.upgrade(2, v2))
        assertEquals(setOf("أبو سامر", "محل الأجهزة", "خالد"), up.snapshot.parties.map { it.name }.toSet())
        assertEquals(PartyIds.forName("خالد"), up.snapshot.parties.first { it.name == "خالد" }.id)
        assertTrue(SnapshotValidator.validate(up.snapshot).isEmpty())
    }
}
