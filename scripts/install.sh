#!/usr/bin/env bash
# Build, install and (on Google Wear emulators) activate the face.  Usage: install.sh <adb-serial>
# On a real Samsung watch the activation broadcast is ignored: long-press the face to pick it.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
serial="${1:?usage: install.sh <adb-serial>}"
"$ROOT/gradlew" -p "$ROOT" -q :app:assembleDebug
"$ADB" -s "$serial" install -r "$APK"
"$ADB" -s "$serial" shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
  --es operation set-watchface --es watchFaceId "$APP_ID" >/dev/null
