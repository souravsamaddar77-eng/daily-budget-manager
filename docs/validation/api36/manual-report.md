# Android 16 manual checks

Tested on the task's AOSP Android 16/API 36 emulator, using synthetic amounts and merchants. No phone or real payment account was used.

- Created a budget with ₹4,000 available through 31 October, starting on 22 October in UTC. The starting daily allowance was ₹400.
- Denied Android's notification permission, verified the app remained usable and offered the permission action, then allowed it through the system dialog.
- Enabled spending and overspend notifications in the app. Granted notification-listener access through Android settings after viewing the in-app explanation.
- Selected PhonePe and a specific destination budget, then explicitly enabled detection and automatic recording.
- Posted a synthetic payment of ₹200 from the labelled emulator-only sender. The Android listener recorded it once. Reposting the same transaction reference with different notification IDs did not create another expense, including after relaunch/update.
- Posted a second synthetic payment of ₹300. Today became −₹100. Android displayed the merchant, amount, and overspend notification. The system channel had high importance, vibration, and the default alarm-tone URI. The emulator used `-noaudio`, so audible playback was not verified.
- Posted ₹40 without a payment reference. It waited in review and did not change spending automatically. Changed the review amount to ₹50 and accepted it. The calendar then showed ₹550 recorded expenses; today was −₹150 and monthly remaining ₹3,450.
- Inspected retained capture metadata without exposing its private hashing key. It contained three capture/import decisions and no raw notification body, title, or payment reference. Daily overspend was not repeated for each subsequent expense.

The synthetic sender used the allowlisted package only in this emulator. It had no payment account or network activity and is not included in the APK or repository. These checks exercise actual Android notification delivery and listener behavior; they do not certify every PhonePe, Google Pay, or Paytm message template.

Screenshots in this folder show the actual native app and Android notification shade. The final automated results are in `results.json` and `instrumentation.log`.
