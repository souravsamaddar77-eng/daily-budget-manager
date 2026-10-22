package dev.daybudget.data

import androidx.room.withTransaction
import dev.daybudget.domain.BudgetEngine
import dev.daybudget.domain.BudgetView
import dev.daybudget.domain.Command
import dev.daybudget.domain.EntryDraft
import dev.daybudget.domain.EntryPreview
import dev.daybudget.domain.EntryType
import dev.daybudget.domain.Ledger
import dev.daybudget.domain.BudgetCalendar
import dev.daybudget.domain.BudgetCalendarEngine
import dev.daybudget.domain.ImportResult
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sole mutation boundary. Read, day initialization, validation and persistence
 * share one SQLite transaction. A failed write publishes no partially updated UI.
 * The mutex also keeps publications in commit order for this application repository.
 */
class BudgetRepository(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val engine = BudgetEngine(clock)
    private val dao = database.ledgerDao()
    private val gate = Mutex()
    private val mutableState = MutableStateFlow<BudgetView?>(null)
    val state: StateFlow<BudgetView?> = mutableState.asStateFlow()

    suspend fun refresh(): BudgetView? = change { ledger, _ -> ledger }

    suspend fun setup(openingAmount: Long, usualMonthlyBudget: Long?, zoneId: String): BudgetView =
        requireNotNull(change { ledger, at ->
            engine.setup(ledger, openingAmount, usualMonthlyBudget, zoneId, at)
        })

    suspend fun execute(command: Command): BudgetView =
        requireNotNull(change { ledger, at -> engine.execute(ledger, command, at) })

    suspend fun calendar(month: YearMonth): BudgetCalendar? = gate.withLock {
        val (calendar, view) = database.withTransaction {
            val at = clock.instant()
            val before = readLedger()
            val current = engine.ensureDay(before, at)
            val calendar = BudgetCalendarEngine.view(current, month, at)
            val view = engine.view(current, at)
            persist(before, current)
            calendar to view
        }
        mutableState.value = view
        calendar
    }

    suspend fun importIfAbsent(draft: EntryDraft, source: String = "NOTIFICATION", occurredAt: Instant,
                               acceptBoundaryChange: Boolean = false, expectedPreview: EntryPreview? = null,
                               reviewed: Boolean = false): ImportResult = gate.withLock {
        val result = database.withTransaction {
            val at = clock.instant()
            val before = readLedger()
            val current = engine.ensureDay(before, at)
            val beforeView = requireNotNull(engine.view(current, at)) { "Set up your spending budget first." }
            val existing = current.entries.find { it.id == draft.id }
            if (dao.importReceipt(draft.id) != null || existing != null) {
                // Also fence off collisions with manual IDs; imported data never replaces edits.
                if (dao.importReceipt(draft.id) == null) dao.insertImportReceipt(ImportReceiptRow(draft.id, at.toString()))
                persist(before, current)
                ImportResult(beforeView, beforeView, existing, false)
            } else {
                val after = engine.importEntry(current, draft, source, occurredAt, at, reviewed, acceptBoundaryChange, expectedPreview)
                val afterView = requireNotNull(engine.view(after, at))
                persist(before, after)
                dao.insertImportReceipt(ImportReceiptRow(draft.id, at.toString()))
                ImportResult(beforeView, afterView, after.entries.single { it.id == draft.id }, true)
            }
        }
        mutableState.value = result.after
        result
    }

    suspend fun preview(draft: EntryDraft): EntryPreview = gate.withLock {
        val (preview, view) = database.withTransaction {
            val at = clock.instant()
            val before = readLedger()
            val current = engine.ensureDay(before, at)
            val preview = engine.preview(current, draft, at)
            val view = engine.view(current, at)
            persist(before, current)
            preview to view
        }
        mutableState.value = view
        preview
    }

    /** Explicit user-requested deletion; the only operation allowed to erase snapshots. */
    suspend fun clear() = gate.withLock {
        database.withTransaction {
            dao.clearImportReceipts()
            dao.clearUndo()
            dao.clearRefunds()
            dao.clearEntries()
            dao.clearAdjustments()
            dao.clearSnapshots()
            dao.clearPeriods()
            dao.clearSettings()
        }
        mutableState.value = null
    }

    private suspend fun change(transform: (Ledger, Instant) -> Ledger): BudgetView? = gate.withLock {
        val view = database.withTransaction {
            // Capture once after entering the transaction, including when queued at midnight.
            val at = clock.instant()
            val before = readLedger()
            val current = engine.ensureDay(before, at)
            val after = transform(current, at)
            // Validate derived totals before the commit; no overflowing SQL SUM is used.
            val view = engine.view(after, at)
            persist(before, after)
            view
        }
        mutableState.value = view
        view
    }

    private suspend fun readLedger() = Ledger(
        settings = dao.settings()?.toDomain(),
        periods = dao.periods().map(PeriodRow::toDomain),
        snapshots = dao.snapshots().map(SnapshotRow::toDomain),
        entries = dao.entries().map { it.entry.toDomain() },
        adjustments = dao.adjustments().map(AdjustmentRow::toDomain),
        undo = dao.undo()?.toDomain(),
    )

    private suspend fun persist(before: Ledger, after: Ledger) {
        val oldPeriods = before.periods.associateBy { it.id }
        val newPeriods = after.periods.associateBy { it.id }
        val oldSnapshots = before.snapshots.associateBy { it.periodId to it.date }
        val newSnapshots = after.snapshots.associateBy { it.periodId to it.date }
        val oldAdjustments = before.adjustments.associateBy { it.id }
        val newAdjustments = after.adjustments.associateBy { it.id }
        val oldEntries = before.entries.associateBy { it.id }
        val newEntries = after.entries.associateBy { it.id }
        check(newPeriods.size == after.periods.size && newEntries.size == after.entries.size)
        check(newSnapshots.size == after.snapshots.size && newAdjustments.size == after.adjustments.size)
        check(oldPeriods.keys.all(newPeriods::containsKey)) { "Budget periods cannot be erased by a ledger change" }
        check(oldSnapshots.all { (id, row) -> newSnapshots[id] == row }) { "Daily snapshots are immutable" }
        check(oldAdjustments.all { (id, row) -> newAdjustments[id] == row }) { "Budget adjustments are immutable" }

        if (after.settings != before.settings) after.settings?.let { dao.upsertSettings(it.toRow()) }
        after.periods.filter { oldPeriods[it.id] != it }.forEach { dao.upsertPeriod(it.toRow()) }
        // Insert the snapshot before the entry; both are committed together or rolled back.
        after.snapshots.filter { (it.periodId to it.date) !in oldSnapshots }.forEach { dao.insertSnapshot(it.toRow()) }
        after.adjustments.filter { it.id !in oldAdjustments }.forEach { dao.insertAdjustment(it.toRow()) }
        after.entries.filter { oldEntries[it.id] != it }
            .sortedBy { if (it.type == EntryType.EXPENSE) 0 else 1 }
            .forEach { dao.upsertEntry(EntryRow(it.toColumns())) }
        before.entries.filter { it.id !in newEntries }
            .sortedBy { if (it.type == EntryType.REFUND) 0 else 1 }
            .forEach { dao.deleteEntry(it.id) }
        if (after.undo != before.undo) {
            val undo = after.undo
            if (undo == null) dao.clearUndo() else dao.upsertUndo(undo.toRow())
        }
    }
}
