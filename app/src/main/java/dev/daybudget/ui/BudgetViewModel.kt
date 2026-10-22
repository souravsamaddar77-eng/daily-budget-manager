package dev.daybudget.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.daybudget.data.BudgetRepository
import dev.daybudget.domain.BudgetException
import dev.daybudget.domain.BudgetView
import dev.daybudget.domain.Command
import dev.daybudget.domain.EntryDraft
import dev.daybudget.domain.EntryPreview
import dev.daybudget.domain.Entry
import dev.daybudget.domain.EntryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.time.YearMonth

data class AppUiState(
    val loading: Boolean = true,
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val budget: BudgetView? = null,
    val error: String? = null,
    val entryReview: PendingEntryReview? = null,
    val completedOperation: CompletedOperation? = null,
    val completionPending: Boolean = false,
)

data class UiNotice(val message: String, val offerUndo: Boolean = false, val revision: Long = 0)

data class PendingEntryReview(val operationId: Long, val draft: EntryDraft, val preview: EntryPreview)
enum class CompletedOperationKind { SETUP, ENTRY_SAVED, ENTRY_DELETED, UNDONE, BUDGET_ADJUSTED, SETTINGS_SAVED, DATA_CLEARED }
data class CompletedOperation(val id: Long, val kind: CompletedOperationKind, val sessionId: String) {
    val key: String get() = "$sessionId:$id"
}

class BudgetViewModel(
    private val repository: BudgetRepository,
    private val onExpenseCommitted: ((BudgetView, BudgetView, Entry) -> Unit)? = null,
) : ViewModel() {
    private val mutableUi = MutableStateFlow(AppUiState())
    val ui = mutableUi.asStateFlow()
    private val notices = Channel<UiNotice>(Channel.BUFFERED)
    val events = notices.receiveAsFlow()
    private var reading = false
    private var actionRevision = 0L
    private val sessionId = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            repository.state.collect { view -> mutableUi.update { it.copy(budget = view) } }
        }
        refresh()
    }

    /** Foreground and calendar-boundary reads use the same atomic operation as writes. */
    fun refresh() {
        if (reading) return
        reading = true
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val budget = repository.refresh()
                mutableUi.update { it.copy(budget = budget, loading = false, initialized = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUi.update { it.copy(loading = false, error = safeMessage(error)) }
            } finally {
                reading = false
            }
        }
    }

    fun dismissError() = mutableUi.update { it.copy(error = null) }

    suspend fun calendar(month: YearMonth) = repository.calendar(month)

    fun setup(opening: Long, usual: Long?, zone: String) = perform { operationId ->
        val budget = repository.setup(opening, usual, zone)
        complete(operationId, CompletedOperationKind.SETUP, budget)
    }

    fun submit(draft: EntryDraft) = perform { operationId ->
        val preview = repository.preview(draft)
        if (preview.requiresConfirmation) {
            mutableUi.update { it.copy(entryReview = PendingEntryReview(operationId, draft, preview)) }
        } else {
            saveEntry(draft, false, operationId)
        }
    }

    fun confirmEntryReview(reviewId: Long) {
        val review = mutableUi.value.entryReview?.takeIf { it.operationId == reviewId } ?: return
        perform { operationId -> saveEntry(review.draft, true, operationId, review.preview) }
    }

    fun dismissEntryReview() {
        if (!mutableUi.value.busy) mutableUi.update { it.copy(entryReview = null) }
    }

    private suspend fun saveEntry(draft: EntryDraft, acceptBoundary: Boolean, operationId: Long, expectedPreview: EntryPreview? = null) {
        val before = repository.state.value
        val budget = repository.execute(Command.UpsertEntry(draft, acceptBoundary, expectedPreview))
        complete(operationId, CompletedOperationKind.ENTRY_SAVED, budget)
        val entry = budget.entries.firstOrNull { it.id == draft.id }
        if (before != null && entry?.type == EntryType.EXPENSE && before.entries.none { it.id == entry.id }) {
            // A notification failure must never turn an already committed expense into a failed save.
            runCatching { onExpenseCommitted?.invoke(before, budget, entry) }
        }
        notices.send(UiNotice(if (draft.type.name == "REFUND") "Refund saved" else "Expense saved", true, operationId))
    }

    fun delete(id: String) = perform { operationId ->
        val budget = repository.execute(Command.DeleteEntry(id))
        complete(operationId, CompletedOperationKind.ENTRY_DELETED, budget)
        notices.send(UiNotice("Entry deleted", true, operationId))
    }

    fun undo() = perform { operationId ->
        val budget = repository.execute(Command.Undo)
        complete(operationId, CompletedOperationKind.UNDONE, budget)
        notices.send(UiNotice("Last change undone"))
    }

    fun undoIfCurrent(revision: Long) {
        if (revision == actionRevision) undo()
    }

    fun adjust(amount: Long, reason: String, expectedPeriodId: String) = perform { operationId ->
        val budget = repository.execute(Command.AdjustBudget(amount, reason, expectedPeriodId))
        complete(operationId, CompletedOperationKind.BUDGET_ADJUSTED, budget)
        notices.send(UiNotice("Monthly budget updated. Today's starting allowance stays the same."))
    }

    fun configure(usual: Long?, futureZone: String) = perform { operationId ->
        val budget = repository.execute(Command.Configure(usual, futureZone))
        complete(operationId, CompletedOperationKind.SETTINGS_SAVED, budget)
        notices.send(UiNotice("Settings saved"))
    }

    fun clear() = perform { operationId ->
        repository.clear()
        complete(operationId, CompletedOperationKind.DATA_CLEARED, null)
        notices.send(UiNotice("All local records deleted"))
    }

    /**
     * Keep the result after acknowledgement: an Activity may have saved its bundle before
     * the old composition handled this result. Its replacement still needs to reconcile it.
     */
    fun acknowledgeCompletion(operationId: Long) {
        mutableUi.update {
            if (it.completedOperation?.id == operationId) it.copy(completionPending = false) else it
        }
    }

    private fun complete(operationId: Long, kind: CompletedOperationKind, budget: BudgetView?) {
        mutableUi.update { it.copy(budget = budget, entryReview = null,
            completedOperation = CompletedOperation(operationId, kind, sessionId), completionPending = true) }
    }

    private fun perform(action: suspend (Long) -> Unit) {
        // Set this before suspension, so a second tap cannot enqueue a second save.
        if (mutableUi.value.busy || mutableUi.value.completionPending) return
        actionRevision++
        val operationId = actionRevision
        mutableUi.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action(operationId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUi.update { it.copy(error = safeMessage(error)) }
            } finally {
                mutableUi.update { it.copy(busy = false) }
            }
        }
    }

    private fun safeMessage(error: Exception): String = when (error) {
        is BudgetException -> error.message ?: "Check the details and try again."
        is ArithmeticException -> "That amount would exceed the supported money range. Enter a smaller amount."
        is IllegalArgumentException -> "Check the amount, date, and timezone, then try again."
        else -> "The change could not be completed. Your saved records are still here. Try again."
    }

    class Factory(
        private val repository: BudgetRepository,
        private val onExpenseCommitted: ((BudgetView, BudgetView, Entry) -> Unit)? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BudgetViewModel::class.java))
            return BudgetViewModel(repository, onExpenseCommitted) as T
        }
    }
}
