@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.daybudget.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybudget.data.BudgetWorkspace
import dev.daybudget.domain.MonthKey
import dev.daybudget.domain.ThemeMode
import dev.daybudget.notifications.NotificationsController
import dev.daybudget.notifications.NotificationSettings
import dev.daybudget.notifications.ParsedPayment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Production workspace UI, isolated databases and notification preferences; no OS grants. */
@RunWith(AndroidJUnit4::class)
class WorkspaceWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<FontScaleTestActivity>()
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
    private lateinit var context: Context
    private lateinit var workspace: BudgetWorkspace
    private lateinit var notifications: NotificationsController
    private lateinit var hub: WorkspaceViewModel
    private lateinit var prefix: String
    @Volatile private var listenerGranted = false
    private val clock = Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC)

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        prefix = "u${UUID.randomUUID().toString().take(8)}-"
        workspace = BudgetWorkspace(context, clock, "${prefix}c.db", "${prefix}l.db", "${prefix}p-")
        notifications = NotificationsController(context, workspace, clock, "${prefix}notifications") { listenerGranted }
        runBlocking {
            workspace.initialize()
            workspace.repository("legacy").setup(400_000, 1_200_000, "Asia/Kolkata")
        }
        compose.runOnIdle { hub = ViewModelProvider(store, WorkspaceViewModel.Factory(workspace, notifications))[WorkspaceViewModel::class.java] }
        compose.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { BudgetWorkspaceApp(hub) } }
        await("workspace opened") { workspace.state.value.initialized && !hub.operation.value.busy }
        awaitText("Left to spend today")
        compose.onNodeWithText("Left to spend today").assertIsDisplayed()
    }

    @After fun close() {
        try {
            compose.runOnUiThread { store.clear() }
            if (::notifications.isInitialized) runBlocking { notifications.closeAndJoin() }
        } finally {
            if (::workspace.isInitialized) workspace.close()
            if (::context.isInitialized && ::prefix.isInitialized) {
                context.databaseList().filter { it.startsWith(prefix) }.forEach { context.deleteDatabase(it) }
                context.deleteSharedPreferences("${prefix}notifications")
            }
        }
    }

    @Test fun namedBudgetCreationAndExpenseStaySeparateWhenSwitching() {
        compose.onNodeWithText("Budgets", useUnmergedTree = true).performClick()
        compose.onNodeWithText("+ New budget").performScrollTo().performClick()
        field("Budget name").performTextInput("Travel")
        field("Spending budget left until month-end").performScrollTo().performTextInput("1000")
        compose.onNodeWithText("Create budget").performScrollTo().performClick()
        await("Travel created") { workspace.state.value.profiles.size == 2 && !hub.operation.value.completionPending }
        val travelId = workspace.state.value.selectedBudgetId
        assertNotEquals("legacy", travelId)
        compose.onNodeWithText("+ Expense").performScrollTo().performClick()
        field("Amount").performTextInput("20")
        compose.onNodeWithText("Save expense").performScrollTo().performClick()
        await("Travel expense saved") { workspace.repository(travelId).state.value?.entries?.size == 1 }
        assertEquals(98_000L, workspace.repository(travelId).state.value!!.monthlyRemaining)
        assertEquals(8_000L, workspace.repository(travelId).state.value!!.todayRemaining)
        compose.onNodeWithText("Budgets", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Open My budget").performScrollTo().performClick()
        await("legacy selected") { workspace.state.value.selectedBudgetId == "legacy" && !hub.operation.value.completionPending }
        assertEquals(400_000L, workspace.repository("legacy").state.value!!.monthlyRemaining)
        assertTrue(workspace.repository("legacy").state.value!!.entries.isEmpty())
        compose.onNodeWithText("My budget · switch budget").performScrollTo().assertIsDisplayed()
    }

    @Test fun savingsDepositAndWithdrawalLeaveDailyBudgetUnchanged() {
        compose.onNodeWithText("Savings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("+ Add savings").performScrollTo().performClick()
        field("Amount").performTextInput("500")
        field("Note (optional)").performTextInput("Emergency fund")
        compose.onNodeWithText("Save deposit").performScrollTo().performClick()
        await("savings deposit") { workspace.state.value.savingsBalance == 50_000L && !hub.operation.value.completionPending }
        compose.onNodeWithText("− Withdraw").performScrollTo().performClick()
        field("Amount").performTextInput("100")
        compose.onNodeWithText("Save withdrawal").performScrollTo().performClick()
        await("savings withdrawal") { workspace.state.value.savingsBalance == 40_000L && !hub.operation.value.completionPending }
        assertEquals(2, workspace.state.value.savingsEntries.size)
        assertEquals(400_000L, workspace.repository("legacy").state.value!!.monthlyRemaining)
        assertEquals(40_000L, workspace.repository("legacy").state.value!!.startingAllowance)
        compose.onNodeWithText("Savings changes do not change any spending budget.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun calendarMonthNameAndDarkPreferenceSaveWithoutChangingAmounts() {
        compose.onNodeWithText("Calendar", useUnmergedTree = true).performClick()
        awaitText("Rename this month")
        compose.onNodeWithText("Rename this month").performScrollTo().performClick()
        field("Month name").performTextInput("October essentials")
        compose.onNodeWithText("Save name").performScrollTo().performClick()
        await("month renamed") { workspace.state.value.monthNames[MonthKey("legacy", "2026-10")] == "October essentials" && !hub.operation.value.completionPending }
        compose.onNodeWithText("October essentials").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        await("dark theme saved") { workspace.state.value.themeMode == ThemeMode.DARK && !hub.operation.value.completionPending }
        compose.onNodeWithText("Dark").assertIsSelected()
        assertEquals(400_000L, workspace.repository("legacy").state.value!!.monthlyRemaining)
        assertEquals(40_000L, workspace.repository("legacy").state.value!!.startingAllowance)
    }

    @Test fun notificationDetectionRequiresExplicitOptIn() {
        assertFalse(notifications.state.value.settings.captureEnabled)
        assertFalse(notifications.state.value.settings.autoRecord)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Set up UPI detection").performScrollTo().performClick()
        compose.onNodeWithText("Choose what this app may read").assertIsDisplayed()
        compose.onNodeWithText("Returning here does not enable detection.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save detection settings").performScrollTo().performClick()
        await("disabled detection saved") { !hub.operation.value.busy && !hub.operation.value.completionPending }
        assertFalse(notifications.state.value.settings.captureEnabled)
        assertFalse(notifications.state.value.settings.autoRecord)
        assertTrue(workspace.repository("legacy").state.value!!.entries.isEmpty())
    }

    @Test fun reviewedPaymentUsesEditedAmountAndCannotBeImportedTwice() {
        listenerGranted = true
        val payment = ParsedPayment(4_000, "Sample cafe", "com.phonepe.app", clock.instant(), null,
            "synthetic-review-payment", false, "No payment reference found. Check before adding.")
        compose.runOnIdle {
            hub.configureNotifications(NotificationSettings(captureEnabled = true, targetBudgetId = "legacy",
                enabledPackages = setOf("com.phonepe.app")))
        }
        await("synthetic capture enabled") { notifications.state.value.settings.captureEnabled && !hub.operation.value.completionPending }
        val firstCapture = requireNotNull(notifications.receive(payment))
        await("synthetic capture completed") { firstCapture.isCompleted }
        firstCapture.getCompleted()
        await("payment waiting for review") { notifications.state.value.inbox.size == 1 }
        assertTrue(workspace.repository("legacy").state.value!!.entries.isEmpty())
        compose.onNodeWithText("Review detected payments", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Review payment").performScrollTo().performClick()
        field("Amount").performTextReplacement("50")
        compose.onNodeWithText("Add to My budget").performScrollTo().performClick()
        await("reviewed payment committed") { notifications.state.value.inbox.isEmpty() && !hub.operation.value.completionPending }
        val entry = workspace.repository("legacy").state.value!!.entries.single()
        assertEquals(5_000L, entry.amount)
        assertEquals("NOTIFICATION_REVIEWED", entry.source)
        assertEquals(395_000L, workspace.repository("legacy").state.value!!.monthlyRemaining)
        assertEquals(35_000L, workspace.repository("legacy").state.value!!.todayRemaining)
        val replay = requireNotNull(notifications.receive(payment))
        await("replayed capture completed") { replay.isCompleted }
        replay.getCompleted()
        await("replayed notification ignored") { notifications.state.value.inbox.isEmpty() }
        assertEquals(1, workspace.repository("legacy").state.value!!.entries.size)
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())
    private fun awaitText(text: String) {
        compose.waitUntil(15_000) {
            compose.runOnIdle { check(hub.operation.value.error == null) }
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun await(stage: String, condition: () -> Boolean) {
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                check(hub.operation.value.error == null) { "$stage: ${hub.operation.value.error}" }
                condition()
            }
        }
        compose.waitForIdle()
    }
}
