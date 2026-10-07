#!/usr/bin/env bash
# Runs the instrumented tests (app/src/androidTest) on a connected device or
# emulator, then copies the screenshots they took into build/emulator-tests.
# The app stays installed afterwards so its files can be read.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/emulator-tests
rm -rf "$OUT" && mkdir -p "$OUT/screenshots"
adb logcat -c || true

./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
status=$?

adb exec-out run-as app.motif tar -cf - -C files screenshots | tar -xf - -C "$OUT" || echo "No screenshots found"
adb logcat -d > "$OUT/logcat.txt" || true
exit $status
