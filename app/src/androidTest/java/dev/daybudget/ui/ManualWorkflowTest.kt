package dev.daybudget.ui

import android.content.Context
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybudget.data.BudgetDatabase
import dev.daybudget.data.BudgetRepository
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Synthetic amounts only. No screenshots, notifications, or personal records are captured. */
@RunWith(AndroidJUnit4::class)
class ManualWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<FontScaleTestActivity>()
    private lateinit var database: BudgetDatabase
    private lateinit var model: BudgetViewModel
    private val store = ViewModelStore()

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java).build()
        val repository = BudgetRepository(database, Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC))
        compose.runOnIdle {
            model = ViewModelProvider(store, BudgetViewModel.Factory(repository))[BudgetViewModel::class.java]
        }
        compose.setContent { DayBudgetTheme { DayBudgetApp(model) } }
        awaitUi("initial records loaded") { it.initialized }
    }

    @After fun close() {
        try {
            if (::model.isInitialized) {
                val scopeJob = model.viewModelScope.coroutineContext[Job]
                // Cleanup also runs if the preceding assertion failed to reach idle.
                compose.runOnUiThread { store.clear() }
                compose.waitUntil(10_000) { compose.runOnUiThread { scopeJob?.isCompleted != false } }
            }
        } finally {
            if (::database.isInitialized) database.close()
        }
    }

    @Test fun setupExpenseRefundUndoAndWeeklyKeepFrozenAllowance() {
        field("Spending budget left until month-end").performScrollTo().performTextInput("4000")
        compose.onNodeWithText("Start tracking").performScrollTo().assertIsEnabled().performClick()
        awaitUi("opening budget saved") { it.budget != null && !it.busy }
        assertEquals(40_000L, model.ui.value.budget!!.startingAllowance)
        assertEquals(10, model.ui.value.budget!!.daysRemaining)

        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        field("Amount").performTextInput("200")
        compose.onNodeWithText("Save expense").performScrollTo().performClick()
        awaitUi("expense saved") { it.budget?.entries?.size == 1 && !it.busy }
        assertEquals(20_000L, model.ui.value.budget!!.todayRemaining)
        assertEquals(380_000L, model.ui.value.budget!!.monthlyRemaining)
        assertEquals(40_000L, model.ui.value.budget!!.startingAllowance)

        compose.onNodeWithText("Transactions").performClick()
        compose.onNodeWithText("Add refund").performClick()
        field("Amount").performTextInput("40")
        compose.onNodeWithText("Save refund").performScrollTo().performClick()
        awaitUi("refund saved") { it.budget?.entries?.size == 2 && !it.busy }
        assertEquals(24_000L, model.ui.value.budget!!.todayRemaining)
        assertEquals(384_000L, model.ui.value.budget!!.monthlyRemaining)
        assertEquals(4_000L, model.ui.value.budget!!.todayRefunds)
        assertEquals(40_000L, model.ui.value.budget!!.startingAllowance)

        compose.onNodeWithText("Undo last entry change").performClick()
        awaitUi("refund undone") { it.budget?.entries?.size == 1 && !it.busy }
        assertEquals(20_000L, model.ui.value.budget!!.todayRemaining)
        assertEquals(0L, model.ui.value.budget!!.todayRefunds)
        compose.onNodeWithText("Weekly").performClick()
        compose.onNodeWithText("Seven days, as recorded").assertIsDisplayed()
        assertEquals(20_000L, model.ui.value.budget!!.weeklyNet)
        assertEquals(7, model.ui.value.budget!!.weeklyDays.size)
        assertNull(model.ui.value.error)
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun awaitUi(stage: String, predicate: (AppUiState) -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                // Room work uses real worker threads. Pump the Android main looper as well
                // as Compose's virtual clock before observing ViewModel continuations.
                compose.runOnIdle {
                    val state = model.ui.value
                    check(state.error == null) { "Failed while waiting for $stage: ${state.error}" }
                    predicate(state)
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            val state = model.ui.value
            throw AssertionError(
                "Timed out waiting for $stage: loading=${state.loading}, initialized=${state.initialized}, " +
                    "busy=${state.busy}, hasBudget=${state.budget != null}, entries=${state.budget?.entries?.size}, " +
                    "error=${state.error}", timeout,
            )
        }
        compose.waitForIdle()
    }
}
