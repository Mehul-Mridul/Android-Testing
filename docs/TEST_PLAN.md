# Real-device QA / acceptance matrix (not yet executed)

## Devices

- [ ] Samsung Galaxy S26+ (recording/reference)
- [ ] Another Samsung/Android phone with different resolution
- [ ] Samsung Galaxy Tab with a different aspect ratio
- [ ] At least one different Android OS version

## Apps — execute the same 2-photo/1-video sequence separately

- [ ] Native Samsung Camera, actual installed version recorded
- [ ] Instagram Story camera, save locally, do **not** publish
- [ ] Facebook Story camera, save locally, do **not** publish
- [ ] WhatsApp camera and authorized local-save route, do **not** send
- [ ] SNOW camera, save locally
- [ ] SODA camera, save locally

For every app record: tested device and OS, app version, exact accessible hierarchy/locators, required correction, expected and observed dimensions, metadata, URI/ownership, timestamps, PASS/PARTIAL/FAIL, and follow-up bug ID.

## Unit / integration checks

- [x] Pure Kotlin schema/selector/verdict smoke test with local Kotlin 1.9 CLI.
- [ ] Gradle `:core:test` on JDK17 + Gradle8.10.2.
- [ ] Android `:app:assembleDebug`.
- [ ] Accessibility user-consent flow and selected-package filtering.
- [ ] Tap, long press, scroll, text placeholder, rotation, missing node, duplicate selector.
- [ ] Interactive preset editor, import/export, name/folder, persistence after restart/reboot.
- [ ] Run, pause, resume, stop, process death and notification stop control.
- [ ] Verify 0-byte image, corrupt video, old media, partial grant, missing owner, app-private save.
- [ ] Ensure NO Story published and NO message or media sent.
- [ ] Batch six apps, expect 12 valid photos / 6 valid videos / 18 attributable files.
- [ ] Failure diagnostics, app update mismatch and OEM battery settings.

## Acceptance rules

A PASS requires all executed steps succeeded and the expected exact count of **new, readable, target-app-attributed media files** is present. Permission gaps, unobservable private saves, ambiguous locators, unsupported workflow elements, or unreviewed steps must never be reported as PASS. Some of these situations result in PARTIAL; deterministic errors result in FAIL.
