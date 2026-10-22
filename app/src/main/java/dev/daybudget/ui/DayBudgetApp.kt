@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.daybudget.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.daybudget.domain.BudgetView
import dev.daybudget.domain.Entry
import dev.daybudget.domain.EntryDraft
import dev.daybudget.domain.EntryType
import dev.daybudget.domain.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val FullDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
private val ShortDate = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val MonthName = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

@Composable
fun DayBudgetApp(model: BudgetViewModel) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var editorKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var refundPurchaseId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var handledCompletionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val budget = ui.budget
    val busy = ui.busy || ui.completionPending
    val compactNavigationLabels = LocalDensity.current.fontScale > 1.3f
    fun openEditor(request: EditorRequest) {
        model.dismissEntryReview()
        editingId = request.entry?.id
        refundPurchaseId = request.refundFor?.id
        editorKind = request.type.name
    }
    val editor = editorKind?.let { kind -> EditorRequest(
        entry = budget?.entries?.firstOrNull { it.id == editingId },
        type = EntryType.valueOf(kind),
        refundFor = budget?.entries?.firstOrNull { it.id == refundPurchaseId },
    ) }

    // The ViewModel owns operation results; callbacks must not target a composition
    // that may have been disposed while Room was committing a change.
    LaunchedEffect(ui.completedOperation?.key) {
        ui.completedOperation?.let { result ->
            if (result.key != handledCompletionKey) {
                when (result.kind) {
                    CompletedOperationKind.ENTRY_SAVED -> editorKind = null
                    CompletedOperationKind.ENTRY_DELETED -> selectedId = null
                    CompletedOperationKind.BUDGET_ADJUSTED,
                    CompletedOperationKind.SETTINGS_SAVED -> settingsOpen = false
                    CompletedOperationKind.DATA_CLEARED -> {
                        settingsOpen = false
                        editorKind = null
                        selectedId = null
                        tab = 0
                    }
                    CompletedOperationKind.SETUP, CompletedOperationKind.UNDONE -> Unit
                }
                handledCompletionKey = result.key
            }
            model.acknowledgeCompletion(result.id)
        }
    }

    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                model.refresh()
                val zone = model.ui.value.budget?.period?.zoneId ?: "Asia/Kolkata"
                delay(nextRefreshDelay(Instant.now(), ZoneId.of(zone)))
            }
        }
    }
    LaunchedEffect(model) {
        model.events.collectLatest { event ->
            snackbar.currentSnackbarData?.dismiss()
            val action = snackbar.showSnackbar(
                message = event.message,
                actionLabel = if (event.offerUndo) "Undo" else null,
                withDismissAction = true,
                duration = if (event.offerUndo) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (action == SnackbarResult.ActionPerformed) model.undoIfCurrent(event.revision)
        }
    }

    Scaffold(
        topBar = {
            if (budget != null) TopAppBar(
                title = { Text("DayBudget", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { settingsOpen = true }, enabled = !busy) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (budget != null) NavigationBar {
                listOf("Today", "Transactions", "Weekly").forEachIndexed { index, title ->
                    val label = if (index == 1 && compactNavigationLabels) "Entries" else title
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(when (index) {
                                0 -> Icons.Default.Home
                                1 -> Icons.AutoMirrored.Filled.List
                                else -> Icons.Default.DateRange
                            }, null)
                        },
                        label = {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = if (label != title) Modifier.clearAndSetSemantics {
                                    contentDescription = title
                                } else Modifier)
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !ui.initialized -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Your records could not be opened", style = MaterialTheme.typography.headlineSmall)
                    Text("Try again to load the records saved on this phone.")
                    Button(onClick = model::refresh) { Text("Try again") }
                }
                budget == null -> OnboardingScreen(busy, model::setup)
                tab == 0 -> TodayScreen(budget, busy, { openEditor(EditorRequest()) }, { settingsOpen = true })
                tab == 1 -> TransactionsScreen(budget, busy,
                    onAdd = { openEditor(EditorRequest()) },
                    onRefund = { openEditor(EditorRequest(type = EntryType.REFUND)) },
                    onEntry = { selectedId = it.id }, onUndo = model::undo)
                else -> WeeklyScreen(budget)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    if (settingsOpen && budget != null) SettingsDialog(
        budget, busy, model,
        onClose = { settingsOpen = false },
    )
    editor?.let { request ->
        if (budget != null) EntryEditor(request, budget, busy, ui.entryReview, model) {
            model.dismissEntryReview()
            editorKind = null
        }
    }
    budget?.entries?.firstOrNull { it.id == selectedId }?.let { entry ->
        EntryDetails(entry, budget, busy,
            onClose = { selectedId = null },
            onEdit = { selectedId = null; openEditor(EditorRequest(entry = entry, type = entry.type)) },
            onRefund = { selectedId = null; openEditor(EditorRequest(type = EntryType.REFUND, refundFor = entry)) },
            onDelete = { model.delete(entry.id) },
        )
    }
    ui.error?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text("Could not complete the change") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text("Close") } },
            dismissButton = {
                if (budget == null) TextButton(onClick = { model.dismissError(); model.refresh() }) { Text("Try again") }
            },
        )
    }
}

/** A midnight refresh plus a 30-second clock check; no background alarms or exact timing permission. */
internal fun nextRefreshDelay(now: Instant, zone: ZoneId): Long {
    val nextDate = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    return Duration.between(now, nextDate).toMillis().coerceIn(250L, 30_000L)
}

internal data class EditorRequest(
    val entry: Entry? = null,
    val type: EntryType = EntryType.EXPENSE,
    val refundFor: Entry? = null,
    val initialDate: LocalDate? = null,
)

private data class AdjustmentReview(val amount: Long, val periodId: String, val monthlyRemaining: Long, val startingAllowance: Long)

@Composable
internal fun ContentColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content,
    )
}

@Composable
internal fun OnboardingScreen(busy: Boolean, onSetup: (Long, Long?, String) -> Unit) {
    var opening by rememberSaveable { mutableStateOf("") }
    var usual by rememberSaveable { mutableStateOf("") }
    var zone by rememberSaveable { mutableStateOf("Asia/Kolkata") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    ContentColumn {
        Spacer(Modifier.height(16.dp))
        Text("DayBudget", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text("Make room for\nthe rest of the month.", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
        Text("Record what you spend. Each morning, the budget left is divided across the dates left in the month.")
        Text("Today's starting allowance stays fixed, so each expense reduces what is left for today.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyField(opening, { opening = it }, "Spending budget left until month-end", busy,
            "Include only the spending you want to track here. Earlier spending is already reflected in this amount.")
        MoneyField(usual, { usual = it }, "Usual full-month budget (optional)", busy,
            "Used from next month. Leave blank to decide each month.")
        ZoneField(zone, !busy) { zone = it }
        error?.let { FormError(it) }
        Button(
            onClick = {
                error = null
                try {
                    val amount = Money.parse(opening)
                    val template = usual.trim().takeIf { it.isNotEmpty() }?.let { Money.parse(it) }
                    onSetup(amount, template, zone)
                } catch (problem: IllegalArgumentException) {
                    error = problem.message ?: "Enter a valid amount with up to two decimal places."
                } catch (_: ArithmeticException) { error = "That amount is too large." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(if (busy) "Starting…" else "Start tracking") }
        Text("Private, offline, and manual. No account or notification access needed. This tracks recorded spending, not your bank balance.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TodayScreen(budget: BudgetView, busy: Boolean, onAdd: () -> Unit, onBudget: () -> Unit) {
    ContentColumn {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(budget.today.format(ShortDate), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Left for today", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Text(Money.format(budget.todayRemaining), style = MaterialTheme.typography.displayMedium,
                color = if (budget.todayRemaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (budget.todayRemaining < 0) {
                Text("${absoluteMoney(budget.todayRemaining)} over today's allowance.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (budget.monthlyRemaining < 0) NoticeCard(
            "Monthly budget exceeded by ${absoluteMoney(budget.monthlyRemaining)}",
            "Keep recording your spending. Any refund reduces this shortfall.", error = true,
        )
        if (budget.awaitingBudget) NoticeCard(
            "Set this month's budget",
            "Today's provisional starting allowance is ₹0.00. You can still record expenses. A budget set today changes tomorrow's allowance.",
            actionLabel = "Set this month's budget", onAction = onBudget,
        )
        Button(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add expense")
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Today's calculation", style = MaterialTheme.typography.titleLarge)
            AmountRow("Starting allowance", budget.startingAllowance)
            AmountRow("Recorded expenses", budget.todayExpenses, prefix = "− ")
            AmountRow("Recorded refunds", budget.todayRefunds, prefix = "+ ")
            HorizontalDivider()
            AmountRow("Left for today", budget.todayRemaining, emphasis = true)
            Text("The starting allowance stays fixed today. The next day uses the latest monthly amount.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(budget.today.format(MonthName), style = MaterialTheme.typography.titleLarge)
                AmountRow("Monthly remaining", budget.monthlyRemaining, emphasis = true)
                Text("${budget.daysRemaining} ${if (budget.daysRemaining == 1) "date" else "dates"} left, including today")
                HorizontalDivider()
                AmountRow("Opening budget", budget.period.openingAmount)
                AmountRow("Budget adjustments", budget.monthlyAdjustments)
                AmountRow("Recorded expenses", budget.monthlyExpenses, prefix = "− ")
                AmountRow("Recorded refunds", budget.monthlyRefunds, prefix = "+ ")
            }
        }
        Text("Budget timezone: ${budget.period.zoneId}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AmountRow(label: String, amount: Long, emphasis: Boolean = false, prefix: String = "") {
    val number = prefix + Money.format(amount)
    val textStyle = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (fontScale > 1.3f || maxWidth < 290.dp || number.length > 17) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = textStyle)
                Text(number, style = textStyle, fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Text(label, Modifier.weight(1f), style = textStyle)
            Text(number, style = textStyle,
                fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
                color = if (amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun NoticeCard(title: String, body: String, error: Boolean = false, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(
        containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    )) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun TransactionsScreen(
    budget: BudgetView, busy: Boolean, onAdd: () -> Unit, onRefund: () -> Unit,
    onEntry: (Entry) -> Unit, onUndo: () -> Unit, manualOnly: Boolean = true,
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val entries = budget.entries.filter { filter == 0 || it.type == if (filter == 1) EntryType.EXPENSE else EntryType.REFUND }
        .sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt })
    LazyColumn(
        Modifier.widthIn(max = 640.dp).fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Your recorded spending", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(if (manualOnly) "Expenses and refunds you added by hand." else "Expenses and refunds saved in this budget.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Expenses", "Refunds").forEachIndexed { index, title ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(title) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAdd, enabled = !busy) { Text("Add expense") }
                TextButton(onClick = onRefund, enabled = !busy) { Text("Add refund") }
            }
            if (budget.canUndo) TextButton(onClick = onUndo, enabled = !busy) { Text("Undo last entry change") }
        }
        if (entries.isEmpty()) item {
            NoticeCard("No ${if (filter == 1) "expenses" else if (filter == 2) "refunds" else "entries"} recorded",
                "Add what you spend, including cash purchases. A transfer between your own accounts is not an expense.")
        }
        items(entries, key = { it.id }) { entry ->
            Column(Modifier.fillMaxWidth().clickable { onEntry(entry) }.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                AmountRow(entry.merchant.ifBlank { if (entry.type == EntryType.EXPENSE) "Expense" else "Refund" }, entry.amount,
                    emphasis = true, prefix = if (entry.type == EntryType.REFUND) "+ " else "")
                Text("${entry.date.format(ShortDate)} • ${if (entry.type == EntryType.EXPENSE) "Expense" else "Refund"} • ${entrySourceLabel(entry)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.category.isNotBlank()) Text(entry.category, style = MaterialTheme.typography.bodyMedium)
                if (!entry.included) Text("Excluded from this budget", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(top = 10.dp))
            }
        }
        item {
            Text(if (manualOnly) "Manual mode. No automatic capture or review queue is active." else "Your history includes manual entries and payments you chose to record.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun WeeklyScreen(budget: BudgetView) {
    ContentColumn {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Seven days, as recorded", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Text("${budget.today.minusDays(6).format(ShortDate)} to ${budget.today.format(ShortDate)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AmountRow("Recorded expenses", budget.weeklyExpenses)
            AmountRow("Recorded refunds", budget.weeklyRefunds)
            HorizontalDivider()
            AmountRow("Net recorded spending", budget.weeklyNet, emphasis = true)
        }
        if (budget.weeklyDays.any { it.partialTracking }) NoticeCard(
            "Partial tracking period", "Tracking started on ${budget.settings.activationDate.format(FullDate)}. Earlier entries do not affect these totals.")
        Text("Daily breakdown", style = MaterialTheme.typography.titleLarge)
        budget.weeklyDays.forEach { day ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(day.date.format(ShortDate), fontWeight = FontWeight.SemiBold)
                if (day.recordedCount == 0) {
                    Text(if (day.partialTracking && day.date < budget.settings.activationDate) "Before tracking began" else "No spending recorded",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    AmountRow("Expenses", day.expenses)
                    AmountRow("Refunds", day.refunds)
                    AmountRow("Net recorded", day.net, emphasis = true)
                }
                if (day.partialTracking && day.date == budget.settings.activationDate) Text("Tracking began during this date",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
        Text("An empty date means nothing was recorded; it does not mean nothing was spent. Edits and refunds update this summary.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun absoluteMoney(amount: Long): String = Money.format(amount).replace("−", "").replace("-", "")

@Composable
internal fun MoneyField(value: String, onValue: (String) -> Unit, label: String, busy: Boolean, hint: String? = null, signed: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = { if (it.length <= 23) onValue(it) },
        label = { Text(label) }, prefix = { Text("₹ ") },
        supportingText = if (hint != null) ({ Text(hint) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal),
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun FormError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun ZoneField(value: String, enabled: Boolean, onValue: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Budget timezone", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(value.replace('_', ' '), modifier = Modifier.padding(vertical = 6.dp))
        }
        Text("Budget dates use this timezone even when your phone's timezone changes.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (choosing) {
        var search by remember { mutableStateOf("") }
        val zones = remember { (listOf("Asia/Kolkata", "UTC") + ZoneId.getAvailableZoneIds().sorted()).distinct() }
        FullScreenForm("Choose timezone", onClose = { choosing = false }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Search city or timezone") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f)) {
                    items(zones.filter { it.contains(search.replace(' ', '_'), ignoreCase = true) }) { zone ->
                        TextButton(onClick = { onValue(zone); choosing = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(zone.replace('_', ' '), Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("DEPRECATION") // Keep pre-29 system navigation backgrounds aligned with the dialog's selected theme.
internal fun FullScreenForm(title: String, busy: Boolean = false, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        // Opt into resize/insets on older Android too; unspecified input mode can pan
        // a full-screen dialog's header and Close action outside the visible window.
        decorFitsSystemWindows = false,
    )) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val lightNavigationBar = MaterialTheme.colorScheme.background.luminance() > 0.5f
        val lightStatusBar = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        val navigationBarColor = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            window?.let {
                if (Build.VERSION.SDK_INT < 29) it.navigationBarColor = navigationBarColor
                WindowInsetsControllerCompat(it, view).apply {
                    isAppearanceLightNavigationBars = lightNavigationBar
                    isAppearanceLightStatusBars = lightStatusBar
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(title) }, navigationIcon = {
                    IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.Default.Close, "Close") }
                })
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                    contentAlignment = Alignment.TopCenter) { content() }
            }
        }
    }
}

@Composable
internal fun EntryEditor(request: EditorRequest, budget: BudgetView, busy: Boolean, review: PendingEntryReview?, model: BudgetViewModel, onClose: () -> Unit) {
    val entry = request.entry
    val isRefund = request.type == EntryType.REFUND
    val title = if (entry != null) (if (isRefund) "Edit refund" else "Edit expense") else (if (isRefund) "Add refund" else "Add expense")
    val id = rememberSaveable { entry?.id ?: UUID.randomUUID().toString() }
    var amount by rememberSaveable { mutableStateOf(entry?.amount?.let { amountInput(it) } ?: "") }
    var dateString by rememberSaveable { mutableStateOf((entry?.date ?: request.initialDate ?: budget.today).toString()) }
    val date = LocalDate.parse(dateString)
    var merchant by rememberSaveable { mutableStateOf(entry?.merchant ?: request.refundFor?.merchant.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(entry?.category ?: request.refundFor?.category.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(entry?.note.orEmpty()) }
    var linkedId by rememberSaveable { mutableStateOf(entry?.linkedExpenseId ?: request.refundFor?.id) }
    var choosePurchase by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(entry != null || request.refundFor != null) }
    var datePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    FullScreenForm(title, busy, onClose) {
        ContentColumn {
            if (isRefund) Text(request.refundFor?.let {
                "Refund for ${it.merchant.ifBlank { "expense" }} of ${Money.format(it.amount)} on ${it.date.format(ShortDate)}."
            } ?: "Record money returned for a purchase. Salary, incoming transfers, and moving your own money are not refunds.")
            if (isRefund) {
                val purchase = budget.entries.firstOrNull { it.id == linkedId }
                if (purchase != null) {
                    Text("Linked purchase: ${purchase.merchant.ifBlank { "Expense" }} of ${Money.format(purchase.amount)}, ${purchase.date.format(ShortDate)}.",
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { linkedId = null }, enabled = !busy) { Text("Remove purchase link") }
                } else TextButton(onClick = { choosePurchase = true }, enabled = !busy) { Text("Link original purchase (optional)") }
            }
            MoneyField(amount, { amount = it }, "Amount", busy)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isRefund) "Date received" else "Date spent", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { datePicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(8.dp)); Text(date.format(FullDate))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = date == budget.today, onClick = { dateString = budget.today.toString() },
                        enabled = !busy, label = { Text("Today") })
                    FilterChip(selected = date == budget.today.minusDays(1), onClick = { dateString = budget.today.minusDays(1).toString() },
                        enabled = !busy, label = { Text("Yesterday") })
                }
            }
            if (!details) TextButton(onClick = { details = true }, enabled = !busy) { Text("Add merchant, category or note") }
            if (details) {
                OutlinedTextField(merchant, { if (it.length <= 120) merchant = it }, label = { Text("Merchant (optional)") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { if (it.length <= 60) category = it }, label = { Text("Category (optional)") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { if (it.length <= 500) note = it }, label = { Text("Note (optional)") },
                    enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            if (date < budget.settings.activationDate) NoticeCard("Before tracking began",
                "This entry is kept in your history. It is excluded from your budget because the opening amount already reflects earlier spending.")
            else if (date == budget.settings.activationDate && (entry == null || entry.date != date)) Text(
                if (date == budget.today) "Only add spending after setup. Earlier spending today is already reflected in your opening budget."
                else "An entry newly added for the setup date is excluded because its time is unknown. The opening budget already reflects earlier spending.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (date != budget.today) Text("This changes the recorded spending for ${date.format(ShortDate)}. Today's starting allowance stays fixed.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { FormError(it) }
            Button(onClick = {
                error = null
                try {
                    val paise = Money.parse(amount)
                    if (paise <= 0) throw IllegalArgumentException("Enter an amount greater than zero.")
                    val draft = EntryDraft(id, request.type, paise, date, merchant.trim(), category.trim(), note.trim(),
                        linkedId)
                    model.submit(draft)
                } catch (problem: IllegalArgumentException) { error = problem.message ?: "Check the amount." }
                catch (_: ArithmeticException) { error = "That amount is too large." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (busy) "Saving…" else if (entry != null) "Save changes" else if (isRefund) "Save refund" else "Save expense")
            }
            if (!isRefund) Text("For cash, record the purchase. Do not count an ATM withdrawal as well.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (datePicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= budget.today
                override fun isSelectableYear(year: Int): Boolean = year <= budget.today.year
            },
        )
        DatePickerDialog(onDismissRequest = { datePicker = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { dateString = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                datePicker = false
            }, enabled = picker.selectedDateMillis != null) { Text("Choose date") } },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
    review?.takeIf { it.draft.id == id }?.let { pending ->
        val change = pending.preview
        AlertDialog(onDismissRequest = { if (!busy) model.dismissEntryReview() },
            title = { Text("Review the budget change") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(change.message)
                    change.monthlyDeltas.forEach { (month, delta) ->
                        Text("$month remaining: ${if (delta > 0) "+" else ""}${Money.format(delta)}")
                    }
                    Text("Previously shown starting allowances stay fixed.")
                }
            },
            confirmButton = { TextButton(onClick = { model.confirmEntryReview(pending.operationId) }, enabled = !busy) { Text("Confirm and save") } },
            dismissButton = { TextButton(onClick = model::dismissEntryReview, enabled = !busy) { Text("Go back") } },
        )
    }
    if (choosePurchase) FullScreenForm("Link original purchase", busy, onClose = { choosePurchase = false }) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Choose the expense this refund relates to. Refunds count on the date received.") }
            val purchases = budget.entries.filter { it.type == EntryType.EXPENSE }.sortedByDescending { it.date }
            if (purchases.isEmpty()) item { Text("No purchases recorded yet. You can save this refund without a link.") }
            items(purchases, key = { it.id }) { purchase ->
                Column(Modifier.fillMaxWidth().clickable { linkedId = purchase.id; choosePurchase = false }.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AmountRow(purchase.merchant.ifBlank { "Expense" }, purchase.amount)
                    Text(purchase.date.format(FullDate), style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                }
            }
        }
    }
}

internal fun amountInput(paise: Long): String = "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

private fun entrySourceLabel(entry: Entry): String = when (entry.source) {
    "NOTIFICATION_REVIEWED" -> "Reviewed payment"
    "NOTIFICATION" -> "Detected payment"
    else -> "Manual"
}

@Composable
internal fun EntryDetails(entry: Entry, budget: BudgetView, busy: Boolean, onClose: () -> Unit,
                         onEdit: () -> Unit, onRefund: () -> Unit, onDelete: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    val linkedRefunds = budget.entries.filter { it.linkedExpenseId == entry.id }
    AlertDialog(onDismissRequest = { if (!busy) onClose() },
        title = { Text(entry.merchant.ifBlank { if (entry.type == EntryType.REFUND) "Refund" else "Expense" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Money.format(entry.amount), style = MaterialTheme.typography.headlineSmall)
                Text(entry.date.format(FullDate))
                Text("${if (entry.type == EntryType.REFUND) "Refund" else "Expense"} · ${entrySourceLabel(entry)}")
                if (entry.category.isNotBlank()) Text("Category: ${entry.category}")
                if (entry.note.isNotBlank()) Text(entry.note)
                if (!entry.included) Text("On or before the setup date; excluded from this budget.")
                if (entry.linkedExpenseId != null) Text("Linked to the original expense.")
                if (linkedRefunds.isNotEmpty()) Text("${linkedRefunds.size} linked ${if (linkedRefunds.size == 1) "refund" else "refunds"}.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
                    TextButton(onClick = { deleting = true }, enabled = !busy) { Text("Delete") }
                }
                if (entry.type == EntryType.EXPENSE) OutlinedButton(onClick = onRefund, enabled = !busy) { Text("Record refund") }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Close") } },
    )
    if (deleting) AlertDialog(onDismissRequest = { if (!busy) deleting = false },
        title = { Text("Delete this ${if (entry.type == EntryType.EXPENSE) "expense" else "refund"}?") },
        text = { Text(if (entry.included) "This will ${if (entry.type == EntryType.EXPENSE) "increase" else "decrease"} ${entry.date.format(MonthName)}'s remaining budget by ${Money.format(entry.amount)}. Starting allowances stay fixed. You can undo this change."
            else "This excluded entry will be removed from your history. Budget amounts stay the same. You can undo this change.",
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDelete, enabled = !busy) { Text("Delete entry") } },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text("Keep entry") } },
    )
}

@Composable
internal fun SettingsDialog(budget: BudgetView, busy: Boolean, model: BudgetViewModel, onClose: () -> Unit, workspaceMode: Boolean = false) {
    var usual by rememberSaveable { mutableStateOf(budget.settings.usualMonthlyBudget?.let(::amountInput).orEmpty()) }
    var zone by rememberSaveable { mutableStateOf(budget.settings.futureZoneId ?: budget.settings.zoneId) }
    var amount by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var adjustmentPreview by remember { mutableStateOf<AdjustmentReview?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    FullScreenForm("Settings", busy, onClose) {
        ContentColumn {
            Text(if (budget.awaitingBudget) "Set this month's budget" else "Adjust this month's budget",
                style = MaterialTheme.typography.headlineSmall)
            AmountRow("Monthly remaining", budget.monthlyRemaining, emphasis = true)
            Text("Add or remove spending money with an explicit adjustment. Today's starting allowance stays fixed; tomorrow uses the updated amount.")
            MoneyField(amount, { amount = it }, "Adjustment (+ or −)", busy,
                "For example, +500 adds ₹500.00; -500 removes ₹500.00.", signed = true)
            OutlinedTextField(reason, { if (it.length <= 200) reason = it }, label = { Text("Reason for adjustment") },
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                error = null
                try {
                    if (reason.isBlank()) throw IllegalArgumentException("Add a reason for the adjustment.")
                    adjustmentPreview = AdjustmentReview(Money.parse(amount, allowNegative = true), budget.period.id,
                        budget.monthlyRemaining, budget.startingAllowance)
                } catch (problem: IllegalArgumentException) { error = problem.message ?: "Check the adjustment." }
                catch (_: ArithmeticException) { error = "That amount is too large." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Review adjustment") }
            if (budget.adjustments.isNotEmpty()) TextButton(onClick = { historyOpen = true }, enabled = !busy) {
                Text("View adjustment history")
            }
            HorizontalDivider()
            Text("Future months", style = MaterialTheme.typography.headlineSmall)
            MoneyField(usual, { usual = it }, "Usual full-month budget", busy,
                "Starts each new month without carrying over a surplus or deficit. Leave blank to set each month yourself.")
            ZoneField(zone, !busy) { zone = it }
            Text("Timezone changes begin next month. Existing dates and daily allowances are preserved.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            budget.settings.futureZoneEffectiveMonth?.let {
                Text("Scheduled timezone: ${budget.settings.futureZoneId} from $it", style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { FormError(it) }
            Button(onClick = {
                error = null
                try {
                    val template = usual.trim().takeIf { it.isNotEmpty() }?.let { Money.parse(it) }
                    model.configure(template, zone)
                } catch (problem: IllegalArgumentException) { error = problem.message ?: "Check the monthly amount." }
                catch (_: ArithmeticException) { error = "That amount is too large." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save future-month settings") }
            HorizontalDivider()
            if (!workspaceMode) {
            Text("Private on this phone", style = MaterialTheme.typography.headlineSmall)
            Text("Your records stay in this app's private storage. There are no accounts, ads, analytics, bank connections, or network requests. The database has no extra app-level encryption.",
                style = MaterialTheme.typography.bodyMedium)
            Text("Notification capture and reminders are not enabled in this version. Every entry is manual.",
                style = MaterialTheme.typography.bodyMedium)
            Text("There is no export or restore yet. Uninstalling, clearing app data, or losing this phone can lose all records. App backups exclude financial data; device-transfer behavior can vary by manufacturer.",
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { deleteConfirm = true }, enabled = !busy) {
                Text("Delete all local data", color = MaterialTheme.colorScheme.error)
            }
            }
        }
    }
    adjustmentPreview?.let { review ->
        val adjustment = review.amount
        val after = runCatching { Math.addExact(review.monthlyRemaining, adjustment) }.getOrNull()
        val landscapeReview = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        AlertDialog(onDismissRequest = { if (!busy) adjustmentPreview = null },
            modifier = if (landscapeReview) Modifier.padding(horizontal = 24.dp)
                .widthIn(max = 560.dp).fillMaxWidth() else Modifier,
            properties = DialogProperties(usePlatformDefaultWidth = !landscapeReview),
            title = { Text("Confirm budget adjustment") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Budget month: ${review.periodId}")
                    AmountRow("Adjustment", adjustment)
                    if (after != null) AmountRow("Monthly remaining after", after, emphasis = true)
                    else FormError("This adjustment exceeds the supported money range.")
                    Text("Reason: ${reason.trim()}")
                    Text("Today's starting allowance remains ${Money.format(review.startingAllowance)}. This affects tomorrow's allowance.")
                }
            },
            confirmButton = { TextButton(onClick = {
                model.adjust(adjustment, reason.trim(), review.periodId)
            }, enabled = !busy && after != null) { Text("Apply adjustment") } },
            dismissButton = { TextButton(onClick = { adjustmentPreview = null }, enabled = !busy) { Text("Cancel") } },
        )
    }
    if (deleteConfirm) AlertDialog(onDismissRequest = { if (!busy) deleteConfirm = false },
        title = { Text("Delete all local data?") },
        text = { Text("This permanently deletes every expense, refund, budget, adjustment, and setting on this phone. There is no undo or backup restore.",
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = model::clear, enabled = !busy) {
            Text("Delete everything", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }, enabled = !busy) { Text("Keep my data") } },
    )
    if (historyOpen) FullScreenForm("Budget adjustments", onClose = { historyOpen = false }) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Text("Adjustments change the monthly amount. Earlier daily allowances stay fixed.") }
            items(budget.adjustments, key = { it.id }) { adjustment ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountRow(adjustment.periodId, adjustment.amount, emphasis = true)
                    Text(adjustment.reason)
                    Text(adjustment.createdAt.atZone(ZoneId.of(budget.period.zoneId)).toLocalDate().format(FullDate),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                }
            }
        }
    }
}
