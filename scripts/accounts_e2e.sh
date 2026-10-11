#!/usr/bin/env bash
# Runs the accounts tests that need the Firebase Emulator Suite, on the phone emulator: every instrumented test in package
# ...phone.accounts. Among them: FirebaseEmulatorTest (the real Firestore / Auth code against the emulators, with
# firebase/firestore.rules loaded), AccountsEndToEndTest (replacing a phone through the real Account UI), the Account
# screens and dialogs, the backup page, AccountsFactoryTest, EmulatorMarkerTest, PhotoDownscalerTest, KeyVaultCostTest.
# The phone emulators have no Google sign-in, so the debug build signs in through the Auth emulator (see FirebaseProvider).
# Real Google sign-in, Credential Manager and App Check are NOT exercised: they need a real Firebase project.
#
#   scripts/accounts_e2e.sh [--keep-emulators] [--class a.B,c.D]
#
# Starts the Firebase emulators (Auth 9099, Firestore 8080) and the phone AVD when they are not running, runs the tests,
# and stops what it started (unless --keep-emulators), and ONLY what it started: emulators that were already running (yours,
# or another session's) are used as they are and left running; if just one of the two ports is taken the script stops with
# the holder's process id and starts and kills nothing. NOTE that AccountsEndToEndTest empties the Firestore and Auth data
# of the emulators it runs against, so do not point it at emulators somebody else is using. The phone emulator lock is
# held for the WHOLE script, so that nobody else installs on the AVD while it boots, runs or is shut down by this script.
# The AVD boot is bounded (5 minutes) and the script stops at once when the emulator process exits. Needs Node 20+, Java 21
# and `npm ci` already run in firebase/ (done here when node_modules is missing). --class runs only those test classes (the
# instrumented runner argument `class`, comma separated, fully qualified) instead of the whole accounts package. The tests
# are told to FAIL, not skip, when the emulators are not reachable (runner argument requireEmulators=true).
#
# Test hooks (environment, for testing this script itself): ACCOUNTS_E2E_EMULATOR (the emulator binary),
# ACCOUNTS_E2E_PHONE_PORT, ACCOUNTS_E2E_PHONE_AVD, ACCOUNTS_E2E_BOOT_TRIES (3-second tries before the boot is given up).
set -euo pipefail
source "$(dirname "$0")/_env.sh"

for a in "$@"; do case "$a" in -h|--help) sed -n '2,23p' "$0"; exit 0 ;; esac; done

# Take the phone lock for the whole run: run this very script again under it.
if [ -z "${ACCOUNTS_E2E_LOCKED:-}" ]; then
  ACCOUNTS_E2E_LOCKED=1 exec "$ROOT/scripts/with_phone_emulator_lock.sh" "$ROOT/scripts/accounts_e2e.sh" "$@"
fi

PHONE_AVD="${ACCOUNTS_E2E_PHONE_AVD:-1_Pixel_8_API_33}"; PHONE_PORT="${ACCOUNTS_E2E_PHONE_PORT:-5562}"; PHONE="emulator-$PHONE_PORT"
EMULATOR="${ACCOUNTS_E2E_EMULATOR:-$EMULATOR}"
BOOT_TRIES="${ACCOUNTS_E2E_BOOT_TRIES:-100}"
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

# Job control: every background job below gets its own process group, so that npm and firebase-tools can be stopped with
# one signal to the group. That does NOT reach the Java Firestore emulator: firebase-tools starts it detached, in a process
# group of its own. It stops when firebase-tools shuts down on SIGTERM; if it is still there afterwards, cleanup kills the
# processes that were seen listening on the emulator ports DURING THIS SCRIPT'S OWN START (OURS_PIDS). Both ports were
# checked to be free before the start, so those are ours; nobody else's process is ever killed.
set -m
FB_PID=""; AVD_PID=""; OURS_PIDS=""
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
holders() { lsof -ti "tcp:$1" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' || true; }
note_ours() { local p; for p in $(holders 8080) $(holders 9099); do case " $OURS_PIDS " in *" $p "*) ;; *) OURS_PIDS="$OURS_PIDS $p" ;; esac; done; }

cleanup() {
  if [ "$KEEP" = no ]; then
    if [ -n "$FB_PID" ]; then
      note_ours
      kill -TERM -- "-$FB_PID" 2>/dev/null || true
      # let firebase-tools shut the emulators down itself
      for _ in $(seq 1 20); do { port_open 8080 || port_open 9099; } || break; sleep 1; done
      kill -KILL -- "-$FB_PID" 2>/dev/null || true
      # what we saw listening during our own start and is still alive (the detached Java emulator)
      local p
      for p in $OURS_PIDS; do kill -TERM "$p" 2>/dev/null || true; done
      sleep 1
      for p in $OURS_PIDS; do kill -KILL "$p" 2>/dev/null || true; done
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
  (cd "$FB" && exec npm run emulators >"$FB/emulators.log" 2>&1) &
  FB_PID=$!
  for _ in $(seq 1 90); do
    note_ours
    port_open 8080 && port_open 9099 && break
    sleep 1
  done
  port_open 8080 && port_open 9099 || die "the Firebase emulators did not start (see firebase/emulators.log)"
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

step "Accounts tests (emulator-backed)"
cd "$ROOT"
if [ -n "$CLASSES" ]; then FILTER="class=$CLASSES"; else FILTER="package=com.seyedjafari.analogplus.phone.accounts"; fi
./gradlew :phone:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.$FILTER" \
  "-Pandroid.testInstrumentationRunnerArguments.requireEmulators=true"
echo "accounts e2e: PASS"
