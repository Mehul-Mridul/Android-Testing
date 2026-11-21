# Camera state repair â€” 0.3.0-dev

This update targets the inspected iQOO 9T / Android 14 Camera package com.android.camera, version 12.1.72.10. Existing presets and reports are retained. App versionCode is 3.

## Changes

- Photo and Video mode selection use live PhotoMode / VideoMode controls, fresh semantic bounds and a single gesture, rather than clicking a RecyclerView or a clickable ancestor. The centered mode control and shutter description must agree before capture. The service includes non-important custom views so the OEM mode labels are available. Content-change events invalidate stale accessibility data, and known controls are explicitly refreshed before readiness/capture checks. After video stop, saving readiness has a bounded 20-second minimum timeout before proceeding to another photo.
- Camera actions enforce Photo-ready, Video-ready, Recording and stopped state transitions. A reused shutter resource ID cannot override a conflicting description or class in generic selector matching. The known camera adapter uses the current observed state to resolve the shutter, rather than trusting stale recorded post-click descriptions.
- Recording derives Start and Stop from observed state transitions. The post-stop Start Recording label cannot create a second Start. Duplicate events do not duplicate video transitions. Confident known-camera photo/transition steps do not require repeated Apply clicks. Unknown controls and unrelated app actions retain review requirements.
- Camera state observation runs only during an explicitly started, visible recording session for the selected camera package; it stops with Pause/Stop and does not resume after process death. Only known camera control state is used. No keyboard contents, entered text, screenshots, or general screen text are added to diagnostics. No Android permissions are added.
- Video duration starts at the start-gesture dispatch, not after the recording confirmation wait. Stop readiness is prepared before the deadline; the shutter is re-resolved after the deadline wait, and at most one stop gesture is sent. This removes readiness overhead which caused observed 7.262s / 7.554s clips to fail a 5s Â± 2s expectation. Duration validation is retained.
- Newly recorded known-camera Stop actions are scheduled from the configured media duration. Their observed duration is retained as recordedVideoDurationMs for reference instead of replaying it as an extra pre-stop delay. Other recorded step delays still apply once outside retries. Long pauses or unexpected device delays can still cause a duration failure.
- The editor displays the actual capture type, updates the label when a type changes, and distinguishes Apply step changes from Save preset changes. Known, tightly constrained camera imports use runtime state checks; unknown imported actions still require review.
- Save camera repair copy creates a new preset ID, replaces broken mode-container navigation, clears stale shutter descriptors in known capture actions, and schedules Stop using the configured duration. It never changes the original preset and never guesses that a second Start means Stop. Correct malformed action kinds first if start/stop counts differ.
- Cancellation preserves intermediate media counts and evidence, while marking verification incomplete. Run diagnostics include mode/state evidence without storing control text.

## Upgrade and use

1. Export presets first. A backup of the original three presets was also collected before this update.
2. The APK uses the same development signing certificate as the previous delivered build. Use a normal update / adb install -r. Do not uninstall or clear data to bypass a signature error.
3. On this phone, replacing the APK or starting instrumentation can leave Accessibility enabled but disconnected. Turn the Automator service off and on through Android Settings after the final update if the app says it is disconnected. Existing photo/video permissions must remain granted.
4. For an old preset, open Edit and choose Save camera repair copy. This leaves the original available. Run the copy, starting with one photo / one video, and inspect media verification.
5. For new recordings, use the normal visible recording flow. Choose expected photo/video counts and the desired playback video duration. Known Camera capture and mode steps should already be reviewed; save the preset once after reviewing the recording.
6. Verify photo-only, video-only and photo â†’ video â†’ photo workflows before larger batches. Recording must actually start and stop, the resulting media must be readable and attributable, and expected counts/duration must match. A successful gesture alone cannot PASS.

## Validation scope

Regression tests cover shared shutter IDs, conflicting state descriptions/classes, duplicate transition events, stop versus start classification, copy-only repair, constrained imports, real-time stop deadlines and final re-resolution. Existing timing, cancellation, count, baseline and polling regression tests remain included.

A separate test APK contains an explicitly invoked UiAutomation camera-control test, which verifies the inspected phone's Video-ready â†’ Recording â†’ Video-ready states. The production-service device tests require reconnecting Accessibility after instrumentation launches; normal-app UI acceptance and saved execution reports provide the end-to-end evidence. See the delivered validation summary for the exact checks and physical results achieved in this session.

## Limitations

The built-in adapter requires the observed English camera state labels and semantic mode controls. Other OEM layouts, localized labels, permission prompts or overlays fail closed and may require calibration. No claim is made for other phone models, third-party camera apps or a complete device matrix. MediaStore ownership, scoped storage, provider delays and concurrent media writers can still make verification incomplete. A gesture already dispatched cannot reliably be recalled by Stop. If a run is stopped while recording, check Camera manually; cancellation does not send an unrequested cleanup gesture.
