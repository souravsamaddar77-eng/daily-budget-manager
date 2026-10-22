package dev.daybudget.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybudget.domain.BudgetException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationCaptureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "notification-test-${UUID.randomUUID()}"
    private val store = NotificationCaptureStore(context, name)
    private val candidate = PaymentCandidate("upi-hash", 12_345, "Tea Shop", "com.phonepe.app",
        Instant.parse("2026-10-22T10:00:00Z"), LocalDate.parse("2026-10-22"), "budget-a", "hash", "Check this payment.")

    @After fun cleanUp() { context.deleteSharedPreferences(name) }

    @Test fun detectionAndAlertsStartDisabled() {
        val state = store.read()
        assertFalse(state.settings.captureEnabled)
        assertFalse(state.settings.autoRecord)
        assertFalse(state.settings.spendingAlertsEnabled)
        assertFalse(state.settings.overspendAlertsEnabled)
        assertTrue(state.settings.enabledPackages.isEmpty())
    }

    @Test fun capturedFactsAndIndependentDecisionsSurviveReopening() {
        val original = CaptureData(
            settings = NotificationSettings(true, true, "budget-a", setOf("com.phonepe.app")),
            captureSinceMillis = 123L, generation = 4,
            captures = listOf(CaptureRecord(candidate, true, 4)),
            decisions = mapOf(candidate.id to "IMPORTED:budget-a"),
            assignments = mapOf(candidate.id to "budget-a"), alertReceipts = setOf("alert-hash"),
        )
        store.write(original)
        assertEquals(original, NotificationCaptureStore(context, name).read())
        val ignored = original.copy(decisions = mapOf(candidate.id to "IGNORED"))
        store.write(ignored)
        assertEquals(original.captures, store.read().captures)
        assertEquals("IGNORED", store.read().decisions[candidate.id])
    }

    @Test fun rawNotificationReferenceAndAccountFieldsAreNotPersisted() {
        val payment = requireNotNull(PaymentNotificationParser.parse("com.phonepe.app", "Payment successful",
            "Paid ₹123.45 to Tea Shop UTR 123456789012 from account 99999999", candidate.postedAt, "secret-platform-key"))
        store.write(CaptureData(captures = listOf(CaptureRecord(candidate.copy(id = payment.captureId,
            referenceHash = payment.referenceHash), true, 0))))
        val text = context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("state", "")!!
        assertFalse(text.contains("123456789012"))
        assertFalse(text.contains("99999999"))
        assertFalse(text.contains("secret-platform-key"))
        assertFalse(text.contains("Paid ₹"))
    }

    @Test fun corruptedReceiptsFailClosedInsteadOfResettingDeduplication() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("state", "{broken").commit()
        try { store.read(); fail("Damaged receipts must not reset silently") } catch (_: BudgetException) { }
    }

    @Test fun referenceHashesUseAPersistentPrivateKeyAndClearRotatesIt() {
        val value = "upi-reference:123456789012"
        val first = store.hashIdentity(value)
        assertEquals(64, first.length)
        assertEquals(first, NotificationCaptureStore(context, name).hashIdentity(value))
        assertNotEquals(PaymentNotificationParser.hash(value), first)
        store.removeIdentityKey()
        assertNotEquals(first, store.hashIdentity(value))
        store.write(CaptureData(captures = listOf(CaptureRecord(candidate, false, 0))))
        store.removeIdentityKey()
        try { store.hashIdentity(value); fail("Lost key must never create a new identity for captured payments") }
        catch (_: BudgetException) { }
    }
}
