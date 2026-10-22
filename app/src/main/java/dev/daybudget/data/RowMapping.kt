package dev.daybudget.data

import dev.daybudget.domain.BudgetAdjustment
import dev.daybudget.domain.BudgetPeriod
import dev.daybudget.domain.DailySnapshot
import dev.daybudget.domain.Entry
import dev.daybudget.domain.EntryType
import dev.daybudget.domain.Settings
import dev.daybudget.domain.UndoRecord
import java.time.Instant
import java.time.LocalDate

internal fun Settings.toRow() = SettingsRow(
    zoneId = zoneId, activatedAt = activatedAt.toString(), activationDate = activationDate.toString(),
    usualMonthlyBudget = usualMonthlyBudget, futureZoneId = futureZoneId,
    futureZoneEffectiveMonth = futureZoneEffectiveMonth,
)

internal fun SettingsRow.toDomain() = Settings(
    zoneId, Instant.parse(activatedAt), LocalDate.parse(activationDate), usualMonthlyBudget,
    futureZoneId, futureZoneEffectiveMonth,
)

internal fun BudgetPeriod.toRow() = PeriodRow(
    id, start.toString(), end.toString(), zoneId, openingAmount, budgetConfigured, activatedAt.toString(),
)

internal fun PeriodRow.toDomain() = BudgetPeriod(
    id, LocalDate.parse(start), LocalDate.parse(end), zoneId, openingAmount, budgetConfigured,
    Instant.parse(activatedAt),
)

internal fun DailySnapshot.toRow() = SnapshotRow(
    periodId, date.toString(), startingAllowance, createdAt.toString(), calculationRevision,
)

internal fun SnapshotRow.toDomain() = DailySnapshot(
    periodId, LocalDate.parse(date), startingAllowance, Instant.parse(createdAt), calculationRevision,
)

internal fun Entry.toColumns() = EntryColumns(
    id, type.name, amount, date.toString(), periodId, occurredAt.toString(), createdAt.toString(),
    updatedAt.toString(), included, merchant, category, note, linkedExpenseId, source,
)

internal fun EntryColumns.toDomain() = Entry(
    id, EntryType.valueOf(type), amount, LocalDate.parse(date), periodId,
    Instant.parse(occurredAt), Instant.parse(createdAt), Instant.parse(updatedAt), included,
    merchant, category, note, linkedExpenseId, source,
)

internal fun BudgetAdjustment.toRow() = AdjustmentRow(id, periodId, amount, reason, createdAt.toString())
internal fun AdjustmentRow.toDomain() = BudgetAdjustment(id, periodId, amount, reason, Instant.parse(createdAt))
internal fun UndoRecord.toRow() = UndoRow(entryId = entryId, previous = previous?.toColumns(), next = next?.toColumns())
internal fun UndoRow.toDomain() = UndoRecord(entryId, previous?.toDomain(), next?.toDomain())
