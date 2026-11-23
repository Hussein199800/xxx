package app.rasidi.core.backup.drive

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * منطق رفع نسخة واحدة والتحقق منها، مستقل عن أندرويد:
 * 1. يتحقق أن الحساب المفوَّض (permissionId) هو حساب الوجهة المثبّت للمهمة.
 * 2. يستخدم معرّف ملف مجهّزاً مسبقاً (generateIds) ويعيد المحاولة بالمعرّف نفسه، فلا تتكرر الملفات.
 * 3. بعد مهلة مجهولة أو 409: ينزّل الملف الموجود ويطابق بصمته قبل اعتماد النجاح.
 * 4. يحتفظ بآخر [keepAuto] نسخ تلقائية متحقّقة من هذا التثبيت، ولا يمسّ النسخ اليدوية.
 */
class DriveBackupEngine(private val api: DriveApi, private val installId: String, private val keepAuto: Int = 7) {

    data class Job(
        val jobId: String,
        val targetPermissionId: String,
        val fileId: String?,
        val backupId: String,
        val kind: String, // AUTO | MANUAL
        val ledgerVersion: Long,
        val createdAtUtc: Long,
    )

    sealed interface Result {
        data class Verified(val meta: DriveApi.FileMeta, val pruned: Int) : Result
        data class AccountMismatch(val expected: String, val actual: String, val actualEmail: String) : Result
        data class Retry(val reason: String, val fileId: String?) : Result
        data class NeedsAuth(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun run(job: Job, bytes: ByteArray, persistFileId: (String) -> Unit): Result {
        val about = try { api.about() } catch (e: DriveAuthException) { return Result.NeedsAuth(e.message ?: "") } catch (e: IOException) { return Result.Retry("الشبكة: ${e.message}", job.fileId) }
        if (about.permissionId != job.targetPermissionId) return Result.AccountMismatch(job.targetPermissionId, about.permissionId, about.email)

        val id = job.fileId ?: try { api.generateId().also(persistFileId) } catch (e: DriveAuthException) { return Result.NeedsAuth(e.message ?: "") } catch (e: IOException) { return Result.Retry("تعذّر تجهيز المعرّف: ${e.message}", null) }
        val sha = sha256(bytes)
        val props = mapOf(
            "rasidi" to "1", "installId" to installId, "backupId" to job.backupId, "kind" to job.kind,
            "sha256" to sha, "ledgerVersion" to job.ledgerVersion.toString(), "owner" to job.targetPermissionId,
        )
        val name = "rasidi-${job.kind.lowercase()}-${job.createdAtUtc}.rasidi"
        val meta: DriveApi.FileMeta = try {
            api.upload(id, name, props, bytes)
        } catch (e: DriveAuthException) {
            return Result.NeedsAuth(e.message ?: "")
        } catch (e: DriveHttpException) {
            if (e.code == 409) existingVerified(id, sha) ?: return Result.Failed("يوجد ملف بالمعرّف نفسه ولا يطابق النسخة")
            else if (e.code in 500..599 || e.code == 429 || e.code == 408) return Result.Retry("Drive ${e.code}", id)
            else return Result.Failed("Drive ${e.code}: ${e.message}")
        } catch (e: SocketTimeoutException) {
            existingVerified(id, sha) ?: return Result.Retry("مهلة مجهولة؛ لم يُعثر على الملف بعد", id)
        } catch (e: IOException) {
            existingVerified(id, sha) ?: return Result.Retry("انقطاع الشبكة أثناء الرفع", id)
        }
        // تحقق: md5 الذي يحسبه Drive أو تنزيل ومطابقة SHA-256
        val ok = if (meta.md5 != null) meta.md5.equals(md5(bytes), ignoreCase = true) else runCatching { sha256(api.download(id)) == sha }.getOrDefault(false)
        if (!ok) return Result.Failed("بصمة الملف المرفوع لا تطابق")
        val pruned = runCatching { prune() }.getOrDefault(0)
        return Result.Verified(meta, pruned)
    }

    private fun existingVerified(id: String, sha: String): DriveApi.FileMeta? = try {
        val m = api.get(id)
        if (m != null && m.appProperties["sha256"] == sha && sha256(api.download(id)) == sha) m else null
    } catch (e: IOException) { null }

    /** يحذف النسخ التلقائية الأقدم من هذا التثبيت ويُبقي آخر [keepAuto]. */
    fun prune(): Int {
        val mine = api.list().filter { it.appProperties["installId"] == installId && it.appProperties["kind"] == "AUTO" }
            .sortedByDescending { it.createdTime }
        val old = mine.drop(keepAuto)
        old.forEach { api.delete(it.id) }
        return old.size
    }

    /** قائمة النسخ للاستعادة، الأحدث أولاً، مجمّعة حسب التثبيت (النسخ من جهازين تبقى مستقلة). */
    fun listBackups(): List<DriveApi.FileMeta> = api.list().filter { it.appProperties["rasidi"] == "1" }.sortedByDescending { it.createdTime }

    companion object {
        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    }
}
