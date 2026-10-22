@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.daybudget.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.daybudget.domain.*
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val FriendlyDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
internal val FriendlyMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
internal fun monthTitle(state: WorkspaceState, budgetId: String, periodId: String): String =
    state.monthNames[MonthKey(budgetId, periodId)] ?: YearMonth.parse(periodId).format(FriendlyMonth)

@Composable
internal fun WorkspaceColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
internal fun LedgerCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun BalanceHero(label: String, amount: Long, detail: String) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = if (dark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
        contentColor = if (dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label)
            Text(Money.format(amount), style = MaterialTheme.typography.displayMedium,
                color = if (dark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun WorkspaceToday(budget: BudgetView, state: WorkspaceState, busy: Boolean,
    onSwitch: () -> Unit, onAdd: () -> Unit, onRefund: () -> Unit, onEntry: (Entry) -> Unit,
    onSettings: () -> Unit, onEntries: () -> Unit, onWeekly: () -> Unit, onReview: () -> Unit, inboxCount: Int) {
    WorkspaceColumn {
        FilledTonalButton(onClick = onSwitch, enabled = !busy) { Text("${state.activeProfile?.name} · switch budget") }
        Text(budget.today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)).uppercase(Locale.ENGLISH),
            style = MaterialTheme.typography.labelLarge)
        BalanceHero("Left to spend today", budget.todayRemaining,
            "${Money.format(budget.startingAllowance)} allowance · ${Money.format(budget.todayExpenses)} spent" +
                if (budget.todayRefunds != 0L) " · ${Money.format(budget.todayRefunds)} refunded" else "")
        if (budget.todayRemaining < 0) NoticeCard("Today's allowance exceeded",
            "Your remaining amount is ${Money.format(budget.todayRemaining)}. Keep recording expenses and refunds.", error = true)
        if (budget.monthlyRemaining < 0) NoticeCard("Monthly budget exceeded",
            "${Money.format(budget.monthlyRemaining)} remains this month.", error = true)
        if (budget.awaitingBudget) NoticeCard("Set this month's budget",
            "Today's provisional allowance is ₹0.00. Set the monthly amount to recalculate from tomorrow.",
            actionLabel = "Set this month's budget", onAction = onSettings)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, enabled = !busy) { Text("+ Expense") }
            FilledTonalButton(onClick = onRefund, enabled = !busy) { Text("− Spending") }
        }
        Text("Reduce spending with a refund, or edit an existing entry in Transactions.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LedgerCard {
            Text(monthTitle(state, state.selectedBudgetId, budget.period.id), style = MaterialTheme.typography.titleLarge)
            AmountRow("Left this month", budget.monthlyRemaining, emphasis = true)
            Text("${budget.daysRemaining} dates left, including today", style = MaterialTheme.typography.bodyMedium)
            Text("Today's allowance stays fixed. Tomorrow uses the latest monthly balance.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Recent activity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        val recent = budget.entries.sortedByDescending { it.createdAt }.take(4)
        if (recent.isEmpty()) LedgerCard { Text("No spending recorded yet."); Text("Add a purchase when you're ready.", style = MaterialTheme.typography.bodyMedium) }
        else LedgerCard {
            recent.forEachIndexed { index, entry ->
                EntrySummary(entry, onEntry)
                if (index < recent.lastIndex) HorizontalDivider()
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEntries, enabled = !busy) { Text("Transactions") }
            TextButton(onClick = onWeekly, enabled = !busy) { Text("Weekly spending") }
        }
        TextButton(onClick = onReview, enabled = !busy) {
            Text("Review detected payments" + if (inboxCount > 0) " ($inboxCount)" else "")
        }
    }
}

@Composable
internal fun EntrySummary(entry: Entry, onEntry: (Entry) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onEntry(entry) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AmountRow(entry.merchant.ifBlank { if (entry.type == EntryType.EXPENSE) "Expense" else "Refund" }, entry.amount,
            prefix = if (entry.type == EntryType.REFUND) "+ " else "− ")
        Text(entry.date.format(FriendlyDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!entry.included) Text("Excluded from budget", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CalendarScreen(budget: BudgetView, state: WorkspaceState, model: BudgetViewModel,
    hub: WorkspaceViewModel, busy: Boolean, onAdd: (LocalDate) -> Unit, onEntry: (Entry) -> Unit) {
    var monthId by rememberSaveable { mutableStateOf(YearMonth.from(budget.today).toString()) }
    var selectedDate by rememberSaveable { mutableStateOf(budget.today.toString()) }
    var calendar by remember { mutableStateOf<BudgetCalendar?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var handled by rememberSaveable { mutableStateOf<String?>(null) }
    val operation by hub.operation.collectAsStateWithLifecycle()
    val month = YearMonth.parse(monthId)
    LaunchedEffect(model, monthId, budget, retry) {
        loadError = false
        if (calendar?.month != month) calendar = null
        try { calendar = model.calendar(month) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadError = true }
    }
    LaunchedEffect(operation.completion?.key) {
        operation.completion?.takeIf { it.key != handled }?.let {
            if (it.action == WorkspaceAction.RENAMED_MONTH) renaming = false
            handled = it.key
        }
    }
    WorkspaceColumn {
        Text("${state.activeProfile?.name} · ${month.format(FriendlyMonth)}", color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { monthId = month.minusMonths(1).toString(); selectedDate = month.minusMonths(1).atDay(1).toString() }) { Text("Previous month") }
            OutlinedButton(onClick = { monthId = month.plusMonths(1).toString(); selectedDate = month.plusMonths(1).atDay(1).toString() }) { Text("Next month") }
        }
        Text(monthTitle(state, state.selectedBudgetId, monthId), style = MaterialTheme.typography.titleLarge)
        Text("Choose a date to see its allowance and recorded spending.", style = MaterialTheme.typography.bodyMedium)
        if (loadError) {
            FormError("This calendar could not be loaded.")
            TextButton(onClick = { retry++ }) { Text("Try again") }
        } else if (calendar == null) CircularProgressIndicator()
        calendar?.let { data ->
            val largeText = LocalDensity.current.fontScale > 1.3f
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val captionStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
            val widestCaption = data.days.maxOf { day ->
                textMeasurer.measure(AnnotatedString(calendarCaption(day)), style = captionStyle,
                    maxLines = 1, softWrap = false).size.width
            }
            LedgerCard {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cellTextWidth = (maxWidth - 12.dp) / 7f - 2.dp
                val useDayList = largeText || widestCaption > with(density) { cellTextWidth.toPx() }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (useDayList) {
                    data.days.forEach { day ->
                        val allowance = day.frozenAllowance ?: day.estimatedAllowance
                        TextButton(onClick = { selectedDate = day.date.toString() }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(day.date.format(FriendlyDate) + if (day.date.toString() == selectedDate) " · selected" else "")
                                Text((if (day.estimatedAllowance != null) "Estimate " else "Allowance ") + (allowance?.let(Money::format) ?: "not recorded"), style = MaterialTheme.typography.bodyMedium)
                                Text("Spent ${Money.format(day.expenses)}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium) }
                    }
                    val cells = List<CalendarDay?>(month.atDay(1).dayOfWeek.value - 1) { null } + data.days
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            (week + List<CalendarDay?>(7 - week.size) { null }).forEach { day ->
                                if (day == null) Spacer(Modifier.weight(1f)) else {
                                    val selected = day.date.toString() == selectedDate
                                    val allowance = day.frozenAllowance ?: day.estimatedAllowance
                                    Surface(Modifier.weight(1f).heightIn(min = 60.dp).clickable { selectedDate = day.date.toString() }
                                        .semantics { contentDescription = "${day.date.format(FriendlyDate)}, ${if (day.estimatedAllowance != null) "estimated allowance" else "allowance"} ${allowance?.let(Money::format) ?: "not recorded"}, spent ${Money.format(day.expenses)}" },
                                        shape = MaterialTheme.shapes.small,
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                                        Column(Modifier.padding(horizontal = 1.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(day.date.dayOfMonth.toString(), fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal)
                                            Text(calendarCaption(day), style = captionStyle, maxLines = 1, softWrap = false)
                                            if (day.recordedCount > 0) Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                }
            }
            Text("Amounts in ₹. Future allowances are estimates and change with spending. Past dates without a saved allowance show —.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            data.days.find { it.date.toString() == selectedDate }?.let { day ->
                LedgerCard {
                    Text(day.date.format(FriendlyDate), style = MaterialTheme.typography.titleLarge)
                    when {
                        day.frozenAllowance != null -> AmountRow("Starting allowance", day.frozenAllowance)
                        day.estimatedAllowance != null -> AmountRow("Estimated allowance", day.estimatedAllowance)
                        else -> Text("Starting allowance was not recorded for this date.")
                    }
                    AmountRow("Recorded expenses", day.expenses)
                    AmountRow("Recorded refunds", day.refunds)
                    day.remaining?.let { AmountRow("Left after included entries", it, emphasis = true) }
                    if (day.recordedCount == 0) Text("No spending recorded. This does not mean nothing was spent.", style = MaterialTheme.typography.bodyMedium)
                    if (day.partialTracking || day.excludedCount > 0) Text("Partial tracking: ${day.excludedCount} recorded entries are excluded from the budget.", style = MaterialTheme.typography.bodyMedium)
                    if (day.isFuture) Text("Estimate assumes no more spending today, then spending each projected daily amount. Future months use your usual budget.", style = MaterialTheme.typography.bodyMedium)
                    else Button(onClick = { onAdd(day.date) }, enabled = !busy) { Text("Add entry for this date") }
                    data.entries.filter { it.date == day.date }.forEach { EntrySummary(it, onEntry) }
                }
            }
            if (data.period != null) TextButton(onClick = { renaming = true }, enabled = !busy) { Text("Rename this month") }
        }
    }
    if (renaming) NameForm("Name this month", "Month name", state.monthNames[MonthKey(state.selectedBudgetId, monthId)].orEmpty(), busy,
        hint = "Leave blank to use ${month.format(FriendlyMonth)}.", onClose = { renaming = false }) {
        hub.renameMonth(state.selectedBudgetId, monthId, it)
    }
}

private fun calendarCaption(day: CalendarDay): String =
    (day.frozenAllowance ?: day.estimatedAllowance)?.let { Money.format(it, false).removeSuffix(".00") } ?: "—"

@Composable
internal fun BudgetsScreen(state: WorkspaceState, hub: WorkspaceViewModel, busy: Boolean) {
    var create by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var handled by rememberSaveable { mutableStateOf<String?>(null) }
    val operation by hub.operation.collectAsStateWithLifecycle()
    LaunchedEffect(operation.completion?.key) {
        operation.completion?.takeIf { it.key != handled }?.let {
            if (it.action == WorkspaceAction.RENAMED_BUDGET) renameId = null
            if (it.action == WorkspaceAction.CREATED_BUDGET) create = false
            handled = it.key
        }
    }
    WorkspaceColumn {
        Text("Keep each plan separate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.profiles.forEach { profile ->
            key(profile.id) {
                val repository = remember(profile.id) { hub.workspace.repository(profile.id) }
                val view by repository.state.collectAsStateWithLifecycle()
                var failed by remember { mutableStateOf(false) }
                LaunchedEffect(repository) {
                    try { repository.refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                }
                LedgerCard {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    if (failed) Text("Balance could not be loaded")
                    else if (view == null) Text("Ready to set up")
                    else {
                        Text(Money.format(view!!.monthlyRemaining), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                        Text("${monthTitle(state, profile.id, view!!.period.id)} · remaining", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.selectedBudgetId == profile.id) Text("Selected budget", color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { renameId = profile.id }, enabled = !busy) { Text("Rename ${profile.name}") }
                        if (state.selectedBudgetId != profile.id) FilledTonalButton(onClick = { hub.selectBudget(profile.id) }, enabled = !busy) { Text("Open ${profile.name}") }
                    }
                }
            }
        }
        Button(onClick = { create = true }, enabled = !busy) { Text("+ New budget") }
        Text("Each budget has its own entries, monthly amount, timezone, and daily allowance.", style = MaterialTheme.typography.bodyMedium)
    }
    if (create) NewBudgetForm(hub, busy) { create = false }
    state.profiles.find { it.id == renameId }?.let { profile ->
        NameForm("Rename budget", "Budget name", profile.name, busy, onClose = { renameId = null }) { hub.renameBudget(profile.id, it) }
    }
}

@Composable
internal fun NameForm(title: String, label: String, initial: String, busy: Boolean, hint: String? = null, onClose: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    FullScreenForm(title, busy, onClose) {
        WorkspaceColumn {
            OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text(label) }, enabled = !busy,
                singleLine = true, modifier = Modifier.fillMaxWidth())
            hint?.let { Text(it) }
            Button(onClick = { onSave(name.trim()) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save name") }
        }
    }
}

@Composable
private fun NewBudgetForm(hub: WorkspaceViewModel, busy: Boolean, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf("") }
    var usual by rememberSaveable { mutableStateOf("") }
    var zone by rememberSaveable { mutableStateOf("Asia/Kolkata") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    FullScreenForm("New budget", busy, onClose) {
        WorkspaceColumn {
            OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("Budget name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            MoneyField(opening, { opening = it }, "Spending budget left until month-end", busy,
                "Earlier spending is already reflected in this amount.")
            MoneyField(usual, { usual = it }, "Usual full-month budget (optional)", busy)
            ZoneField(zone, !busy) { zone = it }
            error?.let { FormError(it) }
            Button(onClick = {
                try {
                    require(name.isNotBlank()) { "Give this budget a name." }
                    hub.createBudget(name.trim(), Money.parse(opening), usual.trim().takeIf { it.isNotEmpty() }?.let(Money::parse), zone)
                } catch (e: IllegalArgumentException) { error = e.message ?: "Check the details." }
                catch (_: ArithmeticException) { error = "That amount is too large." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create budget") }
        }
    }
}

@Composable
internal fun SavingsScreen(state: WorkspaceState, hub: WorkspaceViewModel, busy: Boolean) {
    var action by rememberSaveable { mutableStateOf<String?>(null) }
    var handled by rememberSaveable { mutableStateOf<String?>(null) }
    val operation by hub.operation.collectAsStateWithLifecycle()
    LaunchedEffect(operation.completion?.key) {
        operation.completion?.takeIf { it.key != handled }?.let {
            if (it.action == WorkspaceAction.SAVINGS_ADDED) action = null
            handled = it.key
        }
    }
    WorkspaceColumn {
        BalanceHero("Your savings", state.savingsBalance, "Separate from every spending budget")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { action = "add" }, enabled = !busy) { Text("+ Add savings") }
            FilledTonalButton(onClick = { action = "withdraw" }, enabled = !busy) { Text("− Withdraw") }
        }
        Text("Savings activity", style = MaterialTheme.typography.titleLarge)
        LedgerCard {
            if (state.savingsEntries.isEmpty()) Text("No savings changes recorded yet.")
            state.savingsEntries.forEachIndexed { index, entry ->
                AmountRow(entry.note.ifBlank { if (entry.amount > 0) "Added to savings" else "Withdrawal" }, entry.amount)
                Text(entry.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().format(FriendlyDate), style = MaterialTheme.typography.bodySmall)
                if (index < state.savingsEntries.lastIndex) HorizontalDivider()
            }
        }
        Text("Savings changes do not change any spending budget. Record a budget adjustment separately if you move money between them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    action?.let { mode ->
        var amount by rememberSaveable { mutableStateOf("") }
        var note by rememberSaveable { mutableStateOf("") }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        FullScreenForm(if (mode == "add") "Add savings" else "Withdraw savings", busy, { action = null }) {
            WorkspaceColumn {
                AmountRow("Savings balance", state.savingsBalance, emphasis = true)
                MoneyField(amount, { amount = it }, "Amount", busy)
                OutlinedTextField(note, { if (it.length <= 500) note = it }, label = { Text("Note (optional)") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("This changes only your savings record.")
                error?.let { FormError(it) }
                Button(onClick = {
                    try {
                        val value = Money.parse(amount)
                        require(value > 0) { "Enter an amount greater than zero." }
                        if (mode != "add") require(value <= state.savingsBalance) { "The withdrawal is more than your savings balance." }
                        hub.addSavings(if (mode == "add") value else -value, note.trim())
                    } catch (e: IllegalArgumentException) { error = e.message ?: "Check the amount." }
                    catch (_: ArithmeticException) { error = "That amount is too large." }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (mode == "add") "Save deposit" else "Save withdrawal") }
            }
        }
    }
}
