# Recorded Touches 0.4 validation

Date: 22 November 2026 (Asia/Calcutta). App: 0.4.0-dev, version code 4. Physical device: iQOO 9T/I2201, Android 14/API34.

## Automated checks

41 core JVM tests and 4 app JVM tests passed, zero failures/errors/skips. Android debug assembly and lint completed successfully. Lint: zero errors, 24 warnings. Final command: `gradle --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` using Gradle 8.10.2 and Java 17.

Coverage includes path sampling/bounds/duration, multi-pointer rejection, single dispatch/no retry, old JSON compatibility, profile/device/layout mismatches, per-app profiles and explicit transitions, modal viewport selection, calibration-copy immutability and independent media verdicts.

## Physical phone checks on the delivered APK

- Visible touch recording captured five raw gestures across two local test packages, including a smaller same-app save dialog, with two profiles, explicit Launch steps and zero review/classification flags. The recording stayed active across the popup.
- Final two-app popup replay: **PASS**, run `c1713455-0c7a-4e90-a604-825108dd4e39`. Both app-start checkpoints were accepted. Actual private counters increased by two main taps and one dialog-save tap in app one, and one main tap in app two. The zero-media fixture expectation was verified separately; PASS alone was not used as evidence that the taps reached their targets.
- Final Portrait replay: **PASS**, run `7e0d9371-11a5-420f-bb98-83650c298470`. Existing single-profile schema-2 preset loaded without conversion. Raw taps selected Portrait (mode control moved to the centre) and activated the shutter. MediaStore baseline verification found exactly one newly saved valid photo owned by Camera and zero videos. Camera version: 12.1.72.10. No test media was published or sent.
- Calibration preview and explicit acceptance of five paths across both test apps saved a different preset ID with two profiles. Explore was used to show/dismiss the test popup manually. Private app counters stayed unchanged, confirming calibration forwarded no touches. Original preset JSON was unchanged.
- All 10 presets present before this update retained identical IDs and JSON; none were overwritten or removed by the upgrade. Two isolated diagnostic presets were added during testing. Backups remain local to the output folder.
- The supplied APK was installed as a same-signature update, and the installed APK SHA-256 matched the supplied artifact exactly: `2d9f0961e1c588ba719e7acd645b6ee3c760919ddc33c210d358170e8c1a89b2`.

## Resolved test findings

The first dialog test showed that this OEM can omit the underlying Activity from Accessibility's window list. The implementation now retains the established full-screen app viewport for a strictly inset modal from the same authorised app, while rechecking app/device/configuration. Calibration also needed a longer launch-settling period; it now waits for stable geometry and retains accepted paths through temporary mismatches.

A tap during app launch before Ready passed through without being recorded. This capture-and-forward method requires waiting for the visible Ready overlay; it cannot reconstruct earlier touches. A USB disconnect interrupted result retrieval, but the persisted replay report was recovered after reconnection. Live Logcat was suppressed on this phone; the saved reports contain the run-correlated structured events.

## Limits and checks not claimed

- Instagram's exact local-story save popup was not physically exercised. Standard Android modal save-dialog recording/replay was exercised on the connected phone. No social-account posting or sharing was performed.
- Raw video recording/duration verification was not physically retested in this build. Raw start/stop are unclassified touches; the recorded timing must match the expected duration/tolerance. Earlier Smart camera video results are not presented as raw-touch verification.
- Optional Clear ALL recent apps was implemented and lint/build checked, but not physically exercised. It uses visible supported Recents controls, is off by default, runs only after PASS, and reports unsupported/ambiguous/cancelled cleanup. It cannot force-stop processes.
- Calibration was tested on this phone; portability to another physical device is not verified. Different devices, app versions and layouts require explicit calibration and a smoke test.
- Full-screen, main-display, single-finger input only. Keyboard/password input, system permission-dialog recording, lock screens, multi-touch and interactive real-time dragging are excluded. Unexpected/missing popups can still invalidate coordinate replay. App-private/cloud saves require a readable shared MediaStore item to be independently verified.

## Installation and provenance

Debug testing artifact, minimum Android API29. Signing certificate SHA-256: `48bd3b4b11fefe1bccd94453a30cb9d6be6a1ad54244c1db41d840613de86c46`. Update in place and preserve app storage. A signature conflict requires the original signing key or an intentional export/migration; do not uninstall as a routine fix. CI debug artifacts can be signed with a different key. No new Android permissions, hidden monitoring, keyboard capture or screen-text storage was added to touch recording.

Source branch: `feat/touch-recording-0.4`, based on `9b843a0146c5c28954ec4c13c006a63f1dedf80b`. Main was not changed and no force push was used. See RECORDED_TOUCH_PLAN.md and the output installation guide for operation and scope.
