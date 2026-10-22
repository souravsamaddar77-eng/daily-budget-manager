package dev.daybudget.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A version change requires an explicit Migration and a
 * record-preservation test against exported schemas before shipping an upgrade.
 * Deliberately no destructive migration or downgrade fallback.
 */
@Database(
    entities = [SettingsRow::class, PeriodRow::class, SnapshotRow::class,
        EntryRow::class, AdjustmentRow::class, UndoRow::class, ImportReceiptRow::class],
    version = 2,
    exportSchema = true,
)
abstract class BudgetDatabase : RoomDatabase() {
    abstract fun ledgerDao(): BudgetDao

    companion object {
        const val FILE_NAME = "day-budget.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `import_receipts` (`id` TEXT NOT NULL, `importedAt` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        fun open(context: Context, fileName: String = FILE_NAME): BudgetDatabase =
            Room.databaseBuilder(context.applicationContext, BudgetDatabase::class.java, fileName)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
