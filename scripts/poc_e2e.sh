#!/usr/bin/env bash
# End-to-end proof for the builder POC, on two unpaired emulators (the real Data Layer path is not exercised here:
# the phone app builds the face via its debug receiver, adb relays the APK, the watch debug receiver applies it).
#
#   scripts/poc_e2e.sh [--with-tests] [--keep-emulators]     (env POC_SETTLE_SECONDS, default 25)
#
# Builds and installs both apps, then for three designs (A default, B blue accent with the top slot moved, C quarter
# rings with a green accent): build on the phone, relay, apply on the watch, screenshot, and assert the centre disc
# colour and that the version number went up. Finally checks that exactly one face is installed.
# --with-tests also runs the unit tests and the device test suites of every module (slow).
# Emulators that were already running are left running; the ones this script started are stopped on exit.
# The phone emulator phase (install + the three builds) runs under the machine-wide phone emulator lock
# (scripts/_lock.sh, also used by scripts/with_phone_emulator_lock.sh), so a parallel :phone:connectedDebugAndroidTest
# cannot uninstall the app mid-run. The lock is released before the optional --with-tests part.
# Screenshots: docs/screenshots/poc/<n>-<design>.png.   Exit code is nonzero when any step or assertion fails.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
source "$(dirname "$0")/_lock.sh"

PHONE_AVD="1_Pixel_8_API_33"; PHONE_PORT=5562; PHONE="emulator-$PHONE_PORT"
WATCH_AVD="GalaxyWatch7_API36"; WATCH_PORT=5560; WATCH="emulator-$WATCH_PORT"
APP="com.seyedjafari.analogplus"
FACE_PKG="$APP.watchfacepush.analog"
PHONE_RECV="$APP/$APP.phone.DebugBuildReceiver"
WATCH_RECV="$APP/$APP.watch.DebugApplyReceiver"
SHOTS="$ROOT/docs/screenshots/poc"
SETTLE="${POC_SETTLE_SECONDS:-25}"   # seconds between applying a face and the screenshot
WORK="$(mktemp -d "${TMPDIR:-/tmp}/poc_e2e.XXXXXX")"

WITH_TESTS=no; KEEP=no
for a in "$@"; do
  case "$a" in
    --with-tests) WITH_TESTS=yes ;;
    --keep-emulators) KEEP=yes ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done

STARTED=()      # serials of the emulators this script started
EMU_PIDS=()     # their host processes (fallback when adb never saw them)
BATTERY_SET=no
step() { printf '\n== %s\n' "$*"; }
info() { printf '   %s\n' "$*"; }
die()  { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
cleanup() {
  local s pid
  # undo "dumpsys battery unplug" (matters for a watch emulator that was already running and stays up)
  if [ "$BATTERY_SET" = yes ]; then "$ADB" -s "$WATCH" shell dumpsys battery reset >/dev/null 2>&1 || true; fi
  if [ "$KEEP" = no ]; then
    for s in ${STARTED[@]+"${STARTED[@]}"}; do info "stopping $s"; "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true; done
    sleep 2
    # an emulator that never reached adb is still running headless: stop its process too
    for pid in ${EMU_PIDS[@]+"${EMU_PIDS[@]}"}; do
      if kill -0 "$pid" 2>/dev/null; then pkill -P "$pid" 2>/dev/null || true; kill "$pid" 2>/dev/null || true; fi
    done
  fi
  # only now: somebody who takes the lock must not have the emulator killed under them
  phone_lock_release
  rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

adbp() { "$ADB" -s "$PHONE" "$@"; }
adbw() { "$ADB" -s "$WATCH" "$@"; }

boot_done() { [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }

ensure_emulator() { # avd port
  local avd="$1" port="$2" serial="emulator-$2" tries=0 pid=""
  if "$ADB" devices | grep -q "^${serial}[[:space:]]*device"; then
    info "$serial already running"
  else
    info "starting $avd on port $port (headless)"
    "$EMULATOR" -avd "$avd" -port "$port" -no-window -no-audio -no-snapshot -no-boot-anim >"$WORK/emulator-$port.log" 2>&1 &
    pid=$!
    STARTED+=("$serial"); EMU_PIDS+=("$pid")
  fi
  until boot_done "$serial"; do
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      tail -n 20 "$WORK/emulator-$port.log" >&2 || true
      die "the $avd emulator process exited before it booted"
    fi
    tries=$((tries + 1)); [ "$tries" -le 100 ] || die "$serial did not finish booting in 5 minutes"
    sleep 3
  done
  info "$serial booted"
}

# Polls logcat on a device until a line matching the regex shows up (log was cleared before the action).
wait_log() { # serial tag regex seconds -> prints the matching line
  local serial="$1" tag="$2" re="$3" secs="$4" i=0 line
  while [ "$i" -lt "$secs" ]; do
    line="$("$ADB" -s "$serial" logcat -d -s "$tag:I" 2>/dev/null | tr -d '\r' | grep -E "$re" | tail -1 || true)"
    if [ -n "$line" ]; then printf '%s' "$line"; return 0; fi
    sleep 1; i=$((i + 1))
  done
  return 1
}

# ---------------------------------------------------------------- 1. emulators
step "1. emulators"
ensure_emulator "$PHONE_AVD" "$PHONE_PORT"
ensure_emulator "$WATCH_AVD" "$WATCH_PORT"
BATTERY_SET=yes
adbw shell dumpsys battery unplug >/dev/null   # the emulator boots "charging", which covers the face with the charging screen (reset on exit)

# ---------------------------------------------------------------- 2. build and install
step "2. build and install"
"$ROOT/gradlew" -p "$ROOT" :phone:assembleDebug :watch:assembleDebug -q
PHONE_APK="$ROOT/phone/build/outputs/apk/debug/phone-debug.apk"
WATCH_APK="$ROOT/watch/build/outputs/apk/debug/watch-debug.apk"
for f in "$PHONE_APK" "$WATCH_APK"; do [ -f "$f" ] || die "missing build output $f"; done

# The installed base APK must be byte-identical to the one just built; a failed install (for example out of storage)
# would otherwise leave the previously installed app in place and the run would test the wrong build.
host_sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
device_sha() { # serial -> sha256 of the installed base APK of $APP
  local apk; apk="$("$ADB" -s "$1" shell pm path "$APP" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | grep -m1 'base\.apk$' || true)"
  [ -n "$apk" ] || return 1
  "$ADB" -s "$1" shell sha256sum "$apk" 2>/dev/null | tr -d '\r' | cut -d' ' -f1
}
install_checked() { # serial apk label
  local serial="$1" apk="$2" label="$3" out want got
  if ! out="$("$ADB" -s "$serial" install -r "$apk" 2>&1)"; then die "$label install failed on $serial: $out"; fi
  case "$out" in *Success*) ;; *) die "$label install did not report Success on $serial: $out" ;; esac
  want="$(host_sha "$apk")"
  got="$(device_sha "$serial" || true)"
  [ -n "$got" ] || die "cannot read the installed $label APK on $serial to verify it"
  [ "$got" = "$want" ] || die "installed $label APK on $serial is not the one just built (device $got, build $want)"
  info "$label app installed on $serial (sha256 ${want:0:12} verified)"
}
step "2b. phone emulator lock"
phone_lock_acquire
info "holding the phone emulator lock ($PHONE_LOCK)"
install_checked "$PHONE" "$PHONE_APK" phone
adbw uninstall "$APP" >/dev/null 2>&1 || true        # fresh install: the one-shot activation is available again
install_checked "$WATCH" "$WATCH_APK" watch
# PUSH_WATCH_FACES is granted at install time (pm grant refuses it); SET_PUSHED_WATCH_FACE_AS_ACTIVE is a runtime permission.
adbw shell pm grant "$APP" com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE
PERMS="$(adbw shell dumpsys package "$APP" | tr -d '\r')"
for p in PUSH_WATCH_FACES SET_PUSHED_WATCH_FACE_AS_ACTIVE; do
  grep -q "com.google.wear.permission.$p: granted=true" <<<"$PERMS" || die "permission $p is not granted"
done
info "permissions granted"

# ---------------------------------------------------------------- 3 + 4. three designs
mkdir -p "$SHOTS"
rm -f "$SHOTS"/*.png
PREV_VERSION=0
PHONE_FILES="/sdcard/Android/data/$APP/files/relay"

# Reads the pixel colour at the watch centre and checks it against the expected accent. Python + Pillow.
check_disc() { # png expectedHex
  python3 -I - "$1" "$2" <<'PY'
import sys
from PIL import Image
png, want = sys.argv[1], sys.argv[2].lstrip("#")
want = tuple(int(want[i:i + 2], 16) for i in (0, 2, 4))
im = Image.open(png).convert("RGB")
cx, cy = im.width // 2, im.height // 2
tol, r = 24, 6
hits, best = 0, None
for dx in range(-r, r + 1):
    for dy in range(-r, r + 1):
        p = im.getpixel((cx + dx, cy + dy))
        if all(abs(p[i] - want[i]) <= tol for i in range(3)):
            hits += 1
            best = best or p
total = (2 * r + 1) ** 2
centre = im.getpixel((cx, cy))
print(f"   screenshot {im.width}x{im.height}, centre pixel {centre}, {hits}/{total} pixels within {tol} of {want}")
sys.exit(0 if hits >= 20 else 1)
PY
}

run_design() { # index name expectedAccentHex activate(yes|no) ["extra am extras as ONE string, # quoted for the device shell"]
  local n="$1" name="$2" accent="$3" activate="$4" extras="${5:-}"
  step "3.$n design $name (expect disc $accent)"
  adbp logcat -c; adbw logcat -c
  adbp shell "am broadcast -n $PHONE_RECV -a $APP.DEBUG_BUILD --es template Classic $extras" >/dev/null
  local line; line="$(wait_log "$PHONE" AnalogPlusBuild 'AnalogPlusBuild (ok|FAILED)' 120)" || die "no AnalogPlusBuild line within 120 s"
  case "$line" in *"AnalogPlusBuild ok"*) ;; *) die "phone build failed: $line" ;; esac
  info "$line"
  local dir="$WORK/$n"; mkdir -p "$dir"
  for f in face.apk face.token face.version; do adbp pull "$PHONE_FILES/$f" "$dir/$f" >/dev/null 2>&1; done
  local version token; version="$(tr -d '[:space:]' <"$dir/face.version")"; token="$(tr -d '[:space:]' <"$dir/face.token")"
  [ "$version" -gt "$PREV_VERSION" ] || die "phone version $version is not higher than the previous $PREV_VERSION"
  adbw push "$dir/face.apk" /data/local/tmp/face.apk >/dev/null 2>&1
  adbw shell "am broadcast -n $WATCH_RECV -a $APP.DEBUG_APPLY --es path /data/local/tmp/face.apk --es token '$token' --ei versionCode $version" >/dev/null
  local res; res="$(wait_log "$WATCH" AnalogPlusPush '"ok"' 60)" || die "no AnalogPlusPush result within 60 s"
  local json="{${res#*\{}"
  info "watch: $json"
  python3 -I - "$json" "$version" "$PREV_VERSION" <<'PY' || die "watch result is not ok, or its version is wrong (see above)"
import json, sys
r = json.loads(sys.argv[1]); want, prev = int(sys.argv[2]), int(sys.argv[3])
assert r["ok"] is True, r
assert int(r["versionCode"]) == want, ("version", r, want)
assert int(r["versionCode"]) > prev, ("not higher than previous", r, prev)
PY
  info "watch applied version $version (previous $PREV_VERSION)"
  PREV_VERSION="$version"
  if [ "$activate" = yes ]; then
    # the system finishes installing a pushed face a moment after the apply call returns: wait for the package
    local waited=0
    until adbw shell pm list packages "$FACE_PKG" | tr -d '\r' | grep -qx "package:$FACE_PKG"; do
      [ "$waited" -lt 40 ] || die "the face package $FACE_PKG did not appear within 40 s of the apply"
      sleep 2; waited=$((waited + 2))
    done
    adbw logcat -c
    adbw shell am broadcast -n "$WATCH_RECV" -a "$APP.DEBUG_ACTIVATE" >/dev/null
    res="$(wait_log "$WATCH" AnalogPlusPush '"ok"' 30)" || die "no activation result within 30 s"
    case "$res" in *'"ok":true'*|*'"ok": true'*) info "activated: {${res#*\{}" ;; *) die "activation failed: $res" ;; esac
  fi
  info "waiting $SETTLE s for the face to settle"; sleep "$SETTLE"
  local png="$SHOTS/$n-$name.png"
  adbw exec-out screencap -p >"$png"
  check_disc "$png" "$accent" || die "centre disc colour of $png is not $accent"
  info "ok: $png"
}

run_design 1 default      "#E22B07" yes
run_design 2 blue-moveTop "#1E6BFF" no "--es accent '#FF1E6BFF' --ez moveTop true"
run_design 3 quarterRings "#22C55E" no "--es accent '#FF22C55E' --ez quarterRings true"
phone_lock_release   # the phone emulator is not used again

# ---------------------------------------------------------------- 4b. exactly one face installed
step "4. installed faces"
FACES="$(adbw shell pm list packages 2>/dev/null | tr -d '\r' | grep -E "^package:$APP\.watchfacepush\." || true)"
info "Analog Plus face packages: ${FACES:-none}"
[ "$FACES" = "package:$FACE_PKG" ] || die "expected exactly one face package ($FACE_PKG)"
INSTALLED_VERSION="$(adbw shell dumpsys package "$FACE_PKG" | tr -d '\r' | grep -m1 -oE 'versionCode=[0-9]+' | cut -d= -f2 || true)"
info "installed versionCode: ${INSTALLED_VERSION:-unknown}"
[ "${INSTALLED_VERSION:-}" = "$PREV_VERSION" ] || die "installed versionCode '${INSTALLED_VERSION:-}' is not the last pushed version $PREV_VERSION"

step "E2E PASSED"
for f in "$SHOTS"/*.png; do info "watch screenshot: $f"; done

# ---------------------------------------------------------------- 5. optional test suites
if [ "$WITH_TESTS" = yes ]; then
  step "5. unit tests"
  "$ROOT/gradlew" -p "$ROOT" testDebugUnitTest
  step "5. device tests, facegen + packjni + phone on $PHONE (under the phone emulator lock)"
  ANDROID_SERIAL="$PHONE" "$ROOT/scripts/with_phone_emulator_lock.sh" "$ROOT/gradlew" -p "$ROOT" \
    :facegen:connectedDebugAndroidTest :packjni:connectedDebugAndroidTest :phone:connectedDebugAndroidTest
  step "5. device tests, watch on $WATCH"
  # The e2e leaves the watch app, its face and its one-shot activation installed; the watch tests need a clean slate
  # (gradle removes the app after its own run, so only this first run needs the uninstall). The face goes with the app.
  adbw uninstall "$APP" >/dev/null 2>&1 || true
  ANDROID_SERIAL="$WATCH" "$ROOT/gradlew" -p "$ROOT" :watch:connectedDebugAndroidTest
  step "ALL TESTS PASSED"
fi
