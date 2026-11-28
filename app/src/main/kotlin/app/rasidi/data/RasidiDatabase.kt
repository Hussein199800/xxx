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

/**
 * سجل الطلبات (منع التكرار): لكل نية حفظ معرّف ثابت، ونوع العملية وبصمة مدخلاتها ونتيجتها.
 * محلي للجهاز مثل [BackupJobEntity]: لا يدخل في اللقطة ولا النسخ (DATA_VERSION لا يتغيّر)، ويُمسح عند الاستعادة.
 */
@Entity(tableName = "requests")
data class RequestEntity(
    @PrimaryKey val requestId: String,
    val op: String,
    val payloadHash: String,
    val resultId: String?,
    val createdAtUtc: Long,
)

@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, TxnEntity::class, EntryEntity::class,
        DebtEntity::class, InstallmentEntity::class, DebtPaymentEntity::class,
        GoldHoldingEntity::class, GoldMoveEntity::class, RateEntity::class,
        InventoryEntity::class, InventoryLineEntity::class, ZakatEstimateEntity::class, ZakatPaymentEntity::class,
        SettingEntity::class, AuditEntity::class, BackupJobEntity::class,
        MemberEntity::class, BudgetEntity::class, RecurringEntity::class, PartyEntity::class,
        RequestEntity::class,
    ],
    version = RasidiDatabase.VERSION,
    exportSchema = true,
)
abstract class RasidiDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    abstract fun jobs(): BackupJobDao
    abstract fun requests(): RequestDao

    companion object {
        const val VERSION = 4
        const val LEDGER_DB = "rasidi.db"
        const val DEMO_DB = "rasidi-demo.db"

        /** قائمة الترحيلات. كل ترحيل جديد يُضاف هنا مع اختبار في MigrationTest. */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

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

/** 2 → 3: جدول الحسابات (الأشخاص)، ويُملأ من أسماء الديون الموجودة بمعرّفات ثابتة. لا يمسّ أي بيانات. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `parties` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `note` TEXT NOT NULL, `archived` INTEGER NOT NULL, `createdAtUtc` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_parties_name` ON `parties` (`name`)")
        // التطبيع بـ Kotlin (PartyIds.normalize) لا بـ TRIM في SQLite الذي يقصّ المسافة فقط:
        // النتيجة مطابقة حرفياً لترحيل النسخ الاحتياطية v2 → v3.
        val rows = mutableListOf<Pair<String, String>>()
        db.query("SELECT id, party FROM debts").use { c -> while (c.moveToNext()) rows += c.getString(0) to (c.getString(1) ?: "") }
        rows.forEach { (id, raw) ->
            val n = app.rasidi.core.backup.PartyIds.normalize(raw)
            if (n != raw) db.execSQL("UPDATE debts SET party = ? WHERE id = ?", arrayOf<Any>(n, id))
        }
        rows.map { app.rasidi.core.backup.PartyIds.normalize(it.second) }.filter { it.isNotEmpty() }.distinct().forEach { n ->
            db.execSQL("INSERT OR IGNORE INTO parties (id, name, phone, note, archived, createdAtUtc) VALUES (?, ?, '', '', 0, 0)", arrayOf<Any>(app.rasidi.core.backup.PartyIds.forName(n), n))
        }
    }
}

/** 3 → 4: جدول الطلبات (منع التكرار) — جدول جديد فارغ لا يمسّ أي بيانات. */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `requests` (`requestId` TEXT NOT NULL, `op` TEXT NOT NULL, `payloadHash` TEXT NOT NULL, `resultId` TEXT, `createdAtUtc` INTEGER NOT NULL, PRIMARY KEY(`requestId`))")
    }
}
