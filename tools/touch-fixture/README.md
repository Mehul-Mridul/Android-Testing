# Local touch regression target

FixtureActivity.java is built into two separate test packages, com.example.touchfixture.one and .two. It has no permissions, network or account data and is not included in the Automator APK. Buttons increment private counters or show a smaller same-app save dialog.

Select both packages in Recorded Touches, expected photos/videos 0/0. Record Increment main, Show save popup, Save locally, Increment main, Next, then Increment main in the second app. Stop and save. Assert that recording stayed active through the dialog, every forwarded touch completed, both app profiles were stored, and the switch is a Launch step. Replay from the same starting screens. Compare private counts.xml counters to confirm dialog and second-app input reached their targets.

Do not attach UiAutomation or UI hierarchy inspectors during an active session: Android can interrupt the Accessibility service. This target checks Android dialog geometry and app transitions. It does not prove the Instagram-specific flow or portability to another physical device.
