package dev.daybudget.domain

import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Pure immutable calculations. The repository commits reconciliation and mutations together. */
class BudgetEngine(private val clock: Clock = Clock.systemUTC()) {
    fun setup(
        ledger: Ledger,
        openingAmount: Long,
        usualMonthlyBudget: Long?,
        zoneId: String = "Asia/Kolkata",
        at: Instant = clock.instant(),
    ): Ledger {
        if (ledger.settings != null) throw BudgetException("This budget is already set up.")
        nonNegative(openingAmount)
        usualMonthlyBudget?.let(::nonNegative)
        val zone = validZone(zoneId)
        val today = at.atZone(zone).toLocalDate()
        val month = YearMonth.from(today)
        val period = BudgetPeriod(month.toString(), month.atDay(1), month.atEndOfMonth(), zone.id, openingAmount, true, at)
        return ensureDay(ledger.copy(
            settings = Settings(zone.id, at, today, usualMonthlyBudget),
            periods = listOf(period),
        ), at)
    }

    /** Does not allocate money or backfill missed daily snapshots. */
    fun ensureDay(ledger: Ledger, at: Instant = clock.instant()): Ledger {
        val settings = ledger.settings ?: return ledger
        val context = context(ledger, at)
        val today = context.first
        val month = YearMonth.from(today)
        var state = ledger
        // Record skipped calendar months while the applicable template and timezone are still
        // known. No daily snapshots or spending are invented for the dates the app was closed.
        var intervening = YearMonth.from(settings.activationDate)
        while (intervening <= month) {
            if (state.periods.none { it.id == intervening.toString() }) {
                state = state.copy(periods = state.periods + makePeriod(settings, intervening, zoneForMonth(settings, intervening)))
            }
            intervening = intervening.plusMonths(1)
        }
        var period = state.periods.find { it.id == month.toString() }
        if (period == null) {
            period = makePeriod(settings, month, context.second)
            state = state.copy(periods = state.periods + period)
        }
        if (state.snapshots.none { it.periodId == period.id && it.date == today }) {
            val remaining = periodTotals(state, period).remaining
            val days = ChronoUnit.DAYS.between(today, period.end).toInt() + 1
            val allowance = remaining.coerceAtLeast(0) / days
            state = state.copy(snapshots = state.snapshots + DailySnapshot(period.id, today, allowance, at))
        }
        return state
    }

    fun execute(ledger: Ledger, command: Command, at: Instant = clock.instant()): Ledger {
        var state = ensureDay(ledger, at)
        val settings = state.settings ?: throw BudgetException("Set up your spending budget first.")
        val today = context(state, at).first
        when (command) {
            is Command.UpsertEntry -> {
                val preview = preview(state, command.draft, at)
                if (command.expectedPreview != null && command.expectedPreview != preview) {
                    throw BudgetException("The date or budget effects changed while this preview was open. Review the entry again before saving.")
                }
                if (preview.requiresConfirmation && !command.acceptBoundaryChange) {
                    throw BudgetException(preview.message)
                }
                val previous = state.entries.find { it.id == command.draft.id }
                val entry = buildEntry(state, command.draft, at)
                if (previous != null && previous.copy(updatedAt = entry.updatedAt) == entry) return state
                if (state.periods.none { it.id == entry.periodId }) {
                    state = state.copy(periods = state.periods + makePeriod(settings, YearMonth.from(entry.date), zoneForMonth(settings, YearMonth.from(entry.date))))
                }
                state = state.copy(
                    entries = state.entries.filterNot { it.id == entry.id } + entry,
                    undo = UndoRecord(entry.id, previous, entry),
                )
            }
            is Command.DeleteEntry -> {
                val previous = state.entries.find { it.id == command.id }
                    ?: throw BudgetException("This entry was already removed.")
                state = state.copy(entries = state.entries.filterNot { it.id == command.id }, undo = UndoRecord(command.id, previous, null))
            }
            Command.Undo -> {
                val undo = state.undo ?: throw BudgetException("There is no transaction to undo.")
                val current = state.entries.find { it.id == undo.entryId }
                if (current != undo.next) throw BudgetException("The entry has changed since this action. Edit it directly.")
                state = state.copy(entries = state.entries.filterNot { it.id == undo.entryId } + listOfNotNull(undo.previous), undo = null)
            }
            is Command.AdjustBudget -> {
                val reason = command.reason.trim()
                if (reason.isEmpty()) throw BudgetException("Add a reason for this budget adjustment.")
                val id = YearMonth.from(today).toString()
                if (command.expectedPeriodId != null && command.expectedPeriodId != id) {
                    throw BudgetException("A new month has started. Review the adjustment for this month's budget before saving.")
                }
                state = state.copy(
                    adjustments = state.adjustments + BudgetAdjustment(UUID.randomUUID().toString(), id, command.amount, reason.take(500), at),
                    periods = state.periods.map { if (it.id == id) it.copy(budgetConfigured = true) else it },
                    undo = null,
                )
            }
            is Command.Configure -> {
                command.usualMonthlyBudget?.let(::nonNegative)
                val nextZone = validZone(command.futureZoneId).id
                val currentZone = state.periods.single { it.id == YearMonth.from(today).toString() }.zoneId
                state = state.copy(settings = settings.copy(
                    usualMonthlyBudget = command.usualMonthlyBudget,
                    zoneId = currentZone,
                    futureZoneId = nextZone.takeUnless { it == currentZone },
                    futureZoneEffectiveMonth = if (nextZone == currentZone) null else YearMonth.from(today).plusMonths(1).toString(),
                ), undo = null)
            }
        }
        // Cancelling/changing a pending timezone can advance the budgeting date immediately
        // during a boundary overlap. Return that reconciled ledger, not just a transient view.
        state = ensureDay(state, at)
        validate(state)
        // Check every total exposed on the current screen before committing a write.
        view(state, at)
        return state
    }

    /** Trusted parsed imports retain their event timestamp; duplicate IDs never replace user edits. */
    fun importEntry(ledger: Ledger, draft: EntryDraft, source: String, occurredAt: Instant,
                    at: Instant = clock.instant(), reviewed: Boolean = false,
                    acceptBoundaryChange: Boolean = false, expectedPreview: EntryPreview? = null): Ledger {
        var state = ensureDay(ledger, at)
        val settings = state.settings ?: throw BudgetException("Set up your spending budget first.")
        if (state.entries.any { it.id == draft.id }) return state
        if (occurredAt > at) throw BudgetException("A transaction cannot occur in the future.")
        if (source.isBlank() || source.length > 80) throw BudgetException("This import needs a valid source.")
        val month = YearMonth.from(draft.date)
        val period = state.periods.find { it.id == month.toString() }
            ?: makePeriod(settings, month, zoneForMonth(settings, month))
        if (reviewed) {
            state = execute(state, Command.UpsertEntry(draft, acceptBoundaryChange, expectedPreview), at)
            val original = state.entries.single { it.id == draft.id }
            val actualDateMatches = occurredAt.atZone(validZone(period.zoneId)).toLocalDate() == draft.date
            val actualInclusion = occurredAt >= settings.activatedAt && draft.date >= settings.activationDate
            if (actualDateMatches && original.included && !actualInclusion) {
                throw BudgetException("This payment happened before this budget was set up. It cannot be added as new spending on the setup date. Ignore it if it is already reflected in the opening amount.")
            }
            val entry = original.copy(source = source.trim(), occurredAt =
                if (actualDateMatches && original.included == actualInclusion) occurredAt else original.occurredAt)
            return state.copy(entries = state.entries.map { if (it.id == entry.id) entry else it },
                undo = UndoRecord(entry.id, null, entry))
        }
        if (occurredAt.atZone(validZone(period.zoneId)).toLocalDate() != draft.date) {
            throw BudgetException("The imported date does not match its timestamp in this budget's timezone.")
        }
        val entry = buildEntry(state, draft, at).copy(
            occurredAt = occurredAt,
            included = occurredAt >= settings.activatedAt && draft.date >= settings.activationDate,
            source = source.trim(),
        )
        if (state.periods.none { it.id == period.id }) state = state.copy(periods = state.periods + period)
        state = state.copy(entries = state.entries + entry, undo = UndoRecord(entry.id, null, entry))
        validate(state)
        view(state, at)
        return state
    }

    fun preview(ledger: Ledger, draft: EntryDraft, at: Instant = clock.instant()): EntryPreview {
        val state = ensureDay(ledger, at)
        val previous = state.entries.find { it.id == draft.id }
        val next = buildEntry(state, draft, at)
        validateRefunds(state.entries.filterNot { it.id == draft.id } + next)
        val crossedMonth = previous != null && previous.periodId != next.periodId
        val crossedActivation = previous?.included?.let { it != next.included } ?: !next.included
        val deltas = linkedMapOf<String, BigInteger>()
        if (previous != null && previous.included) deltas[previous.periodId] = effect(previous).negate()
        if (next.included) deltas[next.periodId] = (deltas[next.periodId] ?: BigInteger.ZERO).add(effect(next))
        val exactDeltas = deltas.mapValues { Money.exact(it.value) }
        val explanation = buildList {
            if (crossedMonth) add("This moves the entry from ${previous.periodId} to ${next.periodId}.")
            if (crossedActivation) add(if (next.included) "It will now affect your spending budget." else "It is on or before the date tracking began. Without a confirmed time after setup, it will be kept as an excluded record, so it will not reduce the opening budget again.")
            if (crossedMonth || crossedActivation) {
                exactDeltas.forEach { (month, amount) -> add("$month monthly remaining changes by ${Money.format(amount)}.") }
                add("Previously shown daily starting allowances stay unchanged.")
            }
        }.joinToString(" ")
        return EntryPreview(crossedMonth || crossedActivation, explanation, exactDeltas, next.included)
    }

    fun view(ledger: Ledger, at: Instant = clock.instant()): BudgetView? {
        val state = ensureDay(ledger, at)
        val settings = state.settings ?: return null
        val today = context(state, at).first
        val period = state.periods.single { it.id == YearMonth.from(today).toString() }
        val snapshot = state.snapshots.single { it.periodId == period.id && it.date == today }
        val month = periodTotals(state, period)
        val todayEntries = state.entries.filter { it.included && it.date == today }
        val todayExpenses = expenseTotal(todayEntries)
        val todayRefunds = refundTotal(todayEntries)
        val days = (6L downTo 0L).map { offset ->
            val date = today.minusDays(offset)
            val entries = state.entries.filter { it.included && it.date == date }
            val expenses = expenseTotal(entries)
            val refunds = refundTotal(entries)
            WeeklyDay(date, expenses, refunds, Money.combine(expenses to 1, refunds to -1), date <= settings.activationDate, entries.size)
        }
        val weeklyExpenses = Money.sum(days.map { it.expenses })
        val weeklyRefunds = Money.sum(days.map { it.refunds })
        return BudgetView(
            today, settings, period, snapshot,
            ChronoUnit.DAYS.between(today, period.end).toInt() + 1,
            month.expenses, month.refunds, month.adjustments, month.remaining,
            todayExpenses, todayRefunds, Money.combine(snapshot.startingAllowance to 1, todayExpenses to -1, todayRefunds to 1),
            state.entries.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt }),
            days, weeklyExpenses, weeklyRefunds, Money.combine(weeklyExpenses to 1, weeklyRefunds to -1), state.undo != null,
            state.adjustments.sortedByDescending { it.createdAt },
        )
    }

    /** Target-zone midnight starts a scheduled new month; existing periods retain their zone. */
    private fun context(ledger: Ledger, at: Instant): Pair<LocalDate, String> {
        val settings = ledger.settings ?: throw BudgetException("Set up your spending budget first.")
        val effective = settings.futureZoneEffectiveMonth?.let(YearMonth::parse)
        val futureZone = settings.futureZoneId?.let(::validZone)
        val futureActive = effective != null && futureZone != null && at >= effective.atDay(1).atStartOfDay(futureZone).toInstant()
        var zone = if (futureActive) futureZone else validZone(settings.zoneId)
        var date = at.atZone(zone).toLocalDate()
        if (!futureActive && effective != null && YearMonth.from(date) >= effective) date = effective.atDay(1).minusDays(1)
        val existing = ledger.periods.find { it.id == YearMonth.from(date).toString() }
        if (existing != null && existing.zoneId != zone.id) {
            zone = validZone(existing.zoneId)
            date = at.atZone(zone).toLocalDate()
            if (YearMonth.from(date).toString() != existing.id) date = existing.end
        }
        return date to zone.id
    }

    private fun zoneForMonth(settings: Settings, month: YearMonth): String =
        if (settings.futureZoneId != null && settings.futureZoneEffectiveMonth != null && month >= YearMonth.parse(settings.futureZoneEffectiveMonth)) settings.futureZoneId else settings.zoneId

    private fun makePeriod(settings: Settings, month: YearMonth, zoneId: String): BudgetPeriod {
        val beforeTracking = month < YearMonth.from(settings.activationDate)
        return BudgetPeriod(month.toString(), month.atDay(1), month.atEndOfMonth(), zoneId,
            if (beforeTracking) 0 else settings.usualMonthlyBudget ?: 0,
            !beforeTracking && settings.usualMonthlyBudget != null,
            if (month == YearMonth.from(settings.activationDate)) settings.activatedAt else month.atDay(1).atStartOfDay(validZone(zoneId)).toInstant())
    }

    private fun buildEntry(ledger: Ledger, draft: EntryDraft, at: Instant): Entry {
        val settings = ledger.settings ?: throw BudgetException("Set up your spending budget first.")
        if (draft.id.isBlank()) throw BudgetException("This entry needs an ID.")
        if (draft.amount <= 0) throw BudgetException("Enter an amount greater than zero.")
        val today = context(ledger, at).first
        if (draft.date > today) throw BudgetException("Choose today or an earlier date.")
        if (draft.type == EntryType.EXPENSE && draft.linkedExpenseId != null) throw BudgetException("Only a refund can be linked to a purchase.")
        if (draft.linkedExpenseId == draft.id) throw BudgetException("A refund cannot link to itself.")
        val old = ledger.entries.find { it.id == draft.id }
        val month = YearMonth.from(draft.date)
        val zone = validZone(ledger.periods.find { it.id == month.toString() }?.zoneId ?: zoneForMonth(settings, month))
        val unchangedDate = old != null && old.date == draft.date
        // Date-only historical entries use a documented midnight surrogate. An unknown time on
        // the activation date cannot safely be treated as a purchase made after setup.
        val occurredAt = if (unchangedDate) old.occurredAt else if (draft.date == today) at else draft.date.atStartOfDay(zone).toInstant()
        val included = when {
            unchangedDate -> old.included
            draft.date == today -> draft.date >= settings.activationDate && at >= settings.activatedAt
            else -> draft.date > settings.activationDate
        }
        return Entry(draft.id, draft.type, draft.amount, draft.date, month.toString(), occurredAt, old?.createdAt ?: at, at, included,
            draft.merchant.trim().take(160), draft.category.trim().take(80), draft.note.trim().take(500), draft.linkedExpenseId,
            old?.source ?: "MANUAL")
    }

    private fun validate(ledger: Ledger) {
        if (ledger.entries.map { it.id }.toSet().size != ledger.entries.size) throw BudgetException("Duplicate transaction IDs are not allowed.")
        if (ledger.periods.map { it.id }.toSet().size != ledger.periods.size) throw BudgetException("Duplicate budget periods are not allowed.")
        if (ledger.snapshots.map { it.periodId to it.date }.toSet().size != ledger.snapshots.size) throw BudgetException("Duplicate daily snapshots are not allowed.")
        validateRefunds(ledger.entries)
        ledger.periods.forEach { periodTotals(ledger, it) }
        ledger.entries.filter { it.included }.groupBy { it.date }.values.forEach { entries -> expenseTotal(entries); refundTotal(entries) }
        ledger.snapshots.forEach { snapshot ->
            val entries = ledger.entries.filter { it.included && it.periodId == snapshot.periodId && it.date == snapshot.date }
            Money.combine(snapshot.startingAllowance to 1, expenseTotal(entries) to -1, refundTotal(entries) to 1)
        }
    }

    private fun validateRefunds(entries: List<Entry>) {
        val byId = entries.associateBy { it.id }
        entries.filter { it.linkedExpenseId != null }.groupBy { it.linkedExpenseId!! }.forEach { (id, refunds) ->
            val original = byId[id] ?: throw BudgetException("This purchase has linked refunds. Remove or unlink the refunds before deleting it.")
            if (original.type != EntryType.EXPENSE) throw BudgetException("Link this refund to an expense.")
            if (refunds.any { it.type != EntryType.REFUND }) throw BudgetException("Only refunds can link to a purchase.")
            if (refunds.any { it.date < original.date }) throw BudgetException("A linked refund cannot be dated before its purchase.")
            val total = Money.sum(refunds.map { it.amount })
            if (total > original.amount) throw BudgetException("Linked refunds exceed the original purchase of ${Money.format(original.amount)}. Reduce the refund or remove its purchase link after checking it.")
        }
    }

    private data class PeriodTotals(val expenses: Long, val refunds: Long, val adjustments: Long, val remaining: Long)
    private fun periodTotals(ledger: Ledger, period: BudgetPeriod): PeriodTotals {
        val entries = ledger.entries.filter { it.included && it.periodId == period.id }
        val expenses = expenseTotal(entries)
        val refunds = refundTotal(entries)
        val adjustments = Money.sum(ledger.adjustments.filter { it.periodId == period.id }.map { it.amount })
        return PeriodTotals(expenses, refunds, adjustments, Money.combine(period.openingAmount to 1, adjustments to 1, expenses to -1, refunds to 1))
    }
    private fun expenseTotal(entries: List<Entry>) = Money.sum(entries.filter { it.type == EntryType.EXPENSE }.map { it.amount })
    private fun refundTotal(entries: List<Entry>) = Money.sum(entries.filter { it.type == EntryType.REFUND }.map { it.amount })
    private fun effect(entry: Entry): BigInteger = BigInteger.valueOf(entry.amount).let { if (entry.type == EntryType.EXPENSE) it.negate() else it }
    private fun nonNegative(amount: Long) { if (amount < 0) throw BudgetException("Enter an amount of zero or more.") }
    private fun validZone(id: String): ZoneId = try { ZoneId.of(id) } catch (_: Exception) { throw BudgetException("Choose a valid timezone, such as Asia/Kolkata.") }
}
