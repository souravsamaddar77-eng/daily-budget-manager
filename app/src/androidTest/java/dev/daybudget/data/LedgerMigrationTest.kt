package dev.daybudget.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybudget.domain.Command
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Creates the real exported version-1 schema, then checks every old table after migration. */
@RunWith(AndroidJUnit4::class)
class LedgerMigrationTest {
    @Test fun versionOneMigrationPreservesEveryLedgerTableAndUndo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test-${UUID.randomUUID()}.db"
        val at = Instant.parse("2026-04-01T06:00:00Z")
        val schemaPath = "dev.daybudget.data.BudgetDatabase/1.json"
        // Host tests use the retained export as a classpath resource; native tests use test-APK assets.
        val schemaStream = LedgerMigrationTest::class.java.classLoader?.getResourceAsStream(schemaPath)
            ?: InstrumentationRegistry.getInstrumentation().context.assets.open(schemaPath)
        val schema = schemaStream.bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val tableNames = listOf("budget_settings", "budget_periods", "daily_snapshots", "ledger_entries", "budget_adjustments", "entry_undo")
        try {
            val before = SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    raw.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (item in 0 until indices.length())
                            raw.execSQL(indices.getJSONObject(item).getString("createSql").replace("$" + "{TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) raw.execSQL(setup.getString(index))
                raw.execSQL("PRAGMA user_version = 1")
                raw.execSQL("INSERT INTO budget_settings VALUES(1, 'UTC', ?, '2026-04-01', 500000, 'Asia/Kolkata', '2026-05')", arrayOf(at.toString()))
                raw.execSQL("INSERT INTO budget_periods VALUES('2026-04', '2026-04-01', '2026-04-30', 'UTC', 400000, 1, ?)", arrayOf(at.toString()))
                raw.execSQL("INSERT INTO daily_snapshots VALUES('2026-04', '2026-04-01', 13333, ?, 1)", arrayOf(at.toString()))
                val purchase = entryValues("purchase", "EXPENSE", 20000, at.toString(), null)
                val refund = entryValues("refund", "REFUND", 4000, at.toString(), "purchase")
                raw.insertOrThrow("ledger_entries", null, purchase)
                raw.insertOrThrow("ledger_entries", null, refund)
                raw.execSQL("INSERT INTO budget_adjustments VALUES('adjustment', '2026-04', 50000, 'Seed adjustment', ?)", arrayOf(at.toString()))
                val undo = ContentValues().apply {
                    put("singleton", 1); put("entryId", "refund")
                    refund.valueSet().forEach { (key, value) ->
                        putNull("previous_$key")
                        if (value == null) putNull("next_$key") else put("next_$key", value.toString())
                    }
                }
                raw.insertOrThrow("entry_undo", null, undo)
                tableNames.associateWith { table ->
                    raw.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use { cursor ->
                        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
                    }
                }
            }
            val migrated = BudgetDatabase.open(context, name)
            try {
                val sql = migrated.openHelper.readableDatabase // Room validates the migrated schema.
                val after = tableNames.associateWith { table ->
                    sql.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
                    }
                }
                assertEquals(before, after)
                sql.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
                sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                assertNull(migrated.ledgerDao().importReceipt("purchase"))
                val repository = BudgetRepository(migrated, Clock.fixed(at, ZoneOffset.UTC))
                val restored = repository.refresh()!!
                assertEquals(434_000L, restored.monthlyRemaining)
                assertEquals(13_333L, restored.startingAllowance)
                assertEquals(2, restored.entries.size)
                assertTrue(restored.canUndo)
                val undone = repository.execute(Command.Undo)
                assertEquals(430_000L, undone.monthlyRemaining)
                assertEquals(listOf("purchase"), undone.entries.map { it.id })
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun entryValues(id: String, type: String, amount: Long, at: String, linkedId: String?) = ContentValues().apply {
        put("id", id); put("type", type); put("amount", amount); put("date", "2026-04-01"); put("periodId", "2026-04")
        put("occurredAt", at); put("createdAt", at); put("updatedAt", at); put("included", 1)
        put("merchant", "Original shop"); put("category", "Food"); put("note", "Version one note")
        if (linkedId == null) putNull("linkedExpenseId") else put("linkedExpenseId", linkedId)
        put("source", "MANUAL")
    }
}
