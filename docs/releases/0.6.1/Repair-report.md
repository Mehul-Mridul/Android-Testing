# 0.6.1 repair findings

This update addresses three reported problems: landscape Recents cleanup, Instagram Discard after local saving, and cleanup settings during batch execution.

## Landscape Recents

The previous implementation relied on the active Accessibility root. In landscape, a launcher taskbar or another system window can own that root while the actual overview lives in another visible window. Cleanup now searches launcher/system overview windows, resolves a small clickable parent for known Clear all controls, and waits for the removal animation. Empty Recents is accepted. Android can retain locked or protected tasks; this does not force-stop background processes.

## Instagram Save and Discard

The inspected failed presets did not contain the Discard-dialog response. The old recorder bar also covered Instagram’s upper-left Back button. A focused editable caption could suspend all touch capture, even for buttons elsewhere. Popup-only window geometry could lose the full-screen touch binding.

The recorder bar is narrower and centred. Saved touch bounds are revalidated before reuse across app popups. Editable/password field protection checks the gesture path instead of rejecting the whole screen. Touches outside a focused popup are excluded, preventing taps through a temporary save popup into the underlying editor. No text values are read or stored by this new check.

A reproduction exposed a further timing issue: a Back tap recorded during the temporary save popup expected that popup on replay, but it had already disappeared. The clean recording waits until saving completes, then records Back and the actual Discard button. Existing recordings with a missing Discard or a transient-window tap require a new recording/copy; they are not silently rewritten.

## Batch cleanup setting

The existing batch code already disabled each preset’s standalone cleanup. Failed Instagram steps caused stop-on-failure to end the batch, which appeared to be caused by the checkbox. The update makes that execution policy explicit and tests true/false/legacy-null settings while preserving saved presets. Batch reports now separately retain startup/final cleanup status and errors, including failures before the first preset.

## Validation scope

49 automated tests passed with zero failures or skips. Debug lint and debug/release builds passed. The final build retried successfully after a temporary Windows DEX file lock. GitHub independently passed final commit 5171f9ea225bb43d0b515b3d1bfa3b480bd74f25.

Physical results are recorded separately in the supplied evidence. Only the connected iQOO 9T, Android 14, Instagram 457.0.0.42.73 was available. Samsung, other Android vendors, other Instagram layouts, and all other social apps are not physically certified by these checks. Coordinate presets still need matching starting screen, orientation, window configuration and app layout.

## Test incident

An early test started from Reels instead of the expected camera workflow and accidentally tapped Follow on primatepaige. The change was reversed and the button was visually confirmed back to Follow. Subsequent account-app actions were checked against the current screen. Test captures were saved locally; no story was posted or message sent.


## Final physical results

- Instagram clean recording: PASS, run 403d0e0f-022b-4c6f-90db-f3f0526827da. Seven recorded touch gestures completed, including the Discard popup response; MediaStore verified one new Instagram-owned photo and zero videos. The clean recording is saved separately as Instagram-discard-061-clean. Existing user presets remain unchanged.
- Two cleanup-enabled test copies: both PASS. Saved standalone cleanup flags remained true. Startup and final batch cleanup both reported RECENTS_CLEAR_REQUESTED.
- Landscape regression was reproduced, then fixed using the actual Vivo label “Clear all - button.” Its shared ID and empty transformed bounds prevented the earlier resolver from recognising it. The resolver now accepts that exact directly labelled native click, while retaining package, visibility, enabled-state and uniqueness checks. It does not guess a coordinate.
- Final landscape batch: PASS/PASS, run IDs 4358f722-fd51-4e6a-951a-e3d77ca72137 and 44e2d44a-72c3-4d0e-9bfe-3e5625c57501. An isolated fixture counter confirmed both real button clicks before switching to landscape. Both cleanup requests succeeded. Reopening Recents showed only Automator; the fixture task was removed.
- Batch preset rows now have 56dp minimum height; the previous short rows allowed Android’s enlarged touch targets to overlap. Editor option labels are also clickable. Test copies Batch-061-A/B are retained separately for repeatable checks.
- Normal signed APK installed as an update and opened, versionCode 9, versionName 0.6.1-dev, non-debuggable. The workflow evidence above was collected with the debug build; the signed normal build was not replay-tested after final replacement. Android disconnected Automator Accessibility after replacement: reconnect it before replay. Auto-rotation was enabled at the end of testing; portrait rotation was the last forced test setting.

Source: https://github.com/Mehul-Mridul/Android-Testing/tree/fix/recents-discard-0.6.1
Final CI: https://github.com/Mehul-Mridul/Android-Testing/actions/runs/45061885965

The supplied APK uses a local development signing key, not a production/store signing identity. Preserve that key for compatible updates. Do not uninstall or clear storage to work around a signature mismatch. See INSTALL.txt and signature.txt.
