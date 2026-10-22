# Android 8 manual validation

Test device: Android 8 / API 26 emulator `emulator-5582`, explicitly addressed through the private ADB server on port 5039. The emulator property was checked before every control script. No phone was used.

Final status: **manual flows passed; all 45 native tests passed on final build 9 in 53.856 seconds, with an empty crash buffer.** The final APK SHA-256 is `e2d62be8f4f9a0e36744ac8337601595de496db1dbd73936ed98e01c62afb9d0`.

| Check | Observed result |
|---|---|
| Upgrade from the existing 0.1 installation | Preserved ₹240.00 left today, ₹400.00 starting allowance, ₹200.00 expenses, ₹40.00 refunds, and ₹4,340.00 left this month. |
| Named budgets | Renamed the original budget to **Everyday**. Created **Travel check** with ₹1,000 opening money and ₹3,000 usual monthly amount. |
| Separate spending records | Travel expense ₹50 plus refund ₹10 produced ₹60 left today and ₹960 monthly remaining. Returning to Everyday still showed ₹240 today and ₹4,340 monthly remaining. |
| Monthly record name | Saved **October essentials** for Everyday's October 2026 record and saw that name on Today. |
| Calendar | Opened the month grid and the selected 22 October details. The details showed the original expense/refund and ₹240 remaining. Missing historical allowances were shown as unknown. Final build 9 displayed the complete 482.22 and 482.23 daily captions on one line. |
| Savings | Added ₹500 with note **Emergency reserve**, then withdrew ₹100. Savings showed ₹400 and both changes. Everyday's daily and monthly amounts stayed unchanged. |
| Appearance | Selected Dark, restarted the production app, and observed the dark theme persisted. Selected Light afterward. |
| Android 8 system bars | Confirmed the corrected dark navigation-bar background and light icons on both the main screen and the Settings dialog. |
| Real system font scale 200% | All four navigation labels remained readable. Portrait used two rows; landscape fit one row. |
| Rotation with an unsaved draft | Entered ₹123.45 in an expense draft, rotated portrait to landscape, and verified the amount survived. Close remained visible, and Save expense was reachable by scrolling with the keyboard open. |
| Cancel and restart | Canceled the draft, restored portrait and 100% text, restarted, and verified Everyday still showed ₹240.00. |
| Final APK persistence | After installing build 9 and running all 45 tests, restarted the production app. Everyday still showed ₹240 today and ₹4,340 monthly remaining; Travel check showed ₹960 monthly remaining; savings remained ₹400 with the ₹500 deposit and ₹100 withdrawal. |

Evidence is from real emulator screenshots and UI hierarchy assertions in `manual-checks.jsonl`. Useful captures:

- [Original upgrade balance](01-upgrade-today.png), [original month balance](02-upgrade-month.png)
- [Separate travel balance](03-travel-separate-balance.png), [named budgets](04-named-budgets.png), [savings](06-savings-separate.png)
- [Corrected dark main screen](08-dark-system-bar-fixed.png), [corrected dark Settings dialog](09-dark-dialog-system-bar-fixed.png)
- [200% portrait navigation](10-portrait-200-navigation.png), [portrait draft](11-portrait-200-draft.png), [rotated draft with keyboard](12-landscape-200-draft-keyboard.png), [reachable Save with keyboard](13-landscape-200-save-reachable.png), [200% landscape navigation](14-landscape-200-navigation.png)
- [Final calendar decimal captions](15-final-calendar-exact-paise.png), [final dark navigation bar](16-final-dark-system-bar.png), [final preserved Today balance](17-final-preserved-today.png), [final preserved savings](18-final-preserved-savings.png)

The initial build 3 native run passed all 45 tests in 57.107 seconds. A build 6 repeat exposed a pending-layout timeout in the older manual-workflow test's generic Activity; its cleanup also reported that timeout. The other 44 test cases passed. The test now uses the same resize-aware debug Activity as the remaining UI tests, with all workflow assertions preserved and cleanup independent of Compose idleness. The complete build 9 run passed all 45 tests. See [native results](results.json), [instrumentation output](instrumentation.log), and [crash buffer](crashes.log).

Two product display changes followed screenshot review: API 26–28 navigation-bar colors now follow the selected app theme, and calendar captions preserve fractional amounts on one line or switch to the date-list layout when needed. Both changes were visually verified on build 9. Earlier screenshots 01–07 show predecessor builds; 08–14 show build 7; 15–18 show the final build 9. Screenshot 07 records the earlier navigation-bar contrast problem, corrected in 08, 09, and 16. The final emulator state is portrait, 100% text, and System appearance.

The Android 8 keyboard owns its navigation-bar appearance while visible and shows pale icons in the light keyboard captures. App-controlled bars have the corrected contrast. Scrolled content outside the viewport is expected on this 360 dp screen; the tested form actions remained reachable.
