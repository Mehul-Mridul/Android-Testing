# Recorded Touches: implementation and acceptance plan

## Objective

Add a camera-independent way to record and replay single-finger touches, including Portrait and other controls that do not have useful Accessibility selectors. Preserve Smart Controls and every existing preset. Provide explicit calibration for another device/layout; do not claim that coordinate scaling alone works everywhere.

## Android capture method

Use a visible TYPE_ACCESSIBILITY_OVERLAY window over the selected application area. Capture the user's complete single-finger gesture, remove the capture and controls overlays, recheck the target/window, then dispatch exactly one Accessibility gesture. Restore capture only after forwarding finishes. This is capture-and-forward after release, not passive observation or real-time dragging. It uses the already enabled Accessibility service and adds no Android permissions, root dependency or touch-exploration mode.

Ordinary click events cannot supply a universal raw touch stream. Android's onMotionEvent API also consumes requested input sources instead of transparently passing them to the rest of the system; it is not used here. References: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onMotionEvent(android.view.MotionEvent), https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY.

## Implementation phases

1. **Data and compatibility.** Add schema-2 touch presets containing normalized paths, total durations, inter-gesture delays, app version, device identity, display/window bounds, rotation, density, font scale, locale and Android version. Continue reading old schema-1 Smart presets. Reject invalid paths, unsupported actions and mismatched device/window configurations before injecting input.
2. **Visible recording.** Add Recorded Touches to the normal recording screen. Show movable Ready/Pause/Back/Next/Stop controls. Capture only explicitly selected foreground apps, with a separate profile for each app. Next opens the next selected app, and manual switching is also supported; each switch records an explicit Launch app step. Exclude keyboard, editable/password fields, system bars and other apps. Reject multi-touch, cancelled/out-of-bounds paths, external displays and gestures over ten seconds. Use the largest authorised application window so same-app dialogs do not redefine coordinates. Suspend capture on a genuine layout/orientation mismatch without discarding the draft; resume when the bound layout returns. Do not automatically resume after service/process death.
3. **Playback.** Replay recorded paths directly, without camera mode inference or shutter classification. Apply each recorded delay once, never retry an uncertain gesture, check the live window immediately before dispatch, and keep Pause/Stop cancellable. Keep baseline-based media verification independent of gesture completion.
4. **Starting screen.** Provide a per-app visual checkpoint by default. The user can navigate to the matching starting screen and press Start. An explicit preset option can disable the checkpoint for prepared/unattended runs. Matching geometry cannot prove that the current screen or app layout matches the recording.
5. **Cross-device calibration.** Preview each path on the target app. Drag its tap point or swipe endpoints, use Explore to navigate the app manually, and accept each path. Calibration itself sends no gestures. Save a new profile and preset ID only after all paths are accepted; preserve the original. Recalibration is required after geometry/device/app-version changes and after manual layout rearrangement.
6. **Verification and delivery.** Run JVM tests for sampling, bounds, invalid/multi-touch rejection, no-retry behavior, compatibility, schema round-trips and calibration copies. Build/lint the Android app. On the connected phone, record a Portrait workflow as raw touches, save/reopen without per-step review, prepare its starting screen, replay and require a newly verified photo. Exercise calibration preview/cancel/copy, Pause/Stop, and preservation of old presets. Record exact achieved checks and limitations in the output validation summary.

## Acceptance criteria

- Portrait touch actions remain RECORDED_TOUCH actions; no SELECT_PHOTO_MODE or TAKE_PHOTO inference is imposed on them.
- The selected app receives one forwarded gesture per successful capture; injected gestures are not recorded again.
- No gesture is sent while paused, outside the selected app, into keyboard/system UI, or after a geometry check fails.
- Imported or differently bound touch presets cannot silently become usable on another device. Calibration is a new copy with explicit per-path acceptance.
- A completed gesture with missing, unreadable or wrong-count media does not PASS.
- Existing Smart presets and app data remain unchanged by the upgrade.

## Initial limitations

Single-pointer paths only; no pinch/zoom, physical key recording, keyboard input, system-bar touches or external displays. Gestures are forwarded after release, so users must wait for Ready; fast overlapping gestures/double taps and interactive drag feedback are not supported as live recording. Android replays a path over its total duration, so within-path velocity is approximate. Media timing still depends on app startup/save latency and the configured tolerance. Another device/layout requires visual calibration, and cross-device effectiveness must be tested on that device. Full media permission remains required by this capture-verification app. Stop cannot reliably recall an already dispatched gesture.


## Multi-app and popup repair (22 November 2026)

The earlier monitor bound recording to the focused root node's bounds and stopped on any geometry mismatch. A save dialog can be smaller than its containing Activity. The revised environment selects the largest application window belonging to the focused authorised package. Internal window/content changes do not stop recording. Transient unavailable or mismatched windows suspend capture and retain the draft. Two stable samples are required before capturing again; package and geometry are checked again before forwarding.

Each selected app now has its own profile. Moving to another selected app records an explicit Launch step. The capture sequence uses raw touches and recorded Back actions, with no camera-mode or video-start/stop classification. Accessibility assists with app launching and Back. Existing single-profile touch records and Smart presets remain readable. Each selected app must be visited to obtain its profile before saving/executing.

The recorded delay ends at touch-down, excluding the user's stroke duration and overlay-removal delay. Playback applies it once and sends at most one gesture. Raw video duration follows the recorded start/stop timing, rather than a camera adapter. MediaStore verification independently checks new media against the baseline and expected counts/durations across selected packages.

An optional preset setting requests clearing ALL recent apps after a complete PASS. It is off by default. It opens visible Recents and clicks a unique supported Clear all/Close all control. Reports distinguish requested, unsupported, ambiguous, skipped and cancelled cleanup. No root, ADB or force-stop implementation is used in the app. Recents task removal cannot prove process termination. Stop/failure skips cleanup so the tester can inspect unfinished capture. Unsupported OEM controls require manual cleanup.

Touch replay cannot recognise an unexpected/missing popup or semantic camera state. Include popup responses and adequate loading/save delays in the recording, match each app's starting screen, and smoke-test any changed layout. Keyboards, passwords, system permission dialogs, lock screens and multi-touch stay outside capture. Touch recording stores no screen text, screenshots or keyboard contents. Coordinate replay can activate whatever occupies the recorded location; record only authorised workflows. App-private/cloud outputs cannot pass shared-media verification unless saved to readable MediaStore items.


### Modal-only window lists

Some OEMs omit the underlying Activity entirely while a modal dialog is visible. The environment keeps an anchor obtained from the authorised app occupying the full safe display area. A strictly inset smaller window from that same foreground package can use that anchor only while app version, device, Android version, screen, orientation, density, language and font remain compatible. No reflection or hidden Android APIs are used. Edge-attached/split/freeform window changes are not treated as inset dialogs; unsupported geometry suspends capture. Floating/multi-window use is outside the supported full-screen recording workflow.

After switching apps, wait for the visible Ready bar before touching the target. Touches made before the capture overlay is ready are ordinary app input and cannot be recovered retrospectively. The pipeline does not claim passive system-wide touch recording.
