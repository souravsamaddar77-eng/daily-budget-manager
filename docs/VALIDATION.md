# Validation record

Recorded on 22 October 2026 for DayBudget v0.2.0-debug. All **116 host tests pass**, with zero failures, errors, or skipped tests. Debug and release Android lint both report zero issues. The final APK passed all **45 native tests on Android 8** and all **45 on Android 16**, with empty crash buffers. No physical phone was accessed.

## Current checks

| Check | Result |
|---|---|
| Host tests | 116 passed: 71 pure JVM tests and 45 shared Android-dependent tests |
| Debug and release lint | Zero errors and zero warnings in both variants |
| App and test APK compilation | Passed; debug app and instrumentation APKs built |
| API 26 native regression | 45 passed in 53.856 seconds; empty crash buffer |
| API 36 native regression | 45 passed in 69.801 seconds; empty crash buffer |
| API 26 upgrade and manual flows | Legacy records preserved; independent budgets, savings, naming, theme, large text, and rotation checked |
| API 36 notification integration | Actual Android consent and listener flow exercised using a controlled synthetic fixture |
| Native-library alignment | All four packaged ABIs passed static 16 KB ZIP-offset and ELF PT_LOAD alignment checks; this is not a 16 KB runtime test |
| Physical phone / real payment apps | Not tested; no physical phone accessed |

The host suite runs through testDebugUnitTest. Shared Android tests use Robolectric 4.17, simulated API 35, native SQLite, and PAUSED looper mode. Host execution and native instrumentation are reported separately. Build settings and the host crypto-provider workaround are described in [TOOLCHAIN.md](TOOLCHAIN.md).

Generated local reports are under app/build/reports and app/build/test-results. Exported emulator evidence is in [API 26](validation/api26/manual-checks.jsonl) and [API 36](validation/api36/manual-checks.jsonl). The [APK manifest inspection](validation/artifact/apk-badging.txt), [signature report](validation/artifact/signature-final.txt), and [ZIP alignment report](validation/artifact/zip-alignment.txt) are retained separately.

Detailed [API 26](validation/api26/manual-report.md) and [API 36](validation/api36/manual-report.md) manual reports identify the tested flows and candidate-build attribution.

The final debug APK is **13,015,677 bytes**, application ID `dev.daybudget.debug`, version code 2. SHA-256: `e2d62be8f4f9a0e36744ac8337601595de496db1dbd73936ed98e01c62afb9d0`. Its certificate matches the preceding debug APK, allowing an in-place update. See the [machine-readable summary](validation/artifact/test-summary.json) and native results for [API 26](validation/api26/results.json) and [API 36](validation/api36/results.json).

## Automated coverage

| Area | Tests | Main assertions |
|---|---:|---|
| Budget engine and money | 44 | Exact paise, month/day boundaries, frozen allowances, corrections, linked refunds, undo, overflow, timezone changes, and stale previews |
| Calendar and imported events | 10 | Missing historical allowances remain unknown, explicit future forecasts, exact event dates/times, activation boundaries, and duplicate preservation |
| Notification parser and alert policy | 17 | Conservative outgoing templates, malformed/ambiguous amounts, references, redaction, quiet hours, and alert eligibility |
| Room repositories and workspace | 26 | Restart persistence, concurrency, isolation, savings overflow/overdraw, rollback, durable receipts, legacy adoption, interrupted registration/reset, and real v1-to-v2 migration |
| Notification storage and import | 10 | Private persistent HMAC identity, missing/corrupt-key failure, no raw-text retention, callback filtering, consent checks, replay, reviewed corrections, and crash retry |
| Compose workflows and resilience | 9 | Setup, expenses/refunds/undo, independent budgets, savings, calendar/names, theme, capture settings/review, restored drafts, large text, and delayed commit completion |

The migration test creates the retained version-1 schema, inserts settings, periods, snapshots, linked entries, adjustments, and undo, then compares every original field after migration and exercises restored undo. Version 2 adds import receipts without deleting the original tables. Unknown schema versions fail closed. [Schema 1](../app/schemas/dev.daybudget.data.BudgetDatabase/1.json), [schema 2](../app/schemas/dev.daybudget.data.BudgetDatabase/2.json), and the [catalog schema](../app/schemas/dev.daybudget.data.WorkspaceDatabase/1.json) are retained.

Import tests prove that a repeated ID does not overwrite an edit or recreate a deleted entry. A forced receipt-write failure rolls back the entry and its publication. Cross-budget destination reservations protect retries after a ledger commit but before a capture decision is saved. Earlier events cannot silently become new spending in a budget created later that day.

UI restoration tests are distinct from actual Activity rotation. The delayed-transaction test checks that a commit finishing after composition replacement closes the old form and is applied only once. A debug-only unexported Activity configures font scale before view creation. Windows host fixture names are short enough for native SQLite paths; migration schemas load from host resources or the native test APK without changing assertions.

## API 26 upgrade and manual checks

The existing debug installation was upgraded without clearing its records on an Android 8.0/API 26 AOSP x86_64 emulator. The original ledger was adopted as the default budget and migrated to version 2.

The upgrade and accounting flows were checked on v0.2 candidate builds and remained intact through subsequent rebuilds. The large-text rotation captures use the later build-7 candidate; the final artifact's native regression is reported separately above.

| Flow | Observed result |
|---|---|
| Existing ledger after upgrade | Today remaining ₹240; starting allowance ₹400; monthly remaining ₹4,340; expense ₹200 and refund ₹40 preserved |
| Independent travel budget | Opening ₹1,000 and usual budget ₹3,000; expense ₹50 and refund ₹10 left ₹60 today and ₹960 this month |
| Switch back to default budget | Its ₹240 daily remainder and ₹4,340 monthly remainder remained unchanged |
| Savings deposit and withdrawal | ₹500 deposit minus ₹100 withdrawal left ₹400; the spending budgets were unchanged |
| Names | Budget renamed to Everyday; month label saved as October essentials |
| Theme and restart | Dark selection and committed records survived restart |
| Actual 200% system text and rotation | Unsaved ₹123.45 expense draft remained through portrait-to-landscape rotation, including the keyboard; Close and Save remained reachable |

The rotated draft was cancelled. System text and orientation were restored to 100% and portrait; restart still showed the default budget's ₹240 daily remainder.

Final screenshot review confirmed dark navigation-bar contrast and one-line decimal calendar captions. Wider amounts and large text use the date-list layout without dropping paise. The [final Android 16 calendar](validation/api36/calendar-final.png) shows the selected date's ₹400 allowance and ₹550 recorded spending after the final native test run.

Evidence includes the [upgrade screen](validation/api26/01-upgrade-today.png), [month balance](validation/api26/02-upgrade-month.png), [separate budget](validation/api26/03-travel-separate-balance.png), [savings](validation/api26/06-savings-separate.png), [200% portrait draft](validation/api26/11-portrait-200-draft.png), [rotated keyboard form](validation/api26/12-landscape-200-draft-keyboard.png), [reachable Save](validation/api26/13-landscape-200-save-reachable.png), and the [assertion log](validation/api26/manual-checks.jsonl). This establishes the listed emulator flows, not every keyboard, device, or accessibility configuration.

## API 36 notification integration

On Android 16/API 36 AOSP x86_64, notification permission was denied and then allowed through the actual Android UI. Notification-listener access was also enabled through system settings. A controlled fixture using the supported PhonePe package posted synthetic messages through Android's notification service. This exercised actual OS delivery to the listener; it did not use the real PhonePe application or real financial data.

- A referenced ₹200 outgoing payment was imported once. Reposting the same reference did not create a second expense.
- A further ₹300 payment made the daily remainder negative. Android delivered an overspending notification containing the merchant and amount. The channel reported importance 4, an alarm sound URI, and vibration enabled. The emulator was headless with no audio output, so audible playback is not claimed.
- A ₹40 payment without a reference went to review. It was corrected to ₹50 before acceptance, producing ₹550 total recorded expenses across the three payments.
- Inspection of persisted capture data found extracted fields and hashed identifiers, with no raw notification body or raw payment reference retained.

See [permission denied](validation/api36/notification-permission-denied.png), [capture consent](validation/api36/upi-consent-explanation.png), [capture enabled](validation/api36/upi-opt-in.png), [single import](validation/api36/upi-imported-once.png), [overspending alert](validation/api36/overspend-notification.png), [review form](validation/api36/payment-review.png), and the [capture summary](validation/api36/capture-summary.json).

The runtime reported 4,096-byte pages. Static inspection verified 16 KB-aligned ZIP data offsets and ELF PT_LOAD segments for all four packaged ABIs. This does not establish execution on a 16 KB-page device. The [alignment report](validation/artifact/native-alignment.json) identifies the inspected APK; the [API 36 environment record](validation/api36/environment.json) preserves the actual runtime configuration.

## Limits and historical evidence

Notification parsing is deliberately narrow. Synthetic templates and controlled callback/OS tests do not establish compatibility with every current PhonePe, Google Pay, Paytm, language, bank, or OEM template. The parser rejects uncertain cases or sends them to review. There was no SMS access, bank connection, real account data, or physical-phone installation.

No full TalkBack session, OEM backup/device transfer, process death at every possible write boundary, other screen sizes, or 16 KB-page runtime was tested. Signature verification of a debug APK is not release signing or Play Store validation. Debug and release lint being clean does not mean all build-console warnings are absent: Gradle/AGP deprecation, Java native-access, and AndroidX native-symbol messages are tracked separately.

Earlier v0.1 results belong to their recorded APK identities and are not presented as v0.2 test counts. The evidence bundled here contains synthetic data only; raw notification-system dumps, private hashing keys, and machine-specific build logs are excluded.
