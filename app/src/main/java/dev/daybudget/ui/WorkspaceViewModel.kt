package dev.daybudget.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.daybudget.data.BudgetWorkspace
import dev.daybudget.domain.BudgetException
import dev.daybudget.domain.EntryDraft
import dev.daybudget.domain.EntryPreview
import dev.daybudget.domain.ThemeMode
import dev.daybudget.notifications.NotificationSettings
import dev.daybudget.notifications.NotificationsController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class WorkspaceAction { CREATED_BUDGET, SELECTED_BUDGET, RENAMED_BUDGET, RENAMED_MONTH, SAVINGS_ADDED, PREFERENCES_SAVED, PAYMENT_RECORDED, PAYMENT_IGNORED, CLEARED_ALL }
data class WorkspaceCompletion(val key: String, val action: WorkspaceAction)
data class NotificationReview(val id: Long, val candidateId: String, val budgetId: String, val draft: EntryDraft, val preview: EntryPreview)
data class WorkspaceOperationState(
    val busy: Boolean = false,
    val completionPending: Boolean = false,
    val completion: WorkspaceCompletion? = null,
    val review: NotificationReview? = null,
    val error: String? = null,
)

/** Workspace operations retain their results so recreation cannot leave a submitted form active. */
class WorkspaceViewModel(val workspace: BudgetWorkspace, val notifications: NotificationsController) : ViewModel() {
    val state = workspace.state
    val notificationState = notifications.state
    private val mutableOperation = MutableStateFlow(WorkspaceOperationState())
    val operation = mutableOperation.asStateFlow()
    private val session = UUID.randomUUID().toString()
    private var revision = 0L
    private var refreshing = false

    init { refresh() }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                workspace.initialize()
                workspace.repository(state.value.selectedBudgetId).refresh()
                notifications.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableOperation.update { it.copy(error = message(error)) }
            } finally { refreshing = false }
        }
    }

    fun createBudget(name: String, opening: Long, usual: Long?, zone: String) = perform { id ->
        workspace.createBudget(name, opening, usual, zone)
        complete(id, WorkspaceAction.CREATED_BUDGET)
    }

    fun selectBudget(budgetId: String) = perform { id ->
        workspace.selectBudget(budgetId)
        complete(id, WorkspaceAction.SELECTED_BUDGET)
    }

    fun renameBudget(budgetId: String, name: String) = perform { id ->
        workspace.renameBudget(budgetId, name)
        complete(id, WorkspaceAction.RENAMED_BUDGET)
    }

    fun renameMonth(budgetId: String, periodId: String, name: String) = perform { id ->
        workspace.renameMonth(budgetId, periodId, name)
        complete(id, WorkspaceAction.RENAMED_MONTH)
    }

    fun addSavings(amount: Long, note: String) = perform { id ->
        workspace.addSavings(amount, note)
        complete(id, WorkspaceAction.SAVINGS_ADDED)
    }

    fun setTheme(mode: ThemeMode) = perform { id ->
        workspace.setTheme(mode)
        complete(id, WorkspaceAction.PREFERENCES_SAVED)
    }

    fun configureNotifications(settings: NotificationSettings) = perform { id ->
        notifications.configure(settings)
        complete(id, WorkspaceAction.PREFERENCES_SAVED)
    }

    fun submitPayment(candidateId: String, budgetId: String, draft: EntryDraft) = perform { id ->
        // Candidates use the same stable ID on preview and commit. Switching budgets cannot
        // redirect a pending review because its target ID and draft are retained together.
        val candidateDraft = draft.copy(id = candidateId)
        val preview = workspace.repository(budgetId).preview(candidateDraft)
        if (preview.requiresConfirmation) {
            mutableOperation.update { it.copy(review = NotificationReview(id, candidateId, budgetId, candidateDraft, preview)) }
        } else {
            notifications.acceptCandidate(candidateId, budgetId, candidateDraft)
            complete(id, WorkspaceAction.PAYMENT_RECORDED)
        }
    }

    fun confirmPayment(reviewId: Long) {
        val pending = operation.value.review?.takeIf { it.id == reviewId } ?: return
        perform { id ->
            notifications.acceptCandidate(pending.candidateId, pending.budgetId, pending.draft,
                acceptBoundaryChange = true, expectedPreview = pending.preview)
            complete(id, WorkspaceAction.PAYMENT_RECORDED)
        }
    }

    fun dismissPaymentReview() {
        if (!operation.value.busy) mutableOperation.update { it.copy(review = null) }
    }

    fun ignorePayment(candidateId: String) = perform { id ->
        notifications.ignoreCandidate(candidateId)
        complete(id, WorkspaceAction.PAYMENT_IGNORED)
    }

    fun clearAll() = perform { id ->
        // Stop capture before clearing the ledgers so another notification cannot repopulate them.
        notifications.clearData()
        workspace.clearAll()
        complete(id, WorkspaceAction.CLEARED_ALL)
    }

    fun dismissError() {
        mutableOperation.update { it.copy(error = null) }
        notifications.dismissError()
    }

    fun acknowledge(key: String) = mutableOperation.update {
        if (it.completion?.key == key) it.copy(completionPending = false) else it
    }

    private fun complete(id: Long, action: WorkspaceAction) = mutableOperation.update {
        it.copy(completion = WorkspaceCompletion("$session:$id", action), completionPending = true, review = null)
    }

    private fun perform(action: suspend (Long) -> Unit) {
        if (operation.value.busy || operation.value.completionPending) return
        val id = ++revision
        mutableOperation.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableOperation.update { it.copy(error = message(error)) } }
            finally { mutableOperation.update { it.copy(busy = false) } }
        }
    }

    private fun message(error: Exception): String = when (error) {
        is BudgetException -> error.message ?: "Check the details and try again."
        is ArithmeticException -> "That amount is too large. Enter a smaller amount."
        is IllegalArgumentException -> "Check the name, amount, and selected budget, then try again."
        else -> "The change could not be completed. Try again."
    }

    class Factory(private val workspace: BudgetWorkspace, private val notifications: NotificationsController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WorkspaceViewModel::class.java))
            return WorkspaceViewModel(workspace, notifications) as T
        }
    }
}
