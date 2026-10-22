@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.daybudget.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class TestFontScale(val value: Float)

/**
 * Uses synthetic in-memory records; it never opens the user's application database.
 * StateRestorationTester covers Compose saveable-state restoration. Physical rotation
 * and Android Activity lifecycle behavior remain separate emulator checks.
 */
@RunWith(AndroidJUnit4::class)
class UiResilienceTest {
    val compose = createAndroidComposeRule<FontScaleTestActivity>()
    private val fontConfiguration = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val previous = FontScaleTestActivity.launchFontScale
                FontScaleTestActivity.launchFontScale = description.getAnnotation(TestFontScale::class.java)?.value ?: 1f
                try {
                    base.evaluate()
                } finally {
                    FontScaleTestActivity.launchFontScale = previous
                }
            }
        }
    }
    // Font selection precedes ActivityScenario launch and is reset after Activity disposal.
    @get:Rule val rules: TestRule = RuleChain.outerRule(fontConfiguration).around(compose)
    private lateinit var database: BudgetDatabase
    private lateinit var model: BudgetViewModel
    private val store = ViewModelStore()
    private val transactions = PausingExecutor()

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java)
            .setTransactionExecutor(transactions).build()
        val repository = BudgetRepository(database, Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC))
        compose.runOnIdle {
            model = ViewModelProvider(store, BudgetViewModel.Factory(repository))[BudgetViewModel::class.java]
        }
    }

    @After fun close() {
        transactions.resume()
        try {
            if (::model.isInitialized) {
                val scopeJob = model.viewModelScope.coroutineContext[Job]
                // Teardown must also work after an idling assertion fails.
                compose.runOnUiThread { store.clear() }
                compose.waitUntil(10_000) { compose.runOnUiThread { scopeJob?.isCompleted != false } }
            }
        } finally {
            try {
                if (::database.isInitialized) database.close()
            } finally {
                transactions.close()
            }
        }
    }

    @Test fun onboardingAndExpenseDraftSurviveSaveableStateRestoration() {
        val restoration = launch()
        field("Spending budget left until month-end").performScrollTo().performTextInput("4000")
        field("Usual full-month budget (optional)").performScrollTo().performTextInput("12000")

        restoration.emulateSavedInstanceStateRestore()
        field("Spending budget left until month-end").performScrollTo().assertTextContains("4000")
        field("Usual full-month budget (optional)").performScrollTo().assertTextContains("12000")
        compose.onNodeWithText("Start tracking").performScrollTo().assertIsEnabled().performClick()
        awaitUi("restored opening budget saved") { it.budget != null && !it.busy }
        assertEquals(1_200_000L, model.ui.value.budget!!.settings.usualMonthlyBudget)

        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        field("Amount").performScrollTo().performTextInput("123.45")
        compose.onNodeWithText("Yesterday").performScrollTo().performClick()
        compose.onNodeWithText("Add merchant, category or note").performScrollTo().performClick()
        field("Merchant (optional)").performScrollTo().performTextInput("Sample cafe")
        field("Category (optional)").performScrollTo().performTextInput("Food")
        field("Note (optional)").performScrollTo().performTextInput("Draft before recreation")

        restoration.emulateSavedInstanceStateRestore()
        field("Amount").performScrollTo().assertTextContains("123.45")
        compose.onNodeWithText("Yesterday").performScrollTo().assertIsSelected()
        compose.onNodeWithText("21 October 2026").performScrollTo().assertIsDisplayed()
        field("Merchant (optional)").performScrollTo().assertTextContains("Sample cafe")
        field("Category (optional)").performScrollTo().assertTextContains("Food")
        field("Note (optional)").performScrollTo().assertTextContains("Draft before recreation")
        assertEquals(0, model.ui.value.budget!!.entries.size)

        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled().performClick()
        awaitUi("opening-boundary preview ready") { !it.busy }
        compose.onNodeWithText("Review the budget change").assertIsDisplayed()
        assertEquals(0, model.ui.value.budget!!.entries.size)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Review the budget change").assertIsDisplayed()
        compose.onNodeWithText("Confirm and save").assertIsDisplayed().performClick()
        awaitUi("restored expense saved once") { it.budget?.entries?.size == 1 && !it.busy }

        val budget = model.ui.value.budget!!
        val entry = budget.entries.single()
        assertEquals(12_345L, entry.amount)
        assertEquals(LocalDate.of(2026, 10, 21), entry.date)
        assertEquals("Sample cafe", entry.merchant)
        assertEquals("Food", entry.category)
        assertEquals("Draft before recreation", entry.note)
        assertFalse(entry.included)
        assertEquals(400_000L, budget.monthlyRemaining)
        assertEquals(40_000L, budget.startingAllowance)
    }

    @TestFontScale(2f)
    @Test fun largeTextKeepsExpenseSaveAndBudgetAdjustmentReachable() {
        launch()
        field("Spending budget left until month-end").performScrollTo().performTextInput("4000")
        compose.onNodeWithText("Start tracking").performScrollTo().assertIsDisplayed().performClick()
        awaitUi("large-text opening budget saved") { it.budget != null && !it.busy }

        compose.onNodeWithText("Add expense").performScrollTo().assertIsDisplayed().performClick()
        field("Amount").performScrollTo().performTextInput("20")
        compose.onNodeWithText("Save expense").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        awaitUi("large-text expense saved") { it.budget?.entries?.size == 1 && !it.busy }
        assertEquals(38_000L, model.ui.value.budget!!.todayRemaining)

        compose.onNodeWithContentDescription("Transactions").assertIsDisplayed().performClick()
        compose.onNodeWithText("Add refund").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Weekly").assertIsDisplayed().performClick()
        compose.onNodeWithText("Net recorded spending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        // Catch font overrides that scale only the Activity while a Dialog remains unscaled.
        compose.onNodeWithText("Settings").assertIsDisplayed().assertHeightIsAtLeast(40.dp)

        field("Adjustment (+ or −)").performScrollTo().performTextInput("+500")
        field("Reason for adjustment").performScrollTo().performTextInput("Large text check")
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithText("Review adjustment").performScrollTo().assertIsDisplayed().performClick()
        // Dialog actions must remain reachable even when their body needs to scroll.
        compose.onNodeWithText("Apply adjustment").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        awaitUi("large-text adjustment saved") { it.budget?.adjustments?.size == 1 && !it.busy }
        assertEquals(448_000L, model.ui.value.budget!!.monthlyRemaining)
        assertEquals(40_000L, model.ui.value.budget!!.startingAllowance)
        assertEquals(38_000L, model.ui.value.budget!!.todayRemaining)
        assertNull(model.ui.value.error)
    }

    @Test fun adjustmentCommittedDuringRestorationClosesOldFormAndCannotRepeat() {
        val restoration = launch()
        field("Spending budget left until month-end").performScrollTo().performTextInput("4000")
        compose.onNodeWithText("Start tracking").performScrollTo().performClick()
        awaitUi("opening budget saved") { it.budget != null && !it.busy && !it.completionPending }
        compose.onNodeWithContentDescription("Settings").performClick()
        field("Adjustment (+ or −)").performScrollTo().performTextInput("+500")
        field("Reason for adjustment").performScrollTo().performTextInput("Delayed commit check")
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithText("Review adjustment").performScrollTo().performClick()

        transactions.pause()
        compose.onNodeWithText("Apply adjustment").performClick()
        awaitUi("adjustment suspended in Room") { it.busy && transactions.hasDeferredWork() }
        assertEquals(0, model.ui.value.budget!!.adjustments.size)

        // Dispose the submitting composition while its ViewModel operation is suspended.
        // The completing operation must update the replacement composition, not its old callbacks.
        restoration.emulateSavedInstanceStateRestore()
        transactions.resume()
        awaitUi("adjustment completion handled after restoration") {
            it.budget?.adjustments?.size == 1 && !it.busy && !it.completionPending
        }
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        assertEquals(450_000L, model.ui.value.budget!!.monthlyRemaining)
        assertEquals(40_000L, model.ui.value.budget!!.startingAllowance)

        compose.onNodeWithContentDescription("Settings").performClick()
        field("Adjustment (+ or −)").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        field("Reason for adjustment").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithText("Review adjustment").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, model.ui.value.budget!!.adjustments.size)
        assertEquals(450_000L, model.ui.value.budget!!.monthlyRemaining)
    }

    private fun launch(): StateRestorationTester {
        compose.runOnUiThread {
            assertEquals(FontScaleTestActivity.launchFontScale,
                compose.activity.resources.configuration.fontScale, 0.001f)
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DayBudgetTheme { DayBudgetApp(model) }
        }
        awaitUi("initial records loaded") { it.initialized }
        return restoration
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun awaitUi(stage: String, predicate: (AppUiState) -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                compose.runOnIdle {
                    val state = model.ui.value
                    check(state.error == null) { "Failed while waiting for $stage: ${state.error}" }
                    predicate(state)
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            val state = model.ui.value
            throw AssertionError(
                "Timed out waiting for $stage: initialized=${state.initialized}, busy=${state.busy}, " +
                    "hasBudget=${state.budget != null}, entries=${state.budget?.entries?.size}, error=${state.error}",
                timeout,
            )
        }
        compose.waitForIdle()
    }

    /** Pauses transaction dispatch without sleeping or blocking an Android/UI thread. */
    private class PausingExecutor : Executor {
        private val delegate = Executors.newSingleThreadExecutor()
        private val deferred = ArrayDeque<Runnable>()
        private var paused = false

        @Synchronized override fun execute(command: Runnable) {
            if (paused) deferred.addLast(command) else delegate.execute(command)
        }

        @Synchronized fun pause() { paused = true }
        @Synchronized fun hasDeferredWork(): Boolean = deferred.isNotEmpty()
        @Synchronized fun resume() {
            paused = false
            while (deferred.isNotEmpty()) delegate.execute(deferred.removeFirst())
        }

        fun close() { delegate.shutdownNow() }
    }
}
