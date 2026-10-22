package dev.daybudget.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Entity(tableName = "profiles")
data class ProfileRow(@PrimaryKey val id: String, val name: String, val fileName: String,
                      val ready: Boolean, val createdAt: String)

@Entity(tableName = "workspace_settings")
data class WorkspaceSettingsRow(@PrimaryKey val singleton: Int = 1,
    val selectedBudgetId: String = "legacy", val themeMode: String = "SYSTEM", val resetPending: Boolean = false)

@Entity(tableName = "month_names", primaryKeys = ["budgetId", "periodId"],
    foreignKeys = [ForeignKey(entity = ProfileRow::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)])
data class MonthNameRow(val budgetId: String, val periodId: String, val name: String)

@Entity(tableName = "savings_entries")
data class SavingsRow(@PrimaryKey val id: String, val amount: Long, val note: String, val createdAt: String)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt, id") suspend fun profiles(): List<ProfileRow>
    @Query("SELECT * FROM workspace_settings WHERE singleton = 1") suspend fun settings(): WorkspaceSettingsRow?
    @Query("SELECT * FROM month_names") suspend fun monthNames(): List<MonthNameRow>
    @Query("SELECT * FROM savings_entries ORDER BY createdAt DESC, id DESC") suspend fun savings(): List<SavingsRow>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDefault(row: ProfileRow)
    @Insert suspend fun insertProfile(row: ProfileRow)
    @Upsert suspend fun saveProfile(row: ProfileRow)
    @Upsert suspend fun saveSettings(row: WorkspaceSettingsRow)
    @Upsert suspend fun saveMonthName(row: MonthNameRow)
    @Insert suspend fun insertSavings(row: SavingsRow)
    @Query("DELETE FROM month_names WHERE budgetId = :budgetId AND periodId = :periodId")
    suspend fun deleteMonthName(budgetId: String, periodId: String)
    @Query("DELETE FROM profiles WHERE id = :id") suspend fun deleteProfile(id: String)
    @Query("DELETE FROM month_names") suspend fun clearNames()
    @Query("DELETE FROM savings_entries") suspend fun clearSavings()
    @Query("DELETE FROM profiles") suspend fun clearProfiles()
}

/** Independent catalog; budget money remains in its own ledger database. */
@Database(entities = [ProfileRow::class, WorkspaceSettingsRow::class, MonthNameRow::class, SavingsRow::class],
    version = 1, exportSchema = true)
abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    companion object {
        const val FILE_NAME = "day-budget-workspace.db"
        fun open(context: Context, fileName: String = FILE_NAME): WorkspaceDatabase =
            Room.databaseBuilder(context.applicationContext, WorkspaceDatabase::class.java, fileName).build()
    }
}
