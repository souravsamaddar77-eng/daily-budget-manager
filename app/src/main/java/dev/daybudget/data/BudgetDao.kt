package dev.daybudget.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BudgetDao {
    @Query("SELECT * FROM import_receipts WHERE id = :id")
    suspend fun importReceipt(id: String): ImportReceiptRow?

    @Insert suspend fun insertImportReceipt(row: ImportReceiptRow)
    @Query("DELETE FROM import_receipts") suspend fun clearImportReceipts()

    @Query("SELECT * FROM budget_settings WHERE singleton = 1")
    suspend fun settings(): SettingsRow?

    @Query("SELECT * FROM budget_periods ORDER BY start")
    suspend fun periods(): List<PeriodRow>

    @Query("SELECT * FROM daily_snapshots ORDER BY date")
    suspend fun snapshots(): List<SnapshotRow>

    @Query("SELECT * FROM ledger_entries ORDER BY date, createdAt, id")
    suspend fun entries(): List<EntryRow>

    @Query("SELECT * FROM budget_adjustments ORDER BY createdAt, id")
    suspend fun adjustments(): List<AdjustmentRow>

    @Query("SELECT * FROM entry_undo WHERE singleton = 1")
    suspend fun undo(): UndoRow?

    @Upsert suspend fun upsertSettings(row: SettingsRow)
    @Upsert suspend fun upsertPeriod(row: PeriodRow)
    @Upsert suspend fun upsertEntry(row: EntryRow)
    @Upsert suspend fun upsertUndo(row: UndoRow)
    @Insert suspend fun insertSnapshot(row: SnapshotRow)
    @Insert suspend fun insertAdjustment(row: AdjustmentRow)

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("DELETE FROM entry_undo") suspend fun clearUndo()
    @Query("DELETE FROM ledger_entries WHERE type = 'REFUND'") suspend fun clearRefunds()
    @Query("DELETE FROM ledger_entries") suspend fun clearEntries()
    @Query("DELETE FROM budget_adjustments") suspend fun clearAdjustments()
    @Query("DELETE FROM daily_snapshots") suspend fun clearSnapshots()
    @Query("DELETE FROM budget_periods") suspend fun clearPeriods()
    @Query("DELETE FROM budget_settings") suspend fun clearSettings()
}
