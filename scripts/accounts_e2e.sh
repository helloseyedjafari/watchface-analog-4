#!/usr/bin/env bash
# Runs the accounts tests that need the Firebase Emulator Suite, on the phone emulator:
#   FirebaseEmulatorTest   the real Firestore / Auth code against the emulators, with firebase/firestore.rules loaded
#   (and every other instrumented test in package ...phone.accounts: AccountsFactoryTest, PhotoDownscalerTest, KeyVaultCostTest)
# The phone emulators have no Google sign-in, so the debug build signs in through the Auth emulator (see FirebaseProvider).
# Real Google sign-in, Credential Manager and App Check are NOT exercised: they need a real Firebase project.
#
#   scripts/accounts_e2e.sh [--keep-emulators] [--class a.B,c.D]
#
# Starts the Firebase emulators (Auth 9099, Firestore 8080) and the phone AVD when they are not running, takes the phone
# emulator lock for the Gradle run, and stops what it started (unless --keep-emulators). Needs Node 20+, Java 21 and
# `npm ci` already run in firebase/ (done here when node_modules is missing). --class runs only those test classes
# (the instrumented runner argument `class`, comma separated, fully qualified) instead of the whole accounts package.
set -euo pipefail
source "$(dirname "$0")/_env.sh"

PHONE_AVD="1_Pixel_8_API_33"; PHONE_PORT=5562; PHONE="emulator-$PHONE_PORT"
FB="$ROOT/firebase"
KEEP=no
CLASSES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --keep-emulators) KEEP=yes ;;
    --class) shift; [ $# -gt 0 ] || { echo "--class needs a value" >&2; exit 2; }; CLASSES="$1" ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

FB_PID=""; AVD_STARTED=no
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
cleanup() {
  if [ "$KEEP" = no ]; then
    if [ -n "$FB_PID" ]; then pkill -P "$FB_PID" 2>/dev/null || true; kill "$FB_PID" 2>/dev/null || true; fi
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
scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.$FILTER"
echo "accounts e2e: PASS"
