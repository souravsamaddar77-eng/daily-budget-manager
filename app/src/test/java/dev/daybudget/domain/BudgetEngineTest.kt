package dev.daybudget.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class BudgetEngineTest {
    private class TestClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }

    private val clock = TestClock(Instant.parse("2026-04-01T06:00:00Z"))
    private val engine = BudgetEngine(clock)
    private fun setup(amount: Long = 400_000, usual: Long? = null, zone: String = "Asia/Kolkata") =
        engine.setup(Ledger(), amount, usual, zone)
    private fun state(ledger: Ledger) = engine.view(ledger)!!
    private fun entry(ledger: Ledger, amount: Long, date: LocalDate = state(ledger).today, type: EntryType = EntryType.EXPENSE,
                      id: String = "entry-${ledger.entries.size}", link: String? = null, confirm: Boolean = false): Ledger =
        engine.execute(ledger, Command.UpsertEntry(EntryDraft(id, type, amount, date, linkedExpenseId = link), confirm))
    private fun fails(messagePart: String = "", block: () -> Unit) {
        try { block(); fail("Expected BudgetException") } catch (e: BudgetException) {
            assertTrue("Expected '$messagePart' in '${e.message}'", e.message.orEmpty().contains(messagePart, ignoreCase = true))
        }
    }

    @Test fun overspendingRebalancesTomorrowWithoutChangingToday() {
        var ledger = setup()
        assertEquals(13_333L, state(ledger).startingAllowance)
        ledger = entry(ledger, 20_000)
        assertEquals(-6_667L, state(ledger).todayRemaining)
        assertEquals(380_000L, state(ledger).monthlyRemaining)
        assertEquals(13_333L, state(ledger).startingAllowance)
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        assertEquals(13_103L, state(ledger).startingAllowance)
    }

    @Test fun underspendingStaysInMonthlyBudgetExactlyOnce() {
        var ledger = entry(setup(), 10_000)
        assertEquals(3_333L, state(ledger).todayRemaining)
        assertEquals(390_000L, state(ledger).monthlyRemaining)
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        assertEquals(13_448L, state(ledger).startingAllowance)
        assertEquals(390_000L, state(ledger).monthlyRemaining)
    }

    @Test fun roundingResidueIsConserved() {
        clock.now = Instant.parse("2026-01-29T06:00:00Z")
        var ledger = setup(100)
        val allocations = mutableListOf<Long>()
        repeat(3) { day ->
            clock.now = Instant.parse("2026-01-${29 + day}T06:00:00Z")
            ledger = engine.ensureDay(ledger)
            val amount = state(ledger).startingAllowance
            allocations += amount
            ledger = entry(ledger, amount)
        }
        assertEquals(listOf(33L, 33L, 34L), allocations)
        assertEquals(0L, state(ledger).monthlyRemaining)
    }

    @Test fun inclusiveCalendarMonthLengthsIncludeLeapDay() {
        listOf("2026-02-01" to 28, "2028-02-01" to 29, "2026-04-01" to 30, "2026-05-01" to 31).forEach { (date, days) ->
            clock.now = Instant.parse("${date}T06:00:00Z")
            assertEquals(days, state(setup()).daysRemaining)
        }
    }

    @Test fun midmonthSetupUsesRemainingBudgetAndExcludesEarlierDates() {
        clock.now = Instant.parse("2026-10-22T06:00:00Z")
        var ledger = setup()
        assertEquals(10, state(ledger).daysRemaining)
        assertEquals(40_000L, state(ledger).startingAllowance)
        val draft = EntryDraft(amount = 25_000, date = LocalDate.parse("2026-10-21"))
        val preview = engine.preview(ledger, draft)
        assertTrue(preview.requiresConfirmation)
        assertFalse(preview.affectsBudget)
        fails("tracking began") { engine.execute(ledger, Command.UpsertEntry(draft)) }
        ledger = engine.execute(ledger, Command.UpsertEntry(draft, true))
        assertFalse(ledger.entries.single().included)
        assertEquals(400_000L, state(ledger).monthlyRemaining)
        assertEquals(clock.now, ledger.settings!!.activatedAt)
    }

    @Test fun expensesOnSetupDateArePostSetupByDefault() {
        val ledger = entry(setup(), 100)
        assertTrue(ledger.entries.single().included)
        assertEquals(clock.now, ledger.entries.single().occurredAt)
    }

    @Test fun closedAppSkipsDatesWithoutConsumingMoney() {
        clock.now = Instant.parse("2026-10-22T06:00:00Z")
        var ledger = setup(70_000)
        clock.now = Instant.parse("2026-10-25T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        assertEquals(10_000L, state(ledger).startingAllowance)
        assertEquals(70_000L, state(ledger).monthlyRemaining)
        assertEquals(2, ledger.snapshots.size)
    }

    @Test fun firstWriteAfterMidnightFreezesBeforeExpense() {
        var ledger = setup(30_000)
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = entry(ledger, 5_000)
        assertEquals(30_000L / 29, state(ledger).startingAllowance)
        assertEquals(25_000L, state(ledger).monthlyRemaining)
        assertEquals(30_000L / 29 - 5_000, state(ledger).todayRemaining)
    }

    @Test fun repeatedReconciliationAndClockRollbackNeverDuplicateSnapshots() {
        var ledger = setup()
        val first = ledger.snapshots.single()
        ledger = engine.ensureDay(engine.ensureDay(ledger))
        assertEquals(1, ledger.snapshots.size)
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        clock.now = Instant.parse("2026-04-01T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        assertEquals(2, ledger.snapshots.size)
        assertEquals(first, state(ledger).snapshot)
        assertEquals(1, ledger.periods.size)
    }

    @Test fun phoneTimezoneDoesNotControlBudgetDate() {
        val at = Instant.parse("2026-10-21T20:00:00Z")
        val indiaEngine = BudgetEngine(Clock.fixed(at, ZoneId.of("America/Los_Angeles")))
        val ledger = indiaEngine.setup(Ledger(), 400_000, null, "Asia/Kolkata")
        assertEquals(LocalDate.parse("2026-10-22"), indiaEngine.view(ledger)!!.today)
    }

    @Test fun daylightSavingUsesCalendarDays() {
        clock.now = Instant.parse("2026-03-07T17:00:00Z")
        var ledger = setup(25_000, zone = "America/New_York")
        assertEquals(25, state(ledger).daysRemaining)
        clock.now = Instant.parse("2026-03-08T16:00:00Z") // 23 elapsed hours, next local date
        ledger = engine.ensureDay(ledger)
        assertEquals(24, state(ledger).daysRemaining)
        assertEquals(25_000L / 24, state(ledger).startingAllowance)
    }

    @Test fun monthRolloverUsesSeparateTemplateWithNoCarryover() {
        clock.now = Instant.parse("2026-04-30T06:00:00Z")
        var ledger = entry(setup(100, 31_000), 200)
        assertEquals(-100L, state(ledger).monthlyRemaining)
        clock.now = Instant.parse("2026-05-01T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        assertEquals(31_000L, state(ledger).monthlyRemaining)
        assertEquals(1_000L, state(ledger).startingAllowance)
        assertEquals(2, ledger.periods.size)
        assertEquals(1, ledger.entries.size)
    }

    @Test fun noTemplateLeavesProvisionalZeroButAllowsManualRecording() {
        clock.now = Instant.parse("2026-04-30T06:00:00Z")
        var ledger = setup(1_000)
        clock.now = Instant.parse("2026-05-01T06:00:00Z")
        ledger = entry(ledger, 100)
        assertTrue(state(ledger).awaitingBudget)
        assertEquals(0L, state(ledger).startingAllowance)
        assertEquals(-100L, state(ledger).monthlyRemaining)
        ledger = engine.execute(ledger, Command.AdjustBudget(3_100, "Set May budget"))
        assertFalse(state(ledger).awaitingBudget)
        assertEquals(3_000L, state(ledger).monthlyRemaining)
        assertEquals(0L, state(ledger).startingAllowance)
    }

    @Test fun adjustmentChangesMonthImmediatelyAndTomorrowAllowanceOnly() {
        var ledger = setup(30_000)
        val snapshot = state(ledger).snapshot
        ledger = engine.execute(ledger, Command.AdjustBudget(-10_000, "Reserve for rent"))
        assertEquals(20_000L, state(ledger).monthlyRemaining)
        assertEquals(snapshot, state(ledger).snapshot)
        assertEquals("Reserve for rent", state(ledger).adjustments.single().reason)
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        assertEquals(20_000L / 29, state(ledger).startingAllowance)
    }

    @Test fun zeroThenExpenseThenRefundPreservesBothSignedTotals() {
        var ledger = entry(setup(0), 10_000, id = "purchase")
        ledger = entry(ledger, 4_000, type = EntryType.REFUND, link = "purchase")
        assertEquals(10_000L, state(ledger).todayExpenses)
        assertEquals(4_000L, state(ledger).todayRefunds)
        assertEquals(-6_000L, state(ledger).todayRemaining)
        assertEquals(-6_000L, state(ledger).monthlyRemaining)
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = entry(ledger, 1_000, type = EntryType.REFUND, link = "purchase")
        assertEquals(1_000L, state(ledger).todayRemaining)
        assertEquals(-5_000L, state(ledger).monthlyRemaining)
    }

    @Test fun priorMonthRefundAffectsReceiptMonthOnly() {
        clock.now = Instant.parse("2026-04-30T06:00:00Z")
        var ledger = entry(setup(10_000, 31_000), 5_000, id = "purchase")
        val aprilSnapshot = state(ledger).snapshot
        clock.now = Instant.parse("2026-05-01T06:00:00Z")
        ledger = entry(ledger, 2_000, type = EntryType.REFUND, link = "purchase")
        assertEquals(33_000L, state(ledger).monthlyRemaining)
        assertEquals(3_000L, state(ledger).todayRemaining)
        assertEquals(aprilSnapshot, ledger.snapshots.first())
        assertEquals("2026-05", ledger.entries.last().periodId)
    }

    @Test fun excessiveLinkedRefundAndDeletingPurchaseAreRejected() {
        var ledger = entry(setup(), 1_000, id = "purchase")
        ledger = entry(ledger, 600, type = EntryType.REFUND, id = "refund", link = "purchase")
        fails("exceed") { entry(ledger, 401, type = EntryType.REFUND, link = "purchase") }
        fails("linked refunds") { engine.execute(ledger, Command.DeleteEntry("purchase")) }
        fails("exceed") { engine.execute(ledger, Command.UpsertEntry(ledger.entries.first().toDraft().copy(amount = 500))) }
        ledger = engine.execute(ledger, Command.UpsertEntry(ledger.entries.last().toDraft().copy(amount = 1_000)))
        assertEquals(0L, state(ledger).todayExpenses - state(ledger).todayRefunds)
    }

    @Test fun refundCannotPrecedeItsLinkedPurchase() {
        var ledger = setup()
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = entry(ledger, 1_000, id = "purchase")
        fails("before its purchase") { entry(ledger, 100, LocalDate.parse("2026-04-01"), EntryType.REFUND, link = "purchase") }
    }

    @Test fun backdatedEntryChangesMonthButNotFrozenDay() {
        var ledger = setup()
        clock.now = Instant.parse("2026-04-03T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        val snapshot = state(ledger).snapshot
        ledger = entry(ledger, 10_000, LocalDate.parse("2026-04-02"))
        assertEquals(390_000L, state(ledger).monthlyRemaining)
        assertEquals(snapshot, state(ledger).snapshot)
        assertEquals(0L, state(ledger).todayExpenses)
    }

    @Test fun movingExpenseIntoTodayUpdatesTodayButPreservesSnapshots() {
        var ledger = entry(setup(), 10_000)
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        val snapshots = ledger.snapshots
        ledger = engine.execute(ledger, Command.UpsertEntry(ledger.entries.single().toDraft().copy(date = state(ledger).today)))
        assertEquals(10_000L, state(ledger).todayExpenses)
        assertEquals(390_000L, state(ledger).monthlyRemaining)
        assertEquals(snapshots, ledger.snapshots)
    }

    @Test fun crossMonthMoveRequiresPreviewAndUpdatesBothPeriods() {
        clock.now = Instant.parse("2026-04-30T06:00:00Z")
        var ledger = entry(setup(10_000, 31_000), 2_000, id = "purchase")
        clock.now = Instant.parse("2026-05-01T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        val snapshots = ledger.snapshots
        val draft = ledger.entries.single().toDraft().copy(date = LocalDate.parse("2026-05-01"))
        val preview = engine.preview(ledger, draft)
        assertTrue(preview.requiresConfirmation)
        assertEquals(2_000L, preview.monthlyDeltas.getValue("2026-04"))
        assertEquals(-2_000L, preview.monthlyDeltas.getValue("2026-05"))
        fails("moves") { engine.execute(ledger, Command.UpsertEntry(draft)) }
        ledger = engine.execute(ledger, Command.UpsertEntry(draft, true))
        assertEquals(29_000L, state(ledger).monthlyRemaining)
        assertEquals(snapshots, ledger.snapshots)
        clock.now = Instant.parse("2026-04-30T06:00:00Z")
        assertEquals(10_000L, state(ledger).monthlyRemaining)
    }

    @Test fun movingAcrossActivationBoundaryNeedsAcknowledgmentBothWays() {
        clock.now = Instant.parse("2026-04-10T06:00:00Z")
        var ledger = entry(setup(10_000), 100)
        val original = ledger.entries.single().toDraft()
        val before = original.copy(date = LocalDate.parse("2026-04-09"))
        assertFalse(engine.preview(ledger, before).affectsBudget)
        ledger = engine.execute(ledger, Command.UpsertEntry(before, true))
        assertEquals(10_000L, state(ledger).monthlyRemaining)
        val preview = engine.preview(ledger, original)
        assertTrue(preview.requiresConfirmation)
        assertTrue(preview.affectsBudget)
        ledger = engine.execute(ledger, Command.UpsertEntry(original, true))
        assertEquals(9_900L, state(ledger).monthlyRemaining)
    }

    @Test fun preSetupMonthGetsExcludedRecordAndRealPeriod() {
        val ledger = entry(setup(), 100, LocalDate.parse("2026-03-15"), confirm = true)
        val record = ledger.entries.single()
        assertFalse(record.included)
        assertTrue(ledger.periods.any { it.id == record.periodId })
        assertEquals(400_000L, state(ledger).monthlyRemaining)
    }

    @Test fun deleteAndUndoAreDurableDataAndNeverRewindSnapshots() {
        var ledger = entry(setup(), 100)
        val original = ledger.entries.single()
        ledger = engine.execute(ledger, Command.DeleteEntry(original.id))
        assertEquals(400_000L, state(ledger).monthlyRemaining)
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        val snapshots = ledger.snapshots
        val reopenedEngine = BudgetEngine(clock)
        ledger = reopenedEngine.execute(ledger.copy(), Command.Undo)
        assertEquals(original, ledger.entries.single())
        assertEquals(snapshots, ledger.snapshots)
        assertEquals(399_900L, state(ledger).monthlyRemaining)
        assertFalse(state(ledger).canUndo)
    }

    @Test fun undoAddAndEditRestoreOnlyLatestTransaction() {
        var ledger = entry(setup(), 100)
        ledger = engine.execute(ledger, Command.Undo)
        assertTrue(ledger.entries.isEmpty())
        ledger = entry(ledger, 100)
        val original = ledger.entries.single()
        ledger = engine.execute(ledger, Command.UpsertEntry(original.toDraft().copy(amount = 200)))
        ledger = engine.execute(ledger, Command.Undo)
        assertEquals(original, ledger.entries.single())
    }

    @Test fun duplicateDurableIdNeverAddsSecondExpense() {
        var ledger = setup()
        val draft = EntryDraft(id = "stable", amount = 100, date = state(ledger).today)
        ledger = engine.execute(ledger, Command.UpsertEntry(draft))
        clock.now = clock.now.plusSeconds(60)
        ledger = engine.execute(ledger, Command.UpsertEntry(draft))
        assertEquals(1, ledger.entries.size)
        assertEquals(100L, state(ledger).monthlyExpenses)
        assertNull(ledger.undo!!.previous)
    }

    @Test fun zeroNegativeAndFutureEntriesAreRejected() {
        val ledger = setup()
        fails("greater than zero") { entry(ledger, 0) }
        fails("greater than zero") { entry(ledger, -1) }
        fails("earlier date") { entry(ledger, 1, state(ledger).today.plusDays(1)) }
        fails("zero or more") { setup(-1) }
        fails("valid timezone") { setup(zone = "Not/AZone") }
    }

    @Test fun weeklySummaryUsesCorrectedLedgerAndMarksPartialCoverage() {
        clock.now = Instant.parse("2026-04-03T06:00:00Z")
        var ledger = entry(setup(), 100, id = "purchase")
        clock.now = Instant.parse("2026-04-04T06:00:00Z")
        ledger = entry(ledger, 40, type = EntryType.REFUND, link = "purchase")
        var view = state(ledger)
        assertEquals(100L, view.weeklyExpenses)
        assertEquals(40L, view.weeklyRefunds)
        assertEquals(60L, view.weeklyNet)
        assertEquals(7, view.weeklyDays.size)
        assertTrue(view.weeklyDays.first().partialTracking)
        assertFalse(view.weeklyDays.last().partialTracking)
        assertEquals(0, view.weeklyDays.first().recordedCount)
        assertEquals(1, view.weeklyDays.last().recordedCount)
        ledger = engine.execute(ledger, Command.UpsertEntry(ledger.entries.first().toDraft().copy(amount = 150)))
        view = state(ledger)
        assertEquals(110L, view.weeklyNet)
    }

    @Test fun currentAndHistoricalMonthlyOverflowAreRejected() {
        var ledger = setup(0)
        ledger = entry(ledger, Long.MAX_VALUE, id = "large")
        fails("too large") { entry(ledger, 1) }
        clock.now = Instant.parse("2026-05-10T06:00:00Z")
        ledger = engine.ensureDay(ledger)
        fails("too large") { entry(ledger, 1, LocalDate.parse("2026-04-02")) }
        assertEquals(1, ledger.entries.size)
    }

    @Test fun overflowInBudgetAdjustmentDoesNotProduceWrappedBalance() {
        val ledger = setup(Long.MAX_VALUE)
        fails("too large") { engine.execute(ledger, Command.AdjustBudget(1, "Top up")) }
        assertEquals(Long.MAX_VALUE, state(ledger).monthlyRemaining)
        assertTrue(ledger.adjustments.isEmpty())
    }

    @Test fun exactFinalBalanceCanSurviveLargeOpposingTerms() {
        var ledger = setup(Long.MAX_VALUE)
        ledger = entry(ledger, Long.MAX_VALUE, id = "purchase")
        ledger = entry(ledger, Long.MAX_VALUE, type = EntryType.REFUND, link = "purchase")
        assertEquals(Long.MAX_VALUE, state(ledger).monthlyRemaining)
    }

    @Test fun timezoneChangeWaitsUntilTargetNextMonthMidnight() {
        clock.now = Instant.parse("2026-04-30T06:00:00Z")
        var ledger = setup(1_000, 31_000, "Asia/Kolkata")
        val original = state(ledger).snapshot
        ledger = engine.execute(ledger, Command.Configure(31_000, "America/Los_Angeles"))
        assertEquals("Asia/Kolkata", state(ledger).period.zoneId)
        clock.now = Instant.parse("2026-05-01T02:00:00Z") // May in India, April in target timezone
        ledger = engine.ensureDay(ledger)
        assertEquals(LocalDate.parse("2026-04-30"), state(ledger).today)
        assertEquals(original, state(ledger).snapshot)
        clock.now = Instant.parse("2026-05-01T07:00:00Z")
        ledger = engine.ensureDay(ledger)
        assertEquals("America/Los_Angeles", state(ledger).period.zoneId)
        assertEquals(LocalDate.parse("2026-05-01"), state(ledger).today)
        assertEquals(1_000L, state(ledger).startingAllowance)
        assertEquals("Asia/Kolkata", ledger.periods.first().zoneId)
    }

    @Test fun changingMonthlyTemplateDoesNotChangeCurrentOpening() {
        var ledger = setup(1_000)
        ledger = engine.execute(ledger, Command.Configure(50_000, "Asia/Kolkata"))
        assertEquals(1_000L, state(ledger).monthlyRemaining)
        clock.now = Instant.parse("2026-05-01T06:00:00Z")
        assertEquals(50_000L, state(engine.ensureDay(ledger)).monthlyRemaining)
    }

    @Test fun newActivationDayBackdateIsConsistentlyExcludedRegardlessOfClockHour() {
        val ledger = setup()
        val activationDate = state(ledger).today
        clock.now = Instant.parse("2026-04-02T02:00:00Z")
        val draft = EntryDraft(amount = 100, date = activationDate)
        assertFalse(engine.preview(ledger, draft).affectsBudget)
        clock.now = Instant.parse("2026-04-02T12:00:00Z")
        assertFalse(engine.preview(ledger, draft).affectsBudget)
        val saved = engine.execute(ledger, Command.UpsertEntry(draft, true))
        assertFalse(saved.entries.single().included)
        assertEquals(400_000L, state(saved).monthlyRemaining)
    }

    @Test fun editingActivationDayEntryPreservesItsConfirmedPostSetupInstant() {
        var ledger = entry(setup(), 100)
        val original = ledger.entries.single()
        clock.now = Instant.parse("2026-04-02T02:00:00Z")
        ledger = engine.execute(ledger, Command.UpsertEntry(original.toDraft().copy(amount = 200)))
        assertTrue(ledger.entries.single().included)
        assertEquals(original.occurredAt, ledger.entries.single().occurredAt)
        assertEquals(399_800L, state(ledger).monthlyRemaining)
    }

    @Test fun historicalFrozenDailyRemainderOverflowIsRejected() {
        clock.now = Instant.parse("2026-03-31T06:00:00Z")
        var ledger = entry(setup(Long.MAX_VALUE, Long.MAX_VALUE), Long.MAX_VALUE, id = "purchase")
        clock.now = Instant.parse("2026-04-01T06:00:00Z")
        ledger = engine.execute(ledger, Command.AdjustBudget(-Long.MAX_VALUE, "Reserve funds"))
        clock.now = Instant.parse("2026-04-02T06:00:00Z")
        ledger = entry(ledger, Long.MAX_VALUE, type = EntryType.REFUND, id = "refund", link = "purchase")
        clock.now = Instant.parse("2026-04-03T06:00:00Z")
        val historical = ledger.entries.single { it.id == "refund" }.toDraft().copy(date = LocalDate.parse("2026-04-01"))
        fails("too large") { engine.execute(ledger, Command.UpsertEntry(historical)) }
    }

    @Test fun stalePreviewCannotConfirmDifferentActivationEffectsAfterMidnight() {
        clock.now = Instant.parse("2026-04-01T18:29:00Z")
        val ledger = setup()
        val draft = EntryDraft(amount = 100, date = state(ledger).today)
        val reviewed = engine.preview(ledger, draft)
        assertTrue(reviewed.affectsBudget)
        clock.now = Instant.parse("2026-04-01T18:31:00Z")
        fails("Review the entry again") { engine.execute(ledger, Command.UpsertEntry(draft, true, reviewed)) }
    }

    @Test fun skippedMonthsKeepTheirOriginalTemplateAndZoneBeforeLaterSettingsChange() {
        var ledger = setup(1_000, 20_000, "Asia/Kolkata")
        clock.now = Instant.parse("2026-07-10T06:00:00Z")
        ledger = engine.execute(ledger, Command.Configure(30_000, "America/New_York"))
        assertEquals(4, ledger.periods.size)
        assertEquals(2, ledger.snapshots.size)
        clock.now = Instant.parse("2026-08-10T12:00:00Z")
        ledger = engine.ensureDay(ledger)
        ledger = entry(ledger, 100, LocalDate.parse("2026-05-10"))
        assertEquals(20_000L, ledger.periods.single { it.id == "2026-05" }.openingAmount)
        assertEquals("Asia/Kolkata", ledger.periods.single { it.id == "2026-05" }.zoneId)
        assertEquals(30_000L, state(ledger).period.openingAmount)
        assertEquals("America/New_York", state(ledger).period.zoneId)
    }

    @Test fun adjustmentPreviewCannotAccidentallyTargetNewMonth() {
        clock.now = Instant.parse("2026-04-30T18:29:00Z")
        val ledger = setup()
        val reviewed = Command.AdjustBudget(100, "Top up April", "2026-04")
        clock.now = Instant.parse("2026-04-30T18:31:00Z")
        fails("new month") { engine.execute(ledger, reviewed) }
    }

    @Test fun cancellingPendingTimezoneAtMonthBoundaryPersistsTheNewlyDisplayedDay() {
        clock.now = Instant.parse("2026-04-30T06:00:00Z")
        var ledger = setup(1_000, 31_000, "Asia/Kolkata")
        ledger = engine.execute(ledger, Command.Configure(31_000, "America/Los_Angeles"))
        clock.now = Instant.parse("2026-05-01T02:00:00Z")
        ledger = engine.ensureDay(ledger)
        assertEquals(LocalDate.parse("2026-04-30"), state(ledger).today)
        ledger = engine.execute(ledger, Command.Configure(31_000, "Asia/Kolkata"))
        val displayed = state(ledger)
        assertEquals(LocalDate.parse("2026-05-01"), displayed.today)
        assertTrue(ledger.periods.contains(displayed.period))
        assertTrue(ledger.snapshots.contains(displayed.snapshot))
        assertEquals(2, ledger.snapshots.size)
        assertEquals(1_000L, displayed.startingAllowance)
        assertEquals(ledger, engine.ensureDay(ledger))
    }
}
