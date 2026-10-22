package dev.daybudget.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.daybudget.R
import dev.daybudget.data.BudgetWorkspace
import dev.daybudget.domain.BudgetException
import dev.daybudget.domain.BudgetView
import dev.daybudget.domain.Entry
import dev.daybudget.domain.EntryDraft
import dev.daybudget.domain.EntryPreview
import dev.daybudget.domain.EntryType
import dev.daybudget.domain.Money
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One application-scoped controller serializes detection, durable decisions, and alerts. */
class NotificationsController internal constructor(
    context: Context,
    private val workspace: BudgetWorkspace,
    private val clock: Clock,
    storageName: String,
    private val listenerAccessCheck: () -> Boolean,
) {
    constructor(context: Context, workspace: BudgetWorkspace, clock: Clock = Clock.systemUTC(),
                storageName: String = "payment-notifications-v1") : this(context, workspace, clock, storageName,
        { NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext).contains(context.packageName) })

    private val context = context.applicationContext
    private val store = NotificationCaptureStore(this.context, storageName)
    private var storageError: String? = null
    private var data = try { store.read() } catch (error: BudgetException) {
        storageError = error.message
        CaptureData()
    }
    private val controllerJob = SupervisorJob()
    private val scope = CoroutineScope(controllerJob + Dispatchers.IO)
    private val gate = Mutex()
    private val captures = Channel<CaptureRequest>(64)
    private val mutableState = MutableStateFlow(NotificationState(settings = data.settings, error = storageError))
    val state: StateFlow<NotificationState> = mutableState.asStateFlow()

    init {
        createChannels()
        publish()
        scope.launch {
            for (request in captures) {
                try { gate.withLock { capture(request) } }
                catch (error: Exception) {
                    request.completion.completeExceptionally(error)
                    publish("A payment could not be processed. Check your entries or add it manually.")
                }
                finally { request.completion.complete(Unit) }
            }
        }
        scope.launch { runCatching { refresh() } }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        gate.withLock {
            workspace.initialize()
            if (storageError == null) {
                data.captures.filter { it.candidate.id !in data.decisions && it.autoRequested }.forEach { record ->
                    if (data.settings.captureEnabled && data.settings.autoRecord &&
                        record.generation == data.generation && listenerAccessGranted()) {
                        importAutomatically(record)
                    }
                }
            }
            publish(storageError)
        }
    }

    suspend fun configure(settings: NotificationSettings) = withContext(Dispatchers.IO) {
        gate.withLock {
            requireStorage()
            workspace.initialize()
            val supported = SUPPORTED_PAYMENT_APPS.map { it.packageName }.toSet()
            checkInput(settings.enabledPackages.all { it in supported }, "Choose a supported payment app.")
            if (settings.captureEnabled) {
                checkInput(listenerAccessGranted(), "Grant notification access in Android settings, then enable detection.")
                checkInput(settings.enabledPackages.isNotEmpty(), "Choose at least one payment app.")
                val profile = workspace.state.value.profiles.find { it.id == settings.targetBudgetId }
                    ?: throw BudgetException("Choose a budget for detected payments.")
                checkInput(workspace.repository(profile.id).refresh() != null, "Set up the destination budget first.")
            }
            val old = data.settings
            val captureChanged = old.captureEnabled != settings.captureEnabled || old.autoRecord != settings.autoRecord ||
                old.enabledPackages != settings.enabledPackages || old.targetBudgetId != settings.targetBudgetId
            commit(data.copy(
                settings = settings,
                captureSinceMillis = if (captureChanged) clock.millis() else data.captureSinceMillis,
                generation = if (captureChanged) data.generation + 1 else data.generation,
            ))
            publish()
        }
    }

    suspend fun ignoreCandidate(id: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            requireStorage()
            checkInput(data.captures.any { it.candidate.id == id }, "This payment is no longer available.")
            if (id !in data.decisions) commit(data.copy(decisions = data.decisions + (id to "IGNORED")))
            publish()
        }
    }

    suspend fun acceptCandidate(
        id: String,
        budgetId: String,
        draft: EntryDraft,
        acceptBoundaryChange: Boolean = false,
        expectedPreview: EntryPreview? = null,
    ): BudgetView = withContext(Dispatchers.IO) {
        gate.withLock {
            requireStorage()
            workspace.initialize()
            val candidate = data.captures.find { it.candidate.id == id }?.candidate
                ?: throw BudgetException("This payment is no longer available.")
            checkInput(id !in data.decisions, "This payment has already been handled.")
            checkInput(workspace.state.value.profiles.any { it.id == budgetId }, "Choose an existing budget.")
            checkInput(draft.type == EntryType.EXPENSE, "Detected outgoing payments must be expenses.")
            val destination = workspace.repository(budgetId).refresh()
                ?: throw BudgetException("Set up the destination budget first.")
            if (draft.date == destination.today && candidate.postedAt < destination.settings.activatedAt &&
                candidate.postedAt.atZone(ZoneId.of(destination.period.zoneId)).toLocalDate() == draft.date) {
                throw BudgetException("This payment happened before this budget was set up. Choose its original budget or ignore it.")
            }
            assignImport(id, budgetId)
            val result = workspace.repository(budgetId).importIfAbsent(
                draft = draft.copy(id = candidate.entryId), source = "NOTIFICATION_REVIEWED",
                occurredAt = candidate.postedAt, reviewed = true,
                acceptBoundaryChange = acceptBoundaryChange, expectedPreview = expectedPreview,
            )
            commit(data.copy(decisions = data.decisions + (id to "IMPORTED:$budgetId")))
            if (result.inserted) result.entry?.let { alertCommitted(budgetId, profileName(budgetId), result.before, result.after, it) }
            publish()
            result.after
        }
    }

    /** Used only by the app's explicit Delete everything flow, alongside the workspace reset. */
    suspend fun clearData() = withContext(Dispatchers.IO) {
        gate.withLock {
            val reset = CaptureData(generation = data.generation + 1)
            store.write(reset)
            data = reset
            publish()
            store.removeIdentityKey()
            storageError = null
            context.getSystemService(NotificationManager::class.java).cancelAll()
            publish()
        }
    }

    fun dismissError() { mutableState.value = mutableState.value.copy(error = storageError) }
    fun close() { captures.close(); scope.cancel() }
    internal suspend fun closeAndJoin() { close(); controllerJob.join() }
    internal fun hashIdentity(value: String): String = store.hashIdentity(value)
    internal fun reportDetectionFailure() {
        mutableState.value = state.value.copy(error = "Payment detection could not read this payment safely. Check it and add it manually if needed.")
    }

    fun listenerSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    fun appNotificationSettingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    fun channelSettingsIntent(overspend: Boolean): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, if (overspend) OVERSPEND_CHANNEL else SPENDING_CHANNEL)

    fun acceptsPackage(packageName: String): Boolean = state.value.settings.let {
        it.captureEnabled && packageName in it.enabledPackages && SUPPORTED_PAYMENT_APPS.any { app -> app.packageName == packageName }
    }

    /** Called after parsing; the channel contains extracted fields only, never notification text. */
    internal fun receive(payment: ParsedPayment): Deferred<Unit>? {
        if (!acceptsPackage(payment.sourcePackage)) return null
        val request = CaptureRequest(payment, state.value.settings)
        if (!captures.trySend(request).isSuccess) {
            mutableState.value = state.value.copy(error = "Payment detection is busy. Check this payment and add it manually if needed.")
            request.completion.complete(Unit)
        }
        return request.completion
    }

    fun notifyCommittedExpense(budgetId: String, budgetName: String, before: BudgetView, after: BudgetView, entry: Entry) {
        scope.launch {
            try { gate.withLock { alertCommitted(budgetId, budgetName, before, after, entry) } }
            catch (_: Exception) { publish("The expense was saved, but its notification could not be shown.") }
        }
    }

    private suspend fun capture(request: CaptureRequest) {
        requireStorage()
        if (request.settings != data.settings || !data.settings.captureEnabled || !listenerAccessGranted()) return
        val payment = request.payment
        if (payment.postedAt.toEpochMilli() < data.captureSinceMillis || payment.postedAt > clock.instant().plusSeconds(60)) return
        if (data.captures.any { it.candidate.id == payment.captureId }) return
        workspace.initialize()
        val budgetId = data.settings.targetBudgetId ?: return
        if (workspace.state.value.profiles.none { it.id == budgetId }) {
            publish("The import budget was removed. Choose another budget in notification settings.")
            return
        }
        val view = workspace.repository(budgetId).refresh() ?: return
        if (data.captures.count { it.candidate.id !in data.decisions } >= 500) {
            publish("Review or ignore pending payments before detecting more. Add this payment manually if needed.")
            return
        }
        val date = payment.postedAt.atZone(ZoneId.of(view.period.zoneId)).toLocalDate()
        val record = CaptureRecord(PaymentCandidate(
            payment.captureId, payment.amount, payment.merchant, payment.sourcePackage, payment.postedAt,
            date, budgetId, payment.referenceHash, payment.reviewReason,
        ), data.settings.autoRecord && payment.highConfidence, data.generation)
        commit(data.copy(captures = data.captures + record))
        if (record.autoRequested) importAutomatically(record)
        publish()
    }

    private suspend fun importAutomatically(record: CaptureRecord) {
        val candidate = record.candidate
        if (workspace.state.value.profiles.none { it.id == candidate.budgetId }) return
        try {
            assignImport(candidate.id, candidate.budgetId)
            val result = workspace.repository(candidate.budgetId).importIfAbsent(
                draft = EntryDraft(id = candidate.entryId, amount = candidate.amount, date = candidate.date,
                    merchant = candidate.merchant, category = "UPI"),
                source = "NOTIFICATION", occurredAt = candidate.postedAt,
            )
            commit(data.copy(decisions = data.decisions + (candidate.id to "IMPORTED:${candidate.budgetId}")))
            if (result.inserted) result.entry?.let {
                alertCommitted(candidate.budgetId, profileName(candidate.budgetId), result.before, result.after, it)
            }
        } catch (_: Exception) {
            // A rejected import remains visible in the review inbox, with immutable original facts.
            publish("A detected payment needs review before it can be added.")
        }
    }

    private fun alertCommitted(budgetId: String, budgetName: String, before: BudgetView, after: BudgetView, entry: Entry) {
        requireStorage()
        if (!ExpenseAlertPolicy.isNewSpending(before, after, entry)) return
        if (ExpenseAlertPolicy.isQuietHour(clock.instant().atZone(ZoneId.of(after.period.zoneId)).hour)) return
        val dailyReceipt = ExpenseAlertPolicy.dailyReceipt(budgetId, after.today)
        val overspend = data.settings.overspendAlertsEnabled && after.todayRemaining < 0 && dailyReceipt !in data.alertReceipts
        if (!overspend && !data.settings.spendingAlertsEnabled) return
        if (!notificationsAllowed()) return
        val receipt = ExpenseAlertPolicy.receipt(budgetId, entry)
        if (receipt in data.alertReceipts) return
        // Persist before posting: crash recovery never sounds an already attempted alert again.
        commit(data.copy(alertReceipts = data.alertReceipts + receipt + if (overspend) setOf(dailyReceipt) else emptySet()))
        postExpense(receipt, budgetName, after, entry, overspend)
    }

    @SuppressLint("MissingPermission") // Runtime consent and global block checked immediately before posting.
    private fun postExpense(receipt: String, budgetName: String, after: BudgetView, entry: Entry, overspend: Boolean) {
        if (!notificationsAllowed()) return
        val channel = if (overspend) OVERSPEND_CHANNEL else SPENDING_CHANNEL
        val title = if (overspend) "$budgetName: daily budget exceeded" else "$budgetName: expense saved"
        val merchant = entry.merchant.ifBlank { "Expense" }
        val text = "$merchant · ${Money.format(entry.amount)} · Today remaining ${Money.format(after.todayRemaining)}"
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val publicNotification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_day_budget)
            .setContentTitle("DayBudget update").setContentText("Open DayBudget to view your budget.").build()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_day_budget).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification).setAutoCancel(true).setOnlyAlertOnce(true).build()
        NotificationManagerCompat.from(context).notify(receipt, 1, notification)
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val normal = NotificationChannel(SPENDING_CHANNEL, "Saved expenses", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Merchant and amount after a new expense is saved"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        val overspend = NotificationChannel(OVERSPEND_CHANNEL, "Daily overspending", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "At most one alert per budget/day after overspending; quiet between 22:00 and 08:00"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            // Alarm tone, notification audio usage: no alarm-volume or Do Not Disturb bypass.
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(normal, overspend))
    }

    private fun listenerAccessGranted(): Boolean = listenerAccessCheck()
    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun profileName(id: String) = workspace.state.value.profiles.find { it.id == id }?.name ?: "Budget"
    private fun assignImport(id: String, budgetId: String) {
        val assigned = data.assignments[id]
        checkInput(assigned == null || assigned == budgetId,
            "An import was already attempted in ${profileName(assigned.orEmpty())}. Use that budget to retry or ignore this payment.")
        // If the process dies between SQLite commit and the local receipt, a retry cannot
        // route the same payment to another budget and bypass that ledger's receipt.
        if (assigned == null) commit(data.copy(assignments = data.assignments + (id to budgetId)))
    }
    private fun requireStorage() { storageError?.let { throw BudgetException(it) } }
    private fun checkInput(condition: Boolean, message: String) { if (!condition) throw BudgetException(message) }
    private fun commit(next: CaptureData) { store.write(next); data = next }
    private fun publish(error: String? = null) {
        mutableState.value = NotificationState(data.settings,
            data.captures.filter { it.candidate.id !in data.decisions }.map { record ->
                data.assignments[record.candidate.id]?.let { assigned -> record.candidate.copy(
                    budgetId = assigned,
                    reviewReason = "An import was attempted in this budget. Check its entries, then retry here or ignore this payment.",
                ) } ?: record.candidate
            }.sortedByDescending { it.postedAt },
            listenerAccessGranted(), notificationsAllowed(), error ?: storageError)
    }

    private data class CaptureRequest(val payment: ParsedPayment, val settings: NotificationSettings,
                                      val completion: CompletableDeferred<Unit> = CompletableDeferred())

    companion object {
        const val SPENDING_CHANNEL = "saved-expenses-v1"
        const val OVERSPEND_CHANNEL = "daily-overspending-v1"
    }
}
