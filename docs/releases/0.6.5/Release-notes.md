# 0.6.5-dev — batch continuation after native camera

Version code 13. Development prerelease.

- Verification deadlines no longer unconditionally stop a prepared batch. Stable finalized saves and genuine capture uncertainty are handled separately; failed verification remains visible.
- Continue after ordinary verification problems is the new default. Stop on failure remains available; pending/unsafe capture states always stop with a reason.
- A rejected touch that was never dispatched cannot create a false save barrier.
- Media reads and save barriers have bounded caller waits; late native-read results cannot dispatch actions or retain the playback lock.
- Added verification progress, explicit early-stop/skipped-item explanations, and distinct completion-with-errors/blocked states.

83 automated tests, lint and APK builds passed. On iQOO 9T / Android 14, six camera/handoff checks and nine Stop/lifecycle checks passed. The normal signed APK separately passed counter → native photo → counter without instrumentation and is installed on the phone. Six original presets were preserved; the three temporary presets and test harness were removed.

Samsung firmware was not physically tested. Actual pending captures can still block safely, and expected counts/layouts must match the recorded workflow. See Batch-handoff-repair.md for exact evidence and limits.
