package dev.daybudget.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class Settings(
    val zoneId: String,
    val activatedAt: Instant,
    val activationDate: LocalDate,
    val usualMonthlyBudget: Long? = null,
    val futureZoneId: String? = null,
    val futureZoneEffectiveMonth: String? = null,
)

data class BudgetPeriod(
    val id: String,
    val start: LocalDate,
    val end: LocalDate,
    val zoneId: String,
    val openingAmount: Long,
    val budgetConfigured: Boolean,
    val activatedAt: Instant,
)

data class DailySnapshot(
    val periodId: String,
    val date: LocalDate,
    val startingAllowance: Long,
    val createdAt: Instant,
    val calculationRevision: Int = 1,
)

enum class EntryType { EXPENSE, REFUND }

data class EntryDraft(
    val id: String = UUID.randomUUID().toString(),
    val type: EntryType = EntryType.EXPENSE,
    val amount: Long,
    val date: LocalDate,
    val merchant: String = "",
    val category: String = "",
    val note: String = "",
    val linkedExpenseId: String? = null,
)

data class Entry(
    val id: String,
    val type: EntryType,
    val amount: Long,
    val date: LocalDate,
    val periodId: String,
    val occurredAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val included: Boolean,
    val merchant: String = "",
    val category: String = "",
    val note: String = "",
    val linkedExpenseId: String? = null,
    val source: String = "MANUAL",
) {
    fun toDraft() = EntryDraft(id, type, amount, date, merchant, category, note, linkedExpenseId)
}

data class BudgetAdjustment(
    val id: String,
    val periodId: String,
    val amount: Long,
    val reason: String,
    val createdAt: Instant,
)

/** One durable transaction undo. Snapshots are deliberately never restored or removed. */
data class UndoRecord(val entryId: String, val previous: Entry?, val next: Entry?)

data class Ledger(
    val settings: Settings? = null,
    val periods: List<BudgetPeriod> = emptyList(),
    val snapshots: List<DailySnapshot> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val adjustments: List<BudgetAdjustment> = emptyList(),
    val undo: UndoRecord? = null,
)

sealed interface Command {
    data class UpsertEntry(
        val draft: EntryDraft,
        val acceptBoundaryChange: Boolean = false,
        val expectedPreview: EntryPreview? = null,
    ) : Command
    data class DeleteEntry(val id: String) : Command
    data object Undo : Command
    data class AdjustBudget(val amount: Long, val reason: String, val expectedPeriodId: String? = null) : Command
    data class Configure(val usualMonthlyBudget: Long?, val futureZoneId: String) : Command
}

data class EntryPreview(
    val requiresConfirmation: Boolean,
    val message: String,
    val monthlyDeltas: Map<String, Long>,
    val affectsBudget: Boolean,
)

data class WeeklyDay(
    val date: LocalDate,
    val expenses: Long,
    val refunds: Long,
    val net: Long,
    val partialTracking: Boolean,
    val recordedCount: Int = 0,
)

data class BudgetView(
    val today: LocalDate,
    val settings: Settings,
    val period: BudgetPeriod,
    val snapshot: DailySnapshot,
    val daysRemaining: Int,
    val monthlyExpenses: Long,
    val monthlyRefunds: Long,
    val monthlyAdjustments: Long,
    val monthlyRemaining: Long,
    val todayExpenses: Long,
    val todayRefunds: Long,
    val todayRemaining: Long,
    val entries: List<Entry>,
    val weeklyDays: List<WeeklyDay>,
    val weeklyExpenses: Long,
    val weeklyRefunds: Long,
    val weeklyNet: Long,
    val canUndo: Boolean,
    val adjustments: List<BudgetAdjustment> = emptyList(),
) {
    val startingAllowance: Long get() = snapshot.startingAllowance
    val awaitingBudget: Boolean get() = !period.budgetConfigured
}

class BudgetException(message: String) : IllegalArgumentException(message)
