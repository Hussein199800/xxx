package app.rasidi.core

import app.rasidi.core.backup.drive.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** اختبارات بعميل Drive وهمي (لا تتصل بـ Google). الاختبار الفعلي موثّق في VALIDATION.md. */
class FakeDrive(var permissionId: String = "perm-A") : DriveApi {
    val files = LinkedHashMap<String, Pair<DriveApi.FileMeta, ByteArray>>()
    var nextIds = ArrayDeque<String>()
    var failNextUpload: IOException? = null
    var storeBeforeFailing = false
    var authExpired = false
    var corruptStored = false
    private var clock = 0
    override fun about(): DriveApi.About { if (authExpired) throw DriveAuthException("expired"); return DriveApi.About(permissionId, "$permissionId@example.com", "Test") }
    override fun generateId() = nextIds.removeFirstOrNull() ?: "id-${files.size + 100}-${clock++}"
    override fun upload(id: String, name: String, appProperties: Map<String, String>, bytes: ByteArray): DriveApi.FileMeta {
        failNextUpload?.let { e -> failNextUpload = null; if (storeBeforeFailing) store(id, name, appProperties, bytes); throw e }
        if (files.containsKey(id)) throw DriveHttpException(409, "exists")
        return store(id, name, appProperties, bytes)
    }
    private fun store(id: String, name: String, p: Map<String, String>, b: ByteArray): DriveApi.FileMeta {
        val m = DriveApi.FileMeta(id, name, b.size.toLong(), "2026-11-%02dT00:00:%02dZ".format(1 + clock / 60, clock++ % 60), DriveBackupEngine.md5(if (corruptStored) b + 1 else b), p)
        files[id] = m to b; return m
    }
    override fun get(id: String) = files[id]?.first
    override fun download(id: String) = files[id]?.second ?: throw DriveHttpException(404, "nf")
    override fun list() = files.values.map { it.first }
    override fun delete(id: String) { files.remove(id) }
}

class DriveEngineTest {
    private val bytes = "encrypted-backup".toByteArray()
    private fun job(n: Int = 1, fileId: String? = null, kind: String = "AUTO") = DriveBackupEngine.Job("job-$n", "perm-A", fileId, "b-$n", kind, n.toLong(), 1000L + n)

    @Test fun uploadVerifies() {
        val d = FakeDrive(); var persisted: String? = null
        val r = DriveBackupEngine(d, "inst").run(job(), bytes) { persisted = it }
        assertTrue(r is DriveBackupEngine.Result.Verified); assertNotNull(persisted); assertEquals(1, d.files.size)
    }

    @Test fun wrongAccountRejected() {
        val d = FakeDrive("perm-B")
        val r = DriveBackupEngine(d, "inst").run(job(), bytes) {}
        assertTrue(r is DriveBackupEngine.Result.AccountMismatch); assertEquals(0, d.files.size)
    }

    @Test fun networkDropAfterServerStoredLeavesSingleFile() {
        val d = FakeDrive(); d.nextIds.add("fixed-1"); d.failNextUpload = SocketTimeoutException("timeout"); d.storeBeforeFailing = true
        var id: String? = null
        val r = DriveBackupEngine(d, "inst").run(job(), bytes) { id = it }
        // المهلة مجهولة لكن الملف وصل: يُنزَّل ويُتحقق منه ويُعتمد
        assertTrue(r is DriveBackupEngine.Result.Verified); assertEquals(1, d.files.size); assertEquals("fixed-1", id)
    }

    @Test fun networkDropBeforeStoreRetriesWithSameId() {
        val d = FakeDrive(); d.nextIds.add("fixed-2"); d.failNextUpload = IOException("reset")
        var id: String? = null
        val e = DriveBackupEngine(d, "inst")
        val r1 = e.run(job(), bytes) { id = it }
        assertTrue(r1 is DriveBackupEngine.Result.Retry); assertEquals(0, d.files.size)
        val r2 = e.run(job(fileId = id), bytes) { fail("لا يجب تجهيز معرّف جديد") }
        assertTrue(r2 is DriveBackupEngine.Result.Verified); assertEquals(setOf("fixed-2"), d.files.keys)
        // إعادة نفس المهمة بعد نجاحها: 409 ثم تحقق → ملف واحد
        val r3 = e.run(job(fileId = id), bytes) {}
        assertTrue(r3 is DriveBackupEngine.Result.Verified); assertEquals(1, d.files.size)
    }

    @Test fun conflictWithDifferentContentFails() {
        val d = FakeDrive(); d.nextIds.add("x")
        val e = DriveBackupEngine(d, "inst")
        e.run(job(), bytes) {}
        val r = e.run(job(2, fileId = "x"), "other".toByteArray()) {}
        assertTrue(r is DriveBackupEngine.Result.Failed)
    }

    @Test fun keepsLastSevenAutoAndAllManual() {
        val d = FakeDrive(); val e = DriveBackupEngine(d, "inst")
        repeat(10) { e.run(job(it), bytes) {} }
        e.run(job(50, kind = "MANUAL"), bytes) {}
        d.files["other"] = DriveApi.FileMeta("other", "o", 1, "2026-01-01", null, mapOf("rasidi" to "1", "installId" to "other-device", "kind" to "AUTO")) to bytes
        e.prune()
        val mine = d.files.values.map { it.first }.filter { it.appProperties["installId"] == "inst" }
        assertEquals(7, mine.count { it.appProperties["kind"] == "AUTO" })
        assertEquals(1, mine.count { it.appProperties["kind"] == "MANUAL" })
        assertTrue(d.files.containsKey("other"))
    }

    @Test fun expiredAuthAsksForRelink() {
        val d = FakeDrive(); d.authExpired = true
        assertTrue(DriveBackupEngine(d, "inst").run(job(), bytes) {} is DriveBackupEngine.Result.NeedsAuth)
    }

    /** R15: أخطاء HTTP المؤقتة تعيد المحاولة بالمعرّف نفسه، والدائمة تفشل، والملف التالف لا يُعتمد. */
    @Test fun transientHttpRetriesWithSameIdPermanentFails() {
        for (code in listOf(429, 500, 503, 408)) {
            val d = FakeDrive(); d.nextIds.add("fx-$code"); d.failNextUpload = DriveHttpException(code, "busy")
            var id: String? = null
            val r = DriveBackupEngine(d, "inst").run(job(), bytes) { id = it }
            assertTrue("$code", r is DriveBackupEngine.Result.Retry); assertEquals("fx-$code", (r as DriveBackupEngine.Result.Retry).fileId)
            // إعادة المحاولة بالمعرّف المثبّت تنجح دون تكرار
            assertTrue(DriveBackupEngine(d, "inst").run(job(fileId = id), bytes) {} is DriveBackupEngine.Result.Verified)
            assertEquals(1, d.files.size)
        }
        val d = FakeDrive(); d.failNextUpload = DriveHttpException(403, "forbidden")
        assertTrue(DriveBackupEngine(d, "inst").run(job(), bytes) {} is DriveBackupEngine.Result.Failed)
    }

    @Test fun corruptedUploadIsNotVerifiedAndNothingPruned() {
        val d = FakeDrive()
        repeat(8) { DriveBackupEngine(d, "inst").run(job(it + 1), bytes) {} }
        val before = d.files.size
        d.corruptStored = true
        val r = DriveBackupEngine(d, "inst").run(job(20), bytes) {}
        assertTrue(r is DriveBackupEngine.Result.Failed)
        assertEquals("لا حذف قبل تأكيد نسخة جديدة", before + 1, d.files.size)
    }
}
