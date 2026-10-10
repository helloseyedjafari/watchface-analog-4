#!/usr/bin/env bash
# Re-renders the onboarding screenshots in docs/poc/screens/ (onboarding-<page>-<light|dark>-<portrait|landscape>[-2x].png).
#
# The instrumented test OnboardingScreenshotTest draws every page through the real shell gate in a pinned 360x800 / 800x360 dp
# window and writes the pictures to the app's external files directory. A Gradle instrumented run uninstalls the app when it
# ends (taking those files with it), so this script installs the app and the test APK itself, runs the class with
# `am instrument`, pulls the pictures and removes both packages again.
#
# Usage: scripts/pull_onboarding_screens.sh          (emulator-5562, or ANDROID_SERIAL)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/_lock.sh"
phone_lock_acquire
trap phone_lock_release EXIT

export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5562}"
APP=com.seyedjafari.analogplus
REMOTE="/sdcard/Android/data/$APP/files/onboarding-screens"
OUT="$ROOT/docs/poc/screens"

cd "$ROOT"
./gradlew :phone:installDebug :phone:installDebugAndroidTest
adb -s "$ANDROID_SERIAL" shell rm -rf "$REMOTE"
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class com.seyedjafari.analogplus.phone.OnboardingScreenshotTest \
  "$APP.test/androidx.test.runner.AndroidJUnitRunner" | tee /dev/stderr | grep -q '^OK ('

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"; phone_lock_release' EXIT
adb -s "$ANDROID_SERIAL" pull "$REMOTE/." "$tmp" >/dev/null
count="$(ls "$tmp"/onboarding-*.png | wc -l | tr -d ' ')"
[ "$count" -eq 40 ] || { echo "expected 40 pictures, got $count" >&2; exit 1; }
cp "$tmp"/onboarding-*.png "$OUT/"
adb -s "$ANDROID_SERIAL" uninstall "$APP.test" >/dev/null || true
adb -s "$ANDROID_SERIAL" uninstall "$APP" >/dev/null || true
echo "wrote $count pictures to $OUT"
