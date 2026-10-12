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
# AVD while it boots, runs or is shut down by this script. The AVD boot is bounded (5 minutes) and the script stops at once
# when the emulator process exits.
#
# The device. The tests reach the Firebase emulators at 10.0.2.2, so they run on an Android EMULATOR, and only on one: the script
# exports ANDROID_SERIAL for Gradle (without it Gradle runs the tests on every attached device, a real phone or watch
# included), so other attached devices (the real watch, a watch emulator) do no harm and are never refused. Which emulator:
#  - ANDROID_SERIAL=emulator-NNNN is honoured (that emulator is used, or started on that port when it is not running); any
#    other serial is refused;
#  - ANDROID_SERIAL not set: emulator-5562 if it is attached; else the one attached PHONE emulator (watch emulators and real
#    devices are skipped; a watch is told by ro.build.characteristics); with none attached, emulator-5562 is started.
#    It stops only when it truly cannot choose: several phone emulators are attached and none is emulator-5562; it then lists
#    them and says to set ANDROID_SERIAL.
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
# ACCOUNTS_E2E_SELECT_ONLY (print the device that would be used and stop, before anything is started).
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
BOOT_TRIES="${ACCOUNTS_E2E_BOOT_TRIES:-100}"
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
  phones=""
  for serial in $("$ADB" devices | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-[0-9]+$/ { print $1 }'); do
    [ "$serial" = "$PHONE" ] && { phones="$serial"; break; }
    case "$("$ADB" -s "$serial" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')" in
      *watch*) ;;   # a Wear OS emulator is not a phone
      *) phones="$phones $serial" ;;
    esac
  done
  phones="$(printf '%s' "$phones" | sed 's/^ *//')"
  case "$(printf '%s' "$phones" | wc -w | tr -d ' ')" in
    0) ;;                                   # no phone emulator is attached: $PHONE is started below
    1) PHONE="$phones"; PHONE_PORT="${PHONE#emulator-}" ;;
    *) die "several phone emulators are attached ($phones) and none is emulator-$PHONE_PORT, so there is no way to know which one to use. Run it as: ANDROID_SERIAL=<one of them> scripts/accounts_e2e.sh" ;;
  esac
fi
export ANDROID_SERIAL="$PHONE"
if [ -n "${ACCOUNTS_E2E_SELECT_ONLY:-}" ]; then echo "device: $PHONE"; exit 0; fi

# ---- bookkeeping of what this run started ----------------------------------------------------------------------------------
# Job control: every background job below gets its own process group, so that npm and firebase-tools can be stopped with
# one signal to the group. That does NOT reach the Java Firestore emulator: firebase-tools starts it detached, in a process
# group of its own (but still as its child). See the header for how it is stopped.
set -m
FB_PID=""; AVD_PID=""
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
    if [ -n "$AVD_PID" ]; then
      # we started the AVD: ask it nicely, then make sure the process itself is gone (it may never have reached adb)
      "$ADB" -s "$PHONE" emu kill >/dev/null 2>&1 || true
      for _ in 1 2 3 4 5; do kill -0 "$AVD_PID" 2>/dev/null || break; sleep 1; done
      kill -TERM "$AVD_PID" 2>/dev/null || true
      sleep 1
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
boot_done() { [ "$("$ADB" -s "$PHONE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }
if "$ADB" devices | grep -q "^$PHONE[[:space:]]*device"; then
  echo "   already running"
else
  "$EMULATOR" -avd "$PHONE_AVD" -port "$PHONE_PORT" -no-window -no-audio -no-snapshot -no-boot-anim >"$ROOT/tools/emulator-$PHONE_AVD.log" 2>&1 &
  AVD_PID=$!
  tries=0
  until boot_done; do
    if ! kill -0 "$AVD_PID" 2>/dev/null; then
      tail -n 20 "$ROOT/tools/emulator-$PHONE_AVD.log" >&2 || true
      AVD_PID=""   # already gone
      die "the $PHONE_AVD emulator process exited before it booted (is the same AVD already running on another port?)"
    fi
    tries=$((tries + 1))
    [ "$tries" -le "$BOOT_TRIES" ] || die "$PHONE did not finish booting in $((BOOT_TRIES * 3)) seconds"
    sleep 3
  done
fi

step "Build the debug app and the test app"
cd "$ROOT"
./gradlew :phone:assembleDebug :phone:assembleDebugAndroidTest -q

# One trivial request to each emulator: a Firestore read of a document that does not exist (with the emulator's owner token, which
# skips the rules) and the Auth emulator's status page. A failure here is not a failure of the run.
step "Warm up the emulators"
curl -s -m 30 -o /dev/null -H "Authorization: Bearer owner" \
  "http://127.0.0.1:8080/v1/projects/demo-analogplus/databases/(default)/documents/warmup/ping" && echo "   Firestore answered" || echo "   Firestore did not answer the warm-up request (continuing)"
curl -s -m 30 -o /dev/null "http://127.0.0.1:9099/" && echo "   Auth answered" || echo "   Auth did not answer the warm-up request (continuing)"

step "Accounts tests (emulator-backed) on $PHONE"
if [ -n "$CLASSES" ]; then FILTER="class=$CLASSES"; else FILTER="package=com.seyedjafari.analogplus.phone.accounts"; fi
./gradlew :phone:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.$FILTER" \
  "-Pandroid.testInstrumentationRunnerArguments.requireEmulators=true"
echo "accounts e2e: PASS"
