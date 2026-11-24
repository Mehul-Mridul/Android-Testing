# 3P Capture Automator

Android touch recording and playback for authorised app testing.

## Latest APK and documents

**[Download 0.6.5-dev and supporting files](https://github.com/Mehul-Mridul/Android-Testing/releases/tag/v0.6.5-dev)** · **[Batch repair and phone validation](docs/releases/0.6.5/Batch-handoff-repair.md)** · **[Logo sources](branding/)**

Version **0.6.5-dev (version code 13)** fixes batch continuation after media-verification failures, false save barriers after rejected touches, and silent early completion. Media reads have bounded caller waits; the batch displays scan progress, stop reasons and skipped presets. It retains the 0.6.4 keyboard repair and existing logo.

**83 automated tests**, lint and debug/release builds passed. On iQOO 9T / Android 14, the six-case camera/handoff matrix and nine-case Stop/lifecycle matrix passed. The normal signed APK separately completed counter → native photo → counter without instrumentation. It is installed on the phone; six original presets were preserved.

Batch now defaults to continuing after ordinary verification failures once saving is safe. Stop on failure remains available. Pending/unresolved capture states always block with a reason. Samsung firmware was not physically tested; these results do not certify every Samsung/app configuration. See the report for exact scope and evidence.

![Capture Automator logo](branding/capture-automator-logo.png)

## Install and record

1. Download `3P-Capture-Automator-0.6.5-dev.apk` from Releases. Use the debug APK only for diagnostics.
2. Update the matching-signature app without uninstalling or clearing storage. If Android reports a signature mismatch, stop and export presets before considering a different installation. The APK uses a local development signing key; certificate details and checksums are supplied with the release.
3. Enable Automator Accessibility through Android Settings, and grant the photo/video access needed for verification. After an update, reconnect Accessibility if Android leaves it disconnected. No protection bypass is provided.
4. Choose **Record new automation** and record one app at a time. Touch paths and timing are saved; app opening, Back and Recents use Accessibility assistance. Keyboard typing and password entry remain native and are not recorded/replayed; app touches and Back can continue around them.
5. Include the complete local save and return sequence. Wait for a save popup to finish before tapping the underlying editor. Record the actual Discard button when a confirmation appears. Stop, review, and save the preset.
6. Set the expected photo/video counts to the media actually saved. Playback success requires newly saved readable media when media verification is requested; a completed gesture alone does not prove a capture.
7. Before replay, match the recorded starting screen, orientation, app layout and window configuration. Coordinate recordings require calibration/new recording when those change.
8. For multiple apps, select separate presets in **Batch execution**. Batch manages its own cleanup before and after the sequence; each preset’s standalone cleanup checkbox does not trigger cleanup between batch items. Stop cancels pending actions. Android may retain locked/protected tasks.

Original presets are not silently repaired or overwritten. Old Instagram presets missing Discard or containing a transient-popup tap need a newly recorded copy. Test presets retained on the inspected device are identified in the repair report.

## Build and checks

Use JDK 17, Android SDK Platform/Build Tools 35, and Gradle 8.10.2. Open this repository in Android Studio, or run:

```sh
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The debug APK is written to `app/build/outputs/apk/debug/`. The normal release build is unsigned until signed with your preserved key. Never commit a keystore, signing password, local SDK configuration or account credentials.

## Source and documentation

- `core/`: preset schemas, playback policy, validation and JVM tests.
- `app/`: Compose UI, explicit touch recorder, Accessibility-assisted playback, MediaStore verification, Room storage and batch execution.
- `docs/releases/`: versioned repair findings, installation, automated and physical evidence, build logs, signatures and checksums.
- Release assets: normal/debug APKs, exact app-source archive, patch and full document bundle.
- Other files under `docs/` include earlier design/feasibility material; versioned release evidence describes the current validation scope.

The app is for explicit authorised testing. It does not bypass Android permissions, collect hidden typed text, publish Stories, or send media/messages. MediaStore cannot verify files kept only in another app’s private storage. Coordinate playback cannot guarantee portability across devices or changing app interfaces.
