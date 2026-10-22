package dev.daybudget.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.Instant
import kotlinx.coroutines.Deferred

class PaymentNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val controller = (application as? NotificationControllerOwner)?.notifications ?: return
        if (sbn == null) return
        processPosted(controller, sbn)
    }

    override fun onListenerConnected() {
        // Deliberately do not scan active notifications: enabling access never imports old history.
        super.onListenerConnected()
    }

    companion object {
        internal fun processPosted(controller: NotificationsController, sbn: StatusBarNotification): Deferred<Unit>? {
            // Filter the package before accessing any title or message from unrelated apps.
            if (!controller.acceptsPackage(sbn.packageName)) return null
            val notification = sbn.notification
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            val extras = notification.extras ?: return null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            return runCatching { PaymentNotificationParser.parse(sbn.packageName, title, body,
                Instant.ofEpochMilli(sbn.postTime), sbn.key, controller::hashIdentity) }
                .onFailure { controller.reportDetectionFailure() }.getOrNull()
                ?.let(controller::receive)
        }
    }
}
