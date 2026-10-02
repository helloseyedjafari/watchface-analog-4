# Shared by the other scripts. Sourced, not executed.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
APP_ID="com.seyedjafari.analogwatchface"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
WFF_VERSION=3
