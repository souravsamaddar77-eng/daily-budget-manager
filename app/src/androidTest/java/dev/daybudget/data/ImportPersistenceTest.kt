package dev.daybudget.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybudget.domain.Command
import dev.daybudget.domain.EntryDraft
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
class ImportPersistenceTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: BudgetDatabase
    private lateinit var repository: BudgetRepository
    private val at = Instant.parse("2026-04-01T06:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val draft = EntryDraft(id = "notification-stable-id", amount = 2000, date = LocalDate.parse("2026-04-01"))

    @Before fun before() {
        context = ApplicationProvider.getApplicationContext()
        name = "import-test-${UUID.randomUUID()}.db"
        open()
    }
    private fun open() { db = BudgetDatabase.open(context, name); repository = BudgetRepository(db, clock) }
    @After fun after() { db.close(); context.deleteDatabase(name) }

    @Test fun duplicateImportPreservesUserEditsAndDeletedEntryAcrossRestart() = runBlocking {
        repository.setup(400_000, null, "UTC")
        val first = repository.importIfAbsent(draft, occurredAt = at)
        assertTrue(first.inserted)
        assertEquals(400_000L, first.before.monthlyRemaining)
        assertEquals(398_000L, first.after.monthlyRemaining)
        repository.execute(Command.UpsertEntry(draft.copy(amount = 3000, merchant = "Corrected")))
        val replay = repository.importIfAbsent(draft, occurredAt = at)
        assertFalse(replay.inserted)
        assertEquals(3000L, replay.entry!!.amount)
        assertEquals("Corrected", replay.entry.merchant)
        assertEquals("NOTIFICATION", replay.entry.source)
        repository.execute(Command.DeleteEntry(draft.id))
        db.close()
        open()
        val afterDeletion = repository.importIfAbsent(draft, occurredAt = at)
        assertFalse(afterDeletion.inserted)
        assertNull(afterDeletion.entry)
        assertTrue(afterDeletion.after.entries.isEmpty())
        assertEquals(400_000L, afterDeletion.after.monthlyRemaining)
        assertNotNull(db.ledgerDao().importReceipt(draft.id))
    }

    @Test fun concurrentImportsAcrossRepositoriesInsertOnlyOnce() = runBlocking {
        repository.setup(400_000, null, "UTC")
        val second = BudgetRepository(db, clock)
        val results = coroutineScope {
            (0..19).map { index -> async {
                (if (index % 2 == 0) repository else second).importIfAbsent(draft, occurredAt = at)
            } }.awaitAll()
        }
        assertEquals(1, results.count { it.inserted })
        assertEquals(1, repository.refresh()!!.entries.size)
        assertEquals(398_000L, repository.state.value!!.monthlyRemaining)
    }

    @Test fun receiptFailureRollsBackEntryAndPublishedState() = runBlocking {
        val before = repository.setup(400_000, null, "UTC")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_receipt BEFORE INSERT ON import_receipts BEGIN SELECT RAISE(ABORT, 'test receipt failure'); END")
        assertTrue(runCatching { repository.importIfAbsent(draft, occurredAt = at) }.isFailure)
        assertEquals(before, repository.state.value)
        assertTrue(db.ledgerDao().entries().isEmpty())
        assertNull(db.ledgerDao().importReceipt(draft.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_receipt")
        assertTrue(repository.importIfAbsent(draft, occurredAt = at).inserted)
    }

    @Test fun preActivationImportAndManualIdCollisionNeverReduceBudgetTwice() = runBlocking {
        repository.setup(400_000, null, "UTC")
        val historical = repository.importIfAbsent(draft, occurredAt = at.minusSeconds(1))
        assertFalse(historical.entry!!.included)
        assertEquals(400_000L, historical.after.monthlyRemaining)
        val manual = draft.copy(id = "manual-id", amount = 1000)
        repository.execute(Command.UpsertEntry(manual))
        val collision = repository.importIfAbsent(manual.copy(amount = 9999), occurredAt = at)
        assertFalse(collision.inserted)
        assertEquals(1000L, collision.entry!!.amount)
        assertEquals("MANUAL", collision.entry.source)
        assertEquals(399_000L, collision.after.monthlyRemaining)
    }
}
