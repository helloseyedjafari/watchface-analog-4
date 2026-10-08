#!/usr/bin/env bash
# THROWAWAY spike sequence. Waits for the watch between steps because wireless debugging drops.
SP="$(cd "$(dirname "$0")" && pwd)"; A=~/Library/Android/sdk/platform-tools/adb; S="adb-RFAY307S1VJ-nvYsDx (2)._adb-tls-connect._tcp"
LOG=$SP/seq.log; : > $LOG
wait_dev() { until $A devices | grep -q "RFAY307S1VJ.*[[:space:]]device$"; do sleep 3; done; }
adbw() { wait_dev; $A -s "$S" "$@"; }
step() { # step <cmd> <outfile-suffix>
  wait_dev; $A -s "$S" logcat -c
  $A -s "$S" shell am force-stop com.seyedjafari.wfpspike
  $A -s "$S" shell am start -n com.seyedjafari.wfpspike/.MainActivity --es cmd "$1" >/dev/null
  sleep 10
  { echo "=== $1"; $A -s "$S" logcat -d -s WFPSPIKE:V | grep -v "  at " | cut -c1-300; } >> $LOG
}
shot() { sleep 3; wait_dev; $A -s "$S" exec-out screencap -p > "$SP/$1.png"; echo "shot $1 $(stat -f %z $SP/$1.png) bytes" >> $LOG; }
wait_dev; $A -s "$S" install -r $SP/market/app/build/outputs/apk/debug/app-debug.apk >> $LOG 2>&1
step upd1
step list
step active
shot after_active_v1
step isactive
step upd2
step isactive
shot after_update_v2
echo DONE >> $LOG
