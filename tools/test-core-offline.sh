#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
kotlinc core/src/main/kotlin/com/example/captureautomator/core/*.kt \
  tools/CoreSmokeTest.kt -include-runtime -d "$TMP_DIR/smoke.jar"
java -jar "$TMP_DIR/smoke.jar"
