#!/usr/bin/env bash
# Runs a command while holding a machine-wide lock for the phone emulator (emulator-5562), so parallel agents never
# install the same app over each other. Usage: scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest
# The lock logic (owner PID, stale = owner gone, waiters say who holds it) is in scripts/_lock.sh.
set -u
source "$(dirname "$0")/_lock.sh"
phone_lock_acquire
trap phone_lock_release EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5562}" "$@"
