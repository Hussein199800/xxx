package app.rasidi

import app.rasidi.core.ledger.AccountType
import app.rasidi.core.money.Currencies
import app.rasidi.data.RasidiDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * - المخطط المصدَّر (schemas/…/1.json) يطابق الكيانات ويمكن إنشاء القاعدة منه.
 * - كل ترحيل في RasidiDatabase.MIGRATIONS يُختبر هنا بـ runMigrationsAndValidate عند إضافته.
 * - التحديث فوق قاعدة مستخدمة: نفتح قاعدة فيها بيانات بالإصدار الحالي ونعيد فتحها فتبقى البيانات.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationAndUpgradeTest {
    /** يطابق المخطط المصدَّر (createSql لكل جدول) مع القاعدة التي ينشئها Room من الكيانات الحالية. */
    @Test fun exportedSchemaMatchesEntities() {
        val dir = java.io.File(System.getProperty("rasidi.schemas.dir")!!, "app.rasidi.data.RasidiDatabase")
        val latest = dir.listFiles()!!.maxBy { it.nameWithoutExtension.toInt() }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(latest.readText()).let { it as kotlinx.serialization.json.JsonObject }
        val dbj = json["database"] as kotlinx.serialization.json.JsonObject
        assertEquals(RasidiDatabase.VERSION.toString(), (dbj["version"] as kotlinx.serialization.json.JsonPrimitive).content)
        val db = TestUtil.memDb()
        val actual = HashMap<String, String>()
        db.openHelper.readableDatabase.query("SELECT name, sql FROM sqlite_master WHERE type='table'").use { c -> while (c.moveToNext()) actual[c.getString(0)] = c.getString(1) }
        fun norm(s: String) = s.replace("`", "").replace(Regex("\\s+"), " ").trim()
        (dbj["entities"] as kotlinx.serialization.json.JsonArray).forEach { e ->
            val o = e as kotlinx.serialization.json.JsonObject
            val name = (o["tableName"] as kotlinx.serialization.json.JsonPrimitive).content
            val sql = (o["createSql"] as kotlinx.serialization.json.JsonPrimitive).content.replace("IF NOT EXISTS ", "").replace("\${TABLE_NAME}", name)
            assertEquals("جدول $name", norm(sql), norm(actual[name] ?: "missing"))
        }
        db.close()
        // سلسلة الترحيلات تغطي كل خطوة من 1 إلى الإصدار الحالي دون فجوات (وإلا فشل الفتح بدل مسح البيانات)
        val steps = RasidiDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        assertEquals((1 until RasidiDatabase.VERSION).map { it to it + 1 }, steps)
        // ولكل إصدار مخطط مصدَّر محفوظ في المستودع
        (1..RasidiDatabase.VERSION).forEach { assertTrue("مخطط $it", java.io.File(dir, "$it.json").exists()) }
    }

    @Test fun reopenUsedDatabaseKeepsData() = runBlocking {
        TestUtil.ctx.deleteDatabase("used.db")
        val db = TestUtil.fileDb("used.db"); val repo = TestUtil.repo(db)
        repo.ensureCategories(); repo.createAccount("نقد", AccountType.CASH, Currencies.USD, 7_00, LocalDate.of(2026, 1, 1))
        val fp = repo.snapshot().fingerprint(); db.close()
        val db2 = RasidiDatabase.open(TestUtil.ctx, "used.db")
        assertEquals(fp, TestUtil.repo(db2).snapshot().fingerprint())
        db2.close()
    }

    /** R08/R12: قاعدة v3 حقيقية (من المخطط المصدَّر) ⇒ v4: البيانات كما هي، وجدول الطلبات جديد فارغ يعمل. */
    @Test fun migrationFrom3To4KeepsDataAndEnablesRequests() = runBlocking {
        val ctx = TestUtil.ctx
        ctx.deleteDatabase("v3.db")
        val json = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(System.getProperty("rasidi.schemas.dir")!!, "app.rasidi.data.RasidiDatabase/3.json").readText())
            .let { (it as kotlinx.serialization.json.JsonObject)["database"] as kotlinx.serialization.json.JsonObject }
        fun str(e: kotlinx.serialization.json.JsonElement?) = (e as kotlinx.serialization.json.JsonPrimitive).content
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx).name("v3.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    (json["entities"] as kotlinx.serialization.json.JsonArray).forEach { e ->
                        val o = e as kotlinx.serialization.json.JsonObject; val n = str(o["tableName"])
                        db.execSQL(str(o["createSql"]).replace("\${TABLE_NAME}", n))
                        (o["indices"] as? kotlinx.serialization.json.JsonArray)?.forEach { ix -> db.execSQL(str((ix as kotlinx.serialization.json.JsonObject)["createSql"]).replace("\${TABLE_NAME}", n)) }
                    }
                    (json["setupQueries"] as kotlinx.serialization.json.JsonArray).forEach { db.execSQL(str(it)) }
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        helper.writableDatabase.apply {
            execSQL("INSERT INTO accounts (id,name,type,currency,allowNegative,archived,sort,createdAtUtc) VALUES ('a1','نقد','CASH','USD',0,0,1,0)")
            execSQL("INSERT INTO txns (id,type,date,note,party,requestId,debtId,reversesId,reversedById,fxRate,createdAtUtc,updatedAtUtc,memberId) VALUES ('t1','OPENING','2026-01-01','','','old-req',NULL,NULL,NULL,NULL,0,0,NULL)")
            execSQL("INSERT INTO entries (id,txnId,accountId,kind,amountMinor,currency,date,categoryId) VALUES ('e1','t1','a1','OPENING',700,'USD','2026-01-01',NULL)")
            execSQL("INSERT INTO parties (id,name,phone,note,archived,createdAtUtc) VALUES ('p1','خالد','','',0,0)")
        }
        helper.close()
        val db = RasidiDatabase.open(ctx, "v3.db")
        val repo = TestUtil.repo(db)
        val s = repo.snapshot()
        assertEquals(700L, s.entries.single().amountMinor)
        assertEquals(listOf("خالد"), s.parties.map { it.name })
        assertTrue(app.rasidi.core.backup.SnapshotValidator.validate(s).isEmpty())
        // طلب قديم (قبل جدول الطلبات) يُعاد كما هو لا يُكرَّر
        assertEquals("a1", repo.createAccount("نقد", AccountType.CASH, Currencies.USD, 7_00, LocalDate.of(2026, 1, 1), requestId = "old-req"))
        assertEquals(1, repo.snapshot().accounts.size)
        db.close()
    }
}
