package app.rasidi.core

import app.rasidi.core.backup.*
import app.rasidi.core.data.LedgerView
import app.rasidi.core.demo.DemoData
import app.rasidi.core.household.Household
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class HouseholdTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val v = LedgerView(DemoData.snapshot(today))

    @Test fun budgetsSpentInMonthOriginalCurrency() {
        val b = Household.budgets(v, YearMonth.of(2026, 11))
        val food = b.first { it.categoryId == "cat-food" }
        assertEquals(Money.of("3500", Currencies.TRY), food.spent) // سوق الخضار 3,500 TRY في الشهر
        assertEquals(Money.of("500", Currencies.TRY), food.remaining)
        assertTrue(food.near && !food.over)
        val bills = b.first { it.categoryId == "cat-bills" }
        assertEquals(Money.of("600", Currencies.TRY), bills.spent)
    }

    @Test fun recurringDueAndDoneByStableRequestId() {
        val m = YearMonth.of(2026, 11)
        val due = Household.recurringDue(v, m)
        assertEquals(3, due.size)
        assertTrue(due.none { it.done })
        assertEquals(LocalDate.of(2026, 11, 3), due.first { it.rec.id == "rec-rent" }.dueDate)
        // اليوم 31 في شهر من 30 يوماً ⇒ آخر يوم
        val r = v.s.recurring.first().copy(dayOfMonth = 31)
        assertEquals("rec:${r.id}:2026-11", Household.requestId(r, m))
    }

    @Test fun spendByMember() {
        val s = Household.spendByMember(v, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        assertEquals(Money.of("3500", Currencies.TRY), s.first { it.memberId == "mem-2" }.byCurrency["TRY"])
        assertEquals(Money.of("150", Currencies.SYP), s.first { it.memberId == "mem-3" }.byCurrency["SYP"])
        assertTrue(s.last().name == "مشترك")
    }

    @Test fun v1BackupMigratesToV2() {
        // لقطة بإصدار البيانات 1 (قبل الأفراد والميزانيات والمتكررات)
        val snap = DemoData.snapshot(today).copy(members = emptyList(), budgets = emptyList(), recurring = emptyList())
        val full = BackupJson.encodeToJsonElement(BackupFormat.Payload.serializer(), BackupFormat.Payload("x", emptyMap(), snap.copy(txns = snap.txns.map { it.copy(memberId = null) }))).jsonObject
        val s1 = full["snapshot"]!!.jsonObject
        val v1Snap = JsonObject(s1.filterKeys { it !in setOf("members", "budgets", "recurring") } + ("txns" to JsonArray(s1["txns"]!!.jsonArray.map { JsonObject(it.jsonObject - "memberId") })))
        val v1 = JsonObject(full + ("snapshot" to v1Snap)).toString()
        val upgraded = BackupJson.decodeFromString(BackupFormat.Payload.serializer(), BackupMigrations.upgrade(1, v1))
        assertEquals(snap.txns.size, upgraded.snapshot.txns.size)
        assertTrue(upgraded.snapshot.txns.all { it.memberId == null })
        assertTrue(upgraded.snapshot.members.isEmpty())
        assertTrue(SnapshotValidator.validate(upgraded.snapshot).isEmpty())
    }
}
