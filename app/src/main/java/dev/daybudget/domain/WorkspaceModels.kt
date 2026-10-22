package dev.daybudget.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class BudgetProfile(val id: String, val name: String)
data class MonthKey(val budgetId: String, val periodId: String)
data class SavingsEntry(val id: String, val amount: Long, val note: String, val createdAt: Instant)

data class WorkspaceState(
    val initialized: Boolean = false,
    val profiles: List<BudgetProfile> = emptyList(),
    val selectedBudgetId: String = "legacy",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val savingsBalance: Long = 0,
    val savingsEntries: List<SavingsEntry> = emptyList(),
    val monthNames: Map<MonthKey, String> = emptyMap(),
) {
    val activeProfile: BudgetProfile? get() = profiles.find { it.id == selectedBudgetId }
}

/** Recorded totals include excluded history; remaining uses only budget-included entries. */
data class CalendarDay(
    val date: LocalDate,
    val frozenAllowance: Long?,
    val estimatedAllowance: Long?,
    val expenses: Long,
    val refunds: Long,
    val net: Long,
    val remaining: Long?,
    val recordedCount: Int,
    val excludedCount: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
    val partialTracking: Boolean,
)

/** Forecasts assume no further income and spending each projected daily allowance. */
data class BudgetCalendar(
    val month: YearMonth,
    val today: LocalDate,
    val days: List<CalendarDay>,
    val period: BudgetPeriod?,
    val periods: List<BudgetPeriod>,
    val monthlyRemaining: Long?,
    val entries: List<Entry>,
    val adjustments: List<BudgetAdjustment>,
)

/** A replay may refer to an imported entry that the user has since deleted. */
data class ImportResult(
    val before: BudgetView,
    val after: BudgetView,
    val entry: Entry?,
    val inserted: Boolean,
)
