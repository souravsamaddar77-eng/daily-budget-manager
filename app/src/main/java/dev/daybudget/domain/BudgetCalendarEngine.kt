package dev.daybudget.domain

import java.time.Instant
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Reads recorded history without inventing snapshots for closed-app days. */
object BudgetCalendarEngine {
    fun view(ledger: Ledger, month: YearMonth, at: Instant): BudgetCalendar? {
        val engine = BudgetEngine()
        val state = engine.ensureDay(ledger, at)
        val current = engine.view(state, at) ?: return null
        val period = state.periods.find { it.id == month.toString() }
        val monthEntries = state.entries.filter { it.periodId == month.toString() }
        val included = monthEntries.filter { it.included }
        val adjustments = state.adjustments.filter { it.periodId == month.toString() }
        val remaining = period?.let {
            Money.combine(it.openingAmount to 1, Money.sum(adjustments.map { row -> row.amount }) to 1,
                total(included, EntryType.EXPENSE) to -1, total(included, EntryType.REFUND) to 1)
        }
        val currentMonth = YearMonth.from(current.today)
        val forecastStart = if (month == currentMonth) current.today.plusDays(1) else month.atDay(1)
        val forecastDays = if (month >= currentMonth && forecastStart <= month.atEndOfMonth())
            ChronoUnit.DAYS.between(forecastStart, month.atEndOfMonth()).toInt() + 1 else 0
        val forecastPool = when {
            month == currentMonth && current.period.budgetConfigured -> current.monthlyRemaining.coerceAtLeast(0)
            month > currentMonth -> current.settings.usualMonthlyBudget
            else -> null
        }
        val days = (1..month.lengthOfMonth()).map { number ->
            val date = month.atDay(number)
            val records = monthEntries.filter { it.date == date }
            val budgetRecords = records.filter { it.included }
            val snapshot = state.snapshots.find { it.periodId == month.toString() && it.date == date }
            val expenses = total(records, EntryType.EXPENSE)
            val refunds = total(records, EntryType.REFUND)
            val estimated = if (date > current.today && snapshot == null && records.isEmpty() && forecastDays > 0 && forecastPool != null) {
                val base = forecastPool / forecastDays
                val remainder = forecastPool % forecastDays
                val index = ChronoUnit.DAYS.between(forecastStart, date)
                base + if (index >= forecastDays - remainder) 1 else 0
            } else null
            CalendarDay(date, snapshot?.startingAllowance, estimated, expenses, refunds,
                Money.combine(expenses to 1, refunds to -1), snapshot?.let {
                    Money.combine(it.startingAllowance to 1, total(budgetRecords, EntryType.EXPENSE) to -1,
                        total(budgetRecords, EntryType.REFUND) to 1)
                }, records.size, records.count { !it.included }, date == current.today, date > current.today,
                date <= current.settings.activationDate)
        }
        return BudgetCalendar(month, current.today, days, period, state.periods.sortedByDescending { it.start },
            remaining, monthEntries.sortedByDescending { it.date }, adjustments.sortedByDescending { it.createdAt })
    }

    private fun total(entries: List<Entry>, type: EntryType): Long = Money.sum(entries.filter { it.type == type }.map { it.amount })
}
