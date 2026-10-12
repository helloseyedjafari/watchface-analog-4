#!/usr/bin/env bash
# Runs the accounts tests that need the Firebase Emulator Suite, on the phone emulator: every instrumented test in package
# ...phone.accounts. Among them: FirebaseEmulatorTest (the real Firestore / Auth code against the emulators, with
# firebase/firestore.rules loaded, the account marker rules included), AccountsEndToEndTest (replacing a phone and the kill
# switch of a deleted account through the real Account UI), the Account screens and dialogs, the backup page,
# AccountsFactoryTest, EmulatorMarkerTest, PhotoDownscalerTest, KeyVaultCostTest.
# The phone emulators have no Google sign-in, so the debug build signs in through the Auth emulator (see FirebaseProvider).
# Real Google sign-in, Credential Manager and App Check are NOT exercised: they need a real Firebase project.
#
#   scripts/accounts_e2e.sh [--keep-emulators] [--class a.B,c.D]
#   ANDROID_SERIAL=emulator-5562 scripts/accounts_e2e.sh      (the device to run on, see below)
#
# What it starts, and what it stops. It starts the Firebase emulators (Auth 9099, Firestore 8080) and the phone AVD when they
# are not running, runs the tests, and stops ONLY what this run started (unless --keep-emulators):
#  - emulators that were already running (yours, or another session's) are used as they are and are never stopped;
#  - if just one of the two ports is taken the script stops with the holder's process id and starts and kills nothing;
#  - of the processes it finds listening on the ports during its own start it records only those that descend from the
#    process it started (checked up the parent chain) and remembers each one's start time; at the end it stops the process
#    group it started and then exactly those recorded processes, and a process whose start time changed is left alone.
#    Another session's emulator that binds a port in the same moment is therefore not touched.
# NOTE that AccountsEndToEndTest empties the Firestore and Auth data of the emulators it runs against, so do not point it at
# emulators somebody else is using. The phone emulator lock is held for the WHOLE script, so that nobody else installs on the
# AVD while it boots, runs or is shut down by this script. The wait for the phone emulator to be ready is bounded by ELAPSED
# time, 5 minutes (every adb look, `adb devices` and `getprop` alike, is itself limited to 10 seconds, so it ends close to that),
# and the script stops at once when the emulator process it started exits. Whatever is listed under the serial it uses, in ANY
# state (also offline or still booting), is somebody else's emulator, not a free port: the script never starts a second emulator
# on a listed port, waits until the listed one is `device` AND has finished booting (`sys.boot_completed`; the same bound) or
# stops with a message, and only ever stops an emulator it started itself. "Started itself" is decided by who holds the ports, not
# by timing: an emulator is accepted as ours only when EVERY process listening on its console port (the serial's number) and on
# its adb port (the number plus one: adb names the transport after the console port, but `getprop`, the install and the tests go
# through the adb port) is the one this script started or its descendant, and at least the console port has one. The holder's
# start time is recorded; `emu kill` is sent only while that same process still holds the port and nobody else listens on
# either; and the same test is made once more right before the tests start, after the builds (minutes), because the emulator
# may have died and a foreign one taken the port meanwhile. An emulator of its own that was never accepted (boot bound
# exceeded, not ours, Ctrl-C) is stopped gracefully (SIGTERM, up to 5 seconds), then killed.
#
# The device. The tests reach the Firebase emulators at 10.0.2.2, so they run on an Android EMULATOR, and only on one: the script
# exports ANDROID_SERIAL for Gradle (without it Gradle runs the tests on every attached device, a real phone or watch
# included), so other attached devices (the real watch, a watch emulator, someone else's emulator) do no harm and are never
# refused. Which emulator: ONLY one it was told to use or the one the machine-wide phone lock stands for, never another emulator
# it happens to find (the tests install the app on it and Gradle uninstalls it afterwards, which would wipe that app's data on an
# emulator somebody else started):
#  - ANDROID_SERIAL=emulator-NNNN is honoured (that emulator is used, or started on that port when it is not running); any
#    other serial is refused;
#  - ANDROID_SERIAL not set: emulator-5562, used when it is attached (and ready) and started otherwise. The script does not look at the other
#    attached devices at all (no adb call is made to them).
#
# Before the tests the debug app and the test app are built, and both emulators get one trivial request each, so that the first
# Firestore call of the first test is not also the first request the emulator JVM ever served.
#
# Needs Node 20+, Java 21 and `npm ci` already run in firebase/ (done here when node_modules is missing). --class runs only
# those test classes (the instrumented runner argument `class`, comma separated, fully qualified) instead of the whole
# accounts package. The tests are told to FAIL, not skip, when the emulators are not reachable (runner argument
# requireEmulators=true).
#
# Test hooks (environment, for testing this script itself): ACCOUNTS_E2E_EMULATOR (the emulator binary),
# ACCOUNTS_E2E_PHONE_PORT, ACCOUNTS_E2E_PHONE_AVD, ACCOUNTS_E2E_BOOT_TRIES (3-second tries before the boot is given up),
# ACCOUNTS_E2E_EMULATORS_CMD (the command that starts the Firebase emulators, default `npm run emulators`, run in firebase/),
# ACCOUNTS_E2E_START_TRIES (1-second tries for the Firebase emulators to listen, default 90), ACCOUNTS_E2E_ADB (the adb binary),
# ACCOUNTS_E2E_SELECT_ONLY (print the device that would be used and stop, before anything is started),
# ACCOUNTS_E2E_STOP_AFTER_PHONE (stop with success after the phone emulator step, before anything is built),
# ACCOUNTS_E2E_BUILD_CMD (the build command, default the two Gradle assemble tasks), ACCOUNTS_E2E_STOP_BEFORE_TESTS (stop with
# success after the builds and the ownership check, before the tests). `scripts/tests/accounts_e2e_script_test.sh` runs the
# script against fake adb and fake emulator binaries with these hooks.
set -euo pipefail
source "$(dirname "$0")/_env.sh"

for a in "$@"; do case "$a" in -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print } /^set /{ exit }' "$0"; exit 0 ;; esac; done

# Take the phone lock for the whole run: run this very script again under it. The lock script defaults ANDROID_SERIAL, so the
# caller's own value is passed on separately.
if [ -z "${ACCOUNTS_E2E_LOCKED:-}" ]; then
  ACCOUNTS_E2E_LOCKED=1 ACCOUNTS_E2E_CALLER_SERIAL="${ANDROID_SERIAL:-}" exec "$ROOT/scripts/with_phone_emulator_lock.sh" "$ROOT/scripts/accounts_e2e.sh" "$@"
fi

PHONE_AVD="${ACCOUNTS_E2E_PHONE_AVD:-1_Pixel_8_API_33}"; PHONE_PORT="${ACCOUNTS_E2E_PHONE_PORT:-5562}"
EMULATOR="${ACCOUNTS_E2E_EMULATOR:-$EMULATOR}"
ADB="${ACCOUNTS_E2E_ADB:-$ADB}"
BOOT_LIMIT=$(( ${ACCOUNTS_E2E_BOOT_TRIES:-100} * 3 ))   # seconds of elapsed time (the hook counts 3-second tries)
BUILD_CMD="${ACCOUNTS_E2E_BUILD_CMD:-./gradlew :phone:assembleDebug :phone:assembleDebugAndroidTest -q}"
EMULATORS_CMD="${ACCOUNTS_E2E_EMULATORS_CMD:-npm run emulators}"
START_TRIES="${ACCOUNTS_E2E_START_TRIES:-90}"
CALLER_SERIAL="${ACCOUNTS_E2E_CALLER_SERIAL:-}"
FB="$ROOT/firebase"
KEEP=no
CLASSES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --keep-emulators) KEEP=yes ;;
    --class) shift; [ $# -gt 0 ] || { echo "--class needs a value" >&2; exit 2; }; CLASSES="$1" ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

step() { printf '\n== %s\n' "$*"; }
die()  { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# ---- which device -------------------------------------------------------------------------------------------------------
if [ -n "$CALLER_SERIAL" ]; then
  case "$CALLER_SERIAL" in
    emulator-[0-9]*) PHONE="$CALLER_SERIAL"; PHONE_PORT="${CALLER_SERIAL#emulator-}" ;;
    *) die "ANDROID_SERIAL=$CALLER_SERIAL is not an emulator. The tests reach the Firebase emulators at 10.0.2.2, which only an Android emulator can; use ANDROID_SERIAL=emulator-NNNN, or unset it." ;;
  esac
else
  PHONE="emulator-$PHONE_PORT"
fi
export ANDROID_SERIAL="$PHONE"
if [ -n "${ACCOUNTS_E2E_SELECT_ONLY:-}" ]; then echo "device: $PHONE"; exit 0; fi

# ---- bookkeeping of what this run started ----------------------------------------------------------------------------------
# Job control: every background job below gets its own process group, so that npm and firebase-tools can be stopped with
# one signal to the group. That does NOT reach the Java Firestore emulator: firebase-tools starts it detached, in a process
# group of its own (but still as its child). See the header for how it is stopped.
set -m
FB_PID=""; AVD_PID=""; AVD_START=""; AVD_HOLDER=""; AVD_HOLDER_START=""
OURS_PID=(); OURS_START=()
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
holders() { lsof -ti "tcp:$1" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' || true; }
started_at() { ps -o lstart= -p "$1" 2>/dev/null | sed 's/^ *//; s/ *$//' || true; }
ppid_of() { ps -o ppid= -p "$1" 2>/dev/null | tr -d ' ' || true; }

# True when process $1 is $2 or one of its descendants (up the parent chain).
descends_from() {
  local p="$1" ancestor="$2" i=0
  while [ -n "$p" ] && [ "$p" -gt 1 ] 2>/dev/null && [ "$i" -lt 30 ]; do
    [ "$p" = "$ancestor" ] && return 0
    p="$(ppid_of "$p")"
    i=$((i + 1))
  done
  return 1
}

# Remembers (pid and start time) every process listening on an emulator port that descends from the process this script
# started. Called while that process is still alive, so the parent chain is intact.
note_ours() {
  [ -n "$FB_PID" ] || return 0
  local p i seen
  for p in $(holders 8080) $(holders 9099); do
    seen=no
    for i in ${OURS_PID[@]+"${OURS_PID[@]}"}; do [ "$i" = "$p" ] && seen=yes; done
    if [ "$seen" = no ] && descends_from "$p" "$FB_PID"; then
      OURS_PID+=("$p"); OURS_START+=("$(started_at "$p")")
    fi
  done
}

# Sends signal $1 to the recorded processes that are still the same process (same start time).
signal_ours() {
  local sig="$1" n=${#OURS_PID[@]} i=0
  while [ "$i" -lt "$n" ]; do
    if [ -n "${OURS_START[$i]}" ] && [ "$(started_at "${OURS_PID[$i]}")" = "${OURS_START[$i]}" ]; then
      kill "-$sig" "${OURS_PID[$i]}" 2>/dev/null || true
    fi
    i=$((i + 1))
  done
}

# True when process $1 is listening on TCP port $2.
holds_port() { local p; for p in $(holders "$2"); do [ "$p" = "$1" ] && return 0; done; return 1; }

# True when at least one process listens on the console port and EVERY listener of the console port and of the adb port (the
# console port + 1) is process $1 or one of its descendants (the emulator this script started).
only_ours_on_ports() {
  local port p n=0
  for port in "$PHONE_PORT" "$((PHONE_PORT + 1))"; do
    for p in $(holders "$port"); do
      descends_from "$p" "$1" || return 1
      [ "$port" = "$PHONE_PORT" ] && n=$((n + 1))
    done
  done
  [ "$n" -gt 0 ]
}

# True while the emulator this script accepted as its own still is: the recorded holder is the same process (same start time), it
# still listens on the console port, and nobody else listens on either port.
still_ours() {
  [ -n "$AVD_HOLDER" ] && [ "$(started_at "$AVD_HOLDER")" = "$AVD_HOLDER_START" ] && holds_port "$AVD_HOLDER" "$PHONE_PORT" \
    && only_ours_on_ports "$AVD_PID"
}

# True while a recorded process is still the same process and alive.
ours_alive() {
  local n=${#OURS_PID[@]} i=0
  while [ "$i" -lt "$n" ]; do
    if [ -n "${OURS_START[$i]}" ] && [ "$(started_at "${OURS_PID[$i]}")" = "${OURS_START[$i]}" ]; then return 0; fi
    i=$((i + 1))
  done
  return 1
}

cleanup() {
  if [ "$KEEP" = no ]; then
    if [ -n "$FB_PID" ]; then
      note_ours
      kill -TERM -- "-$FB_PID" 2>/dev/null || true
      # let firebase-tools shut the emulators down itself (wait for OUR processes, not for whoever else holds a port)
      for _ in $(seq 1 20); do { kill -0 -- "-$FB_PID" 2>/dev/null || ours_alive; } || break; sleep 1; done
      kill -KILL -- "-$FB_PID" 2>/dev/null || true
      # what this run recorded as its own (the detached Java emulator) and is still the same process
      signal_ours TERM
      sleep 1
      signal_ours KILL
    fi
    # The AVD this run started. `emu kill` goes to the serial only while the process that holds the console port is still the one
    # recorded when it was accepted as ours (same pid, same start time): after that, whoever answers to the serial is not ours.
    if still_ours; then
      "$ADB" -s "$PHONE" emu kill >/dev/null 2>&1 || true
      for _ in 1 2 3 4 5; do kill -0 "$AVD_HOLDER" 2>/dev/null || break; sleep 1; done
    fi
    # the process this script started, if it is still that process (a reused pid is not signalled): a graceful stop first (SIGTERM,
    # up to 5 seconds, so that an emulator that was still booting can close its disk files), then SIGKILL
    if [ -n "$AVD_PID" ] && [ -n "$AVD_START" ] && [ "$(started_at "$AVD_PID")" = "$AVD_START" ]; then
      kill -TERM "$AVD_PID" 2>/dev/null || true
      for _ in 1 2 3 4 5; do kill -0 "$AVD_PID" 2>/dev/null || break; sleep 1; done
      kill -KILL "$AVD_PID" 2>/dev/null || true
    fi
  fi
}
trap cleanup EXIT

step "Firebase emulators"
[ -d "$FB/node_modules" ] || (cd "$FB" && npm ci)
if port_open 8080 && port_open 9099; then
  echo "   already running (not started by this script: it will not stop them)"
elif port_open 8080 || port_open 9099; then
  die "only one of the emulator ports is taken (8080: $(holders 8080)| 9099: $(holders 9099)|): another process holds it. Stop it or free both ports; nothing was started or killed."
else
  (cd "$FB" && exec $EMULATORS_CMD >"$FB/emulators.log" 2>&1) &
  FB_PID=$!
  for _ in $(seq 1 "$START_TRIES"); do
    note_ours
    port_open 8080 && port_open 9099 && break
    sleep 1
  done
  port_open 8080 && port_open 9099 || die "the Firebase emulators did not start (see firebase/emulators.log)"
  note_ours   # both are listening now: the Java emulator is recorded even if the loop above ended on the first look
fi

step "Phone emulator"
# Every adb look is bounded: an adb server or an emulator that accepts a call and never answers must not hang the run while it
# holds the phone lock.
adb_bounded() { perl -e 'alarm 10; exec @ARGV' "$ADB" "$@"; }
boot_done() { [ "$(adb_bounded -s "$PHONE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }
adb_state() { # prints the state of the serial ("" when it is not listed); fails when adb does not answer
  local devs; devs="$(adb_bounded devices)" || return 1
  printf '%s\n' "$devs" | awk -v serial="$PHONE" 'NR > 1 && $1 == serial { print $2 }'
}
listed="$(adb_state)" || die "adb did not answer 'adb devices' within 10 seconds; nothing was started or stopped."
waited=$SECONDS
if [ -n "$listed" ]; then
  # The serial is listed (ready or not): somebody else's emulator, never ours to replace. Use it once it is a `device` that has
  # finished booting: adb reports `device` well before sys.boot_completed.
  if [ "$listed" = "device" ]; then echo "   already running (not started by this script: it will not stop it); checking that it has booted"
  else echo "   $PHONE is listed as '$listed' (not started by this script); waiting for it to become ready"; fi
  until [ "$(adb_state || true)" = "device" ] && boot_done; do
    [ $((SECONDS - waited)) -le "$BOOT_LIMIT" ] || die "$PHONE is listed as '$listed' and did not become ready (device, booted) within $BOOT_LIMIT seconds. It is not started or stopped by this script (a second emulator is never started on a listed port): wait for it, close it, or use another ANDROID_SERIAL."
    sleep 3
  done
else
  "$EMULATOR" -avd "$PHONE_AVD" -port "$PHONE_PORT" -no-window -no-audio -no-snapshot -no-boot-anim >"$ROOT/tools/emulator-$PHONE_AVD.log" 2>&1 &
  AVD_PID=$!
  AVD_START="$(started_at "$AVD_PID")"
  until boot_done; do
    if ! kill -0 "$AVD_PID" 2>/dev/null; then
      tail -n 20 "$ROOT/tools/emulator-$PHONE_AVD.log" >&2 || true
      die "the $PHONE_AVD emulator process exited before it booted (is the same AVD already running on another port?)"
    fi
    [ $((SECONDS - waited)) -le "$BOOT_LIMIT" ] || die "$PHONE did not finish booting in $BOOT_LIMIT seconds"
    sleep 3
  done
  # Something answers to the serial. It is ours only if every process listening on the console port and on the adb port is the one
  # this script started (or a child of it: the launcher may hand over to another process). If anything else listens on either,
  # whoever answers is somebody else's emulator: it is not used and not stopped (our own process, which cannot get the ports, is
  # stopped by the cleanup).
  if only_ours_on_ports "$AVD_PID"; then
    for p in $(holders "$PHONE_PORT"); do AVD_HOLDER="$p"; AVD_HOLDER_START="$(started_at "$p")"; done
  fi
  if [ -z "$AVD_HOLDER" ]; then
    die "$PHONE answers, but the console port ($PHONE_PORT: pid $(holders "$PHONE_PORT")) or the adb port ($((PHONE_PORT + 1)): pid $(holders "$((PHONE_PORT + 1))")) is held by something other than the emulator this script started: another emulator holds a port. Nothing of it is used or stopped."
  fi
fi
if [ -n "${ACCOUNTS_E2E_STOP_AFTER_PHONE:-}" ]; then echo "phone emulator step done"; exit 0; fi

step "Build the debug app and the test app"
cd "$ROOT"
$BUILD_CMD

# One trivial request to each emulator: a Firestore read of a document that does not exist (with the emulator's owner token, which
# skips the rules) and the Auth emulator's status page. A failure here is not a failure of the run.
step "Warm up the emulators"
curl -s -m 30 -o /dev/null -H "Authorization: Bearer owner" \
  "http://127.0.0.1:8080/v1/projects/demo-analogplus/databases/(default)/documents/warmup/ping" && echo "   Firestore answered" || echo "   Firestore did not answer the warm-up request (continuing)"
curl -s -m 30 -o /dev/null "http://127.0.0.1:9099/" && echo "   Auth answered" || echo "   Auth did not answer the warm-up request (continuing)"

# The builds took minutes. The emulator this script started may have died meanwhile, and something that does not take the phone lock
# may have started another on the same serial: Gradle would install on it and uninstall afterwards. Check once more.
if [ -n "$AVD_PID" ] && ! still_ours; then
  die "the emulator this script started on $PHONE is no longer the one holding its ports (it died, or another emulator took them) while the builds ran. Nothing of the other one is used or stopped."
fi
if [ -n "${ACCOUNTS_E2E_STOP_BEFORE_TESTS:-}" ]; then echo "ready for the tests"; exit 0; fi

step "Accounts tests (emulator-backed) on $PHONE"
if [ -n "$CLASSES" ]; then FILTER="class=$CLASSES"; else FILTER="package=com.seyedjafari.analogplus.phone.accounts"; fi
./gradlew :phone:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.$FILTER" \
  "-Pandroid.testInstrumentationRunnerArguments.requireEmulators=true"
echo "accounts e2e: PASS"
