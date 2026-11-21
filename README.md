# 3P Capture Automator

**Status:** An implementation-stage Android source project, **not** a validated production release.

This repository is an Android Studio Kotlin/Jetpack Compose application with a usable design for explicit recording, in-app preset editing, Room persistence, accessibility-based playback, a MediaStore verifier, reporting, and sequential batch execution. It is **not an APK** and has **not been compiled against an Android SDK or exercised on physical Samsung devices** in the creation environment.

## Core workflow

1. Import this root directory into **Android Studio**.
2. Install **JDK 17**, Android SDK Platform **35**, Android SDK Build Tools **35**, and Gradle **8.10.2**. Allow the IDE to download the Google/Maven dependencies.
3. Sync and build using **Build → Make Project**, or with the command-line `gradle :app:assembleDebug`. The expected debug output **after a successful build** is `app/build/outputs/apk/debug/app-debug.apk`.
4. Install your locally built APK on your QA device. In Settings → Accessibility, explicitly enable *3P Capture Automator (authorized testing)*. Android may require a restricted-settings approval for some sideloaded apps; follow the device's normal authorized Settings flow, never bypass it.
5. Open the app → **Settings and permissions** and request the required Android photo/video access and notification permission. Choose **full** photo/video access to enable verification. A selected-photos-only grant is insufficient.
6. Select **Record new automation**, name it, and choose only the target application packages. The recorder opens the first chosen app. Perform two photo saves and a five-second video save as a real tester, then tap the persistent notification **Stop** (or return to the app and press Stop).
7. Tap **Review and edit captured steps**. Correct unknown actions. For camera controls that emit no accessibility click events, insert `TAKE_PHOTO`, `START_VIDEO`, `STOP_VIDEO`, and any missing save/return-to-camera actions manually, giving each an observed **resource ID, content description, or text**. If no stable selector exists, explicitly opt in to a normalized coordinate fallback and record normalized coordinates. The service never auto-enables fallback.
8. Use the `START_VIDEO` and `STOP_VIDEO` action types; the runner enforces the preset's minimum configured video duration between these actions. The 5s default is configurable in the preset JSON and can be changed by editing an exported preset and importing it (import marks every step for review). A STOP_VIDEO without a confirmed start fails safely.
9. **Save** the preset, choose **Run**, and return via the notification to view the result. The final verdict depends on newly saved, readable media—not a shutter tap.
10. For a six-app sequence, create and calibrate six separate presets, then select them in **Batch execution**. The engine runs each with its own pre-run media baseline.

## Source tree

- `core/` — versioned Kotlin preset/action/report schema, input validation, fail-closed verdict and pure-JVM selector ranking.
- `app/.../automation/RecordingSession.kt` — limited-scope explicit recording, sanitized text placeholders, recovered draft review.
- `AutomationAccessibilityService.kt` — system-bound accessibility event intake, gesture capability and user-visible notification.
- `UiResolver.kt` — resource-ID/description/text ranking, ambiguity rejection, user-approved gesture fallbacks, element waits.
- `ExecutionEngine.kt` — deterministic replay, retries, app scope checking, video pacing, safe cancellation, and verdict.
- `MediaVerifier.kt` — media inventories, new file filtering, image/video metadata checks, ownership attribution and explicit partial outcomes.
- `BatchController.kt` — sequential reports and stop-on-failure policy.
- `app/.../data/` — Room preset and report storage, validated JSON import/export.
- `app/.../ui/` — Compose home, creation, recorder controls, preset editor, library, batch, history, settings and device screen.
- `samples/instagram_unverified_sample.json` — illustrative schema only; not a prerecorded selector mapping.
- `docs/` — build, feasibility, limitations and test procedure.

## Development checks

Run the platform-independent core smoke tests with installed Kotlin CLI:

```bash
./tools/test-core-offline.sh
```

With a complete Android environment, run:

```bash
gradle :core:test :app:assembleDebug
```

GitHub Actions `.github/workflows/android.yml` is included to perform an Android build and core tests after the project is pushed to a GitHub repository. CI is **configured but has not run** as part of this delivery.

## Important truth-in-testing rules

- `TYPE_VIEW_CLICKED` and scroll accessibility events are *observable events*, **not raw taps and not every event a custom camera view may emit**.
- UI Automator / ADB assistance is a separate potential backend and is **not implemented** in this Android-only source snapshot.
- Manual step correction is an explicit part of recording, especially Instagram/Facebook Story camera views.
- Semantic control selection returns **no match** for ambiguous candidates. Coordinate fallback requires an explicit per-step opt-in and is not safe to assume portable across devices.
- Android MediaStore cannot read app-private files. If permissions are limited or owner attribution is unconfirmed, the status remains PARTIAL rather than fabricated PASS.
- The application does not publish Stories, send WhatsApp media, store passwords, intercept hidden keystrokes, or bypass any Android protections.
- This build snapshot is intended for **authorized internal QA use**, not an assertion of compliance with Google Play accessibility-service distribution policy.

See `docs/PLATFORM_FEASIBILITY.md`, `docs/TEST_PLAN.md`, and `docs/KNOWN_LIMITATIONS.md` before real-device trials.
