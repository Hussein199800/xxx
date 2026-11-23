package app.rasidi.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * كيانات الدفتر. مصدر واحد لـ Room (في app) وللنسخة الاحتياطية (JSON).
 * - المعرّفات UUID نصية ثابتة.
 * - التواريخ التجارية نص ISO «yyyy-MM-dd» بتوقيت الدفتر المحلي (قابل للفرز).
 * - أوقات التدقيق UTC بالميلي ثانية.
 * - المبالغ وحدات صغرى صحيحة؛ الأسعار والأوزان نص عشري دقيق.
 * أي تغيير هنا يتطلب رفع إصدار قاعدة البيانات وترحيلاً مُختبراً، ورفع BackupFormat.DATA_VERSION.
 */

@Serializable
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val currency: String,
    val allowNegative: Boolean = false,
    val archived: Boolean = false,
    val sort: Int = 0,
    val createdAtUtc: Long = 0,
)

@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** INCOME | EXPENSE */
    val kind: String,
    val icon: String = "",
    val archived: Boolean = false,
    val system: Boolean = false,
    val sort: Int = 0,
)

@Serializable
@Entity(
    tableName = "txns",
    indices = [Index("date"), Index(value = ["requestId"], unique = true), Index("debtId")],
)
data class TxnEntity(
    @PrimaryKey val id: String,
    val type: String,
    val date: String,
    val note: String = "",
    val party: String = "",
    /** معرّف الطلب لمنع حفظ الضغط المكرر وإعادة المحاولة. */
    val requestId: String,
    val debtId: String? = null,
    /** إذا كانت الحركة عكساً لحركة أخرى. */
    val reversesId: String? = null,
    /** إذا عُكست هذه الحركة. */
    val reversedById: String? = null,
    /** السعر الفعلي للتحويل بين عملتين (وحدة الوجهة لكل وحدة مصدر)، نصّي دقيق. */
    val fxRate: String? = null,
    val createdAtUtc: Long = 0,
    val updatedAtUtc: Long = 0,
)

@Serializable
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txnId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("txnId"), Index(value = ["accountId", "date"]), Index("date"), Index("categoryId")],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    val txnId: String,
    val accountId: String,
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    val date: String,
    val categoryId: String? = null,
)

@Serializable
@Entity(tableName = "debts", indices = [Index("party")])
data class DebtEntity(
    @PrimaryKey val id: String,
    val party: String,
    /** OWED_TO_ME | I_OWE */
    val direction: String,
    val principalMinor: Long,
    val currency: String,
    val date: String,
    val dueDate: String? = null,
    val note: String = "",
    /** حركة القرض النقدي المرتبطة، أو null لدين قديم بلا حركة نقدية. */
    val txnId: String? = null,
    val archived: Boolean = false,
    val createdAtUtc: Long = 0,
)

@Serializable
@Entity(
    tableName = "installments",
    foreignKeys = [ForeignKey(entity = DebtEntity::class, parentColumns = ["id"], childColumns = ["debtId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("debtId")],
)
data class InstallmentEntity(
    @PrimaryKey val id: String,
    val debtId: String,
    val dueDate: String,
    val amountMinor: Long,
)

@Serializable
@Entity(
    tableName = "debt_payments",
    foreignKeys = [
        ForeignKey(entity = DebtEntity::class, parentColumns = ["id"], childColumns = ["debtId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("debtId"), Index("txnId")],
)
data class DebtPaymentEntity(
    @PrimaryKey val id: String,
    val debtId: String,
    val txnId: String?,
    val date: String,
    val debtAmountMinor: Long,
    val walletAmountMinor: Long? = null,
    val walletCurrency: String? = null,
    val accountId: String? = null,
)

@Serializable
@Entity(tableName = "gold_holdings")
data class GoldHoldingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val karat: Int,
    /** SAVINGS | JEWELRY | OTHER */
    val kind: String,
    val note: String = "",
    val archived: Boolean = false,
)

@Serializable
@Entity(
    tableName = "gold_moves",
    foreignKeys = [
        ForeignKey(entity = GoldHoldingEntity::class, parentColumns = ["id"], childColumns = ["holdingId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("holdingId"), Index("txnId")],
)
data class GoldMoveEntity(
    @PrimaryKey val id: String,
    val holdingId: String,
    val type: String,
    /** صافي الوزن بالغرام، موقّع. */
    val grams: String,
    val date: String,
    val txnId: String? = null,
)

@Serializable
@Entity(tableName = "rates", indices = [Index(value = ["unit", "date"])])
data class RateEntity(
    @PrimaryKey val id: String,
    val unit: String,
    val entered: String,
    /** UNITS_PER_USD | USD_PER_UNIT */
    val direction: String,
    val date: String,
    val source: String,
    val kind: String = "MANUAL",
    val observedAtUtc: Long = 0,
)

@Serializable
@Entity(tableName = "inventories", indices = [Index("groupId")])
data class InventoryEntity(
    @PrimaryKey val id: String,
    /** كل مراجعات الجرد نفسه تشترك بـ groupId. */
    val groupId: String,
    val revision: Int,
    val date: String,
    /** DRAFT | FINAL */
    val status: String,
    val yearClose: Boolean = false,
    val pricedNetUsd: String,
    val missingCount: Int,
    val note: String = "",
    val createdAtUtc: Long = 0,
)

@Serializable
@Entity(
    tableName = "inventory_lines",
    foreignKeys = [ForeignKey(entity = InventoryEntity::class, parentColumns = ["id"], childColumns = ["inventoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("inventoryId")],
)
data class InventoryLineEntity(
    @PrimaryKey val id: String,
    val inventoryId: String,
    val itemKey: String,
    val kind: String,
    val label: String,
    val unit: String,
    val quantity: String,
    val original: String,
    val goldKind: String? = null,
    val refId: String? = null,
    val state: String,
    val usd: String? = null,
    val rateLabel: String? = null,
    val rateSource: String? = null,
    val rateDate: String? = null,
    val note: String? = null,
)

@Serializable
@Entity(
    tableName = "zakat_estimates",
    foreignKeys = [ForeignKey(entity = InventoryEntity::class, parentColumns = ["id"], childColumns = ["inventoryId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("inventoryId")],
)
data class ZakatEstimateEntity(
    @PrimaryKey val id: String,
    val inventoryId: String,
    val hawlHijri: String,
    val hawlMethod: String,
    val hawlComplete: Boolean,
    val nisabLabel: String,
    val nisabUsd: String?,
    val percent: String,
    val assetsUsd: String,
    val receivablesUsd: String,
    val deductionsUsd: String,
    val baseUsd: String,
    val zakatUsd: String,
    val status: String,
    /** القرارات لكل بند: JSON {itemKey: {decision, partialUsd, reason}} */
    val decisionsJson: String,
    val createdAtUtc: Long = 0,
)

@Serializable
@Entity(
    tableName = "zakat_payments",
    foreignKeys = [
        ForeignKey(entity = ZakatEstimateEntity::class, parentColumns = ["id"], childColumns = ["estimateId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("estimateId"), Index(value = ["txnId"], unique = true)],
)
data class ZakatPaymentEntity(
    @PrimaryKey val id: String,
    val estimateId: String,
    val txnId: String,
    val date: String,
    val amountMinor: Long,
    val currency: String,
    val rateEntered: String,
    val rateDirection: String,
    val rateDate: String,
    val usd: String,
    val accountId: String,
    val beneficiary: String = "",
)

@Serializable
@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

@Serializable
@Entity(tableName = "audit_log", indices = [Index("entityId")])
data class AuditEntity(
    @PrimaryKey val id: String,
    val atUtc: Long,
    val entityType: String,
    val entityId: String,
    val action: String,
    val detail: String = "",
)
