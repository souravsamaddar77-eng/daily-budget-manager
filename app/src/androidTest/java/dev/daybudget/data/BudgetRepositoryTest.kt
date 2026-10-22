package dev.daybudget.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybudget.domain.Command
import dev.daybudget.domain.EntryDraft
import dev.daybudget.domain.EntryType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Shared by Robolectric host tests and device instrumentation; no production database is opened. */
@RunWith(AndroidJUnit4::class)
class BudgetRepositoryTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: BudgetDatabase
    private lateinit var repository: BudgetRepository
    private lateinit var clock: TestClock

    @Before fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        name = "budget-test-${UUID.randomUUID()}.db"
        clock = TestClock(Instant.parse("2026-04-01T03:30:00Z"))
        openDatabase()
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, BudgetDatabase::class.java, name).addMigrations(BudgetDatabase.MIGRATION_1_2).build()
        repository = BudgetRepository(database, clock)
    }

    @After fun deleteDatabase() {
        database.close()
        context.deleteDatabase(name)
    }

    private suspend fun setup() = repository.setup(400_000, 500_000, "Asia/Kolkata")

    private fun expense(id: String = UUID.randomUUID().toString(), amount: Long = 20_000) =
        EntryDraft(id = id, amount = amount, date = LocalDate.parse("2026-04-01"), merchant = "Local shop")

    @Test fun restartPreservesExactMoneyFrozenSnapshotAndUndo() = runBlocking {
        setup()
        val draft = expense(id = "purchase")
        repository.execute(Command.UpsertEntry(draft))
        repository.execute(Command.DeleteEntry(draft.id))
        database.close()
        openDatabase()

        val restored = requireNotNull(repository.refresh())
        assertEquals(400_000L, restored.monthlyRemaining)
        assertEquals(13_333L, restored.startingAllowance)
        assertTrue(restored.canUndo)
        val undone = repository.execute(Command.Undo)
        assertEquals(380_000L, undone.monthlyRemaining)
        assertEquals(-6_667L, undone.todayRemaining)
        assertEquals("purchase", undone.entries.single().id)
        assertFalse(undone.canUndo)
        assertEquals(1, database.ledgerDao().snapshots().size)
    }

    @Test fun concurrentSavesAcrossRepositoriesDoNotLoseOrDoubleCountEntries() = runBlocking {
        setup()
        val otherRepository = BudgetRepository(database, clock)
        coroutineScope {
            (0 until 50).map { index ->
                async(Dispatchers.Default) {
                    val writer = if (index % 2 == 0) repository else otherRepository
                    writer.execute(Command.UpsertEntry(expense("purchase-$index", 1_000)))
                }
            }.awaitAll()
        }
        val view = requireNotNull(repository.refresh())
        assertEquals(50, view.entries.size)
        assertEquals(50, view.entries.map { it.id }.toSet().size)
        assertEquals(350_000L, view.monthlyRemaining)
        assertEquals(-36_667L, view.todayRemaining)
        assertEquals(1, database.ledgerDao().snapshots().size)
    }

    @Test fun firstWriteOfDaySnapshotsPreviouslyCommittedLedger() = runBlocking {
        setup()
        repository.execute(Command.UpsertEntry(expense()))
        clock.now = Instant.parse("2026-04-02T03:30:00Z")
        val view = repository.execute(Command.UpsertEntry(
            expense(amount = 10_000).copy(date = LocalDate.parse("2026-04-02")),
        ))
        assertEquals(13_103L, view.startingAllowance)
        assertEquals(3_103L, view.todayRemaining)
        assertEquals(370_000L, view.monthlyRemaining)
        assertEquals(2, database.ledgerDao().snapshots().size)
    }

    @Test fun failedDatabaseWriteRollsBackNewDayAndDoesNotPublishPartialState() = runBlocking {
        setup()
        repository.execute(Command.UpsertEntry(expense(id = "old")))
        val committedView = repository.state.value
        clock.now = Instant.parse("2026-04-02T03:30:00Z")
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_test_insert BEFORE INSERT ON ledger_entries
            WHEN NEW.amount = 101 BEGIN SELECT RAISE(ABORT, 'Test write fault'); END
        """.trimIndent())
        val draft = expense(id = "new", amount = 101).copy(date = LocalDate.parse("2026-04-02"))

        val failure = runCatching { repository.execute(Command.UpsertEntry(draft)) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(committedView, repository.state.value)
        assertEquals(listOf("old"), database.ledgerDao().entries().map { it.entry.id })
        assertEquals(listOf("2026-04-01"), database.ledgerDao().snapshots().map { it.date })

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_insert")
        database.close()
        openDatabase()
        val retried = repository.execute(Command.UpsertEntry(draft))
        assertEquals(13_103L, retried.startingAllowance)
        assertEquals(379_899L, retried.monthlyRemaining)
        assertEquals(2, database.ledgerDao().snapshots().size)
    }

    @Test fun linkedRefundConstraintAndCorrectionSurviveRestart() = runBlocking {
        setup()
        repository.execute(Command.UpsertEntry(expense(id = "purchase", amount = 10_000)))
        repository.execute(Command.UpsertEntry(EntryDraft(
            id = "refund", type = EntryType.REFUND, amount = 4_000,
            date = LocalDate.parse("2026-04-01"), linkedExpenseId = "purchase",
        )))
        val before = repository.state.value
        assertTrue(runCatching { repository.execute(Command.DeleteEntry("purchase")) }.isFailure)
        assertTrue(runCatching {
            repository.execute(Command.UpsertEntry(EntryDraft(
                type = EntryType.REFUND, amount = 6_001,
                date = LocalDate.parse("2026-04-01"), linkedExpenseId = "purchase",
            )))
        }.isFailure)
        assertEquals(before, repository.state.value)
        assertTrue(runCatching {
            database.openHelper.writableDatabase.execSQL("DELETE FROM ledger_entries WHERE id = 'purchase'")
        }.isFailure)
        database.close()
        openDatabase()
        val view = requireNotNull(repository.refresh())
        assertEquals(394_000L, view.monthlyRemaining)
        assertEquals(10_000L, view.todayExpenses)
        assertEquals(4_000L, view.todayRefunds)
        assertEquals("purchase", view.entries.single { it.type == EntryType.REFUND }.linkedExpenseId)
    }

    @Test fun previewAndRepeatedRefreshNeverRewriteExistingSnapshots() = runBlocking {
        setup()
        val original = database.ledgerDao().snapshots().single()
        repository.preview(expense(id = "draft"))
        repository.execute(Command.AdjustBudget(50_000, "Extra spending budget"))
        repeat(3) { repository.refresh() }
        assertEquals(original, database.ledgerDao().snapshots().single())
        assertEquals(450_000L, requireNotNull(repository.state.value).monthlyRemaining)
        assertTrue(database.ledgerDao().entries().isEmpty())
    }

    @Test fun crossMonthCorrectionKeepsOldPeriodAndBothFrozenSnapshots() = runBlocking {
        clock.now = Instant.parse("2026-04-30T03:30:00Z")
        setup()
        val draft = expense(id = "move-me").copy(date = LocalDate.parse("2026-04-30"))
        repository.execute(Command.UpsertEntry(draft))
        clock.now = Instant.parse("2026-05-01T03:30:00Z")
        val newMonth = requireNotNull(repository.refresh())
        assertEquals(500_000L, newMonth.monthlyRemaining)
        assertEquals(16_129L, newMonth.startingAllowance)
        val originalSnapshots = database.ledgerDao().snapshots()
        val movedDraft = draft.copy(date = LocalDate.parse("2026-05-01"))
        assertTrue(repository.preview(movedDraft).requiresConfirmation)
        repository.execute(Command.UpsertEntry(movedDraft, acceptBoundaryChange = true))
        database.close()
        openDatabase()
        val corrected = requireNotNull(repository.refresh())
        assertEquals(480_000L, corrected.monthlyRemaining)
        assertEquals(-3_871L, corrected.todayRemaining)
        assertEquals(originalSnapshots, database.ledgerDao().snapshots())
        assertEquals(listOf("2026-04", "2026-05"), database.ledgerDao().periods().map { it.id })
        assertEquals("2026-05", database.ledgerDao().entries().single().entry.periodId)
    }

    @Test fun savedConfigurationAppliesOnlyAtNextPeriodAfterRestart() = runBlocking {
        setup()
        repository.execute(Command.Configure(600_000, "America/New_York"))
        repository.execute(Command.AdjustBudget(-25_000, "Revised April budget"))
        database.close()
        openDatabase()
        val april = requireNotNull(repository.refresh())
        assertEquals("Asia/Kolkata", april.period.zoneId)
        assertEquals("America/New_York", april.settings.futureZoneId)
        assertEquals(375_000L, april.monthlyRemaining)
        assertEquals(-25_000L, database.ledgerDao().adjustments().single().amount)

        clock.now = Instant.parse("2026-05-01T12:00:00Z")
        val may = requireNotNull(repository.refresh())
        assertEquals("America/New_York", may.period.zoneId)
        assertEquals(600_000L, may.monthlyRemaining)
        assertEquals(19_354L, may.startingAllowance)
        assertEquals("Asia/Kolkata", database.ledgerDao().periods().first().zoneId)
    }

    @Test fun deletingAllDataAlsoClearsRefundsUndoAndHistory() = runBlocking {
        setup()
        repository.execute(Command.UpsertEntry(expense(id = "purchase")))
        repository.execute(Command.UpsertEntry(EntryDraft(
            type = EntryType.REFUND, amount = 100, date = LocalDate.parse("2026-04-01"),
            linkedExpenseId = "purchase",
        )))
        repository.clear()
        assertNull(repository.state.value)
        database.close()
        openDatabase()
        assertNull(repository.refresh())
        assertTrue(database.ledgerDao().entries().isEmpty())
        assertTrue(database.ledgerDao().periods().isEmpty())
        assertTrue(database.ledgerDao().snapshots().isEmpty())
        assertNull(database.ledgerDao().undo())
    }

    @Test fun freshSchemaIsVersionTwoWithUniqueSnapshotKey() = runBlocking {
        setup()
        val sql = database.openHelper.readableDatabase
        sql.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }
        val original = database.ledgerDao().snapshots().single()
        assertTrue(runCatching { database.ledgerDao().insertSnapshot(original) }.isFailure)
        assertEquals(listOf(original), database.ledgerDao().snapshots())
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    /** Unknown newer versions must still fail closed, rather than erase records. */
    @Test fun unknownVersionFailsClosedInsteadOfDestroyingRecords() = runBlocking {
        setup()
        repository.execute(Command.UpsertEntry(expense(id = "preserve-me")))
        database.openHelper.writableDatabase.execSQL("PRAGMA user_version = 3")
        database.close()
        openDatabase()
        assertTrue(runCatching { repository.refresh() }.isFailure)
        database.close()

        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("SELECT id, amount FROM ledger_entries", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("preserve-me", it.getString(0))
                assertEquals(20_000L, it.getLong(1))
                assertFalse(it.moveToNext())
            }
        }
    }
}

private class TestClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)
}
