#!/usr/bin/env bash
# Re-renders the Account pictures in docs/screenshots/accounts/ (account-<scene>-<light|dark>-<portrait|landscape>[-2x].png).
#
# The instrumented test AccountScreenshotTest draws the Backup and sync card in every state and every dialog on the real screen
# (so dialogs and the keyboard are in the pictures), light and dark, upright and sideways, at font scale 1 and 2, and writes the
# pictures to the app's external files directory. It is skipped in the normal suite, so this script asks for it with
# `-e screenshots true`. A Gradle instrumented run uninstalls the app when it ends (taking those files with it), so this script
# installs the app and the test APK itself, runs the class with `am instrument`, pulls the pictures and removes the packages it
# installed again (also when something fails; a package that was on the device before the script ran is left alone).
#
# Usage: scripts/pull_account_screens.sh          (emulator-5562, or ANDROID_SERIAL)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/_lock.sh"
phone_lock_acquire

export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5562}"
APP=com.seyedjafari.analogplus
REMOTE="/sdcard/Android/data/$APP/files/account-screens"
OUT="$ROOT/docs/screenshots/accounts"
tmp="$(mktemp -d)"

installed() { adb -s "$ANDROID_SERIAL" shell pm list packages "$1" | tr -d '\r' | grep -qx "package:$1"; }
had_app=no; installed "$APP" && had_app=yes
had_test=no; installed "$APP.test" && had_test=yes

cleanup() {
  rm -rf "$tmp"
  [ "$had_test" = yes ] || adb -s "$ANDROID_SERIAL" uninstall "$APP.test" >/dev/null 2>&1 || true
  [ "$had_app" = yes ] || adb -s "$ANDROID_SERIAL" uninstall "$APP" >/dev/null 2>&1 || true
  phone_lock_release
}
trap cleanup EXIT

cd "$ROOT"
./gradlew :phone:installDebug :phone:installDebugAndroidTest
adb -s "$ANDROID_SERIAL" shell rm -rf "$REMOTE"
# the output is captured first: `... | grep -q` could stop reading early and kill the writer with SIGPIPE under pipefail
out="$(adb -s "$ANDROID_SERIAL" shell am instrument -w -e screenshots true \
  -e class com.seyedjafari.analogplus.phone.accounts.AccountScreenshotTest \
  "$APP.test/com.seyedjafari.analogplus.phone.AccountsTestRunner" | tee /dev/stderr)"
grep -q '^OK (' <<<"$out" || { echo "the screenshot test did not pass" >&2; exit 1; }

adb -s "$ANDROID_SERIAL" pull "$REMOTE/." "$tmp" >/dev/null
count="$(ls "$tmp"/account-*.png | wc -l | tr -d ' ')"
[ "$count" -eq 112 ] || { echo "expected 112 pictures, got $count" >&2; exit 1; }
cp "$tmp"/account-*.png "$OUT/"
echo "wrote $count pictures to $OUT"
