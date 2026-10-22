package dev.daybudget.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybudget.domain.BudgetException
import dev.daybudget.domain.Command
import dev.daybudget.domain.EntryDraft
import dev.daybudget.domain.MonthKey
import dev.daybudget.domain.ThemeMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BudgetWorkspaceTest {
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var workspace: BudgetWorkspace
    private val instant = Instant.parse("2026-04-01T06:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-04-01")
    private val legacy: String get() = "${prefix}legacy.db"
    private val catalog: String get() = "${prefix}catalog.db"
    private fun open() = BudgetWorkspace(context, clock, catalog, legacy, "${prefix}profile-")

    @Before fun before() {
        context = ApplicationProvider.getApplicationContext()
        // Keep generated profile paths below Windows native SQLite's path limit on the host.
        prefix = "ws-${UUID.randomUUID().toString().take(8)}-"
        workspace = open()
    }

    @After fun after() {
        workspace.close()
        context.databaseList().filter { it.startsWith(prefix) }.forEach { context.deleteDatabase(it) }
    }

    @Test fun legacyAdoptionPreservesRecordsFrozenSnapshotAndUndo() = runBlocking {
        val old = BudgetDatabase.open(context, legacy)
        val repo = BudgetRepository(old, clock)
        repo.setup(400_000, 500_000, "Asia/Kolkata")
        val original = repo.execute(Command.UpsertEntry(EntryDraft(id = "old", amount = 25_000, date = date)))
        old.close()
        workspace.initialize()
        assertEquals(listOf("legacy"), workspace.state.value.profiles.map { it.id })
        val adopted = requireNotNull(workspace.repository("legacy").refresh())
        assertEquals(original, adopted)
        assertTrue(adopted.canUndo)
        assertSame(workspace.repository("legacy"), workspace.repository("legacy"))
        workspace.initialize()
        assertEquals(1, workspace.state.value.profiles.size)
    }

    @Test fun budgetsAndSavingsAreIsolatedAcrossRestart() = runBlocking {
        workspace.initialize()
        workspace.repository("legacy").setup(100_000, null, "UTC")
        val travel = workspace.createBudget("Travel", 200_000, 300_000, "UTC")
        workspace.repository(travel.id).execute(Command.UpsertEntry(EntryDraft(id = "same-id", amount = 20_000, date = date)))
        workspace.repository("legacy").execute(Command.UpsertEntry(EntryDraft(id = "same-id", amount = 3_000, date = date)))
        workspace.addSavings(70_000, "Separate reserve")
        workspace.addSavings(-10_000, "Withdrawal")
        workspace.close()
        workspace = open()
        workspace.initialize()
        assertEquals(travel.id, workspace.state.value.selectedBudgetId)
        assertEquals(60_000L, workspace.state.value.savingsBalance)
        assertEquals(2, workspace.state.value.savingsEntries.size)
        assertEquals(180_000L, workspace.repository(travel.id).refresh()!!.monthlyRemaining)
        assertEquals(97_000L, workspace.repository("legacy").refresh()!!.monthlyRemaining)
    }

    @Test fun namesSelectionAndThemePersistWithoutChangingAccounting() = runBlocking {
        workspace.initialize()
        val profile = workspace.createBudget("Food", 400_000, null, "UTC")
        val before = workspace.repository(profile.id).refresh()
        workspace.renameBudget(profile.id, "Groceries")
        workspace.renameMonth(profile.id, "2026-04", "April essentials")
        workspace.setTheme(ThemeMode.DARK)
        workspace.selectBudget("legacy")
        workspace.close()
        workspace = open()
        workspace.initialize()
        assertEquals("Groceries", workspace.state.value.profiles.find { it.id == profile.id }!!.name)
        assertEquals("April essentials", workspace.state.value.monthNames[MonthKey(profile.id, "2026-04")])
        assertEquals(ThemeMode.DARK, workspace.state.value.themeMode)
        assertEquals("legacy", workspace.state.value.selectedBudgetId)
        assertEquals(before, workspace.repository(profile.id).refresh())
        workspace.renameMonth(profile.id, "2026-04", " ")
        assertTrue(workspace.state.value.monthNames.isEmpty())
    }

    @Test fun savingsOverflowAndOverdrawRollbackWholeChange() = runBlocking {
        workspace.initialize()
        workspace.addSavings(Long.MAX_VALUE, "Maximum")
        val before = workspace.state.value
        assertTrue(runCatching { workspace.addSavings(1, "Overflow") }.exceptionOrNull() is BudgetException)
        assertEquals(before, workspace.state.value)
        assertTrue(runCatching { workspace.addSavings(Long.MIN_VALUE, "Overdraw") }.exceptionOrNull() is BudgetException)
        assertEquals(before, workspace.state.value)
        workspace.close()
        workspace = open()
        workspace.initialize()
        assertEquals(Long.MAX_VALUE, workspace.state.value.savingsBalance)
        assertEquals(1, workspace.state.value.savingsEntries.size)
    }

    @Test fun concurrentCreationAndSavingsDoNotLoseUpdates() = runBlocking {
        workspace.initialize()
        coroutineScope {
            (1..5).map { index -> async { workspace.createBudget("Budget $index", 1000L * index, null, "UTC") } }.awaitAll()
            (1..25).map { async { workspace.addSavings(100, "Deposit") } }.awaitAll()
        }
        assertEquals(6, workspace.state.value.profiles.size)
        assertEquals(6, workspace.state.value.profiles.map { it.id }.distinct().size)
        assertEquals(2500L, workspace.state.value.savingsBalance)
        assertEquals(25, workspace.state.value.savingsEntries.size)
    }

    @Test fun interruptedProfileRegistrationAdoptsCommittedLedger() = runBlocking {
        workspace.initialize()
        val fileName = "${prefix}profile-interrupted.db"
        val db = BudgetDatabase.open(context, fileName)
        BudgetRepository(db, clock).setup(12345, null, "UTC")
        db.close()
        val catalogDb = WorkspaceDatabase.open(context, catalog)
        catalogDb.workspaceDao().insertProfile(ProfileRow("interrupted", "Recovered", fileName, false, instant.toString()))
        catalogDb.close()
        workspace.initialize()
        assertEquals("Recovered", workspace.state.value.profiles.find { it.id == "interrupted" }!!.name)
        assertEquals(12345L, workspace.repository("interrupted").refresh()!!.monthlyRemaining)
    }

    @Test fun failedSetupDoesNotExposeHalfInitializedBudget() = runBlocking {
        workspace.initialize()
        assertTrue(runCatching { workspace.createBudget("Invalid", -1, null, "UTC") }.isFailure)
        workspace.initialize()
        assertEquals(listOf("legacy"), workspace.state.value.profiles.map { it.id })
        assertNull(workspace.repository("legacy").refresh())
    }

    @Test fun startupFinishesInterruptedExplicitAllDataReset() = runBlocking {
        workspace.initialize()
        workspace.repository("legacy").setup(100, null, "UTC")
        val other = workspace.createBudget("Other", 200, null, "UTC")
        workspace.addSavings(500, "Reserve")
        val catalogDb = WorkspaceDatabase.open(context, catalog)
        catalogDb.workspaceDao().saveSettings(catalogDb.workspaceDao().settings()!!.copy(resetPending = true))
        catalogDb.close()
        workspace.repository(other.id).clear() // Process stopped part way through the explicit reset.
        workspace.close()
        workspace = open()
        workspace.initialize()
        assertEquals(listOf("legacy"), workspace.state.value.profiles.map { it.id })
        assertNull(workspace.repository("legacy").refresh())
        assertEquals(0L, workspace.state.value.savingsBalance)
    }

    // Robolectric includes the method name in its temporary database path on Windows.
    @Test fun clearAllResetsWorkspace() = runBlocking {
        workspace.initialize()
        val default = workspace.repository("legacy")
        default.setup(100, null, "UTC")
        val other = workspace.createBudget("Other", 200, null, "UTC")
        val otherRepo = workspace.repository(other.id)
        workspace.addSavings(500, "Reserve")
        workspace.renameMonth(other.id, "2026-04", "Special")
        workspace.setTheme(ThemeMode.LIGHT)
        workspace.clearAll()
        assertSame(default, workspace.repository("legacy"))
        assertNull(default.state.value)
        assertNull(otherRepo.state.value)
        assertEquals(listOf("legacy"), workspace.state.value.profiles.map { it.id })
        assertEquals(0L, workspace.state.value.savingsBalance)
        assertEquals(ThemeMode.SYSTEM, workspace.state.value.themeMode)
        assertTrue(workspace.state.value.monthNames.isEmpty())
        workspace.close()
        workspace = open()
        workspace.initialize()
        assertNull(workspace.repository("legacy").refresh())
        assertEquals(0L, workspace.state.value.savingsBalance)
    }

    @Test fun calendarReadDoesNotCreateFuturePeriodsOrHistoricalSnapshots() = runBlocking {
        workspace.initialize()
        val repo = workspace.repository("legacy")
        repo.setup(400_000, 500_000, "UTC")
        val before = repo.calendar(YearMonth.parse("2026-04"))!!
        val future = repo.calendar(YearMonth.parse("2026-05"))!!
        assertEquals(1, before.days.count { it.frozenAllowance != null })
        assertTrue(future.days.all { it.frozenAllowance == null && it.estimatedAllowance != null })
        assertEquals(before.periods, future.periods)
        assertEquals(500_000L, future.days.sumOf { it.estimatedAllowance ?: 0 })
        assertEquals(before, repo.calendar(YearMonth.parse("2026-04")))
    }
}
