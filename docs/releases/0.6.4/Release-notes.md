# 0.6.4-dev — keyboard-safe touch recording

Version code 12. Development prerelease.

The touch recorder keeps Pause, Back and Stop available when the keyboard opens, preserves the absolute coordinate frame, excludes keyboard/password input and continues through same-app input dialogs. Capture layers exclude the floating controls themselves, including after dragging. New steps retain keyboard/window state for guarded replay; readiness timeouts report state flags without typed text.

72 automated tests, Android lint and debug/release builds passed. On iQOO 9T / Android 14, the isolated keyboard recording/replay test passed nine checks. Instagram's local Story keyboard, movable controls, Back and later Discard were physically checked without posting or typing. The normal signed APK was separately checked without instrumentation and is installed on the phone. Six original saved presets were preserved; temporary drafts and test apps were removed.

Typing/password entry is not recorded or replayed. Rerecord incomplete presets containing missing keyboard transitions. Samsung devices were not physically tested, and Instagram replay/media verification and the full batch device matrix were not rerun in this release. See Keyboard-repair-status.md for evidence and precise scope.
