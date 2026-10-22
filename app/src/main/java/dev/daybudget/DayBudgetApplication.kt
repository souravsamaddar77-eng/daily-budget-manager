package dev.daybudget

import android.app.Application
import dev.daybudget.data.BudgetRepository
import dev.daybudget.data.BudgetWorkspace
import dev.daybudget.notifications.NotificationControllerOwner
import dev.daybudget.notifications.NotificationsController

class DayBudgetApplication : Application(), NotificationControllerOwner {
    val workspace: BudgetWorkspace by lazy { BudgetWorkspace(this) }
    override val notifications: NotificationsController by lazy { NotificationsController(this, workspace) }
    /** Compatibility access to the migrated original budget; new UI resolves the selected profile. */
    val repository: BudgetRepository get() = workspace.repository("legacy")
}
