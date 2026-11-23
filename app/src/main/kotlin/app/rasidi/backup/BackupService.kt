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
 * ثم نسخة أمان محلية للسجل الحالي إن لم يكن فارغاً، ثم استبدال في معاملة واحدة، ثم التحقق من التساوي.
 */
class BackupService(private val ctx: Context, private val repo: LedgerRepository, private val installId: () -> String, private val clock: () -> Long = System::currentTimeMillis) {

    data class Created(val header: BackupFormat.Header, val bytes: ByteArray, val fingerprint: String, val counts: Map<String, Int>)

    suspend fun create(code: RecoveryCode, kind: String): Created {
        val snap = repo.snapshot()
        val (h, bytes) = BackupFormat.encrypt(snap, code, installId(), kind, clock(), BuildConfig.VERSION_NAME)
        return Created(h, bytes, snap.fingerprint(), snap.counts())
    }

    data class Preview(
        val opened: BackupFormat.Opened,
        val currentCounts: Map<String, Int>,
        val currentFingerprint: String,
        val currentEmpty: Boolean,
    ) {
        val backupCounts get() = opened.payload.counts
        val sameAsCurrent get() = opened.payload.fingerprint == currentFingerprint
    }

    /** يفك ويتحقق ويقارن بالسجل الحالي. لا يكتب شيئاً. */
    suspend fun preview(bytes: ByteArray, code: RecoveryCode): Preview {
        val opened = BackupFormat.decrypt(bytes, code)
        val cur = repo.snapshot()
        return Preview(opened, cur.counts(), cur.fingerprint(), cur.accounts.isEmpty() && cur.txns.isEmpty())
    }

    sealed interface RestoreResult {
        data class Done(val safetyFile: File?, val fingerprint: String) : RestoreResult
        /** تغيّر السجل بعد المعاينة: حدّث المعاينة قبل التأكيد. */
        data object Stale : RestoreResult
    }

    /**
     * ينفذ الاستعادة. [safetyCode] لتشفير نسخة الأمان (رمز هذا الجهاز). يتحقق أن السجل لم يتغير منذ المعاينة.
     */
    suspend fun restore(preview: Preview, safetyCode: RecoveryCode?): RestoreResult {
        val cur = repo.snapshot()
        if (cur.fingerprint() != preview.currentFingerprint) return RestoreResult.Stale
        var safety: File? = null
        if (!preview.currentEmpty) {
            val code = safetyCode ?: throw IllegalStateException("يلزم رمز استعادة لهذا الجهاز لإنشاء نسخة الأمان قبل الاستبدال")
            val (_, bytes) = BackupFormat.encrypt(cur, code, installId(), "SAFETY", clock(), BuildConfig.VERSION_NAME)
            safety = File(safetyDir(), "safety-${clock()}.rasidi").apply { writeBytes(bytes) }
            // تحقق أن نسخة الأمان تُفتح قبل الاستبدال
            BackupFormat.decrypt(safety.readBytes(), code)
        }
        repo.replaceAll(preview.opened.payload.snapshot)
        val after = repo.snapshot()
        val fp = after.fingerprint()
        check(fp == preview.opened.payload.fingerprint) { "بصمة السجل بعد الاستعادة لا تطابق النسخة" }
        return RestoreResult.Done(safety, fp)
    }

    fun safetyDir() = File(ctx.filesDir, "safety").apply { mkdirs() }
    fun safetyFiles(): List<File> = safetyDir().listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun fileName(kind: String) = "rasidi-${kind.lowercase()}-${java.time.LocalDateTime.now().toString().take(16).replace(':', '-')}.rasidi"
}
