# shellcheck shell=bash
# Machine-wide lock for the phone emulator (emulator-5562). Sourced, not executed.
#
# Whoever installs or drives the phone app on that emulator (scripts/poc_e2e.sh, scripts/with_phone_emulator_lock.sh)
# holds it, so parallel runs never install over each other or uninstall the app from under a run.
# The lock is a directory with a file `owner` inside: line 1 the owner's PID, line 2 the owner's process start time.
# A lock is stale only when that process is gone (kill -0 fails, or the PID was reused by another process); age
# never matters, so a run may last as long as it needs. A waiter prints who holds the lock. Release removes the
# lock only if this shell owns it.
PHONE_LOCK="${ANALOGPLUS_PHONE_LOCK:-${TMPDIR:-/tmp}/analogplus-phone-emulator.lock}"
PHONE_LOCK_HELD=no

_lock_started() { ps -o lstart= -p "$1" 2>/dev/null | sed 's/^ *//; s/ *$//'; }

# True when the lock directory exists and its owner is gone (or never finished writing `owner`, for over a minute).
_lock_is_stale() {
  local pid started now
  pid="$(sed -n 1p "$PHONE_LOCK/owner" 2>/dev/null || true)"
  if [ -z "$pid" ]; then
    # mkdir succeeded but the owner file is not there yet: give the creator a minute before calling it dead
    [ -n "$(find "$PHONE_LOCK" -maxdepth 0 -mmin +1 2>/dev/null)" ]; return
  fi
  kill -0 "$pid" 2>/dev/null || return 0
  started="$(sed -n 2p "$PHONE_LOCK/owner" 2>/dev/null || true)"
  now="$(_lock_started "$pid")"
  [ -n "$started" ] && [ "$now" != "$started" ]   # same PID, different process: reused
}

_lock_holder_text() {
  local pid; pid="$(sed -n 1p "$PHONE_LOCK/owner" 2>/dev/null || true)"
  if [ -n "$pid" ]; then printf 'pid %s (%s)' "$pid" "$(ps -o command= -p "$pid" 2>/dev/null | cut -c1-100)"; else printf 'an unknown run'; fi
}

phone_lock_acquire() {
  local waited=0 stale
  until mkdir "$PHONE_LOCK" 2>/dev/null; do
    if _lock_is_stale; then
      # Take the dead lock out of the way atomically; only one waiter wins the rename.
      stale="$PHONE_LOCK.stale.$$"
      if mv "$PHONE_LOCK" "$stale" 2>/dev/null; then
        echo "phone emulator lock: removed the stale lock of $(sed -n 1p "$stale/owner" 2>/dev/null || echo '?') (process gone)" >&2
        rm -rf "$stale"
      fi
      continue
    fi
    if [ $((waited % 60)) -eq 0 ]; then
      echo "phone emulator lock: waiting for $(_lock_holder_text) (${waited}s so far)" >&2
    fi
    sleep 5; waited=$((waited + 5))
  done
  printf '%s\n%s\n' "$$" "$(_lock_started "$$")" >"$PHONE_LOCK/owner"
  PHONE_LOCK_HELD=yes
}

phone_lock_release() {
  [ "$PHONE_LOCK_HELD" = yes ] || return 0
  PHONE_LOCK_HELD=no
  if [ "$(sed -n 1p "$PHONE_LOCK/owner" 2>/dev/null || true)" = "$$" ]; then rm -rf "$PHONE_LOCK"; fi
}
