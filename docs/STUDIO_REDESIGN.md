# 3P Automator studio redesign

## Existing architecture and screen inventory
The app uses Kotlin, Jetpack Compose Material 3, and a Page destination switch inside one Scaffold. Room/Gson preset storage and the recording, playback, verification and batch controllers remain the functional interfaces. The nine destinations are Home, Create, Recording/review, Library, Preset editor, History, Batch, Settings and Device. Step editing, deletion confirmation and execution details use dialogs. Recording/calibration/checkpoint controls use native Accessibility overlay windows.

## Design foundation
The supplied images guide charcoal, forest-tinted surfaces, mint emphasis and champagne highlights. No reference-image pixels or smart-home content were incorporated. A native sans-serif font uses a 38sp hero, 30sp screen headings, 23sp section headings, 17sp card titles, 14–16sp body copy and 12sp metadata. Cards use 20–30dp radii. Actions are rounded pills with at least 48dp height. Content uses a restrained gradient backdrop, opaque/translucent surfaces, thin outlines and low shadows. Expensive backdrop blur was omitted in favour of this material fallback.

StudioDesign.kt owns both dark and light palettes, typography, shape tokens, shared panels, native vector navigation marks and buttons. Appearance is saved separately from automation preferences. Android status and navigation bar colours follow the chosen appearance.

## Screens and interactions
- Home has an editorial heading, primary recording panel and responsive destination cards. Wide layouts use two columns.
- Create retains all fields and the single-package selection. Its form/list scrolls while Start stays available above the safe area and keyboard.
- Recording/review uses the theme and explicit active, paused, uncertain and stopped controls.
- Library keeps search, Run/Edit/Copy, import/export and delete confirmation, with stable item keys and empty/no-match messages.
- Preset editor scrolls its header, media expectations and steps together. Actions wrap at smaller widths. Camera repair and calibration actions remain reachable.
- History keeps actual verdicts, counts, step evidence and detail dialogs.
- Batch retains ordered selection, pause/resume/stop, pre/post cleanup and real run results.
- Settings groups appearance with the existing permission and diagnostic controls.
- Device groups real hardware and permission information in a shared panel.
- All dialogs inherit the shared geometry, typography and palette. Native overlay controls receive a dark green gradient, rounded outline, mint buttons and a small elevation.

## Motion and rendering
Navigation uses an interruptible 160ms entrance fade and a 90ms exit fade. Action buttons compress to 97% while pressed and restore with a spring using damping 0.8 and stiffness 700. Native checkbox, switch, ripple and dialog feedback continues to use Compose/Android animation behaviour. Android animator duration scaling is respected by the standard Compose animation runtime. No independent timers or infinite decorative animations were added.

Installed-app enumeration now runs on Dispatchers.IO with a retained list refreshed on recording-screen visits. Loading is shown only while that query is pending. Large collections remain lazy and keyed. Graphic effects use gradients, small vector drawings, opacity and transforms. Processing controllers have no animation dependencies.

## Verification and limits
See the packaged validation report, raw gfxinfo samples, screenshots and saved fixture run. The 0.6 redesign is a debug build. Physical Samsung, release-mode performance, long-running memory profiling and every possible third-party app workflow remain outside the completed checks. The measured frame statistics must be assessed with their device, build and test conditions. No universal refresh-rate or zero-jank guarantee is claimed.

## Installation
Version 0.6.0-dev, version code 8, minimum Android 10/API29. Use the supplied same-key APK as an in-place update. Export important presets before a deliberate signing-key migration. The local debug certificate SHA-256 is 48bd3b4b11fefe1bccd94453a30cb9d6be6a1ad54244c1db41d840613de86c46. CI debug artifacts may have a different signing key.
