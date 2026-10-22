package dev.daybudget.notifications

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybudget.data.BudgetWorkspace
import dev.daybudget.domain.Command
import dev.daybudget.domain.EntryDraft
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic SBN callback coverage with simulated listener consent; no third-party app is read. */
@RunWith(AndroidJUnit4::class)
class NotificationImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefix = "ni-${UUID.randomUUID().toString().take(8)}"
    private val at = Instant.parse("2026-10-22T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val workspace = BudgetWorkspace(context, clock, "$prefix-catalog.db", "$prefix-legacy.db", "$prefix-profile-")
    private val prefs = "$prefix-capture"
    private var granted = true
    private lateinit var controller: NotificationsController
    private val app = "com.phonepe.app"
    private val settings = NotificationSettings(true, true, "legacy", setOf(app))

    @Before fun prepare() = runBlocking {
        workspace.initialize()
        workspace.repository("legacy").setup(100_000, null, "UTC")
        controller = newController()
        controller.refresh()
    }

    @After fun cleanUp() {
        if (::controller.isInitialized) runBlocking { controller.closeAndJoin() }
        workspace.close()
        context.deleteSharedPreferences(prefs)
        context.databaseList().filter { it.startsWith(prefix) }.forEach(context::deleteDatabase)
    }

    private fun newController() = NotificationsController(context, workspace, clock, prefs) { granted }
    @Suppress("DEPRECATION")
    private fun notification(body: String, source: String = app, id: Int = 1, time: Instant = at,
                             summary: Boolean = false): StatusBarNotification {
        val notification = Notification.Builder(context, "synthetic-test")
            .setContentTitle("Payment successful").setContentText(body).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setGroup("synthetic").setGroupSummary(summary).build()
        return StatusBarNotification(source, source, id, "synthetic-$id", Process.myUid(), 0, 0,
            notification, Process.myUserHandle(), time.toEpochMilli())
    }

    private suspend fun post(notification: StatusBarNotification) {
        withTimeout(10_000) { PaymentNotificationListener.processPosted(controller, notification)?.await() }
    }

    @Test fun exactCallbackImportsOnceAndNeverOverwritesAnEditOrResurrectsADeletion() = runBlocking {
        controller.configure(settings)
        val posted = notification("Paid ₹200 to Tea Shop UTR 123456789012")
        post(posted)
        val repo = workspace.repository("legacy")
        val entry = requireNotNull(repo.refresh()).entries.single()
        assertEquals(20_000L, entry.amount)
        assertEquals("NOTIFICATION", entry.source)
        assertTrue(controller.state.value.inbox.isEmpty())
        repo.execute(Command.UpsertEntry(entry.toDraft().copy(amount = 15_000, merchant = "Corrected merchant")))
        post(posted)
        assertEquals(15_000L, requireNotNull(repo.refresh()).entries.single().amount)
        controller.closeAndJoin()
        controller = newController()
        controller.refresh()
        repo.execute(Command.DeleteEntry(entry.id))
        post(posted)
        assertTrue(requireNotNull(repo.refresh()).entries.isEmpty())
    }

    @Test fun noReferenceRequiresReviewSupportsCorrectionsAndIgnoredReplayStaysIgnored() = runBlocking {
        controller.configure(settings)
        val posted = notification("Paid ₹200 to Tea Shop")
        post(posted)
        val candidate = controller.state.value.inbox.single()
        assertTrue(requireNotNull(workspace.repository("legacy").refresh()).entries.isEmpty())
        val draft = EntryDraft(id = candidate.entryId, amount = 18_000, date = candidate.date, merchant = "Tea and snacks")
        val preview = workspace.repository("legacy").preview(draft)
        val after = controller.acceptCandidate(candidate.id, "legacy", draft, expectedPreview = preview)
        assertEquals(18_000L, after.entries.single().amount)
        assertEquals("NOTIFICATION_REVIEWED", after.entries.single().source)
        post(notification("Paid ₹100 to Another Shop", id = 2))
        val ignored = controller.state.value.inbox.single()
        controller.ignoreCandidate(ignored.id)
        post(notification("Paid ₹100 to Another Shop", id = 2))
        assertTrue(controller.state.value.inbox.isEmpty())
        assertEquals(1, requireNotNull(workspace.repository("legacy").refresh()).entries.size)
    }

    @Test fun disabledUnrelatedGroupedFailedOldAndRevokedNotificationsDoNotImport() = runBlocking {
        post(notification("Paid ₹200 to Shop UTR 123456789012"))
        controller.configure(settings)
        post(notification("Paid ₹200 to Shop UTR 123456789012", source = "unknown.payment.app"))
        post(notification("Paid ₹200 to Shop UTR 123456789012", summary = true))
        post(notification("Failed. Paid ₹200 to Shop UTR 123456789012"))
        post(notification("Paid ₹200 to Shop UTR 123456789012", time = at.minusMillis(1)))
        granted = false
        post(notification("Paid ₹200 to Shop UTR 123456789012"))
        assertTrue(requireNotNull(workspace.repository("legacy").refresh()).entries.isEmpty())
        assertTrue(controller.state.value.inbox.isEmpty())
        try { controller.configure(settings); fail("Denied listener consent must block enabling") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun pendingCaptureResumesAfterDatabaseCommitWithoutDoubleCounting() = runBlocking {
        controller.closeAndJoin()
        val parsed = requireNotNull(PaymentNotificationParser.parse(app, "Payment successful",
            "Paid ₹200 to Shop UTR 123456789012", at, "key"))
        val candidate = PaymentCandidate(parsed.captureId, parsed.amount, parsed.merchant, app, at,
            at.atZone(ZoneOffset.UTC).toLocalDate(), "legacy", parsed.referenceHash, parsed.reviewReason)
        NotificationCaptureStore(context, prefs).write(CaptureData(settings, 0, 1,
            captures = listOf(CaptureRecord(candidate, true, 1)), assignments = mapOf(candidate.id to "legacy")))
        workspace.repository("legacy").importIfAbsent(
            EntryDraft(id = candidate.id, amount = candidate.amount, date = candidate.date), occurredAt = at)
        controller = newController()
        controller.refresh()
        assertEquals(1, requireNotNull(workspace.repository("legacy").refresh()).entries.size)
        assertTrue(controller.state.value.inbox.isEmpty())
        assertEquals("IMPORTED:legacy", NotificationCaptureStore(context, prefs).read().decisions[candidate.id])
    }

    @Test fun retryCannotSwitchBudgetAfterAnImportWasReserved() = runBlocking {
        controller.closeAndJoin()
        val other = workspace.createBudget("Other", 100_000, null, "UTC")
        val candidate = PaymentCandidate("review-id", 10_000, "Shop", app, at,
            at.atZone(ZoneOffset.UTC).toLocalDate(), "legacy", null, "Check for duplicate entries.")
        NotificationCaptureStore(context, prefs).write(CaptureData(
            captures = listOf(CaptureRecord(candidate, false, 0)), assignments = mapOf(candidate.id to "legacy")))
        controller = newController()
        controller.refresh()
        val draft = EntryDraft(id = candidate.id, amount = candidate.amount, date = candidate.date)
        try { controller.acceptCandidate(candidate.id, other.id, draft); fail("A reserved retry must keep its destination") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("already attempted")) }
        assertTrue(requireNotNull(workspace.repository(other.id).refresh()).entries.isEmpty())
        val preview = workspace.repository("legacy").preview(draft)
        controller.acceptCandidate(candidate.id, "legacy", draft, expectedPreview = preview)
        assertEquals(1, requireNotNull(workspace.repository("legacy").refresh()).entries.size)
    }
}
