package app.rasidi.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import app.rasidi.core.data.*

/** سجل مهام النسخ إلى Drive: محلي فقط، لا يدخل في اللقطة ويُعاد إنشاؤه بعد الاستعادة. */
@Entity(tableName = "backup_jobs", indices = [Index(value = ["jobId"], unique = true)])
data class BackupJobEntity(
    @PrimaryKey val jobId: String,
    val kind: String,
    val targetPermissionId: String,
    val fileId: String?,
    val backupId: String,
    val ledgerVersion: Long,
    val localPath: String,
    /** QUEUED | RUNNING | VERIFIED | FAILED | CANCELLED | ACCOUNT_MISMATCH */
    val status: String,
    val attempts: Int = 0,
    val lastError: String = "",
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
)

@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, TxnEntity::class, EntryEntity::class,
        DebtEntity::class, InstallmentEntity::class, DebtPaymentEntity::class,
        GoldHoldingEntity::class, GoldMoveEntity::class, RateEntity::class,
        InventoryEntity::class, InventoryLineEntity::class, ZakatEstimateEntity::class, ZakatPaymentEntity::class,
        SettingEntity::class, AuditEntity::class, BackupJobEntity::class,
        MemberEntity::class, BudgetEntity::class, RecurringEntity::class,
    ],
    version = RasidiDatabase.VERSION,
    exportSchema = true,
)
abstract class RasidiDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    abstract fun jobs(): BackupJobDao

    companion object {
        const val VERSION = 2
        const val LEDGER_DB = "rasidi.db"
        const val DEMO_DB = "rasidi-demo.db"

        /** قائمة الترحيلات. كل ترحيل جديد يُضاف هنا مع اختبار في MigrationTest. */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(MIGRATION_1_2)

        fun open(ctx: Context, name: String): RasidiDatabase =
            Room.databaseBuilder(ctx, RasidiDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                // لا fallbackToDestructiveMigration: فقد البيانات ممنوع؛ الفشل أفضل من المسح الصامت
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA foreign_keys=ON") }
                })
                .build()

        fun inMemory(ctx: Context): RasidiDatabase =
            Room.inMemoryDatabaseBuilder(ctx, RasidiDatabase::class.java).allowMainThreadQueries().build()
    }
}

/** 1 → 2: أفراد البيت، الميزانيات الشهرية، المتكررات، و memberId للحركات. لا يمسّ أي بيانات موجودة. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `txns` ADD COLUMN `memberId` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_txns_memberId` ON `txns` (`memberId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `members` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `archived` INTEGER NOT NULL, `sort` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `currency` TEXT NOT NULL, `limitMinor` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_categoryId_currency` ON `budgets` (`categoryId`, `currency`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `recurring` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `accountId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `dayOfMonth` INTEGER NOT NULL, `memberId` TEXT, `active` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_accountId` ON `recurring` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_categoryId` ON `recurring` (`categoryId`)")
    }
}
