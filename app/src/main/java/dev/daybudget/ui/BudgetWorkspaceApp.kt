@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.daybudget.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.daybudget.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A retained model per ledger prevents a pending save from following a budget switch. */
@Composable
@Suppress("DEPRECATION") // API 26–28 three-button bars need an explicit color when app theme differs from system.
fun BudgetWorkspaceApp(workspaceModel: WorkspaceViewModel) {
    val workspace by workspaceModel.state.collectAsStateWithLifecycle()
    val operation by workspaceModel.operation.collectAsStateWithLifecycle()
    DayBudgetTheme(workspace.themeMode) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
        val navigationBarColor = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                if (Build.VERSION.SDK_INT < 29) window.navigationBarColor = navigationBarColor
                WindowInsetsControllerCompat(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        if (!workspace.initialized) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (operation.error == null) CircularProgressIndicator()
                else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(operation.error.orEmpty())
                    Button(onClick = workspaceModel::refresh) { Text("Try again") }
                }
            }
        } else {
            val profile = workspace.activeProfile ?: return@DayBudgetTheme
            val model: BudgetViewModel = viewModel(key = "budget:${profile.id}", factory =
                BudgetViewModel.Factory(workspaceModel.workspace.repository(profile.id)) { before, after, entry ->
                    val name = workspaceModel.state.value.profiles.find { it.id == profile.id }?.name ?: profile.name
                    workspaceModel.notifications.notifyCommittedExpense(profile.id, name, before, after, entry)
                })
            key(profile.id) { WorkspaceLedger(model, workspaceModel, workspace) }
        }
    }
}

@Composable
private fun WorkspaceLedger(model: BudgetViewModel, hub: WorkspaceViewModel, workspace: WorkspaceState) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val operation by hub.operation.collectAsStateWithLifecycle()
    val notifications by hub.notificationState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var editorKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editorDate by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var refundId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var handledBudgetCompletion by rememberSaveable { mutableStateOf<String?>(null) }
    var handledWorkspaceCompletion by rememberSaveable { mutableStateOf<String?>(null) }
    val budget = ui.budget
    val busy = ui.busy || ui.completionPending || operation.busy || operation.completionPending
    fun openEditor(request: EditorRequest) {
        model.dismissEntryReview()
        editingId = request.entry?.id
        refundId = request.refundFor?.id
        editorDate = request.initialDate?.toString()
        editorKind = request.type.name
    }

    LaunchedEffect(ui.completedOperation?.key) {
        ui.completedOperation?.let { result ->
            if (handledBudgetCompletion != result.key) {
                when (result.kind) {
                    CompletedOperationKind.ENTRY_SAVED -> editorKind = null
                    CompletedOperationKind.ENTRY_DELETED -> selectedId = null
                    CompletedOperationKind.BUDGET_ADJUSTED, CompletedOperationKind.SETTINGS_SAVED -> route = null
                    CompletedOperationKind.DATA_CLEARED -> { editorKind = null; selectedId = null; route = null; tab = 0 }
                    else -> Unit
                }
                handledBudgetCompletion = result.key
            }
            model.acknowledgeCompletion(result.id)
        }
    }
    LaunchedEffect(operation.completion?.key) {
        operation.completion?.let { result ->
            if (handledWorkspaceCompletion != result.key) {
                when (result.action) {
                    WorkspaceAction.CLEARED_ALL -> { route = null; editorKind = null; selectedId = null; tab = 0 }
                    WorkspaceAction.CREATED_BUDGET, WorkspaceAction.SELECTED_BUDGET -> { route = null; tab = 0 }
                    else -> Unit
                }
                handledWorkspaceCompletion = result.key
            }
            hub.acknowledge(result.key)
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                model.refresh()
                hub.refresh()
                delay(nextRefreshDelay(Instant.now(), ZoneId.of(model.ui.value.budget?.period?.zoneId ?: "Asia/Kolkata")))
            }
        }
    }
    LaunchedEffect(model) {
        model.events.collectLatest { event ->
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(event.message, if (event.offerUndo) "Undo" else null,
                withDismissAction = true, duration = if (event.offerUndo) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) model.undoIfCurrent(event.revision)
        }
    }

    val titles = listOf("Today", "Calendar", "Budgets", "Savings")
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(if (budget == null) "DayBudget" else titles[tab], style = MaterialTheme.typography.headlineLarge) },
            actions = { TextButton(onClick = { route = "settings" }, enabled = !busy) { Text("Settings") } }) },
        bottomBar = {
            if (budget != null || tab >= 2) Surface(color = MaterialTheme.colorScheme.background) {
                FlowRow(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    titles.forEachIndexed { index, title ->
                        if (tab == index) FilledTonalButton(onClick = { tab = index }, enabled = !busy) { Text(title) }
                        else TextButton(onClick = { tab = index }, enabled = !busy) { Text(title) }
                    }
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !ui.initialized -> ContentColumn {
                    Text("Your records could not be opened")
                    Button(onClick = model::refresh) { Text("Try again") }
                }
                tab == 2 -> BudgetsScreen(workspace, hub, busy)
                tab == 3 -> SavingsScreen(workspace, hub, busy)
                budget == null -> OnboardingScreen(busy, model::setup)
                tab == 0 -> WorkspaceToday(budget, workspace, busy,
                    onSwitch = { tab = 2 }, onAdd = { openEditor(EditorRequest()) },
                    onRefund = { openEditor(EditorRequest(type = EntryType.REFUND)) },
                    onEntry = { selectedId = it.id }, onSettings = { route = "budget" },
                    onEntries = { route = "entries" }, onWeekly = { route = "weekly" }, onReview = { route = "inbox" },
                    inboxCount = notifications.inbox.size)
                else -> CalendarScreen(budget, workspace, model, hub, busy,
                    onAdd = { date -> openEditor(EditorRequest(initialDate = date)) },
                    onEntry = { selectedId = it.id })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    when (route) {
        "settings" -> WorkspaceSettings(workspace, hub, busy, budget != null,
            onClose = { route = null }, onBudget = { route = "budget" },
            onCapture = { route = "capture" }, onInbox = { route = "inbox" })
        "budget" -> if (budget != null) SettingsDialog(budget, busy, model, { route = null }, workspaceMode = true)
        "capture" -> CaptureSettings(workspace, hub, busy, onClose = { route = "settings" })
        "inbox" -> PaymentInbox(workspace, hub, busy, onClose = { hub.dismissPaymentReview(); route = null })
        "entries" -> if (budget != null) FullScreenForm("Transactions", busy, { route = null }) {
            TransactionsScreen(budget, busy, { openEditor(EditorRequest()) },
                { openEditor(EditorRequest(type = EntryType.REFUND)) }, { selectedId = it.id }, model::undo, manualOnly = false)
        }
        "weekly" -> if (budget != null) FullScreenForm("Weekly spending", busy, { route = null }) { WeeklyScreen(budget) }
    }
    if (budget != null) {
        editorKind?.let { kind ->
            EntryEditor(EditorRequest(budget.entries.find { it.id == editingId }, EntryType.valueOf(kind),
                budget.entries.find { it.id == refundId }, editorDate?.let(LocalDate::parse)),
                budget, busy, ui.entryReview, model) { model.dismissEntryReview(); editorKind = null }
        }
        budget.entries.find { it.id == selectedId }?.let { entry ->
            EntryDetails(entry, budget, busy, { selectedId = null },
                { selectedId = null; openEditor(EditorRequest(entry, entry.type)) },
                { selectedId = null; openEditor(EditorRequest(type = EntryType.REFUND, refundFor = entry)) },
                { model.delete(entry.id) })
        }
    }
    val error = ui.error ?: operation.error ?: notifications.error
    if (error != null) AlertDialog(onDismissRequest = { model.dismissError(); hub.dismissError() },
        title = { Text("Could not complete the change") }, text = { Text(error) },
        confirmButton = { TextButton(onClick = { model.dismissError(); hub.dismissError() }) { Text("Close") } })
}
