#!/usr/bin/env bash
# Runs a command while holding a machine-wide lock for the phone emulator (emulator-5562), so parallel agents never
# install the same app over each other. Usage: scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest
set -u
LOCK="${TMPDIR:-/tmp}/analogplus-phone-emulator.lock"
until mkdir "$LOCK" 2>/dev/null; do
  # a lock older than 20 minutes belongs to a dead run
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +20 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null; fi
  sleep 5
done
trap 'rmdir "$LOCK"' EXIT INT TERM
ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5562}" "$@"
