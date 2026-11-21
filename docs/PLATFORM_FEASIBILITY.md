# Android capability and engineering decisions

| User operation | Public Android mechanism | Implemented in source | Caveat |
|---|---|---|---|
| Observe some button clicks | Accessibility `TYPE_VIEW_CLICKED` | Yes | Canvas/GL camera views may not emit usable events |
| Observe some scrolls | `TYPE_VIEW_SCROLLED` | Yes | Gesture direction may require manual correction |
| Read accessible UI hierarchy | `rootInActiveWindow`, node fields | Yes | Secure/inaccessible UI cannot be inspected |
| Capture raw touches from another app | No reliable general-purpose Accessibility API | No | Must add manual steps or separate ADB-assisted recorder |
| Collect external keystrokes | Intentionally excluded | No | User must enter synthetic test strings in editor |
| Automate accessible buttons | `ACTION_CLICK` | Yes | Success callback alone is not capture proof |
| Automate gestures | `dispatchGesture` | Yes | Scoped and verified; coordinates opt-in only |
| Trigger start/stop recording | Semantic action applied to UI controls | Yes, for calibrated selector | Cannot directly command third-party camera without exposed UI |
| Capture screenshots | Accessibility screenshot API 30+ | Optional/diagnostic | `FLAG_SECURE` and platform restrictions respected |
| Verify saved media | MediaStore, BitmapFactory, MediaMetadataRetriever | Yes | Scoped/partial/app-private media can prevent verification |
| Store presets/history | Room, local JSON | Yes | No cloud synchronization |
| Run across devices | Selectors + normalized-window coordinate override | Baseline | Needs real-device UI calibration and comparative QA |
| Vision/template matching | OpenCV/ML alternative | No | Not included in this source snapshot |
| ADB/UI Automator | Desktop/lab backend | No | Planned extension, not advertised as working |
| iOS support | Separate platform implementation | No | An Android APK cannot run on iPhone |

## Safety model

Observation is limited to the selected packages, during a tester-initiated active session. Recordings do not keep external text values. Password/OTP inputs are never stored. Failed targeting causes a stop, not speculative taps. Dangerous publish/send labels on Instagram, Facebook and WhatsApp are blocked as one preventive measure, but safe navigation still requires controlled QA accounts and physical validation.

## Execution semantics

- `LAUNCH_APP` opens an installed target package and waits until active.
- Semantic target priority is exact resource ID > exact content description > exact text. Conflicting candidates cause failure.
- Explicitly allowed normalized coordinates are mapped to the *current app window* bounds.
- `TAKE_PHOTO`, `START_VIDEO`, `STOP_VIDEO` are **semantic names for UI clicks**, not hidden camera API calls.
- Video STOP requires a previous successful START_VIDEO and a configured minimum elapsed duration.
- `VERIFY_MEDIA` uses an inventory collected before the run. Newly indexed assets must be readable, temporally new and attributable to the selected app package to count as verified. Ownership conflicts force PARTIAL/FAIL, never PASS.
- Failed steps record reasons and optional screenshots when the user explicitly enables local diagnostics.

Official platform references:

- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent
- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
