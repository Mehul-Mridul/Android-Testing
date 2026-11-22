# Touch recording and prepared batches

Record one app per preset. Tap a selected app again to deselect it. The selected app name stays visible while filtering the list. Existing presets can still be played.

Wait for Ready after opening the app. Record a single-finger tap, hold or swipe. Include the actual response to every popup, including dismissals. Touches over a separately focused app dialog carry a window-state check. A popup drawn inside the main app window may have no separate window identity. Keyboard input and system dialogs must be handled manually. Re-record a problematic old preset to add window checks to its steps.

Replay uses the recorded finger-up to finger-down gaps and gesture duration. A ready frame proceeds immediately. A popup, different window, busy app or Android gesture callback can still change timing. Very rapid overlapping input and multi-touch are outside this recorder's capabilities.

For multiple apps, record each workflow separately. Open Batch execution and tap the presets in playback order. The displayed numbers show their order. Start prepared batch opens Recents and requests Clear all, runs the presets without individual starting-screen prompts, and requests Clear all again when finished. Prepare each app to reopen at the recorded screen. Each preset gets a fresh media baseline. Stop cancels pending actions. Unsupported launcher cleanup is shown in the batch status. Android can retain pinned or protected tasks.

The app uses its visible Android Accessibility service for the recording overlay, gesture injection, and Recents navigation. Touch mode stores coordinates, durations and popup state. Presets from another phone, tablet, orientation or app version need calibration and a short smoke test.
