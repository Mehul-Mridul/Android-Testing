# Build and test evidence — 2026-11-20

Environment:
- JDK 21 command-line runtime available.
- `kotlinc` 1.9.0 available.
- Android SDK (`android.jar`) **not present**.
- `gradle` executable **not present**.
- Network access to Gradle/Google Maven hosts was unavailable.

Executed successfully:

```
./tools/test-core-offline.sh
PASS: pure JVM model, schema validation, safe verdicts, invalid locator rejection
```

This checks schema validation, safe verdicts for complete/incomplete/wrong counts, resource-ID matching, ambiguity rejection, and coordinate-only semantic denial. Does not execute Android/Compose/Room code.

A Kotlin parser diagnostic pass was attempted on the Android source without Android/AndroidX dependencies. It necessarily failed with unresolved Android and Compose symbols and **is not evidence of successful Android compilation**.

**Build status:** Source project assembled; Android compilation NOT VERIFIED; APK NOT GENERATED; six-app and cross-device acceptance NOT TESTED.
