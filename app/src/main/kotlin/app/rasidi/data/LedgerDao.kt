package app.rasidi.data

import androidx.room.*
import app.rasidi.core.data.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    // ---- قراءة كاملة (لقطة) ----
    @Query("SELECT * FROM accounts ORDER BY sort, name") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM categories ORDER BY kind, sort, name") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM txns") suspend fun txns(): List<TxnEntity>
    @Query("SELECT * FROM entries") suspend fun entries(): List<EntryEntity>
    @Query("SELECT * FROM debts") suspend fun debts(): List<DebtEntity>
    @Query("SELECT * FROM installments") suspend fun installments(): List<InstallmentEntity>
    @Query("SELECT * FROM debt_payments") suspend fun debtPayments(): List<DebtPaymentEntity>
    @Query("SELECT * FROM gold_holdings") suspend fun goldHoldings(): List<GoldHoldingEntity>
    @Query("SELECT * FROM gold_moves") suspend fun goldMoves(): List<GoldMoveEntity>
    @Query("SELECT * FROM rates ORDER BY date DESC, observedAtUtc DESC") suspend fun rates(): List<RateEntity>
    @Query("SELECT * FROM inventories ORDER BY date DESC, revision DESC") suspend fun inventories(): List<InventoryEntity>
    @Query("SELECT * FROM inventory_lines") suspend fun inventoryLines(): List<InventoryLineEntity>
    @Query("SELECT * FROM zakat_estimates ORDER BY createdAtUtc DESC") suspend fun zakatEstimates(): List<ZakatEstimateEntity>
    @Query("SELECT * FROM zakat_payments") suspend fun zakatPayments(): List<ZakatPaymentEntity>
    @Query("SELECT * FROM settings") suspend fun settings(): List<SettingEntity>
    @Query("SELECT * FROM audit_log ORDER BY atUtc DESC") suspend fun audit(): List<AuditEntity>

    /** يُصدر إشارة عند أي تغيير في الجداول المالية. */
    @Query("SELECT (SELECT COUNT(*) FROM txns) + (SELECT COUNT(*) FROM entries) + (SELECT COUNT(*) FROM accounts) + (SELECT COUNT(*) FROM debts) + (SELECT COUNT(*) FROM debt_payments) + (SELECT COUNT(*) FROM rates) + (SELECT COUNT(*) FROM gold_moves) + (SELECT COUNT(*) FROM inventories) + (SELECT COUNT(*) FROM zakat_payments) + (SELECT COUNT(*) FROM audit_log) + (SELECT COUNT(*) FROM settings) + (SELECT COUNT(*) FROM categories) + (SELECT COUNT(*) FROM gold_holdings) + (SELECT COUNT(*) FROM zakat_estimates)")
    fun changes(): Flow<Long>

    @Query("SELECT * FROM txns WHERE id = :id") suspend fun txn(id: String): TxnEntity?
    @Query("SELECT * FROM txns WHERE requestId = :rid") suspend fun txnByRequest(rid: String): TxnEntity?
    @Query("SELECT * FROM entries WHERE txnId = :txnId") suspend fun entriesOf(txnId: String): List<EntryEntity>
    @Query("SELECT * FROM debt_payments WHERE txnId = :txnId") suspend fun paymentsOfTxn(txnId: String): List<DebtPaymentEntity>
    @Query("SELECT * FROM gold_moves WHERE txnId = :txnId") suspend fun goldMovesOfTxn(txnId: String): List<GoldMoveEntity>
    @Query("SELECT * FROM zakat_payments WHERE txnId = :txnId") suspend fun zakatPaymentsOfTxn(txnId: String): List<ZakatPaymentEntity>
    @Query("SELECT value FROM settings WHERE `key` = :key") suspend fun setting(key: String): String?
    @Query("SELECT COUNT(*) FROM txns") suspend fun txnCount(): Int

    /** صفحات السجل: الأحدث أولاً. */
    @Query("SELECT * FROM txns ORDER BY date DESC, createdAtUtc DESC LIMIT :limit OFFSET :offset") suspend fun txnPage(limit: Int, offset: Int): List<TxnEntity>

    // ---- كتابة ----
    @Insert suspend fun insertAccount(a: AccountEntity)
    @Update suspend fun updateAccount(a: AccountEntity)
    @Insert suspend fun insertCategory(c: CategoryEntity)
    @Update suspend fun updateCategory(c: CategoryEntity)
    @Insert suspend fun insertTxn(t: TxnEntity)
    @Update suspend fun updateTxn(t: TxnEntity)
    @Insert suspend fun insertEntries(e: List<EntryEntity>)
    @Update suspend fun updateEntries(e: List<EntryEntity>)
    @Insert suspend fun insertDebt(d: DebtEntity)
    @Update suspend fun updateDebt(d: DebtEntity)
    @Insert suspend fun insertInstallments(i: List<InstallmentEntity>)
    @Insert suspend fun insertDebtPayment(p: DebtPaymentEntity)
    @Insert suspend fun insertGoldHolding(g: GoldHoldingEntity)
    @Update suspend fun updateGoldHolding(g: GoldHoldingEntity)
    @Insert suspend fun insertGoldMove(m: GoldMoveEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRate(r: RateEntity)
    @Delete suspend fun deleteRate(r: RateEntity)
    @Insert suspend fun insertInventory(i: InventoryEntity)
    @Insert suspend fun insertInventoryLines(l: List<InventoryLineEntity>)
    @Insert suspend fun insertZakatEstimate(z: ZakatEstimateEntity)
    @Insert suspend fun insertZakatPayment(z: ZakatPaymentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSetting(s: SettingEntity)
    @Insert suspend fun insertAudit(a: AuditEntity)
    @Query("DELETE FROM txns WHERE id = :id") suspend fun deleteTxn(id: String)

    // ---- استبدال كامل للاستعادة ----
    @Insert suspend fun insertAccounts(l: List<AccountEntity>)
    @Insert suspend fun insertCategories(l: List<CategoryEntity>)
    @Insert suspend fun insertTxns(l: List<TxnEntity>)
    @Insert suspend fun insertDebts(l: List<DebtEntity>)
    @Insert suspend fun insertDebtPayments(l: List<DebtPaymentEntity>)
    @Insert suspend fun insertGoldHoldings(l: List<GoldHoldingEntity>)
    @Insert suspend fun insertGoldMoves(l: List<GoldMoveEntity>)
    @Insert suspend fun insertRates(l: List<RateEntity>)
    @Insert suspend fun insertInventories(l: List<InventoryEntity>)
    @Insert suspend fun insertZakatEstimates(l: List<ZakatEstimateEntity>)
    @Insert suspend fun insertZakatPayments(l: List<ZakatPaymentEntity>)
    @Insert suspend fun insertSettings(l: List<SettingEntity>)
    @Insert suspend fun insertAudits(l: List<AuditEntity>)

    @Query("DELETE FROM zakat_payments") suspend fun clearZakatPayments()
    @Query("DELETE FROM zakat_estimates") suspend fun clearZakatEstimates()
    @Query("DELETE FROM inventory_lines") suspend fun clearInventoryLines()
    @Query("DELETE FROM inventories") suspend fun clearInventories()
    @Query("DELETE FROM debt_payments") suspend fun clearDebtPayments()
    @Query("DELETE FROM installments") suspend fun clearInstallments()
    @Query("DELETE FROM gold_moves") suspend fun clearGoldMoves()
    @Query("DELETE FROM entries") suspend fun clearEntries()
    @Query("DELETE FROM txns") suspend fun clearTxns()
    @Query("DELETE FROM debts") suspend fun clearDebts()
    @Query("DELETE FROM gold_holdings") suspend fun clearGoldHoldings()
    @Query("DELETE FROM rates") suspend fun clearRates()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM settings") suspend fun clearSettings()
    @Query("DELETE FROM audit_log") suspend fun clearAudit()
}

@Dao
interface BackupJobDao {
    @Insert suspend fun insert(j: BackupJobEntity)
    @Update suspend fun update(j: BackupJobEntity)
    @Query("SELECT * FROM backup_jobs WHERE jobId = :id") suspend fun get(id: String): BackupJobEntity?
    @Query("SELECT * FROM backup_jobs WHERE status IN ('QUEUED','RUNNING') ORDER BY createdAtUtc") suspend fun pending(): List<BackupJobEntity>
    @Query("SELECT * FROM backup_jobs ORDER BY createdAtUtc DESC LIMIT 20") fun recent(): Flow<List<BackupJobEntity>>
    @Query("SELECT * FROM backup_jobs WHERE status = 'VERIFIED' ORDER BY updatedAtUtc DESC LIMIT 1") fun lastVerified(): Flow<BackupJobEntity?>
    @Query("UPDATE backup_jobs SET status = 'CANCELLED', updatedAtUtc = :now WHERE status IN ('QUEUED','RUNNING')") suspend fun cancelPending(now: Long)
    @Query("DELETE FROM backup_jobs") suspend fun clear()
}
