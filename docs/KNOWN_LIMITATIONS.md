# Known limitations — read before testing

1. **Uncompiled Android app:** Android SDK, Gradle and network access to Android dependencies were unavailable in the authoring runtime. The Android code has not passed Gradle compilation, emulator runs, instrumentation tests or device acceptance tests. Do not treat this source snapshot as production-ready.
2. **No debug APK in the delivered archive:** `app-debug.apk` exists only after building on an Android-equipped machine. See README.
3. **Not every interaction is observed:** Third-party camera apps often render shutter buttons in canvas or custom views with no accessibility click. Such steps will not be recorded by the Accessibility API. Use the manual step editor and observed selectors.
4. **No low-level cross-app touch recorder:** Accessibility cannot generally sniff coordinates of arbitrary taps. ADB-assisted `getevent`/UI Automator workflow is not implemented here.
5. **No third-party app-specific presets have been validated:** Instagram, Facebook, WhatsApp, SNOW, SODA and Samsung Camera must all be calibrated and tested. No fabricated UI IDs are included.
6. **No universal device compatibility guarantee:** Different aspect ratios and tablet UIs may require per-device calibration. Currently no visual template matching, dynamic anchoring, or device override profile UI.
7. **Restricted MediaStore verification:** App-private media, Android partial photo access, and mismatched/missing `OWNER_PACKAGE_NAME` cannot be validated strongly. They yield PARTIAL/FAIL rather than PASS.
8. **No video frame decoding / codec integrity scan:** Video metadata/retriever verifies duration/resolution/readability but not every video frame or frame rate. Can be added using MediaExtractor/MediaCodec/ffprobe lab mode.
9. **No automatic repair after selector drift:** The runner retries non-capture actions and fails safely; user must update selectors in the editor. It avoids blindly retrying potentially duplicate photo/video actions.
10. **Resume from an arbitrary failed capture step is deliberately disabled:** Without preserving the original media baseline and confirmed completed captures, retry could duplicate media and misattribute files. Stop and repeat the run or create a new safe preset.
11. **Batch selection order:** Uses the saved library order (most recently edited first); manual drag-reordering is not yet available. Each preset saves its own report and a batch JSON file is written to private storage.
12. **No foreground-service guarantee:** The recording notification is tied to a system-bound AccessibilityService; OEM battery management and process death can interrupt execution. Drafts are recoverable for review only and recording never auto-restarts.
13. **Not all desired editor features:** CRUD, reorder, insert, edit, explicit selector correction, waits, and test string editing are present. Arbitrary conditional branches, loops, per-step re-record, and screenshot visual diff are not implemented.
14. **Safety guarantee is limited:** Blocking obvious post/send labels does not constitute formal proof that a third-party app cannot publish. Test on QA accounts with network controls as appropriate, and inspect the exact saved workflow.
15. **UI polish and accessibility are unverified:** Compose UI is implemented but has not undergone instrumented accessibility or responsive-layout testing.
16. **App versions vary:** Package identifiers and workflows for vendor apps can change. Installed app selection avoids assuming SNOW/SODA package IDs; target app versions are saved and reported when different.

The implementation must not be called "finished", "fully automated for Instagram", or "production-quality" until all baseline real-device tests have passed.
