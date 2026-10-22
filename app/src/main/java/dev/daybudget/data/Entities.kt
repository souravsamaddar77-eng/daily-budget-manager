package dev.daybudget.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Kept after entry deletion so a replay cannot recreate a user-deleted import. */
@Entity(tableName = "import_receipts")
data class ImportReceiptRow(@PrimaryKey val id: String, val importedAt: String)

@Entity(tableName = "budget_settings")
data class SettingsRow(
    @PrimaryKey val singleton: Int = 1,
    val zoneId: String,
    val activatedAt: String,
    val activationDate: String,
    val usualMonthlyBudget: Long?,
    val futureZoneId: String?,
    val futureZoneEffectiveMonth: String?,
)

@Entity(tableName = "budget_periods", indices = [Index(value = ["start"], unique = true)])
data class PeriodRow(
    @PrimaryKey val id: String,
    val start: String,
    val end: String,
    val zoneId: String,
    val openingAmount: Long,
    val budgetConfigured: Boolean,
    val activatedAt: String,
)

@Entity(
    tableName = "daily_snapshots",
    primaryKeys = ["periodId", "date"],
    foreignKeys = [ForeignKey(
        entity = PeriodRow::class,
        parentColumns = ["id"], childColumns = ["periodId"],
        onDelete = ForeignKey.RESTRICT,
    )],
)
data class SnapshotRow(
    val periodId: String,
    val date: String,
    val startingAllowance: Long,
    val createdAt: String,
    val calculationRevision: Int,
)

/** Also embedded in undo; no serialized ledger or raw imported content is stored. */
data class EntryColumns(
    val id: String,
    val type: String,
    val amount: Long,
    val date: String,
    val periodId: String,
    val occurredAt: String,
    val createdAt: String,
    val updatedAt: String,
    val included: Boolean,
    val merchant: String,
    val category: String,
    val note: String,
    val linkedExpenseId: String?,
    val source: String,
)

@Entity(
    tableName = "ledger_entries",
    primaryKeys = ["id"],
    indices = [Index("periodId"), Index("date"), Index("linkedExpenseId")],
    foreignKeys = [
        ForeignKey(entity = PeriodRow::class, parentColumns = ["id"], childColumns = ["periodId"],
            onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EntryRow::class, parentColumns = ["id"], childColumns = ["linkedExpenseId"],
            onDelete = ForeignKey.RESTRICT),
    ],
)
data class EntryRow(@Embedded val entry: EntryColumns)

@Entity(
    tableName = "budget_adjustments",
    indices = [Index("periodId")],
    foreignKeys = [ForeignKey(
        entity = PeriodRow::class,
        parentColumns = ["id"], childColumns = ["periodId"],
        onDelete = ForeignKey.RESTRICT,
    )],
)
data class AdjustmentRow(
    @PrimaryKey val id: String,
    val periodId: String,
    val amount: Long,
    val reason: String,
    val createdAt: String,
)

@Entity(tableName = "entry_undo")
data class UndoRow(
    @PrimaryKey val singleton: Int = 1,
    val entryId: String,
    @Embedded(prefix = "previous_") val previous: EntryColumns?,
    @Embedded(prefix = "next_") val next: EntryColumns?,
)
