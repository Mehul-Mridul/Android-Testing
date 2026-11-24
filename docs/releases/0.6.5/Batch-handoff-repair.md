# Batch camera handoff repair — 0.6.5-dev

Version code 13 · development prerelease for authorised testing

## Findings

The reported symptoms were stopping after two apps and stopping after a native-camera preset. No Samsung phone or Samsung execution report was available. The attached iQOO contained older interrupted media checks, count mismatches and app-version compatibility failures; those are evidence of those runs, not proof of the exact Samsung failure.

The source nevertheless contained specific batch defects:

1. Any media-verification deadline forced batch termination, even with Stop on failure unchecked. A finalized file with an incorrect expected count or duration could be treated like an unfinished capture.
2. An early break could be archived and displayed as COMPLETED / Batch finished, without explaining why later presets were skipped. The default Stop on failure checkbox also stopped every failed/unverifiable preset.
3. A touch was marked as having affected the capture app before dispatch. A readiness rejection that sent no gesture could therefore incorrectly block the next app.
4. MediaStore/metadata reads ran in an ordinary I/O coroutine. A native/provider call that ignored cancellation could retain the caller despite the surrounding coroutine deadline.

## Changes

Batch continuation now uses a structured handoff reason rather than matching the words deadline reached. Verification failures remain failures. With Stop on failure unchecked (the new default), the next prepared preset runs once the capture/save state is safe. Checking the box stops after the failed preset and shows the reason and remaining skipped count.

Pending files, unreadable target files, an active known camera recording, an uncertain dispatched touch, or an unconfirmed save still block switching. Missing expected captures with unknown camera idle also block. A count discrepancy can continue after stable readable saving and positive idle confirmation; a finalized video with a duration mismatch is distinguished from an unreadable/pending video. The app does not leave an unresolved capture merely to make the batch look successful.

New report fields preserve pending/unreadable counts, stable save evidence and a handoff-block reason. File identity and size must settle across repeated scans; coordinates and recorded timing are not silently modified. Input is marked as dispatched only when it was actually forwarded. No capture gesture is retried because verification failed.

The batch UI shows saved-media scan progress and counts. Terminal states distinguish COMPLETED, COMPLETED_WITH_ERRORS, STOPPED_ON_FAILURE, BLOCKED and cancellation; early endings show skipped presets and the reason. Final completion clears transient progress text.

Each media read has an eight-second caller bound. A shared fixed-size worker pool and bounded queues isolate native reads, cancellation signals are requested asynchronously, and late results cannot invoke playback/UI actions. Overall verification/save barriers also have bounded deadlines. A permanently blocked platform call may remain in a bounded worker until Android returns it; the app cannot forcibly terminate Android's provider, but that work no longer owns the batch lock.

Android documents cancellable queries through [ContentResolverCompat](https://developer.android.com/reference/kotlin/androidx/core/content/ContentResolverCompat) and notes that metadata data-source setup can take time in [MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever). The repair uses public APIs and does not bypass Android protections.

No new permissions, hidden monitoring, text collection, posting or sending were added. The 0.6.4 keyboard recorder and earlier foreground-session/Stop protections remain.

## Automated checks

83 tests passed, including nine new handoff-policy/polling tests and two native-read cancellation tests. Cases cover third/fourth-item continuation, stop-on-failure policy, pending/changing media, a late night-style save, a wrong count/duration, unknown camera idle, and cancellation of a read that ignores interrupts. The previous touch and batch lifecycle tests remain included. Android lint and debug/release/device-test builds passed.

## Physical checks — iQOO 9T / Android 14

The handoff device matrix passed all six cases:

- Counter → native photo touch preset → third counter → fourth counter: all four PASS.
- Native camera second, deliberately expecting two photos while capturing one: PASS / FAIL / PASS / PASS, COMPLETED_WITH_ERRORS. The failure was retained and items three/four ran.
- Same mismatch with Stop on failure enabled: two reports, one skipped item, STOPPED_ON_FAILURE and a clear reason.
- Readiness rejection before dispatch: failed item recorded, no unsafe-save block, third preset PASS.
- Native video start → timed stop → video verification, followed by the third preset: all three PASS. This checks the existing native camera adapter; photo batch tests use recorded touches.
- Original saved preset JSONs unchanged.

The separate nine-case Stop/lifecycle matrix passed: startup cleanup Stop, recorded-delay Stop, notification Stop while paused, media-verification Stop, final-cleanup Stop, Accessibility interruption, pause/resume, three presets after own-task removal, and original-preset preservation. Observed Stop-to-idle times were 3–67 ms in those cases. This is an iQOO test of task removal, not Samsung firmware certification.

## Normal signed APK

The final normal APK was tested without instrumentation using three separately labelled temporary presets: counter → native photo → counter. The app displayed 3/3 Batch finished with all three PASS. Counter one changed 26 → 27; counter two changed 47 → 48, proving the third app received its touch. The native photo passed the app's new-file verification and final Recents cleanup completed.

The installed APK was read back and matched the release file byte-for-byte. SHA-256: 228a988568071bc0ece0d15cca29877bef828344370277a641b7a00e3f501fa5. The normal APK uses the same preserved local development certificate as previous releases. Its embedded build-revision metadata predates the final source commit; the source archive/manifest identifies the committed tested runtime source.

Six original saved presets were verified unchanged before the temporary normal-APK setup. Only the three known test presets were added, then removed through their own confirmed Delete dialogs. The test harness was removed, pre-existing counter fixtures were retained, rotation/stay-awake settings were restored and Accessibility was connected at the final check. Test camera media was not deleted, avoiding removal of user media. No private phone database or original preset JSON is published.

## Limitations and Samsung retest

Samsung-specific reproduction remains unverified without the device or its failed report. The fix removes the demonstrated batch policy/diagnostic defects and passes the iQOO scenarios; it is not a claim that every Samsung firmware/app combination is fixed. Actual Samsung battery/task restrictions, window geometry and native camera mode state still need a retest. The app does not infer Samsung camera controls from iQOO selectors.

Keep one app per touch preset and prepare its recorded starting screen. Device/app-version, rotation and layout mismatches remain guarded; rerecord or explicitly calibrate a copy instead of overwriting the original. Expected counts must match locally saved media. A real pending capture may still stop with a reason; absence of readable shared media is not silently passed. Private in-app media cannot be verified through MediaStore. Keyboard text/password entry remains excluded.

## Build and installation

JDK 17, Android SDK/Build Tools 35 and Gradle 8.10.2. Run :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease. Device tests are in BatchHandoffDeviceTest and BatchDeviceTest and use separate offline counter fixtures. Do not attach a UI hierarchy inspector during an active recorder/replay; it can interrupt Accessibility.

Install the normal APK as a matching-signature update. Do not uninstall or clear storage. Stop if Android reports a signature mismatch; export presets before considering a different installation. See INSTALL.txt, signature.txt and the artifact manifest.
