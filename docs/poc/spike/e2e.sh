#!/usr/bin/env bash
# THROWAWAY: phone emulator builds -> (adb relay stands in for Data Layer) -> watch emulator pushes
set -u
SP="$(cd "$(dirname "$0")" && pwd)"; A=~/Library/Android/sdk/platform-tools/adb; PH=emulator-5562; WA=emulator-5560; PKG=com.seyedjafari.wfpspike
L=$SP/e2e.log; : > $L
say() { echo "$*" | tee -a $L; }
phone_build() { # accent size vc
  $A -s $PH logcat -c; $A -s $PH shell am force-stop $PKG
  $A -s $PH shell "am start -n $PKG/.BuilderActivity --es accent '$1' --ei size $2 --ei vc $3" >/dev/null
  for i in $(seq 1 40); do sleep 1; $A -s $PH logcat -d -s WFPPHONE:V | grep -q "RESULT" && break; done
  $A -s $PH logcat -d -s WFPPHONE:V | grep -E "RESULT|validator|ERROR|FAIL" | cut -c1-200 | tee -a $L
}
relay() { # phone files -> watch app files (stand-in for the Data Layer)
  $A -s $PH exec-out run-as $PKG cat files/out.apk > $SP/relay.apk
  $A -s $PH exec-out run-as $PKG cat files/out.token > $SP/relay.token
  $A -s $WA push $SP/relay.apk /data/local/tmp/in.apk >/dev/null; $A -s $WA push $SP/relay.token /data/local/tmp/in.token >/dev/null
  $A -s $WA shell "run-as $PKG mkdir -p files; run-as $PKG cp /data/local/tmp/in.apk files/in.apk && run-as $PKG cp /data/local/tmp/in.token files/in.token"
  say "relayed $(stat -f %z $SP/relay.apk) bytes, token $(cat $SP/relay.token | cut -c1-20)..."
}
watch_cmd() {
  $A -s $WA logcat -c; $A -s $WA shell am force-stop $PKG
  $A -s $WA shell am start -n $PKG/.MainActivity --es cmd "$1" >/dev/null; sleep 9
  say "--- watch: $1"; $A -s $WA logcat -d -s WFPSPIKE:V | grep -v "  at " | grep -v "== cmd" | cut -c1-230 | tee -a $L
}
shot() { $A -s $WA shell input keyevent KEYCODE_HOME; sleep 4; $A -s $WA exec-out screencap -p > $SP/$1.png; say "shot $1 $(stat -f %z $SP/$1.png)"; }
$A -s $WA install -r $SP/market/app/build/outputs/apk/debug/app-debug.apk | tail -1 | tee -a $L
$A -s $WA shell pm grant $PKG com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE 2>&1 | tee -a $L
say "=== 1. phone builds blue/30/v5"; phone_build '#ff1e6bff' 30 5; relay
watch_cmd addf; watch_cmd active; shot emu_v5_blue
say "=== 2. phone builds green/20/v6"; phone_build '#ff22c55e' 20 6; relay
watch_cmd updf; watch_cmd isactive; shot emu_v6_green
say DONE
