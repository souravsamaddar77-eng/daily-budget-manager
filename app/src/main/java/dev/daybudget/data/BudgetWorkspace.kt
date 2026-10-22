package dev.daybudget.data

import android.content.Context
import androidx.room.withTransaction
import dev.daybudget.domain.BudgetException
import dev.daybudget.domain.BudgetProfile
import dev.daybudget.domain.MonthKey
import dev.daybudget.domain.Money
import dev.daybudget.domain.SavingsEntry
import dev.daybudget.domain.ThemeMode
import dev.daybudget.domain.WorkspaceState
import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Profile registration uses a recoverable pending row because catalog and ledgers are separate
 * databases. Only fully initialized profiles are published. Existing day-budget.db is adopted
 * in place, with no copying or deletion of its records.
 */
class BudgetWorkspace(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    catalogName: String = WorkspaceDatabase.FILE_NAME,
    private val legacyDatabaseName: String = BudgetDatabase.FILE_NAME,
    private val databasePrefix: String = "day-budget-profile-",
) : Closeable {
    private val appContext = context.applicationContext
    private val catalog = WorkspaceDatabase.open(appContext, catalogName)
    private val dao = catalog.workspaceDao()
    private val gate = Mutex()
    private val cacheLock = Any()
    private val ledgers = mutableMapOf<String, Pair<BudgetDatabase, BudgetRepository>>()
    private var readyProfiles = emptyMap<String, ProfileRow>()
    private val mutableState = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = mutableState.asStateFlow()

    suspend fun initialize() = gate.withLock { initializeLocked() }

    /** The legacy instance is available before initialization for existing application callers. */
    fun repository(budgetId: String): BudgetRepository = synchronized(cacheLock) {
        val row = readyProfiles[budgetId] ?: if (budgetId == LEGACY_BUDGET_ID) defaultProfile()
            else throw BudgetException("Choose an existing budget.")
        repositoryFor(row)
    }

    suspend fun createBudget(name: String, openingAmount: Long, usualMonthlyBudget: Long?, zoneId: String): BudgetProfile = gate.withLock {
        initializeLocked()
        val cleanName = validName(name)
        val id = UUID.randomUUID().toString()
        val row = ProfileRow(id, cleanName, "$databasePrefix$id.db", false, clock.instant().toString())
        catalog.withTransaction { dao.insertProfile(row) }
        // If execution stops here, initialize either completes registration or removes the empty
        // pending catalog row. A successfully committed ledger is never discarded.
        repositoryFor(row).setup(openingAmount, usualMonthlyBudget, zoneId)
        catalog.withTransaction {
            dao.saveProfile(row.copy(ready = true))
            dao.saveSettings(requireNotNull(dao.settings()).copy(selectedBudgetId = id))
        }
        publishLocked()
        BudgetProfile(id, cleanName)
    }

    suspend fun selectBudget(id: String) = mutate {
        requireProfile(id)
        dao.saveSettings(requireNotNull(dao.settings()).copy(selectedBudgetId = id))
    }

    suspend fun renameBudget(id: String, name: String) = mutate {
        dao.saveProfile(requireProfile(id).copy(name = validName(name)))
    }

    /** Blank text removes the custom label and restores the calendar-month title. */
    suspend fun renameMonth(budgetId: String, periodId: String, name: String) = mutate {
        requireProfile(budgetId)
        if (runCatching { YearMonth.parse(periodId).toString() }.getOrNull() != periodId)
            throw BudgetException("Choose a valid calendar month.")
        if (name.isBlank()) dao.deleteMonthName(budgetId, periodId)
        else dao.saveMonthName(MonthNameRow(budgetId, periodId, validName(name)))
    }

    suspend fun setTheme(mode: ThemeMode) = mutate {
        dao.saveSettings(requireNotNull(dao.settings()).copy(themeMode = mode.name))
    }

    /** Signed deposits/withdrawals affect only savings. A withdrawal cannot exceed savings. */
    suspend fun addSavings(signedAmount: Long, note: String) = mutate {
        if (signedAmount == 0L) throw BudgetException("Enter a non-zero savings change.")
        if (note.trim().length > 500) throw BudgetException("Keep the savings note within 500 characters.")
        val balance = Money.sum(dao.savings().map { it.amount } + signedAmount)
        if (balance < 0) throw BudgetException("A withdrawal cannot exceed your savings balance.")
        dao.insertSavings(SavingsRow(UUID.randomUUID().toString(), signedAmount, note.trim(), clock.instant().toString()))
    }

    /** A durable reset marker resumes an interrupted explicit all-data deletion on next open. */
    suspend fun clearAll() = gate.withLock {
        initializeLocked()
        catalog.withTransaction { dao.saveSettings(requireNotNull(dao.settings()).copy(resetPending = true)) }
        finishResetLocked()
        publishLocked()
    }

    private suspend fun mutate(change: suspend () -> Unit) = gate.withLock {
        initializeLocked()
        catalog.withTransaction { change() }
        publishLocked()
    }

    private suspend fun initializeLocked() {
        catalog.withTransaction {
            dao.insertDefault(defaultProfile())
            if (dao.settings() == null) dao.saveSettings(WorkspaceSettingsRow())
        }
        if (dao.settings()?.resetPending == true) finishResetLocked()
        dao.profiles().filter { !it.ready }.forEach { row ->
            val initialized = repositoryFor(row).refresh() != null
            catalog.withTransaction {
                if (initialized) dao.saveProfile(row.copy(ready = true)) else dao.deleteProfile(row.id)
            }
        }
        catalog.withTransaction {
            val settings = requireNotNull(dao.settings())
            if (dao.profiles().none { it.ready && it.id == settings.selectedBudgetId })
                dao.saveSettings(settings.copy(selectedBudgetId = LEGACY_BUDGET_ID))
        }
        publishLocked()
    }

    private suspend fun finishResetLocked() {
        dao.profiles().forEach { repositoryFor(it).clear() }
        catalog.withTransaction {
            dao.clearNames()
            dao.clearSavings()
            dao.clearProfiles()
            dao.insertDefault(defaultProfile())
            dao.saveSettings(WorkspaceSettingsRow())
        }
    }

    private suspend fun requireProfile(id: String): ProfileRow = dao.profiles().find { it.id == id && it.ready }
        ?: throw BudgetException("Choose an existing budget.")

    private suspend fun publishLocked() {
        val (rows, view) = catalog.withTransaction {
            val profiles = dao.profiles().filter { it.ready }
            val settings = requireNotNull(dao.settings())
            val savings = dao.savings().map { SavingsEntry(it.id, it.amount, it.note, Instant.parse(it.createdAt)) }
            profiles to WorkspaceState(true, profiles.map { BudgetProfile(it.id, it.name) }, settings.selectedBudgetId,
                ThemeMode.valueOf(settings.themeMode), Money.sum(savings.map { it.amount }), savings,
                dao.monthNames().associate { MonthKey(it.budgetId, it.periodId) to it.name })
        }
        synchronized(cacheLock) { readyProfiles = rows.associateBy { it.id } }
        mutableState.value = view
    }

    private fun repositoryFor(row: ProfileRow): BudgetRepository = synchronized(cacheLock) {
        ledgers.getOrPut(row.id) {
            val database = BudgetDatabase.open(appContext, row.fileName)
            database to BudgetRepository(database, clock)
        }.second
    }

    private fun defaultProfile() = ProfileRow(LEGACY_BUDGET_ID, "My budget", legacyDatabaseName, true, "1970-01-01T00:00:00Z")
    private fun validName(value: String): String = value.trim().also {
        if (it.isEmpty() || it.length > 80) throw BudgetException("Use a name between 1 and 80 characters.")
    }

    /** Call only after users of the workspace have stopped; normal app switching keeps instances. */
    override fun close() {
        synchronized(cacheLock) { ledgers.values.forEach { it.first.close() }; ledgers.clear() }
        catalog.close()
    }

    companion object { const val LEGACY_BUDGET_ID = "legacy" }
}
