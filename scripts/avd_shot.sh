#!/usr/bin/env bash
# Boot one AVD headless, install the face, screenshot it, shut the AVD down.
# Usage: avd_shot.sh <avd-name> <even-port>   ->   docs/screenshots/<avd-name>.png
set -euo pipefail
source "$(dirname "$0")/_env.sh"
name="${1:?avd name}"; port="${2:?even port, e.g. 5560}"
serial="emulator-$port"
mkdir -p "$ROOT/docs/screenshots"
"$EMULATOR" -avd "$name" -port "$port" -no-window -no-audio -no-snapshot -no-boot-anim \
  >"$ROOT/tools/emulator-$name.log" 2>&1 &
trap '"$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true' EXIT
"$ADB" -s "$serial" wait-for-device
until [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
"$ADB" -s "$serial" shell dumpsys battery unplug   # emulator boots "charging", which covers the face with the charging screen
sleep 10   # let sysui settle on the default face
"$ADB" -s "$serial" uninstall "$APP_ID" >/dev/null 2>&1 || true   # drop saved complication choices from earlier installs
"$ROOT/scripts/install.sh" "$serial"
sleep 6    # let complication providers deliver their first data
"$ADB" -s "$serial" exec-out screencap -p >"$ROOT/docs/screenshots/$name.png"
echo "saved docs/screenshots/$name.png"
