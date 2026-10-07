#!/usr/bin/env bash
# Runs the instrumented tests (app/src/androidTest) on a connected device or
# emulator, then copies the screenshots they took into build/emulator-tests.
# The app stays installed afterwards so its files can be read.
# No -e: the screenshots and logcat are collected even when tests fail, and
# the script exits with the test run's status. Every adb and Gradle call has
# a time limit so a stuck test or device fails the job instead of hanging it.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/emulator-tests
rm -rf "$OUT" && mkdir -p "$OUT/screenshots"
timeout 30 adb logcat -c || true

timeout -k 30 20m ./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
status=$?
if [[ $status -eq 124 ]]; then echo "::error::Instrumented tests did not finish within 20 minutes"; fi

timeout 60 adb exec-out run-as app.motif tar -cf - -C files screenshots | tar -xf - -C "$OUT" || echo "No screenshots found"
timeout 60 adb logcat -d > "$OUT/logcat.txt" || true
if [[ $status -ne 0 ]]; then
  # Print each failure's full stack trace into the CI log.
  find app/build/outputs/androidTest-results -name '*.xml' -exec sed -n '/<failure/,/<\/failure>/p' {} +
fi
exit $status
