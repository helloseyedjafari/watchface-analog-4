#!/usr/bin/env bash
# Tests of scripts/accounts_e2e.sh itself, against a fake adb and fake emulator binaries (no Android emulator, no Gradle, no
# Firebase emulator is needed). It checks the safety properties of the script:
#   - which device it picks (only the named serial or emulator-5562, no adb call to other devices);
#   - it never uses, starts over or stops an emulator that is not its own (who holds the console port AND the adb port decides);
#   - a listed serial is waited for until it is a `device` that has booted; every adb look is bounded;
#   - an emulator of its own that was never accepted is stopped gracefully;
#   - the ownership is checked again after the builds;
#   - another session's Firebase emulator on one of the two ports is not touched.
#
#   scripts/tests/accounts_e2e_script_test.sh [case-prefix]     (for example: P, S, F; default all)
#
# Safe on a machine where other sessions work: it uses its own ports for the fake phone emulator (5598 console, 5599 adb; override
# with ACCOUNTS_E2E_TEST_PORT) and stops ONLY the processes it started itself (its decoys and the fake emulators). The script under
# test starts a fake Firebase emulator on 8080 and 9099 (a stand-in that the script stops again); when those ports are taken by
# someone else the cases that need them are skipped (both taken: the script uses them as "already running" and leaves them alone).
# Takes the machine-wide phone emulator lock like the real script (the cases wait for it), about 3 minutes in all.
# Exit status: 0 when every case passed, 1 when one failed, 2 when the ports of the fake emulator are not free.
set -u
# A caller's serial would change which device the script under test picks; the cases that need one set it themselves.
unset ANDROID_SERIAL
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
S="$ROOT/scripts/accounts_e2e.sh"
PORT="${ACCOUNTS_E2E_TEST_PORT:-5598}"
ADBPORT=$((PORT + 1))
ONLY="${1:-}"
TMP="$(mktemp -d)"
LOCK="${TMPDIR:-/tmp}/analogplus-phone-emulator.lock"
MYPIDS=""        # processes this harness started
FAILS=0; PASSES=0

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
cleanup() {
  local p
  for p in $MYPIDS $(cat "$TMP"/foreign.pid 2>/dev/null); do kill "$p" 2>/dev/null; done
  rm -rf "$TMP"
}
trap cleanup EXIT

for p in $PORT $ADBPORT; do
  if port_open $p; then echo "port $p is in use: set ACCOUNTS_E2E_TEST_PORT to a free pair of ports (N and N+1)" >&2; exit 2; fi
done

# ---------------------------------------------------------------------------------------------------- the fakes
cat > "$TMP/fake-adb.sh" <<'EOF'
#!/bin/bash
# FAKE selects the behaviour. Without a mode: nothing is listed and "booted" iff something listens on the console port.
echo "$*" >> "$ADBLOG"
listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
case "$FAKE" in
  multi)            # the devices of $DEVICES ("serial:kind ...") are listed; never asked anything else
    if [ "$1" = devices ]; then echo "List of devices attached"; for d in $DEVICES; do printf '%s\tdevice\n' "${d%%:*}"; done; echo; fi; exit 0 ;;
  hang_devices) if [ "$1" = devices ]; then exec sleep 60; fi; exit 0 ;;
  listed_booting)   # listed as device from the start; sys.boot_completed is 1 from the 3rd look on
    if [ "$1" = devices ]; then printf 'List of devices attached\nemulator-%s\tdevice\n\n' "$PORT"; exit 0; fi
    if [ "$3" = shell ]; then n=$(cat "$TMPD/boots" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$TMPD/boots"; [ "$n" -ge 3 ] && echo 1 || echo 0; fi
    exit 0 ;;
  listed_never)     # listed as device, never booted
    if [ "$1" = devices ]; then printf 'List of devices attached\nemulator-%s\tdevice\n\n' "$PORT"; exit 0; fi
    if [ "$3" = shell ]; then echo 0; fi; exit 0 ;;
  offline)          # listed offline for ever
    if [ "$1" = devices ]; then printf 'List of devices attached\nemulator-%s\toffline\n\n' "$PORT"; fi; exit 0 ;;
  offline_then_ready)   # offline for the first 2 listings, then a booted device
    n=$(cat "$TMPD/count" 2>/dev/null || echo 0)
    if [ "$1" = devices ]; then
      n=$((n + 1)); echo "$n" > "$TMPD/count"
      if [ "$n" -le 2 ]; then printf 'List of devices attached\nemulator-%s\toffline\n\n' "$PORT"; else printf 'List of devices attached\nemulator-%s\tdevice\n\n' "$PORT"; fi
      exit 0
    fi
    if [ "$3" = shell ] && [ "$n" -gt 2 ]; then echo 1; fi; exit 0 ;;
  never_boots)      # nothing listed, nothing ever boots
    if [ "$1" = devices ]; then printf 'List of devices attached\n\n'; fi; if [ "$3" = shell ]; then echo 0; fi; exit 0 ;;
  *)
    if [ "$1" = devices ]; then printf 'List of devices attached\n\n'; exit 0; fi
    if [ "$3" = shell ]; then listening "$PORT" && echo 1; exit 0; fi
    if [ "$3" = emu ]; then pkill -f "http.server $PORT" 2>/dev/null; pkill -f "http.server $((PORT + 1))" 2>/dev/null; fi
    exit 0 ;;
esac
EOF
cat > "$TMP/emu-binds.sh" <<'EOF'
#!/bin/bash
echo started >> "$EMULOG"
trap 'kill $(jobs -p) 2>/dev/null; exit 0' TERM
python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1 &
python3 -m http.server "$((PORT + 1))" --bind 127.0.0.1 >/dev/null 2>&1 &
wait
EOF
cat > "$TMP/emu-binds-console-only.sh" <<'EOF'
#!/bin/bash
echo started >> "$EMULOG"
trap 'kill $(jobs -p) 2>/dev/null; exit 0' TERM
python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1 &
wait
EOF
cat > "$TMP/emu-sleeps.sh" <<'EOF'
#!/bin/bash
echo started >> "$EMULOG"
exec sleep 600
EOF
cat > "$TMP/emu-exits.sh" <<'EOF'
#!/bin/bash
echo started >> "$EMULOG"
exit 1
EOF
cat > "$TMP/emu-graceful.sh" <<'EOF'
#!/bin/bash
# takes 2 seconds to close its files when asked to stop, then says so; killed earlier it never says
echo started >> "$EMULOG"
trap 'sleep 2; echo graceful >> "$EMULOG"; kill $child 2>/dev/null; exit 0' TERM
sleep 600 &
child=$!
wait
EOF
cat > "$TMP/fake-firebase.sh" <<'EOF'
#!/bin/bash
# stands in for `npm run emulators`: binds the two ports; the script under test stops it with its process group
python3 -m http.server 8080 --bind 127.0.0.1 >/dev/null 2>&1 &
python3 -m http.server 9099 --bind 127.0.0.1 >/dev/null 2>&1 &
wait
EOF
cat > "$TMP/replace-emulator.sh" <<'EOF'
#!/bin/bash
# stands in for the builds: while they run, our emulator dies and somebody else's takes both ports
pkill -f "http.server $PORT"; pkill -f "http.server $((PORT + 1))"; sleep 1
python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1 &
echo $! >> "$TMPD/foreign.pid"
python3 -m http.server "$((PORT + 1))" --bind 127.0.0.1 >/dev/null 2>&1 &
echo $! >> "$TMPD/foreign.pid"
sleep 1
EOF
chmod +x "$TMP"/*.sh

# ---------------------------------------------------------------------------------------------------- helpers
want() { [ -z "$ONLY" ] || [ "${1#"$ONLY"}" != "$1" ]; }
check() { # description, condition result (0 = holds)
  if [ "$2" -eq 0 ]; then PASSES=$((PASSES + 1)); echo "   ok    $1"; else FAILS=$((FAILS + 1)); echo "   FAIL  $1"; fi
}
decoy() { # port: a server that belongs to "somebody else"; remembers its pid
  python3 -m http.server "$1" --bind 127.0.0.1 >/dev/null 2>&1 &
  MYPIDS="$MYPIDS $!"; LASTDECOY=$!
  sleep 1
}
alive() { kill -0 "$1" 2>/dev/null; }
stop_decoy() { kill "$1" 2>/dev/null; wait "$1" 2>/dev/null; sleep 1; }

OUT=""; CODE=0; SECS=0
# run_script FAKE EMULATOR-BINARY [extra env...]: the script under test with the fakes and the phone ports of this harness
run_script() {
  local fake="$1" emu="$2"; shift 2
  : > "$TMP/adb.log"; : > "$TMP/emu.log"; rm -f "$TMP/boots" "$TMP/count"
  local start=$SECONDS
  OUT="$(env TMPD="$TMP" PORT="$PORT" FAKE="$fake" ADBLOG="$TMP/adb.log" EMULOG="$TMP/emu.log" \
        ACCOUNTS_E2E_ADB="$TMP/fake-adb.sh" ACCOUNTS_E2E_EMULATOR="$TMP/$emu" ACCOUNTS_E2E_PHONE_PORT="$PORT" \
        ACCOUNTS_E2E_EMULATORS_CMD="$TMP/fake-firebase.sh" ACCOUNTS_E2E_START_TRIES=20 ACCOUNTS_E2E_BOOT_TRIES=3 \
        "$@" "$S" 2>&1)"; CODE=$?
  SECS=$((SECONDS - start))
}
adb_calls() { grep -c "$1" "$TMP/adb.log"; }
started() { wc -l < "$TMP/emu.log" | tr -d ' '; }
lock_free() { [ ! -d "$LOCK" ]; }
msg() { echo "$OUT" | grep -E '^(FAIL|device|phone emulator step done|ready for the tests)' | head -1 | cut -c1-200; }

# Firebase ports: the fake Firebase emulator needs 8080 and 9099 free, or both taken (then the script leaves them alone)
fb_ok=yes
if port_open 8080 && ! port_open 9099 || port_open 9099 && ! port_open 8080; then fb_ok=no; fi

# ================================================================================================= S: device selection
sel() { # name, expected device or "FAIL", DEVICES, extra env
  local name="$1" want_dev="$2" devices="$3"; shift 3
  run_script multi emu-binds.sh DEVICES="$devices" ACCOUNTS_E2E_SELECT_ONLY=1 "$@"
  if [ "$want_dev" = FAIL ]; then check "$name: refused (exit 1)" "$([ "$CODE" -eq 1 ] && echo 0 || echo 1)"
  else check "$name: uses $want_dev" "$([ "$CODE" -eq 0 ] && echo "$OUT" | grep -q "^device: $want_dev\$" && echo 0 || echo 1)"; fi
  check "$name: no adb call at all about other devices" "$([ "$(wc -l < "$TMP/adb.log" | tr -d ' ')" -eq 0 ] && echo 0 || echo 1)"
}
if want S; then
  echo "S: device selection"
  sel "S1 one device (the lock's phone emulator)" "emulator-$PORT" "emulator-$PORT:phone"
  sel "S2 none attached (the lock's emulator would be started)" "emulator-$PORT" ""
  sel "S3 only one OTHER phone emulator (5554) + a watch + a real device" "emulator-$PORT" "emulator-5560:watch R58M123ABC:phone emulator-5554:phone"
  sel "S4 the lock's emulator among several" "emulator-$PORT" "emulator-5560:watch emulator-5554:phone emulator-$PORT:phone R58M123ABC:phone"
  sel "S5 two other phone emulators, none is the lock's" "emulator-$PORT" "emulator-5554:phone emulator-5556:phone"
  sel "S6 the caller's serial is honoured with several attached" emulator-5556 "emulator-5554:phone emulator-5556:phone" ANDROID_SERIAL=emulator-5556
  sel "S7 the caller's serial is a real device" FAIL "emulator-$PORT:phone R58M123ABC:phone" ANDROID_SERIAL=R58M123ABC
fi

# ================================================================================================= P: the phone emulator
phone_case() { want P; }
if phone_case && [ "$fb_ok" = no ]; then echo "P: skipped (exactly one of the ports 8080 and 9099 is taken by someone else)"; fi
if phone_case && [ "$fb_ok" = yes ]; then
  echo "P: the phone emulator step"
  stop_phone="ACCOUNTS_E2E_STOP_AFTER_PHONE=1"

  run_script none emu-binds.sh "$stop_phone"
  check "P1 our emulator holds both ports: accepted (exit 0, 'phone emulator step done')" "$([ "$CODE" -eq 0 ] && echo "$OUT" | grep -q 'phone emulator step done' && echo 0 || echo 1)"
  check "P1 it was stopped with emu kill, both ports are closed afterwards" "$([ "$(adb_calls 'emu kill')" -ge 1 ] && ! port_open $PORT && ! port_open $ADBPORT && echo 0 || echo 1)"
  check "P1 the phone lock is released" "$(lock_free && echo 0 || echo 1)"

  decoy $PORT; d=$LASTDECOY
  run_script none emu-sleeps.sh "$stop_phone"
  check "P2 another emulator holds the console port, ours cannot bind: refused" "$([ "$CODE" -eq 1 ] && echo "$OUT" | grep -q 'held by something other' && echo 0 || echo 1)"
  check "P2 the other emulator survives and gets no emu kill" "$(alive $d && [ "$(adb_calls 'emu kill')" -eq 0 ] && echo 0 || echo 1)"
  stop_decoy $d

  decoy $PORT; d=$LASTDECOY
  run_script none emu-exits.sh "$stop_phone"
  check "P3 another emulator holds the port, ours exits at once: refused, the other survives, no emu kill" "$([ "$CODE" -eq 1 ] && alive $d && [ "$(adb_calls 'emu kill')" -eq 0 ] && echo 0 || echo 1)"
  stop_decoy $d

  decoy $ADBPORT; d=$LASTDECOY
  run_script none emu-binds-console-only.sh "$stop_phone"
  check "P4 ours holds the console port but another process holds the adb port: refused (exit 1)" "$([ "$CODE" -eq 1 ] && echo "$OUT" | grep -q 'held by something other' && echo 0 || echo 1)"
  check "P4 the other process survives, no emu kill" "$(alive $d && [ "$(adb_calls 'emu kill')" -eq 0 ] && echo 0 || echo 1)"
  stop_decoy $d

  run_script listed_booting emu-binds.sh "$stop_phone"
  check "P5 listed as device but still booting: waited for, then used, nothing started" "$([ "$CODE" -eq 0 ] && [ "$(started)" -eq 0 ] && [ "$(adb_calls 'emu kill')" -eq 0 ] && echo 0 || echo 1)"

  run_script listed_never emu-binds.sh "$stop_phone"
  check "P6 listed as device, never boots: stops after the bound, nothing started" "$([ "$CODE" -eq 1 ] && [ "$(started)" -eq 0 ] && [ "$SECS" -lt 40 ] && echo 0 || echo 1)"

  run_script offline emu-binds.sh "$stop_phone"
  check "P7 listed offline for ever: stops, nothing started, no emu kill" "$([ "$CODE" -eq 1 ] && [ "$(started)" -eq 0 ] && [ "$(adb_calls 'emu kill')" -eq 0 ] && echo 0 || echo 1)"

  run_script offline_then_ready emu-binds.sh "$stop_phone"
  check "P8 listed offline, then ready: used, nothing started" "$([ "$CODE" -eq 0 ] && [ "$(started)" -eq 0 ] && echo 0 || echo 1)"

  run_script hang_devices emu-binds.sh "$stop_phone"
  check "P9 'adb devices' never answers: stops within the 10 second bound, nothing started" "$([ "$CODE" -eq 1 ] && [ "$(started)" -eq 0 ] && [ "$SECS" -lt 45 ] && echo 0 || echo 1)"

  run_script never_boots emu-graceful.sh "$stop_phone"
  check "P10 ours never boots (bound exceeded): stopped gracefully, it had time to say so" "$([ "$CODE" -eq 1 ] && grep -q graceful "$TMP/emu.log" && echo 0 || echo 1)"
  check "P10 and no emu kill was sent to the serial" "$([ "$(adb_calls 'emu kill')" -eq 0 ] && echo 0 || echo 1)"

  # after the builds: our emulator dies and another takes both ports
  rm -f "$TMP/foreign.pid"
  run_script none emu-binds.sh ACCOUNTS_E2E_BUILD_CMD="$TMP/replace-emulator.sh" ACCOUNTS_E2E_STOP_BEFORE_TESTS=1
  check "P11 our emulator was replaced during the builds: the run stops before the tests" "$([ "$CODE" -eq 1 ] && echo "$OUT" | grep -q 'no longer the one holding' && echo 0 || echo 1)"
  fp="$(head -1 "$TMP/foreign.pid" 2>/dev/null)"
  check "P11 the other emulator survives and gets no emu kill" "$([ -n "$fp" ] && alive "$fp" && [ "$(adb_calls 'emu kill')" -eq 0 ] && echo 0 || echo 1)"
  for p in $(cat "$TMP/foreign.pid" 2>/dev/null); do kill $p 2>/dev/null; done; sleep 1

  run_script none emu-binds.sh ACCOUNTS_E2E_BUILD_CMD=true ACCOUNTS_E2E_STOP_BEFORE_TESTS=1
  check "P12 nothing changed during the builds: the run goes on to the tests ('ready for the tests')" "$([ "$CODE" -eq 0 ] && echo "$OUT" | grep -q 'ready for the tests' && echo 0 || echo 1)"
  check "P12 and our emulator is stopped at the end" "$(! port_open $PORT && ! port_open $ADBPORT && echo 0 || echo 1)"
fi

# ================================================================================================= F: the Firebase emulators
if want F && [ "$fb_ok" = yes ] && ! port_open 8080 && ! port_open 9099; then
  echo "F: the Firebase emulators"
  decoy 8080; d=$LASTDECOY
  run_script none emu-binds.sh ACCOUNTS_E2E_STOP_AFTER_PHONE=1
  check "F1 another session's server on 8080 only: the script stops and starts nothing" "$([ "$CODE" -eq 1 ] && echo "$OUT" | grep -q 'only one of the emulator ports' && echo 0 || echo 1)"
  check "F1 the other server survives, 9099 stays free" "$(alive $d && ! port_open 9099 && echo 0 || echo 1)"
  stop_decoy $d

  run_script none emu-binds.sh ACCOUNTS_E2E_STOP_AFTER_PHONE=1
  check "F2 free ports: the script starts the (fake) Firebase emulators and stops them again" "$([ "$CODE" -eq 0 ] && ! port_open 8080 && ! port_open 9099 && echo 0 || echo 1)"
fi

echo
echo "passed: $PASSES, failed: $FAILS"
[ "$FAILS" -eq 0 ]
