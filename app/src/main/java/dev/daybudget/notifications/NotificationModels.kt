package dev.daybudget.notifications

import java.time.Instant
import java.time.LocalDate

data class SupportedPaymentApp(val packageName: String, val label: String)

val SUPPORTED_PAYMENT_APPS = listOf(
    SupportedPaymentApp("com.phonepe.app", "PhonePe"),
    SupportedPaymentApp("com.google.android.apps.nbu.paisa.user", "Google Pay"),
    SupportedPaymentApp("net.one97.paytm", "Paytm"),
)

data class NotificationSettings(
    val captureEnabled: Boolean = false,
    val autoRecord: Boolean = false,
    val targetBudgetId: String? = null,
    val enabledPackages: Set<String> = emptySet(),
    val spendingAlertsEnabled: Boolean = false,
    val overspendAlertsEnabled: Boolean = false,
)

/** Only these extracted fields persist. Notification text and raw references never do. */
data class PaymentCandidate(
    val id: String,
    val amount: Long,
    val merchant: String,
    val sourcePackage: String,
    val postedAt: Instant,
    val date: LocalDate,
    val budgetId: String,
    val referenceHash: String?,
    val reviewReason: String,
) {
    val entryId: String get() = id
}

data class NotificationState(
    val settings: NotificationSettings = NotificationSettings(),
    val inbox: List<PaymentCandidate> = emptyList(),
    val listenerAccessGranted: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val error: String? = null,
)

interface NotificationControllerOwner {
    val notifications: NotificationsController
}
