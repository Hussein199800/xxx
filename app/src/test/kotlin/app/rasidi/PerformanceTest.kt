package app.rasidi

import app.rasidi.backup.BackupService
import app.rasidi.core.backup.RecoveryCode
import app.rasidi.core.demo.DemoData
import app.rasidi.core.data.netWorth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import kotlin.system.measureTimeMillis

/** دفتر بعشرة آلاف حركة اصطناعية: الفتح والبحث والنسخ. النتائج تُكتب في docs/perf-results.txt (على JVM مع Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PerformanceTest {
    @Test fun tenThousandTransactions() = runBlocking {
        TestUtil.ctx.deleteDatabase("perf.db")
        val db = TestUtil.fileDb("perf.db"); val repo = TestUtil.repo(db)
        val snap = DemoData.snapshot(LocalDate.of(2026, 11, 20), extraTxns = 10_000)
        val tLoad = measureTimeMillis { repo.replaceAll(snap) }
        db.close()
        val db2 = TestUtil.fileDb("perf.db"); val repo2 = TestUtil.repo(db2)
        lateinit var v: app.rasidi.core.data.LedgerView
        val tOpen = measureTimeMillis { v = repo2.view() }
        val tHome = measureTimeMillis { v.netWorth(LocalDate.of(2026, 11, 20)) }
        var hits = 0
        val tSearch = measureTimeMillis { hits = v.s.txns.count { it.note.contains("اصطناعي 99") } }
        var size = 0
        val tBackup = measureTimeMillis { size = BackupService(TestUtil.ctx, repo2, { "perf" }).create(RecoveryCode.generate(), "MANUAL").bytes.size }
        db2.close()
        val report = """
            txns=${snap.txns.size} entries=${snap.entries.size}
            استيراد في Room (معاملة واحدة): ${tLoad} ms
            فتح الدفتر (قراءة لقطة + بناء العرض): ${tOpen} ms
            حساب الرئيسية (صافي الأموال): ${tHome} ms
            بحث نصي في البيان: ${tSearch} ms (${hits} نتيجة)
            إنشاء نسخة مشفّرة: ${tBackup} ms، الحجم ${size / 1024} KB
        """.trimIndent()
        println(report)
        System.getProperty("rasidi.screens.dir")?.let { File(it).parentFile.resolve("perf-results.txt").apply { parentFile.mkdirs(); writeText(report + "\n") } }
        assertTrue(tOpen < 20_000)
    }
}
