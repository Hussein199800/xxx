package app.rasidi.backup

import android.content.Context
import app.rasidi.BuildConfig
import app.rasidi.core.backup.BackupFormat
import app.rasidi.core.backup.RecoveryCode
import app.rasidi.core.backup.Snapshot
import app.rasidi.data.LedgerRepository
import java.io.File

/**
 * إنشاء ملف .rasidi ومعاينته واستعادته.
 * الاستعادة: فحص كامل (الحجم، الترويسة، الإصدار، سلامة التشفير، العلاقات، البصمة) قبل أي كتابة،
 * ثم — في معاملة واحدة لا تتداخل معها كتابة — مقارنة السجل بالمعاينة، ونسخة أمان مُتحقَّق منها
 * إن لم يكن السجل فارغاً فعلاً، والاستبدال، ومطابقة البصمة؛ أي فشل يُرجع السجل السابق كاملاً.
 */
class BackupService(
    private val ctx: Context,
    private val repo: LedgerRepository,
    private val installId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val safetyRoot: () -> File = { File(ctx.filesDir, "safety") },
) {

    data class Created(val header: BackupFormat.Header, val bytes: ByteArray, val fingerprint: String, val counts: Map<String, Int>)

    suspend fun create(code: RecoveryCode, kind: String): Created {
        val snap = repo.snapshot()
        val (h, bytes) = BackupFormat.encrypt(snap, code, installId(), kind, clock(), BuildConfig.VERSION_NAME)
        return Created(h, bytes, snap.fingerprint(), snap.counts())
    }

    data class Preview(val opened: BackupFormat.Opened, val currentCounts: Map<String, Int>, val currentFingerprint: String, val currentEmpty: Boolean) {
        val backupCounts get() = opened.payload.counts
        val sameAsCurrent get() = opened.payload.fingerprint == currentFingerprint
    }

    suspend fun preview(bytes: ByteArray, code: RecoveryCode): Preview {
        val opened = BackupFormat.decrypt(bytes, code)
        val cur = repo.snapshot()
        return Preview(opened, cur.counts(), cur.fingerprint(), cur.isEffectivelyEmpty())
    }

    sealed interface RestoreResult {
        data class Done(val safetyFile: File?, val fingerprint: String) : RestoreResult
        data object Stale : RestoreResult
    }

    class SafetyRequired : IllegalStateException("يلزم رمز استعادة لهذا الجهاز لإنشاء نسخة الأمان قبل الاستبدال")

    suspend fun restore(preview: Preview, safetyCode: RecoveryCode?): RestoreResult {
        val target = preview.opened.payload.snapshot
        val expected = preview.opened.payload.fingerprint
        var safety: File? = null
        val outcome = try {
            repo.replaceIfUnchanged(preview.currentFingerprint, target, expected) { cur ->
                if (!cur.isEffectivelyEmpty()) {
                    val code = safetyCode ?: throw SafetyRequired()
                    safety = writeSafety(cur, code)
                }
            }
        } catch (e: Exception) {
            // فشلت الاستعادة ورجع السجل؛ نسخة أمان كُتبت لسجل لم يُستبدل لا حاجة لها
            safety?.delete()
            throw e
        }
        return when (outcome) {
            LedgerRepository.ReplaceOutcome.Stale -> RestoreResult.Stale
            is LedgerRepository.ReplaceOutcome.Replaced -> RestoreResult.Done(safety, outcome.fingerprint)
        }
    }

    /** كتابة ذرّية: ملف مؤقت + مزامنة + إعادة تسمية، ثم فكّه والتحقق من بصمته قبل المتابعة. */
    private fun writeSafety(cur: Snapshot, code: RecoveryCode): File {
        val (_, bytes) = BackupFormat.encrypt(cur, code, installId(), "SAFETY", clock(), BuildConfig.VERSION_NAME)
        val dir = safetyDir()
        if (!dir.isDirectory) throw java.io.IOException("تعذّر إنشاء مجلد نسخ الأمان")
        val name = "safety-${clock()}-${java.util.UUID.randomUUID().toString().take(8)}.rasidi"
        val tmp = File(dir, "$name.tmp")
        try {
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            val dest = File(dir, name)
            if (!tmp.renameTo(dest)) throw java.io.IOException("تعذّر حفظ نسخة الأمان")
            val check = BackupFormat.decrypt(dest.readBytes(), code)
            if (check.payload.fingerprint != cur.canonical().fingerprint()) { dest.delete(); throw java.io.IOException("نسخة الأمان لا تطابق السجل الحالي") }
            return dest
        } finally { tmp.delete() }
    }

    fun safetyDir() = safetyRoot().apply { mkdirs() }
    fun safetyFiles(): List<File> = safetyDir().listFiles()?.filter { it.name.endsWith(".rasidi") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun fileName(kind: String) = "rasidi-${kind.lowercase()}-${java.time.LocalDateTime.now().toString().take(16).replace(':', '-')}.rasidi"
}
