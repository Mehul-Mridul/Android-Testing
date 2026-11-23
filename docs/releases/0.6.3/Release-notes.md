# 0.6.3-dev — batch Stop and task-removal repair

Version code 11. Development prerelease for authorised testing.

- Fixed cancellation leaving the batch locked as running, including report-save and archive-error paths.
- Stop releases pause, saves the partial report with a bounded wait, and cannot start another preset or final cleanup.
- Added a visible foreground batch session independent of the Activity, plus interruption checkpoints that never automatically replay touches.
- Notification Stop uses a private receiver; old Stop intents are consumed once. Recreated screens show the live batch.
- Retains the 0.6.2 app logo and 0.6.1 capture/popup/cleanup fixes.

61 automated tests, lint and both APK builds passed. On iQOO 9T / Android 14, the nine-check device matrix passed and controlled process-death recovery sent zero replay touches. The normal APK was separately tested without instrumentation: task removal kept the same process alive, both presets passed, Stop returned to a usable Start state, and another batch passed after task removal again. Real fixture counters verified clicks.

The tested normal APK is installed on the connected phone. Its Accessibility service is connected. The two temporary test presets and test harness were removed; the five original presets were preserved. Rotation settings were restored.

Samsung firmware was not physically tested. These control-flow tests use an isolated counter app, not new Instagram/camera media workflows. Android may still terminate a force-stopped app or retain protected Recents tasks. Stop cannot undo an already dispatched gesture; check the target app if video may still be recording.

Install the normal APK as a matching-signature update, keeping app storage. See INSTALL.txt and the full batch repair report for evidence, permissions and limitations.
