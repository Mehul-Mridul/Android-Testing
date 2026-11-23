# 0.6.3 batch lifecycle repair and validation

## Confirmed code defects

1. The old batch finalizer switched to `NonCancellable + Dispatchers.IO` to save files, then cleared `running` after that context switch. A cancelled parent can throw again when the context returns, skipping the flag reset. Archive failures could skip it too. This explains a batch staying locked after Stop.
2. ExecutionEngine can return a CANCELLED partial report. The old batch loop lacked an explicit cancellation check before the next preset, allowing another loop iteration after that return.
3. Batch execution was held only by an in-process controller and a normal notification. There was no foreground execution session protecting it when Recents removed Automator’s own task. Activity recreation also opened Home instead of the live batch page.
4. Notification Stop reopened MainActivity with a Stop action. That action was not consumed, so later recreation could deliver an old Stop to a new run.

## Changes

- Core BatchRunLoop makes sequencing and cancellation testable without Android. Ownership is released in a non-suspending finalizer. Detailed archive writes run independently and cannot hold the execution lock or clear a newer session’s notification.
- Stop exits pause, cancels pending work, shows Stopping while the current coroutine acknowledges cancellation, saves the current partial report with a bounded wait, and cannot start another preset or final Recents cleanup. A gesture already handed to Android may finish; an active video may need a manual stop in the target app.
- Capture/verification diagnostics survive cancellation during return-to-app and cleanup. Standalone execution also always releases its running flag.
- A visible foreground session runs only for a user-started batch. The existing Accessibility service declares `stopWithTask=false` and Android 14’s `specialUse` foreground type, with its specific purpose recorded in the manifest. Two normal foreground-service permissions were added. No hidden monitoring or text collection was added.
- Notification Stop uses a private receiver rather than recreating an Activity. Legacy Stop intents are consumed once.
- Recreated screens open the live batch page; Home also offers Stop. A private checkpoint retains session/phase/progress/report IDs. A new process reports an interruption and allows a fresh start; it never resumes capture gestures automatically.
- Saved presets are not rewritten. Batch still disables each preset’s standalone cleanup flag in memory and owns its startup/final cleanup.

## Verification

61 JVM/unit tests pass: the original 49 plus 12 new batch lifecycle cases. New cases cover startup/final cleanup cancellation, a cancelled engine returning a report, pause/Stop and restart, storage error and timeout, failure policy, cancellation during report saving, 20 repeated Stop/restart cycles, and process-interruption checkpoint policy. Debug lint, debug/release APK builds and the separate Android test APK compile successfully.

The phone matrix passed on the connected iQOO 9T / Android 14. It uses an isolated counter app and in-memory test presets, not social-account actions or real camera captures. Its one instrumentation test contains nine asserted checks: six cancellation/interruption cases, pause/resume across two presets, three presets after removing Automator’s own task, and preservation of the user’s five saved presets. All nine passed. The six Stop/interruption cases returned to idle in 3–65ms in this test environment; this is an observation, not a latency guarantee for every device or an already dispatched gesture.

The six cases were Stop during startup cleanup (3ms), recorded delay (64ms), notification Stop while paused (65ms), media verification (62ms), final cleanup (3ms), and Accessibility interruption (62ms). Every following test batch could start. The pause/resume run passed both presets. After own-task removal, all three presets passed and both startup/final cleanup requests succeeded.

A separate controlled process-death test killed Automator during a 60-second fixture wait, with an active checkpoint. Reopening changed the checkpoint to inactive/INTERRUPTED. The counter remained 29 → 29: zero automatic replay touches. The instrumentation abort for this separate destructive scenario was expected; the normal matrix returned OK.

## Normal APK validation without instrumentation

To avoid a test runner artificially keeping the app process alive, the normal signed APK was installed and tested separately using two temporary calibrated counter-app presets. Each had its standalone cleanup checkbox enabled. Only those two temporary presets were created; the existing five presets were not rewritten.

- First run: removed only Automator’s isolated root task while the counter app was foreground. Foreground service was active. PID 6441 remained 6441. Both presets passed; counter 29 → 31 confirmed exactly two real clicks. Batch finished and final cleanup was accepted; the foreground session ended.
- Stop run: Stop before the first recorded touch ended the foreground session. Counter stayed 31 → 31. UI showed Batch stopped, a CANCELLED report and an enabled Start button.
- Restart run: started again immediately after Stop and removed Automator’s own task again. Same PID remained alive, both presets passed, counter 31 → 33. No extra click from the cancelled batch occurred. UI showed Batch finished and final cleanup accepted.

The two temporary presets were removed through the normal app UI after testing. Their filtered search was empty afterward. The separately installed test harness is not part of the normal APK. Normal-build results and cropped screenshots accompany this report. Full private preset/database backups remain local and are not release assets.

The connected test phone is iQOO 9T / Android 14. Removing its own task approximates standard task-removal semantics; it does not physically certify Samsung firmware. If an OEM force-stops the entire app rather than removing its task, Android can still terminate the session. The recovery path is deliberately non-resuming to avoid duplicate captures.

References: [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Service task-removal contract](https://developer.android.com/reference/android/app/Service#onTaskRemoved(android.content.Intent)).
