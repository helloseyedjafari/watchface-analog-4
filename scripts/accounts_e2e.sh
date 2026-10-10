#!/usr/bin/env bash
# Runs the accounts tests that need the Firebase Emulator Suite, on the phone emulator:
#   FirebaseEmulatorTest   the real Firestore / Auth code against the emulators, with firebase/firestore.rules loaded
#   (and every other instrumented test in package ...phone.accounts: AccountsFactoryTest, EmulatorMarkerTest, PhotoDownscalerTest, KeyVaultCostTest)
# The phone emulators have no Google sign-in, so the debug build signs in through the Auth emulator (see FirebaseProvider).
# Real Google sign-in, Credential Manager and App Check are NOT exercised: they need a real Firebase project.
#
#   scripts/accounts_e2e.sh [--keep-emulators] [--class a.B,c.D]
#
# Starts the Firebase emulators (Auth 9099, Firestore 8080) and the phone AVD when they are not running, runs the tests,
# and stops what it started (unless --keep-emulators). The phone emulator lock is held for the WHOLE script, so that nobody
# else installs on the AVD while it boots, runs or is shut down by this script. Needs Node 20+, Java 21 and `npm ci`
# already run in firebase/ (done here when node_modules is missing). --class runs only those test classes (the instrumented
# runner argument `class`, comma separated, fully qualified) instead of the whole accounts package. The tests are told to
# FAIL, not skip, when the emulators are not reachable (runner argument requireEmulators=true).
set -euo pipefail
source "$(dirname "$0")/_env.sh"

for a in "$@"; do case "$a" in -h|--help) sed -n '2,16p' "$0"; exit 0 ;; esac; done

# Take the phone lock for the whole run: run this very script again under it.
if [ -z "${ACCOUNTS_E2E_LOCKED:-}" ]; then
  ACCOUNTS_E2E_LOCKED=1 exec "$ROOT/scripts/with_phone_emulator_lock.sh" "$ROOT/scripts/accounts_e2e.sh" "$@"
fi

PHONE_AVD="1_Pixel_8_API_33"; PHONE_PORT=5562; PHONE="emulator-$PHONE_PORT"
FB="$ROOT/firebase"
KEEP=no
CLASSES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --keep-emulators) KEEP=yes ;;
    --class) shift; [ $# -gt 0 ] || { echo "--class needs a value" >&2; exit 2; }; CLASSES="$1" ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

# Job control: every background job below gets its own process group, so that npm and firebase-tools can be stopped with
# one signal to the group. That does NOT reach the Java Firestore emulator: firebase-tools starts it detached, in a process
# group of its own. It stops when firebase-tools shuts down on SIGTERM; if it is still listening afterwards (firebase-tools
# was killed, or was slow), cleanup kills whatever still listens on the emulator ports, but only when this script started
# the emulators.
set -m
FB_PID=""; AVD_STARTED=no
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
cleanup() {
  if [ "$KEEP" = no ]; then
    if [ -n "$FB_PID" ]; then
      kill -TERM -- "-$FB_PID" 2>/dev/null || true
      # let firebase-tools shut the emulators down itself
      for _ in $(seq 1 20); do { port_open 8080 || port_open 9099; } || break; sleep 1; done
      kill -KILL -- "-$FB_PID" 2>/dev/null || true
      # whatever still listens (the detached Java emulator) is ours: we started the emulators
      for port in 8080 9099; do
        for pid in $(lsof -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null || true); do kill -TERM "$pid" 2>/dev/null || true; done
      done
      sleep 1
      for port in 8080 9099; do
        for pid in $(lsof -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null || true); do kill -KILL "$pid" 2>/dev/null || true; done
      done
    fi
    if [ "$AVD_STARTED" = yes ]; then "$ADB" -s "$PHONE" emu kill >/dev/null 2>&1 || true; fi
  fi
}
trap cleanup EXIT

step "Firebase emulators"
[ -d "$FB/node_modules" ] || (cd "$FB" && npm ci)
if port_open 8080 && port_open 9099; then
  echo "   already running"
else
  (cd "$FB" && exec npm run emulators >"$FB/emulators.log" 2>&1) &
  FB_PID=$!
  for _ in $(seq 1 90); do port_open 8080 && port_open 9099 && break; sleep 1; done
  port_open 8080 && port_open 9099 || die "the Firebase emulators did not start (see firebase/emulators.log)"
fi

step "Phone emulator"
if "$ADB" devices | grep -q "^$PHONE[[:space:]]*device"; then
  echo "   already running"
else
  "$EMULATOR" -avd "$PHONE_AVD" -port "$PHONE_PORT" -no-window -no-audio -no-snapshot -no-boot-anim >"$ROOT/tools/emulator-$PHONE_AVD.log" 2>&1 &
  AVD_STARTED=yes
  "$ADB" -s "$PHONE" wait-for-device
  until [ "$("$ADB" -s "$PHONE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
fi

step "Accounts tests (emulator-backed)"
cd "$ROOT"
if [ -n "$CLASSES" ]; then FILTER="class=$CLASSES"; else FILTER="package=com.seyedjafari.analogplus.phone.accounts"; fi
./gradlew :phone:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.$FILTER" \
  "-Pandroid.testInstrumentationRunnerArguments.requireEmulators=true"
echo "accounts e2e: PASS"
