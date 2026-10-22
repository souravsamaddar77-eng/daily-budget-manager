# Accounting and storage

The application separates accounting, persistence, presentation, and optional notification capture:

- The domain layer contains immutable records, exact money arithmetic, BudgetEngine, and BudgetCalendarEngine. An injected clock makes date boundaries reproducible.
- The data layer contains Room databases, BudgetRepository, and BudgetWorkspace. Repositories own ledger transactions; the workspace owns profiles, names, selection, theme, and savings.
- The UI layer contains Compose screens and retained ViewModels. A ViewModel is keyed by budget ID, so a pending save keeps its original destination when selection changes.
- The notifications layer parses supported notifications, persists extracted candidates and decisions, imports through the same repository, and posts optional expense alerts.

## Accounting invariants

Monthly remaining is opening amount plus explicit adjustments minus included expenses plus included refunds. Money uses signed Long paise, parsed without floating point. Exact intermediate arithmetic rejects overflow rather than wrapping; SQL aggregates do not bypass this validation.

A daily snapshot stores the nonnegative monthly remaining divided by inclusive days left, truncated to whole paise. It is unique by period/date and created before the first operation of that budgeting date. Corrections, refunds, and undo never rewrite an existing snapshot. Assigning an allowance does not spend money. Reopening after several days creates the current snapshot without inventing missed snapshots.

Today remaining is the frozen allowance minus today's included expenses plus today's included refunds. Deficits remain signed. Each month has its own opening amount and immutable timezone; no surplus or deficit carries over automatically. The usual monthly budget applies separately from the initial partial-month amount. A missing usual budget creates an unconfigured month; an explicit adjustment can configure it without changing today's snapshot.

Reconciliation creates intervening month records using the saved settings, without creating historical daily allowances. A scheduled timezone takes effect at the next month's start in the new zone, which can shorten or extend the final old budgeting date.

The opening amount already accounts for earlier spending. Manual entries for today use the current instant; historical dates use midnight in that period's zone. New historical entries on or before the setup date are excluded because their time relative to setup is unknown. Same-date edits preserve the recorded instant and inclusion. Imports retain their exact event instant when consistent with the chosen date and reviewed effects. A reviewed pre-setup payment cannot become new spending merely by selecting a budget created later on the same day.

Refunds are separate positive amounts with refund type. Optional links require an existing expense, cannot predate it, and cannot exceed its amount in total. Future entry dates are rejected. Previews protect moves across month or tracking boundaries; confirmed effects are checked again inside the transaction.

## Independent budgets and savings

The workspace adopts the original day-budget.db as the default profile in place. Other budgets use separate ledger database files and cached, stable repository instances. Each budget has its own entries, settings, periods, snapshots, adjustments, and undo record.

The new day-budget-workspace.db catalog stores profile names and file references, selected profile, theme, custom month names, and savings entries. Renaming a budget or month changes metadata only. Savings deposits and withdrawals never affect any spending ledger. Savings totals use exact arithmetic, and withdrawals cannot exceed the savings balance.

Creating a budget first records a pending catalog row, then initializes its ledger, then publishes the ready profile. Startup completes a registration whose ledger committed, or removes an empty pending registration. This handles interruption across the two databases without discarding a committed ledger.

Explicit Delete everything disables and clears notification capture, then resets the workspace. A durable catalog reset marker resumes interrupted ledger/catalog deletion on the next initialization. Cached repository instances publish cleared state; selection, names, savings, and theme reset with the catalog. Snapshot deletion is allowed only through explicit data clearing.

## Calendar values

Calendar reads reconcile the current day through the repository before deriving a view. Past dates show actual recorded expenses/refunds and a frozen allowance only when one exists. Missing historical allowances remain unknown. Recorded totals include excluded entries, while remaining amounts use only included entries; the view exposes the excluded count separately.

Future allowances are estimates, not persisted snapshots. Within the current month, the estimate assumes no more spending today and then spending each projected daily allowance. Future months use the usual monthly budget, if configured. Integer division and a remainder allocation conserve the forecast pool. Known later snapshots or records, such as after a backward clock change, are not overwritten with forecasts.

## Transactions and migrations

BudgetRepository captures one instant after entering its Room transaction, loads the ledger, ensures the day, applies validation, and persists all changes together. Snapshot creation and mutation either both commit or both roll back. A mutex keeps StateFlow publication in commit order; failed writes publish no partial result. Ordinary edits use stable IDs, while import IDs are insert-once.

Ledger schema version 2 adds import_receipts to the original settings, periods, snapshots, entries, adjustments, and undo tables. The explicit MIGRATION_1_2 adds this table without rewriting existing records. Both [version 1](../app/schemas/dev.daybudget.data.BudgetDatabase/1.json) and [version 2](../app/schemas/dev.daybudget.data.BudgetDatabase/2.json) exports are retained. The separate catalog begins at [version 1](../app/schemas/dev.daybudget.data.WorkspaceDatabase/1.json). Unknown versions fail closed; there is no destructive fallback.

The migration test creates the actual exported version-1 schema, inserts linked entries, adjustments, snapshots, settings, and undo, then checks every original field after migration and exercises the restored ledger. Future schema changes must retain exports, supply explicit migrations, and extend preservation tests.

## Notification capture and retry

Detection is off by default and requires Android notification-listener access plus explicit app and destination selection. The listener filters the enabled package before reading text and ignores grouped summaries. It does not scan existing notifications when connected. Current parser patterns cover conservative English templates for PhonePe, Google Pay, and Paytm; they are not a claim that every live template is supported.

Ambiguous, malformed, incoming, failed, pending, or refund notifications are rejected. A missing unique reference or unsafe merchant requires review instead of automatic entry. Raw notification text and raw payment references are not stored. Persistent candidates contain only extracted amount, merchant, package, time/date, destination, reason, and a hashed reference/ID.

Capture facts commit before import. A durable destination assignment prevents a crash retry from routing the same payment to another ledger. Inside the ledger transaction, importIfAbsent commits an entry and receipt together. A duplicate never overwrites a user's edit; the receipt also prevents replay after deletion. Capture decisions and alert receipts persist separately. Storage corruption pauses capture rather than discarding duplicate protection.

Reviewed candidates can correct fields and use the existing accounting preview. Automatic imports check timestamp/date consistency in the destination period's zone. A rejected import stays in the review queue. Without a reference, reliable cross-notification matching is unavailable and the user must check for a manually recorded purchase.

Optional saved-expense and overspending alerts use separate Android channels. Alert receipts are committed before posting, preferring a missed alert after a crash over repeated sound. Lock-screen content is private with a generic public version. Sound respects Android channel settings and does not bypass Do Not Disturb.

## UI state and validation

Drafts, selected dates, and open forms use Compose saveable state. ViewModels retain pending previews and completed operations across Activity recreation. Completion keys combine a ViewModel session ID and operation number; results remain available after acknowledgement so a replacement composition can reconcile older saved state. Writes stay disabled while a completion awaits acknowledgement.

These UI results are not process-death recovery records: committed Room data is authoritative after restart. Scrollable forms handle keyboard insets, and large-font layouts avoid relying on narrow calendar cells or side-by-side amount rows.

Room and Compose instrumentation sources are also shared with debug host tests under Robolectric API 35, native SQLite, and a paused looper. A debug-only unexported Activity provides font-scale fixtures before view creation. Host tests, native emulator tests, synthetic notification callbacks, and actual manual rotation are distinct forms of evidence. Current measured outcomes and limitations belong in [VALIDATION.md](VALIDATION.md).

## Privacy boundary

The app requests notification-posting permission and declares a notification listener protected by Android's binding permission. Notification access is granted separately in Android settings. There is no SMS permission, network client, analytics SDK, or account-credential collection. Supported notifications are processed locally; unrelated notification text is not read by the listener code.

Ledgers, savings, candidates, and receipts remain in app-private storage. Room alone is not encryption. Backup is disabled and paired with legacy and Android 12+ backup/device-transfer exclusions. No export or restore exists; uninstalling or clearing app data can lose the records. No physical phone is required for the automated test harness, and real payment-app interoperability still requires suitable consented examples and testing.
