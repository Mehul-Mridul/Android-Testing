#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v gradle >/dev/null 2>&1; then
  echo 'Gradle is not installed. Install Gradle 8.10.2 (or use Android Studio) and Android SDK 35.' >&2
  exit 2
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" && ! -f local.properties ]]; then
  echo 'ANDROID_HOME/ANDROID_SDK_ROOT or local.properties sdk.dir must point to Android SDK.' >&2
  exit 2
fi
gradle :core:test :app:assembleDebug
