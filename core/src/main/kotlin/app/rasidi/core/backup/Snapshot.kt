package app.rasidi.core.backup

import app.rasidi.core.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** لقطة متّسقة كاملة للدفتر. لا تحتوي رموز وصول أو أسرار جهاز. */
@Serializable
data class Snapshot(
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val txns: List<TxnEntity> = emptyList(),
    val entries: List<EntryEntity> = emptyList(),
    val debts: List<DebtEntity> = emptyList(),
    val installments: List<InstallmentEntity> = emptyList(),
    val debtPayments: List<DebtPaymentEntity> = emptyList(),
    val goldHoldings: List<GoldHoldingEntity> = emptyList(),
    val goldMoves: List<GoldMoveEntity> = emptyList(),
    val rates: List<RateEntity> = emptyList(),
    val inventories: List<InventoryEntity> = emptyList(),
    val inventoryLines: List<InventoryLineEntity> = emptyList(),
    val zakatEstimates: List<ZakatEstimateEntity> = emptyList(),
    val zakatPayments: List<ZakatPaymentEntity> = emptyList(),
    val settings: List<SettingEntity> = emptyList(),
    val audit: List<AuditEntity> = emptyList(),
) {
    fun counts(): Map<String, Int> = linkedMapOf(
        "accounts" to accounts.size, "categories" to categories.size, "txns" to txns.size, "entries" to entries.size,
        "debts" to debts.size, "installments" to installments.size, "debtPayments" to debtPayments.size,
        "goldHoldings" to goldHoldings.size, "goldMoves" to goldMoves.size, "rates" to rates.size,
        "inventories" to inventories.size, "inventoryLines" to inventoryLines.size,
        "zakatEstimates" to zakatEstimates.size, "zakatPayments" to zakatPayments.size,
        "settings" to settings.size, "audit" to audit.size,
    )

    /** نسخة مرتّبة حتمياً لحساب البصمة ومقارنة المحتوى. */
    fun canonical(): Snapshot = Snapshot(
        accounts.sortedBy { it.id }, categories.sortedBy { it.id }, txns.sortedBy { it.id }, entries.sortedBy { it.id },
        debts.sortedBy { it.id }, installments.sortedBy { it.id }, debtPayments.sortedBy { it.id },
        goldHoldings.sortedBy { it.id }, goldMoves.sortedBy { it.id }, rates.sortedBy { it.id },
        inventories.sortedBy { it.id }, inventoryLines.sortedBy { it.id }, zakatEstimates.sortedBy { it.id },
        zakatPayments.sortedBy { it.id }, settings.sortedBy { it.key }, audit.sortedBy { it.id },
    )

    /** بصمة محتوى اللقطة (SHA-256 بالست عشري) — مستقلة عن ترتيب الصفوف. */
    fun fingerprint(): String {
        val bytes = BackupJson.encodeToString(serializer(), canonical()).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** أرصدة كل حساب بالوحدات الصغرى، لمقارنة التساوي بعد الاستعادة. */
    fun balances(): Map<String, Long> = entries.groupBy { it.accountId }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }

    companion object {
        /** مفاتيح الإعدادات المسموح نسخها (لا رموز وصول ولا ربط حساب). */
        val BACKUP_SETTING_KEYS = setOf(
            "ledger.timezone", "ui.theme", "ui.fontScale", "ui.hideAmounts", "default.account", "default.expenseCategory",
            "syp.effectiveDate", "syp.withdrawalEnd", "zakat.nisabPolicy", "zakat.percent", "export.hideNames",
        )
    }
}

val BackupJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = true }
