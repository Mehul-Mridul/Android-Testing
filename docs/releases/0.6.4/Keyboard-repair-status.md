# Keyboard recording repair — 0.6.4-dev

24 November 2026 · version code 12 · development prerelease

## Problem and causes

The earlier recorder treated every visible keyboard as a reason to remove its overlay, including Pause, Back and Stop. Keyboard resizing also changed the app frame used by the strict coordinate guard. Editable-field checks rejected the focus tap that opens a keyboard, and a rejected pre-dispatch gesture could end the session. These behaviors explain the disappearing recorder in keyboard-based editors such as Instagram.

Physical testing also caught a capture layer receiving the coordinates of the floating Back button. Merely adding the controls above other overlays was insufficient on the inspected phone. Capture windows now have an explicit geometric hole around the controls. Dragging updates that hole after release.

## Implemented behavior

- Preserve the full recorded coordinate origin during a recognized keyboard resize. Coordinates are not automatically scaled.
- Keep Pause, Back and Stop above the keyboard. Public, bounded Accessibility overlay windows cover only safe portions of the selected app. Keyboard rectangles, password fields and currently editable input areas remain native touch regions. There are at most 32 capture windows; uncertain geometry leaves controls available without unsafe capture.
- Allow short, stationary focus taps on non-password fields before the keyboard opens. Never record keyboard keys or text entry. Password fields are excluded even before the keyboard opens.
- Store keyboard presence and the live app rectangle on new touch/Back steps. Poll for the recorded app/window/popup/keyboard state before replay, wait for stable geometry, and recheck before one gesture. A keyboard Back is guarded separately to avoid navigating the app when the expected keyboard is absent.
- Skip rejected input/window gestures that were never dispatched while retaining the recording. An uncertain gesture already handed to Android still stops for review and is not retried.
- Explain the typing limitation in recording review and preset description. Add run-correlated readiness-timeout diagnostics containing state/geometry flags, not typed text.
- Old presets remain unchanged. Missing keyboard metadata defaults to keyboard absent. Explicit calibration creates a copy and clears stale per-step rectangles.

No new permission, hidden monitoring, keyboard listener, text collection or hidden Android API was added. Accessibility is still required for the visible touch recorder, gesture forwarding, app navigation and batch assistance.

## Automated validation

72 tests passed: 67 core and 5 app codec tests. This includes 10 new geometry/input tests and a new metadata/legacy-preset round-trip check. The existing batch policy and 12 batch lifecycle tests remain included. Android lint, debug/release APK builds and the device-test APK build passed. See automated-tests.json, build-and-tests.log and Test-reports.zip.

## Physical validation — iQOO 9T, Android 14

The isolated offline keyboard fixture passed nine checks, with a successful eight-step generated preset replay. Tested: controls/frame retention; a native keyboard tap excluded from recording; an app button used while the keyboard was visible; field focus and recorded Back; later counter touch; Pause/Resume/Stop; starting with an already-open keyboard; password exclusion; and continuing after a same-app input dialog. The fixture stores integer counters/length only, never entered text. The replay expected 0 photos/0 videos: it validates touch execution, not new camera capture or media duration.

Instagram's local Story editor was checked separately on the same phone. A new local photo editor opened; the Aa tool opened the keyboard; Pause/Back/Stop stayed visible and the coordinate frame was retained. The controls were dragged, then Back exited the text tool. Further touches expanded the toolbar, opened the Discard edits dialog and discarded only that newly-created test edit. Recording stayed active without a recording issue. The test recorded six steps. No text was entered and nothing was posted, sent or saved as a Story draft. This is a recording/keyboard/dialog check; Instagram preset replay and MediaStore verification were not rerun in this release.

## Normal APK and cleanup

The signed normal APK was installed as an update and tested without instrumentation. The keyboard opened while the controls remained visible; recorded Back dismissed it; a counter touch reached the app (6 to 7); another keyboard opening was retained; Stop returned to Recording review with five observed steps. That unsaved test draft was discarded through the app UI. Wait for Ready/the controls after each forwarded touch; overlays briefly leave while a gesture is forwarded.

The installed APK was read back and matched the distributed file byte-for-byte. SHA-256: 263b2f887beedd0701de2092ba2f15262ced8153d97e2a9d72285c1e67bc4d67. The six original saved preset JSONs matched exactly before replacement with the normal APK. No test preset was saved. The temporary test harness and new keyboard fixture were removed; pre-existing counter fixtures were retained. Original rotation settings were restored and Accessibility was connected at the final check.

## Scope and remaining limits

Samsung phones/tablets, floating/split Samsung keyboards and landscape extract-mode keyboards were not physically tested here. Pure geometry tests include floating keyboards and landscape tablet regions; those do not constitute device certification. Unsupported/unknown window geometry can suspend capture while retaining the draft and controls. App/OEM updates, changed layout, device, orientation, font/display scaling and keyboard configuration may require a newly recorded or explicitly calibrated copy.

Typing and password entry are deliberately not replayed. Presets that depend on entered text need manual preparation and a suitable starting state. Existing incomplete recordings cannot acquire missing keyboard transitions automatically. Gesture completion still does not prove media saving. The prior batch/capture fixes remain; the full 0.6.3 physical batch matrix was not repeated in this keyboard-focused release.

## Reproduction and installation

Build with JDK 17, Android SDK/Build Tools 35 and Gradle 8.10.2. Run :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease. KeyboardDeviceTest is in the test APK only and uses the separate offline KeyboardFixtureActivity. Do not attach a UI hierarchy inspector during a live recorder/replay; doing so can interrupt Accessibility on the inspected device.

Use the normal APK as a matching-signature update. Do not uninstall or clear app storage. See INSTALL.txt. The tested binaries were built from the committed runtime changes while the earlier Git HEAD was still embedded as build-revision metadata; the source archive/manifest identifies the complete release source. No signing key or private phone database is published.
