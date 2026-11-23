# 0.6.2-dev — app logo update

Version code: 10. This is a development prerelease for authorised testing.

## New identity

A mint camera frame, play symbol and warm gold capture light on dark forest green. The editable SVG master and PNG export are included under `branding/` and as release downloads.

The APK now declares a custom app icon and includes adaptive foreground/background resources, round-launcher compatibility, an explicit Android 13+ monochrome themed layer and a matching white notification icon. The mark stays within Android’s adaptive icon safe area. The vector resources remain sharp across phone and tablet densities. Actual shape/tint depends on the launcher/theme; this does not establish physical Samsung compatibility.

## Scope and checks

This update changes branding and version metadata. It retains 0.6.1 capture, popup, verification and batch logic and permissions. The 0.6.1 physical workflow evidence remains in `docs/releases/0.6.1/`; those device tests were not repeated solely for this icon change. Current build/test/signature evidence accompanies the APK.

## Installation

Use `3P-Capture-Automator-0.6.2-dev.apk` for normal use. A debug APK is supplied for diagnostics. Install as an update to the matching-signature app; do not uninstall or clear storage. Stop on a signature mismatch and export presets before considering a different installation.

Local development certificate SHA-256: `48bd3b4b11fefe1bccd94453a30cb9d6be6a1ad54244c1db41d840613de86c46`.

Android may require reconnecting Automator Accessibility after updating. Launcher icon caches may take a moment to refresh; reopening the launcher is sufficient to check it without clearing app storage.

Adaptive icon reference: https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
