package dev.daybudget.notifications

import dev.daybudget.domain.BudgetView
import dev.daybudget.domain.Entry
import dev.daybudget.domain.EntryType
import java.time.LocalDate

internal object ExpenseAlertPolicy {
    fun isNewSpending(before: BudgetView, after: BudgetView, entry: Entry): Boolean {
        if (entry.type != EntryType.EXPENSE || !entry.included || entry.date != after.today) return false
        val previous = before.entries.find { it.id == entry.id } ?: return true
        return previous.type != EntryType.EXPENSE || previous.date != entry.date || !previous.included || previous.amount < entry.amount
    }

    fun receipt(budgetId: String, entry: Entry): String = PaymentNotificationParser.hash(
        "$budgetId|${entry.id}|${entry.updatedAt}|${entry.amount}|${entry.date}",
    )

    fun isQuietHour(hour: Int): Boolean = hour >= 22 || hour < 8
    fun dailyReceipt(budgetId: String, date: LocalDate): String = "overspend-day-${PaymentNotificationParser.hash("$budgetId|$date") }"
}
