package app.rasidi.backup

import android.content.Context
import androidx.work.*
import app.rasidi.RasidiApp
import app.rasidi.core.backup.drive.DriveAuthException
import app.rasidi.core.backup.drive.DriveBackupEngine
import app.rasidi.core.backup.drive.DriveRestClient
import app.rasidi.data.BackupJobEntity
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * عامل النسخ إلى Drive (WorkManager): «انسخ الآن» عند الطلب، ونسخ تلقائي يومي تقريبي عند توفر الشبكة بعد تغيّر البيانات.
 * المهمة تثبّت حساب الوجهة (permissionId) وإصدار السجل ومعرّف الملف؛ الملف المشفّر نفسه هو عنصر الطابور.
 * إعادة المحاولة تدريجية (أسّية) بالمعرّف نفسه.
 */
class DriveBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as RasidiApp
        val c = app.container
        if (c.demo) return Result.success() // دفتر العرض لا يُنسخ
        val kind = inputData.getString(KEY_KIND) ?: "AUTO"
        val dev = c.device
        val perm = dev.drivePermissionId ?: return Result.success()
        if (kind == "AUTO" && !dev.autoBackup) return Result.success()

        return c.backupLock.withLock {
            val jobs = c.db.jobs()
            val existing = jobs.pending().firstOrNull { it.targetPermissionId == perm && (kind == "AUTO" || it.kind == kind) }
            val job: BackupJobEntity = existing ?: run {
                if (kind == "AUTO" && dev.ledgerVersion == dev.lastVerifiedVersion) return@withLock Result.success()
                val code = dev.recoveryCode() ?: return@withLock Result.failure(workDataOf(KEY_ERROR to "لا يوجد رمز استعادة على هذا الجهاز"))
                val created = c.backupService.create(code, kind)
                val id = UUID.randomUUID().toString()
                val f = File(queueDir(applicationContext), "$id.rasidi").apply { writeBytes(created.bytes) }
                BackupJobEntity(id, kind, perm, null, created.header.backupId, dev.ledgerVersion, f.absolutePath, "QUEUED", 0, "", System.currentTimeMillis(), System.currentTimeMillis()).also { jobs.insert(it) }
            }
            val file = File(job.localPath)
            if (!file.exists()) { jobs.update(job.copy(status = "FAILED", lastError = "ملف الطابور مفقود", updatedAtUtc = now())); return@withLock Result.failure() }

            val token = when (val t = DriveAuth.silentToken(applicationContext)) {
                is DriveAuth.Silent.Token -> t.value
                DriveAuth.Silent.NeedsUser -> { dev.driveRelinkNeeded = true; jobs.update(job.copy(lastError = "يلزم إعادة ربط Google", updatedAtUtc = now())); return@withLock Result.success() }
                is DriveAuth.Silent.Unavailable -> { jobs.update(job.copy(attempts = job.attempts + 1, lastError = t.reason, updatedAtUtc = now())); return@withLock if (runAttemptCount < 6) Result.retry() else Result.failure() }
            }
            jobs.update(job.copy(status = "RUNNING", attempts = job.attempts + 1, updatedAtUtc = now()))
            val engine = DriveBackupEngine(DriveRestClient({ token }), dev.installId)
            val ej = DriveBackupEngine.Job(job.jobId, job.targetPermissionId, job.fileId, job.backupId, job.kind, job.ledgerVersion, job.createdAtUtc)
            var current: BackupJobEntity = job
            val r = try {
                engine.run(ej, file.readBytes()) { fid -> current = current.copy(fileId = fid); kotlinx.coroutines.runBlocking { jobs.update(current) } }
            } catch (e: DriveAuthException) { DriveBackupEngine.Result.NeedsAuth(e.message ?: "") }
            when (r) {
                is DriveBackupEngine.Result.Verified -> {
                    jobs.update(current.copy(status = "VERIFIED", lastError = "", updatedAtUtc = now()))
                    dev.lastVerifiedAt = now(); dev.lastVerifiedVersion = job.ledgerVersion; dev.driveRelinkNeeded = false
                    file.delete()
                    Result.success()
                }
                is DriveBackupEngine.Result.Retry -> { jobs.update(current.copy(status = "QUEUED", lastError = r.reason, updatedAtUtc = now())); Result.retry() }
                is DriveBackupEngine.Result.AccountMismatch -> { jobs.update(current.copy(status = "ACCOUNT_MISMATCH", lastError = "الحساب المفوَّض ${r.actualEmail} ليس حساب الوجهة", updatedAtUtc = now())); file.delete(); Result.failure() }
                is DriveBackupEngine.Result.NeedsAuth -> { DriveAuth.clearToken(applicationContext, token); dev.driveRelinkNeeded = true; jobs.update(current.copy(status = "QUEUED", lastError = "يلزم إعادة ربط Google", updatedAtUtc = now())); Result.success() }
                is DriveBackupEngine.Result.Failed -> { jobs.update(current.copy(status = "FAILED", lastError = r.reason, updatedAtUtc = now())); file.delete(); Result.failure() }
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val KEY_KIND = "kind"; const val KEY_ERROR = "error"
        const val UNIQUE_NOW = "drive-backup-now"; const val UNIQUE_AUTO = "drive-backup-auto"
        fun queueDir(ctx: Context) = File(ctx.filesDir, "drive-queue").apply { mkdirs() }

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun backupNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DriveBackupWorker>().setConstraints(constraints)
                    .setInputData(workDataOf(KEY_KIND to "MANUAL"))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }

        /** يومي تقريبي؛ العامل يتخطى إن لم يتغير السجل منذ آخر نسخة متحقّقة. */
        fun scheduleAuto(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) { wm.cancelUniqueWork(UNIQUE_AUTO); return }
            wm.enqueueUniquePeriodicWork(UNIQUE_AUTO, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DriveBackupWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                    .setConstraints(constraints).setInputData(workDataOf(KEY_KIND to "AUTO"))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
        }

        /** يوقف كل المهام قبل الاستعادة أو فصل الحساب. */
        suspend fun cancelAll(ctx: Context, jobs: app.rasidi.data.BackupJobDao) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(UNIQUE_NOW); wm.cancelUniqueWork(UNIQUE_AUTO)
            jobs.cancelPending(System.currentTimeMillis())
            queueDir(ctx).listFiles()?.forEach { it.delete() }
        }
    }
}
