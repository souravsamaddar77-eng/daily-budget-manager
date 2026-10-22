package dev.daybudget.notifications

import dev.daybudget.domain.BudgetEngine
import dev.daybudget.domain.Command
import dev.daybudget.domain.EntryDraft
import dev.daybudget.domain.EntryType
import dev.daybudget.domain.Ledger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class ExpenseAlertPolicyTest {
    private val at = Instant.parse("2026-10-22T12:00:00Z")
    private val engine = BudgetEngine(Clock.fixed(at, ZoneOffset.UTC))
    private val ledger = engine.setup(Ledger(), 10_000, null, "UTC", at)
    private val before = requireNotNull(engine.view(ledger, at))

    @Test fun newTodayExpenseQualifiesIncludingOverspending() {
        val changed = engine.execute(ledger, Command.UpsertEntry(EntryDraft(id = "expense", amount = 20_000, date = before.today)), at)
        val after = requireNotNull(engine.view(changed, at))
        assertTrue(after.todayRemaining < 0)
        assertTrue(ExpenseAlertPolicy.isNewSpending(before, after, changed.entries.single()))
    }

    @Test fun refundsHistoricalSpendingAndNonAmountEditsDoNotAlert() {
        val changed = engine.execute(ledger, Command.UpsertEntry(EntryDraft(id = "expense", amount = 100, date = before.today)), at)
        val after = requireNotNull(engine.view(changed, at))
        val entry = changed.entries.single()
        assertFalse(ExpenseAlertPolicy.isNewSpending(before, after, entry.copy(type = EntryType.REFUND)))
        assertFalse(ExpenseAlertPolicy.isNewSpending(before, after, entry.copy(date = before.today.minusDays(1))))
        assertFalse(ExpenseAlertPolicy.isNewSpending(before, after, entry.copy(included = false)))
        assertFalse(ExpenseAlertPolicy.isNewSpending(after, after, entry.copy(merchant = "Renamed")))
        assertFalse(ExpenseAlertPolicy.isNewSpending(after, after, entry.copy(amount = 50)))
        assertTrue(ExpenseAlertPolicy.isNewSpending(after, after, entry.copy(amount = 200)))
    }

    @Test fun alertReceiptsRemainStableAcrossReplayButSeparateBudgetsAndEdits() {
        val changed = engine.execute(ledger, Command.UpsertEntry(EntryDraft(id = "expense", amount = 100, date = before.today)), at)
        val entry = changed.entries.single()
        assertEquals(ExpenseAlertPolicy.receipt("A", entry), ExpenseAlertPolicy.receipt("A", entry.copy()))
        assertNotEquals(ExpenseAlertPolicy.receipt("A", entry), ExpenseAlertPolicy.receipt("B", entry))
        assertNotEquals(ExpenseAlertPolicy.receipt("A", entry), ExpenseAlertPolicy.receipt("A", entry.copy(amount = 200)))
        assertEquals(ExpenseAlertPolicy.dailyReceipt("A", before.today), ExpenseAlertPolicy.dailyReceipt("A", before.today))
        assertNotEquals(ExpenseAlertPolicy.dailyReceipt("A", before.today), ExpenseAlertPolicy.dailyReceipt("B", before.today))
        assertNotEquals(ExpenseAlertPolicy.dailyReceipt("A", before.today), ExpenseAlertPolicy.dailyReceipt("A", before.today.plusDays(1)))
    }

    @Test fun quietHoursInclude22Until08InTheBudgetTimezone() {
        assertTrue(ExpenseAlertPolicy.isQuietHour(22))
        assertTrue(ExpenseAlertPolicy.isQuietHour(23))
        assertTrue(ExpenseAlertPolicy.isQuietHour(0))
        assertTrue(ExpenseAlertPolicy.isQuietHour(7))
        assertFalse(ExpenseAlertPolicy.isQuietHour(8))
        assertFalse(ExpenseAlertPolicy.isQuietHour(21))
    }
}
