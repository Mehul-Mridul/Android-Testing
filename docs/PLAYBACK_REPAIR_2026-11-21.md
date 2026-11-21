# Playback repair — 21 November 2026

Source baseline: cc847c9484ea95da681e418fd4ba47819fa9022f. App version: 0.2.1-dev, version code 2, application ID com.example.captureautomator.

## Behavior

- Recorded interActionDelayMs runs once before a step, outside retries. 100ms slices check Pause/Stop. Explicit WAIT_MS remains a separate intentional wait. Recorded delays must be 0–300000ms; invalid presets require review, never silent clamping.
- Launch is attempted once. Readiness requires two consecutive visible, enabled, nonempty target windows. Capture polls for a unique enabled semantic shutter at stable bounds, checks the pause gate, resolves it freshly, then sends at most one 80ms gesture. Capture does not send ACTION_CLICK first and does not use coordinate fallback. Generic navigation retains its existing explicit coordinate opt-in.
- Capture gestures are never retried, including cancellation, dispatch rejection and callback uncertainty. A gesture callback is not camera acceptance or proof of saving.
- Execution validates photo/start/stop counts, video ordering, matching target packages, unclosed starts and premature verification. These playback rules do not rewrite or prevent editing existing count-mismatched presets. Review inferred recording classifications explicitly in the step editor. START_VIDEO completion only starts the expected-duration timer; actual recording remains unconfirmed until readable video evidence exists.
- STOP_VIDEO waits the recorded delay first, then only the remaining configured minimum duration. Pausing can lengthen a real camera recording; the duration validator will report any mismatch. Stop issues no cleanup gesture; check Camera manually if it may still be recording.
- Media baseline is captured before launch, including pending rows exposed by Android. Verification excludes baseline IDs, pre-run DATE_ADDED rows, pending and zero-byte media; validates dimensions/MIME and video duration; checks owner attribution. Unknown/non-target ownership is explicitly unverifiable and cannot PASS. Existing full-library permissions are required; Android 14 selected-photo access is insufficient.
- Media polling uses the same baseline for at most 31 scans, 500ms between scans, with a 20s coroutine deadline. Exact evidence must remain stable across three scans. No capture is repeated to repair missing media. Blocking Android provider/decoder calls may outlast coroutine deadlines; this is not a hard real-time guarantee.
- Reports and compact Logcat JSON (tag CaptureRun) share one run ID and include step IDs, attempts, timing, readiness/gesture status, media scan counts and final verdict. New logs contain no screen text, selectors, entered text, or keyboard data. Logs exist only for explicitly started execution. No permissions or background monitoring were added. Existing optional diagnostic screenshots remain opt-in.
- Mutual exclusion prevents single, batch and recording sessions from overlapping. Cancellation reports are saved even when the execution coroutine is cancelled.

## One-photo / zero-video smoke check (requires a physical device)

1. Export existing presets first. Import samples/iqoo_one_photo_smoke.json; import creates a new preset ID. It never replaces another preset.
2. Verify the actual Camera package on the device. The sample uses com.android.camera and the recorded shutter ID; these are calibration references, not verified device selectors.
3. Open Camera manually, grant its permissions, dismiss first-run prompts and select Photo mode. Unlock the device and keep it foregrounded/visible.
4. Review every imported step using Edit → Apply. Confirm the TAKE_PHOTO kind and actual semantic shutter selector. This clears its uncertainty flag only after explicit review. Expected counts must remain 1 photo and 0 videos. Save the new preset.
5. Grant Automator its normal full photo/video read access and enable its Accessibility service explicitly. Run the smoke preset once with other camera/gallery writers idle.
6. Require a newly readable full-size photo, zero new videos and a PASS report with owner attribution. Inspect the saved report and the gallery. A completed gesture alone is insufficient. If ownership cannot be established, preserve the PARTIAL result; do not override it.
7. Test Pause and Stop during the recorded pre-action delay and readiness wait; no new gesture should be dispatched while paused or after Stop. A gesture already handed to Android cannot be recalled reliably.
8. Only after photo smoke success, duplicate the preset and calibrate distinct START_VIDEO and STOP_VIDEO controls. Run a short recording and verify duration. Then expand counts in a separate copy.

## Installation and signature safety

The normal debug build uses a local development certificate. A GitHub Actions build may use a different ephemeral debug key; neither is assumed to match the installed 0.2.0-dev APK.

- Export presets before installation. Do not clear data or uninstall to get around a signature mismatch; uninstallation deletes private presets and reports because backup is disabled.
- Run Android SDK apksigner verify --verbose --print-certs on the new APK and on a trusted copy of the currently installed APK. Compare signer SHA-256 certificate digests. Compare APK SHA-256 with the delivered checksum file separately: file hashes and signing certificates serve different purposes.
- If package ID and signer match, use the normal Android update prompt or adb install -r <apk>. Android must also accept the version code. Never bypass downgrade/signature failures.
- If signers differ, rebuild these sources using the original development signing key in a trusted local environment. Keep the key private. Alternatively build an explicitly different applicationIdSuffix for side-by-side testing and import exported preset copies; it has separate app data, permissions and Accessibility settings.
- Authorize the install source only for this installation, then disable it again. Recheck Accessibility and media permissions in Settings and keep the visible test indicator enabled.

## Verification limits

Automated JVM policy tests and Android compilation/lint do not verify OEM Camera behavior. No physical iQOO 9T or Android 14 camera run has been performed by this repair session. Enabled/stable Accessibility nodes cannot prove sensor/preview readiness, actual camera mode or video recording state. Same-app concurrent writers, hidden pending rows, second-resolution timestamps, OEM metadata quirks and private app storage can prevent conclusive attribution. The verifier remains conservative.
