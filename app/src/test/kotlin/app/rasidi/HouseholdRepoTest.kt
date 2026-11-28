package app.rasidi

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.rasidi.core.data.MemberEntity
import app.rasidi.core.data.RecurringEntity
import app.rasidi.core.household.Household
import app.rasidi.core.ledger.AccountType
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import app.rasidi.data.RasidiDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HouseholdRepoTest {
    private val d = LocalDate.of(2026, 11, 20)

    @Test fun recurringRecordedOncePerMonthWithMember() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb()); repo.ensureCategories()
        val acc = repo.createAccount("بنك", AccountType.BANK, Currencies.TRY, 50_000_00, d.minusMonths(2))
        repo.saveMember(MemberEntity("m1", "أم محمد"))
        val r = RecurringEntity("r1", "الإنترنت", "EXPENSE", acc, "cat-bills", 600_00, 13, "m1")
        repo.saveRecurring(r)
        val m = YearMonth.of(2026, 11)
        val t1 = repo.recordRecurring(r, m); val t2 = repo.recordRecurring(r, m)
        assertEquals(t1, t2)
        val v = repo.view()
        assertTrue(Household.recurringDue(v, m).single().done)
        assertFalse(Household.recurringDue(v, m.plusMonths(1)).single().done)
        assertEquals("m1", v.s.txns.first { it.id == t1 }.memberId)
        repo.saveBudget("cat-bills", Currencies.TRY, 500_00)
        val b = Household.budgets(repo.view(), m).single()
        assertTrue(b.over); assertEquals(Money.of("600", Currencies.TRY), b.spent)
    }

    /** قاعدة v2 حقيقية فيها دينان لشخص واحد (باسم فيه مسافة زائدة) ⇒ v3: حساب واحد بمعرّف ثابت والاسم موحّد. */
    @Test fun migrationFromV2CreatesParties() = runBlocking {
        val ctx = TestUtil.ctx
        ctx.deleteDatabase("v2.db")
        val json = Json.parseToJsonElement(File(System.getProperty("rasidi.schemas.dir")!!, "app.rasidi.data.RasidiDatabase/2.json").readText()).jsonObject["database"]!!.jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx).name("v2.db").callback(object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                json["entities"]!!.jsonArray.forEach { e ->
                    val o = e.jsonObject; val n = o["tableName"]!!.jsonPrimitive.content
                    db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", n))
                    o["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", n)) }
                }
                json["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }).build())
        helper.writableDatabase.apply {
            execSQL("INSERT INTO debts (id,party,direction,principalMinor,currency,date,dueDate,note,txnId,archived,createdAtUtc) VALUES ('d1','خالد ','I_OWE',100,'USD','2026-01-01',NULL,'',NULL,0,0)")
            execSQL("INSERT INTO debts (id,party,direction,principalMinor,currency,date,dueDate,note,txnId,archived,createdAtUtc) VALUES ('d2','خالد','I_OWE',50,'USD','2026-02-01',NULL,'',NULL,0,0)")
        }
        helper.close()
        val db = RasidiDatabase.open(ctx, "v2.db")
        val s = TestUtil.repo(db).snapshot()
        assertEquals(listOf("خالد"), s.parties.map { it.name })
        assertEquals(app.rasidi.core.backup.PartyIds.forName("خالد"), s.parties.single().id)
        assertTrue(s.debts.all { it.party == "خالد" })
        assertTrue(app.rasidi.core.backup.SnapshotValidator.validate(s).isEmpty())
        db.close()
    }

    /** R01/R02: ترحيل Room وترحيل النسخة يوحّدان الأسماء بالطريقة نفسها (Kotlin trim لا TRIM في SQLite). */
    @Test fun roomAndBackupMigrationNormalizePartyNamesIdentically() = runBlocking {
        val ctx = TestUtil.ctx
        ctx.deleteDatabase("v2ws.db")
        val json = Json.parseToJsonElement(File(System.getProperty("rasidi.schemas.dir")!!, "app.rasidi.data.RasidiDatabase/2.json").readText()).jsonObject["database"]!!.jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx).name("v2ws.db").callback(object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                json["entities"]!!.jsonArray.forEach { e ->
                    val o = e.jsonObject; val n = o["tableName"]!!.jsonPrimitive.content
                    db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", n))
                    o["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", n)) }
                }
                json["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }).build())
        val names = listOf("خالد\t", "خالد\u00A0", "خالد", " سامر\n")
        helper.writableDatabase.apply {
            names.forEachIndexed { i, n -> execSQL("INSERT INTO debts (id,party,direction,principalMinor,currency,date,dueDate,note,txnId,archived,createdAtUtc) VALUES (?,?,'I_OWE',100,'USD','2026-01-01',NULL,'',NULL,0,0)", arrayOf<Any>("d$i", n)) }
        }
        helper.close()
        val db = RasidiDatabase.open(ctx, "v2ws.db")
        val room = TestUtil.repo(db).snapshot()
        db.close()
        // المسار الثاني: ترحيل حمولة نسخة v2 بالأسماء نفسها
        val debts = names.mapIndexed { i, n -> app.rasidi.core.data.DebtEntity("d$i", n, "I_OWE", 100, "USD", "2026-01-01", null, "", null, false, 0) }
        val v3 = app.rasidi.core.backup.Snapshot(debts = debts)
        val asV2 = Json.parseToJsonElement(app.rasidi.core.backup.BackupJson.encodeToString(app.rasidi.core.backup.Snapshot.serializer(), v3)).jsonObject.let { JsonObject(it - "parties") }
        val payload = buildJsonObject { put("fingerprint", ""); put("counts", JsonObject(emptyMap())); put("snapshot", asV2) }
        val migrated = app.rasidi.core.backup.BackupJson.decodeFromString(app.rasidi.core.backup.BackupFormat.Payload.serializer(), app.rasidi.core.backup.BackupMigrations.upgrade(2, payload.toString())).snapshot
        assertEquals(listOf("سامر", "خالد").sorted(), room.parties.map { it.name }.sorted())
        assertEquals(room.parties.sortedBy { it.id }, migrated.parties.sortedBy { it.id })
        assertEquals(room.debts.sortedBy { it.id }.map { it.party }, migrated.debts.sortedBy { it.id }.map { it.party })
        assertTrue(room.debts.all { it.party == "خالد" || it.party == "سامر" })
    }

    @Test fun addPartyWithOpeningAndRename() = runBlocking {
        val repo = TestUtil.repo(TestUtil.memDb()); repo.ensureCategories()
        repo.addParty("أم علي", "+963 900", "", 0, Currencies.USD, true, d, "p1")
        repo.addParty("حليمة الخالد", "", "", 500_00, Currencies.USD, true, d, "p2")
        var v = repo.view()
        val ps = app.rasidi.core.ledger.PartyLedger.parties(v)
        assertTrue(ps.first { it.party == "أم علي" }.settled)
        assertEquals(Money.of("500", Currencies.USD), ps.first { it.party == "حليمة الخالد" }.balances["USD"])
        assertTrue(runCatching { repo.addParty("أم علي", "", "", 0, Currencies.USD, true, d, "p3") }.exceptionOrNull() is app.rasidi.data.LedgerException)
        repo.updateParty(v.s.parties.first { it.name == "حليمة الخالد" }, "حليمة", "", "")
        v = repo.view()
        assertEquals("حليمة", v.debts.single().party)
        assertTrue(app.rasidi.core.backup.SnapshotValidator.validate(v.s).isEmpty())
    }

    /** يبني قاعدة v1 حقيقية من المخطط المصدَّر 1.json فيها بيانات، ثم يفتحها Room بالإصدار الحالي عبر الترحيل. */
    @Test fun migrationFromV1KeepsDataAndMatchesSchema() = runBlocking {
        val ctx = TestUtil.ctx
        ctx.deleteDatabase("v1.db")
        val json = Json.parseToJsonElement(File(System.getProperty("rasidi.schemas.dir")!!, "app.rasidi.data.RasidiDatabase/1.json").readText()).jsonObject["database"]!!.jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx).name("v1.db").callback(object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                json["entities"]!!.jsonArray.forEach { e ->
                    val o = e.jsonObject; val n = o["tableName"]!!.jsonPrimitive.content
                    db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", n))
                    o["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", n)) }
                }
                json["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }).build())
        helper.writableDatabase.apply {
            execSQL("INSERT INTO accounts (id,name,type,currency,allowNegative,archived,sort,createdAtUtc) VALUES ('a','نقد','CASH','USD',0,0,1,0)")
            execSQL("INSERT INTO txns (id,type,date,note,party,requestId,debtId,reversesId,reversedById,fxRate,createdAtUtc,updatedAtUtc) VALUES ('t','OPENING','2026-01-01','','','r',NULL,NULL,NULL,NULL,0,0)")
            execSQL("INSERT INTO entries (id,txnId,accountId,kind,amountMinor,currency,date,categoryId) VALUES ('e','t','a','OPENING',12345,'USD','2026-01-01',NULL)")
        }
        helper.close()
        val db = RasidiDatabase.open(ctx, "v1.db")
        val v = TestUtil.repo(db).view()
        assertEquals(Money(12345, Currencies.USD), v.balance(v.accounts.single()))
        assertNull(v.s.txns.single().memberId)
        assertTrue(v.s.members.isEmpty())
        db.close()
    }
}
