package app.rasidi

import android.app.Application
import androidx.work.Configuration
import app.rasidi.backup.BackupService
import app.rasidi.backup.DriveBackupWorker
import app.rasidi.data.LedgerRepository
import app.rasidi.data.RasidiDatabase
import app.rasidi.security.DeviceStore
import kotlinx.coroutines.sync.Mutex

/** حاوية الاعتمادات. قاعدة العرض التجريبي منفصلة تماماً عن الدفتر الفعلي. */
class AppContainer(val app: Application, val device: DeviceStore, val demo: Boolean) {
    val db: RasidiDatabase = RasidiDatabase.open(app, if (demo) RasidiDatabase.DEMO_DB else RasidiDatabase.LEDGER_DB)
    val repo = LedgerRepository(db, onWrite = { if (!demo) device.bumpLedgerVersion() })
    val backupService = BackupService(app, repo, { device.installId })
    /** ينسّق رفع النسخة مع الاستعادة وفصل الحساب. */
    val backupLock = Mutex()
    fun close() = db.close()
}

class RasidiApp : Application(), Configuration.Provider {
    lateinit var device: DeviceStore
    @Volatile lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        device = DeviceStore(this)
        container = AppContainer(this, device, device.demoMode)
        if (!device.demoMode) DriveBackupWorker.scheduleAuto(this, device.autoBackup && device.drivePermissionId != null)
    }

    /** يبدّل بين الدفتر الفعلي ودفتر العرض. */
    fun switchDemo(demo: Boolean) {
        device.demoMode = demo
        container.close()
        container = AppContainer(this, device, demo)
    }

    override val workManagerConfiguration: Configuration get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
}
