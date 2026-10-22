package dev.daybudget.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class CalendarAndImportTest {
    private val engine = BudgetEngine()
    private val start = Instant.parse("2026-04-01T06:00:00Z")
    private fun setup(amount: Long = 400_000, usual: Long? = 500_000) =
        engine.setup(Ledger(), amount, usual, "Asia/Kolkata", start)
    private fun calendar(ledger: Ledger, month: String = "2026-04", at: Instant = start) =
        requireNotNull(BudgetCalendarEngine.view(ledger, YearMonth.parse(month), at))

    @Test fun missedHistoricalDaysHaveNoInventedAllowance() {
        val later = Instant.parse("2026-04-05T06:00:00Z")
        val ledger = engine.ensureDay(setup(), later)
        val days = calendar(ledger, at = later).days
        assertEquals(13_333L, days[0].frozenAllowance)
        assertNull(days[1].frozenAllowance)
        assertNull(days[1].remaining)
        assertNull(days[1].estimatedAllowance)
        assertNotNull(days[4].frozenAllowance)
        assertTrue(days[4].isToday)
        assertEquals(2, ledger.snapshots.size)
    }

    @Test fun futureForecastDistributesRemainderWithoutWritingSnapshots() {
        val at = Instant.parse("2026-04-28T06:00:00Z")
        val ledger = engine.setup(Ledger(), 101, 93, "UTC", at)
        val current = calendar(ledger, at = at)
        assertEquals(listOf(50L, 51L), current.days.takeLast(2).map { it.estimatedAllowance })
        assertTrue(current.days.takeLast(2).all { it.frozenAllowance == null && it.isFuture })
        val next = calendar(ledger, "2026-05", at)
        assertEquals(93L, next.days.sumOf { it.estimatedAllowance ?: 0 })
        assertTrue(next.days.all { it.estimatedAllowance == 3L && it.frozenAllowance == null })
        assertNull(next.period)
        assertEquals(1, ledger.periods.size)
        assertEquals(1, ledger.snapshots.size)
    }

    @Test fun unknownFutureBudgetAndHistoricalAllowanceRemainUnknown() {
        val view = calendar(setup(usual = null), "2026-05")
        assertTrue(view.days.all { it.estimatedAllowance == null && it.remaining == null })
        assertNull(view.monthlyRemaining)
    }

    @Test fun backwardClockKeepsKnownLaterSnapshotWithoutForecastOverlay() {
        val ledger = engine.ensureDay(setup(), Instant.parse("2026-04-02T06:00:00Z"))
        val laterDay = calendar(ledger, at = start).days[1]
        assertTrue(laterDay.isFuture)
        assertNotNull(laterDay.frozenAllowance)
        assertNull(laterDay.estimatedAllowance)
    }

    @Test fun excludedHistoryIsVisibleWithoutReducingBudget() {
        val later = Instant.parse("2026-04-05T06:00:00Z")
        val ledger = engine.execute(setup(), Command.UpsertEntry(
            EntryDraft(id = "history", amount = 900, date = LocalDate.parse("2026-04-01")), true), later)
        val day = calendar(ledger, at = later).days.first()
        assertEquals(900L, day.expenses)
        assertEquals(1, day.excludedCount)
        assertEquals(13_333L, day.remaining)
        assertEquals(400_000L, calendar(ledger, at = later).monthlyRemaining)
    }

    @Test fun calendarUsesBudgetDateAcrossUtcMonthBoundary() {
        val at = Instant.parse("2026-04-30T20:00:00Z")
        val ledger = engine.ensureDay(setup(), at)
        val view = calendar(ledger, "2026-05", at)
        assertEquals(LocalDate.parse("2026-05-01"), view.today)
        assertTrue(view.days.first().isToday)
        assertNotNull(view.days.first().frozenAllowance)
    }

    @Test fun importsUseExactEventTimeForActivationAndPreserveSourceOnEdit() {
        val at = start.plusSeconds(3600)
        val before = engine.importEntry(setup(), EntryDraft(id = "before", amount = 500,
            date = LocalDate.parse("2026-04-01")), "NOTIFICATION:sample", start.minusSeconds(1), at)
        assertFalse(before.entries.single().included)
        assertEquals(start.minusSeconds(1), before.entries.single().occurredAt)
        assertEquals(400_000L, engine.view(before, at)!!.monthlyRemaining)
        val imported = engine.importEntry(before, EntryDraft(id = "after", amount = 600,
            date = LocalDate.parse("2026-04-01")), "NOTIFICATION:sample", start.plusSeconds(1), at)
        assertTrue(imported.entries.last().included)
        val edited = engine.execute(imported, Command.UpsertEntry(imported.entries.last().toDraft().copy(amount = 700)), at)
        assertEquals("NOTIFICATION:sample", edited.entries.last().source)
        assertEquals(start.plusSeconds(1), edited.entries.last().occurredAt)
    }

    @Test fun importRejectsFutureAndMismatchedZoneDate() {
        val draft = EntryDraft(id = "event", amount = 100, date = LocalDate.parse("2026-04-01"))
        assertThrows(BudgetException::class.java) { engine.importEntry(setup(), draft, "NOTIFICATION", start.plusSeconds(1), start) }
        assertThrows(BudgetException::class.java) {
            engine.importEntry(setup(), draft.copy(date = LocalDate.parse("2026-03-31")), "NOTIFICATION", start, start)
        }
    }

    @Test fun reviewedImportRequiresExactBoundaryPreviewAndDuplicatePreservesEdits() {
        val later = Instant.parse("2026-04-03T06:00:00Z")
        val original = setup()
        val draft = EntryDraft(id = "review", amount = 100, date = LocalDate.parse("2026-03-31"))
        val preview = engine.preview(original, draft, later)
        assertTrue(preview.requiresConfirmation)
        assertThrows(BudgetException::class.java) {
            engine.importEntry(original, draft, "NOTIFICATION", start, later, reviewed = true)
        }
        val saved = engine.importEntry(original, draft, "NOTIFICATION", start, later,
            reviewed = true, acceptBoundaryChange = true, expectedPreview = preview)
        assertFalse(saved.entries.single().included)
        assertEquals("NOTIFICATION", saved.entries.single().source)
        val duplicate = engine.importEntry(saved, draft.copy(amount = 999), "NOTIFICATION", start, later)
        assertEquals(saved, duplicate)
    }

    @Test fun reviewingEarlierPaymentIntoNewSameDayBudgetCannotReduceItsOpeningBalance() {
        val ledger = setup()
        val draft = EntryDraft(id = "earlier-payment", amount = 500, date = LocalDate.parse("2026-04-01"))
        val at = start.plusSeconds(3600)
        val preview = engine.preview(ledger, draft, at)
        assertFalse(preview.requiresConfirmation)
        val error = assertThrows(BudgetException::class.java) {
            engine.importEntry(ledger, draft, "NOTIFICATION_REVIEWED", start.minusSeconds(1), at,
                reviewed = true, expectedPreview = preview)
        }
        assertTrue(error.message!!.contains("before this budget was set up"))
        assertTrue(ledger.entries.isEmpty())
        assertEquals(400_000L, engine.view(ledger, at)!!.monthlyRemaining)
    }
}
