@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.daybudget.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.daybudget.domain.*
import dev.daybudget.notifications.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun WorkspaceSettings(state: WorkspaceState, hub: WorkspaceViewModel, busy: Boolean, hasBudget: Boolean,
    onClose: () -> Unit, onBudget: () -> Unit, onCapture: () -> Unit, onInbox: () -> Unit) {
    val notifications by hub.notificationState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var spendingAlerts by rememberSaveable { mutableStateOf(notifications.settings.spendingAlertsEnabled) }
    var overspendAlerts by rememberSaveable { mutableStateOf(notifications.settings.overspendAlertsEnabled) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hub.refresh() }
    FullScreenForm("Settings", busy, onClose) {
        WorkspaceColumn {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(state.themeMode == mode, onClick = { hub.setTheme(mode) }, enabled = !busy,
                        label = { Text(when (mode) { ThemeMode.SYSTEM -> "Use system theme"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }) })
                }
            }
            if (hasBudget) {
                Text("Current budget: ${state.activeProfile?.name}", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onBudget, enabled = !busy) { Text("Adjust budget & future months") }
            }
            Text("Spending notifications", style = MaterialTheme.typography.titleLarge)
            LedgerCard {
                ToggleRow("Spending updates + merchant", "A notification after a newly recorded expense.", spendingAlerts, !busy) { spendingAlerts = it }
                ToggleRow("Daily limit alerts", "At most one alert per budget per day when a new expense leaves today's allowance below zero.", overspendAlerts, !busy) { overspendAlerts = it }
                Text("Alerts are quiet from 22:00 to 08:00 in the budget's timezone. Sound follows your phone and notification-channel settings. No alarm or scheduled reminder is created.", style = MaterialTheme.typography.bodyMedium)
            }
            if (!notifications.notificationsAllowed) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(hub.notifications.appNotificationSettingsIntent())
                }, enabled = !busy) { Text("Allow notifications") }
                Text("Android notification permission is separate from payment detection. You can keep both off.", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { hub.configureNotifications(notifications.settings.copy(spendingAlertsEnabled = spendingAlerts, overspendAlertsEnabled = overspendAlerts)) },
                enabled = !busy) { Text("Save notification preferences") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { context.startActivity(hub.notifications.channelSettingsIntent(false)) }) { Text("Spending sound") }
                TextButton(onClick = { context.startActivity(hub.notifications.channelSettingsIntent(true)) }) { Text("Daily-limit sound") }
                TextButton(onClick = { context.startActivity(hub.notifications.appNotificationSettingsIntent()) }) { Text("Android notification settings") }
            }
            Text("UPI detection", style = MaterialTheme.typography.titleLarge)
            Text("Opt in to on-device detection from selected payment apps. Choose one budget for imports. Other budgets stay separate.")
            FilledTonalButton(onClick = onCapture, enabled = !busy) { Text("Set up UPI detection") }
            TextButton(onClick = onInbox, enabled = !busy) { Text("Review detected payments (${notifications.inbox.size})") }
            HorizontalDivider()
            Text("Private on this phone", style = MaterialTheme.typography.titleLarge)
            Text("No accounts, ads, analytics, bank connection, or network requests. Records stay in the app's private storage, with no extra app-level encryption.", style = MaterialTheme.typography.bodyMedium)
            Text("Payment detection is off by default. If you enable it, only selected payment apps are processed on this phone. Raw notification text is not stored. You can review extracted amount, merchant, date, and source before adding a payment.", style = MaterialTheme.typography.bodyMedium)
            Text("There is no export or restore yet. Uninstalling, clearing app data, or losing this phone can lose every budget and savings record. Financial records are excluded from app backup.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { deleting = true }, enabled = !busy) { Text("Delete all local data", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { if (!busy) deleting = false },
        title = { Text("Delete all local data?") },
        text = { Text("This permanently deletes every budget, expense, refund, adjustment, monthly name, savings record, and detected payment. Detection is turned off. There is no undo or backup restore.", modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = hub::clearAll, enabled = !busy) { Text("Delete everything", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text("Keep my data") } })
}

@Composable
internal fun ToggleRow(label: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CaptureSettings(state: WorkspaceState, hub: WorkspaceViewModel, busy: Boolean, onClose: () -> Unit) {
    val notifications by hub.notificationState.collectAsStateWithLifecycle()
    val operation by hub.operation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var enabled by rememberSaveable { mutableStateOf(notifications.settings.captureEnabled) }
    var automatic by rememberSaveable { mutableStateOf(notifications.settings.autoRecord) }
    var budgetId by rememberSaveable { mutableStateOf(notifications.settings.targetBudgetId ?: state.selectedBudgetId) }
    var packages by rememberSaveable { mutableStateOf(notifications.settings.enabledPackages.toList()) }
    var handled by rememberSaveable { mutableStateOf(operation.completion?.key) }
    LaunchedEffect(operation.completion?.key) {
        operation.completion?.takeIf { it.key != handled }?.let {
            if (it.action == WorkspaceAction.PREFERENCES_SAVED) onClose()
            handled = it.key
        }
    }
    FullScreenForm("UPI detection", busy, onClose) {
        WorkspaceColumn {
            Text("Choose what this app may read", style = MaterialTheme.typography.headlineSmall)
            Text("Android notification access can expose notifications from other apps. DayBudget processes only the payment apps you select below, locally on this phone. It does not read SMS or connect to a bank.")
            Text("Only extracted payment details and a hashed payment reference are kept. A notification can be incomplete or wrong: review uncertain payments, and keep recording cash yourself.", style = MaterialTheme.typography.bodyMedium)
            Text(if (notifications.listenerAccessGranted) "Android notification access is granted." else "Android notification access is not granted.", color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = { context.startActivity(hub.notifications.listenerSettingsIntent()) }, enabled = !busy) {
                Text(if (notifications.listenerAccessGranted) "Manage Android notification access" else "Grant Android notification access")
            }
            Text("Returning here does not enable detection. Select your apps and save your choice below.", style = MaterialTheme.typography.bodyMedium)
            Text("Payment apps", style = MaterialTheme.typography.titleLarge)
            SUPPORTED_PAYMENT_APPS.forEach { app ->
                ToggleRow(app.label, "Detect supported payment notifications from ${app.label}.", app.packageName in packages, !busy) { checked ->
                    packages = if (checked) (packages + app.packageName).distinct() else packages - app.packageName
                }
            }
            Text("Budget for detected payments", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.profiles.forEach { profile ->
                    FilterChip(budgetId == profile.id, onClick = { budgetId = profile.id }, enabled = !busy, label = { Text(profile.name) })
                }
            }
            Text("Set up the selected budget before enabling detection. Switching the budget on Today does not change this destination.", style = MaterialTheme.typography.bodyMedium)
            ToggleRow("Enable payment detection", "I agree to process notifications from the selected apps on this phone.", enabled, !busy) { enabled = it }
            ToggleRow("Automatically add clear payments", "Only supported successful payments with a payment reference are added automatically. Other detected payments wait for review.", automatic, !busy && enabled) { automatic = it }
            if (enabled && !notifications.listenerAccessGranted) FormError("Grant Android notification access first, then return and save.")
            Button(onClick = { hub.configureNotifications(notifications.settings.copy(captureEnabled = enabled,
                autoRecord = enabled && automatic, targetBudgetId = budgetId, enabledPackages = packages.toSet())) },
                enabled = !busy && (!enabled || notifications.listenerAccessGranted), modifier = Modifier.fillMaxWidth()) { Text("Save detection settings") }
            Text("Turning detection off stops new imports. Previously recorded expenses stay in their budgets and can be edited or deleted.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun PaymentInbox(state: WorkspaceState, hub: WorkspaceViewModel, busy: Boolean, onClose: () -> Unit) {
    val notifications by hub.notificationState.collectAsStateWithLifecycle()
    val operation by hub.operation.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var handled by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(operation.completion?.key) {
        operation.completion?.takeIf { it.key != handled }?.let {
            if (it.action in listOf(WorkspaceAction.PAYMENT_RECORDED, WorkspaceAction.PAYMENT_IGNORED)) selectedId = null
            handled = it.key
        }
    }
    FullScreenForm("Detected payments", busy, onClose) {
        WorkspaceColumn {
            Text("Check before adding", style = MaterialTheme.typography.headlineSmall)
            Text("These payments need review. Check your entries before adding; ignore anything already recorded, unrelated, or incorrect.")
            if (notifications.inbox.isEmpty()) LedgerCard { Text("No payments waiting for review."); Text("Payment detection is optional and off until you enable it.", style = MaterialTheme.typography.bodyMedium) }
            notifications.inbox.forEach { candidate ->
                LedgerCard {
                    Text(Money.format(candidate.amount), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    Text(candidate.merchant.ifBlank { "Merchant not provided" }, style = MaterialTheme.typography.titleLarge)
                    Text("${paymentAppLabel(candidate.sourcePackage)} · ${candidate.date.format(FriendlyDate)}", style = MaterialTheme.typography.bodyMedium)
                    Text(candidate.reviewReason)
                    Button(onClick = { hub.dismissPaymentReview(); selectedId = candidate.id }, enabled = !busy) { Text("Review payment") }
                    TextButton(onClick = { hub.ignorePayment(candidate.id) }, enabled = !busy) { Text("Ignore payment") }
                }
            }
        }
    }
    notifications.inbox.find { it.id == selectedId }?.let { candidate ->
        key(candidate.id) { PaymentReviewForm(candidate, state, hub, busy) { hub.dismissPaymentReview(); selectedId = null } }
    }
}

private fun paymentAppLabel(packageName: String) = SUPPORTED_PAYMENT_APPS.find { it.packageName == packageName }?.label ?: packageName

@Composable
private fun PaymentReviewForm(candidate: PaymentCandidate, state: WorkspaceState, hub: WorkspaceViewModel, busy: Boolean, onClose: () -> Unit) {
    val operation by hub.operation.collectAsStateWithLifecycle()
    var amount by rememberSaveable { mutableStateOf(amountInput(candidate.amount)) }
    var merchant by rememberSaveable { mutableStateOf(candidate.merchant) }
    var date by rememberSaveable { mutableStateOf(candidate.date.toString()) }
    var budgetId by rememberSaveable { mutableStateOf(candidate.budgetId) }
    var note by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    FullScreenForm("Payment review", busy, onClose) {
        WorkspaceColumn {
            Text("Check before adding", style = MaterialTheme.typography.titleLarge)
            Text("${paymentAppLabel(candidate.sourcePackage)} · ${candidate.postedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}")
            NoticeCard("Review needed", candidate.reviewReason)
            MoneyField(amount, { amount = it }, "Amount", busy)
            OutlinedTextField(merchant, { if (it.length <= 100) merchant = it }, label = { Text("Merchant") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { if (it.length <= 10) date = it }, label = { Text("Date (YYYY-MM-DD)") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Add to budget", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.profiles.forEach { profile ->
                    FilterChip(budgetId == profile.id, onClick = { budgetId = profile.id; hub.dismissPaymentReview() }, enabled = !busy, label = { Text(profile.name) })
                }
            }
            OutlinedTextField(note, { if (it.length <= 500) note = it }, label = { Text("Note (optional)") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("Check that you have not already entered this purchase by hand. A payment without a reference cannot be reliably matched to another notification.", style = MaterialTheme.typography.bodyMedium)
            error?.let { FormError(it) }
            Button(onClick = {
                try {
                    val value = Money.parse(amount)
                    require(value > 0) { "Enter an amount greater than zero." }
                    val parsedDate = try { LocalDate.parse(date) } catch (_: Exception) { throw IllegalArgumentException("Enter a date as YYYY-MM-DD.") }
                    hub.submitPayment(candidate.id, budgetId, EntryDraft(id = candidate.entryId, type = EntryType.EXPENSE,
                        amount = value, date = parsedDate, merchant = merchant.trim(), note = note.trim()))
                } catch (e: IllegalArgumentException) { error = e.message ?: "Check the payment details." }
                catch (_: ArithmeticException) { error = "That amount is too large." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Add to ${state.profiles.find { it.id == budgetId }?.name ?: "budget"}") }
            TextButton(onClick = { hub.ignorePayment(candidate.id) }, enabled = !busy) { Text("Ignore payment") }
        }
    }
    operation.review?.takeIf { it.candidateId == candidate.id }?.let { review ->
        AlertDialog(onDismissRequest = { if (!busy) hub.dismissPaymentReview() }, title = { Text("Review the budget change") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(review.preview.message)
                review.preview.monthlyDeltas.forEach { (month, delta) -> Text("$month remaining: ${Money.format(delta)}") }
                Text("Previously shown starting allowances stay fixed.")
            } },
            confirmButton = { TextButton(onClick = { hub.confirmPayment(review.id) }, enabled = !busy) { Text("Confirm and add") } },
            dismissButton = { TextButton(onClick = hub::dismissPaymentReview, enabled = !busy) { Text("Go back") } })
    }
}
