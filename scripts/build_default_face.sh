#!/usr/bin/env bash
# Builds a push-compliant Analog Plus face APK from the existing `app/` resources, signs it, validates it with
# Google's validator-push-cli and writes the validation token next to it.
#
# Default run (regenerates the bundled default face and its token; commit both results):
#   scripts/build_default_face.sh
#     -> watch/src/main/assets/default_watchface.apk
#     -> watch/src/main/res/values/default_token.xml   (string default_wf_token)
#
# Second face for the instrumented test (higher versionCode, own token file):
#   scripts/build_default_face.sh --version-code 2 \
#       --out watch/src/androidTest/assets/face_v2.apk --token-file watch/src/androidTest/assets/face_v2.token
#
# Options: --version-code N (default 1)  --out APK  --token-xml XML | --token-file TXT
#
# SIGNING KEY (the single variable): FACE_KEY_PEM, default ~/.config/analogplus/default-face.pem
#   A PEM with certificate + private key, created on first run if missing. It is NOT in git (*.pem is ignored).
#   The SAME key must be used for every build of this face package, forever: an update signed with another key is
#   rejected by Watch Face Push. Back this file up. Whatever builds the face on the phone (Task 7 FacePackager)
#   must sign with this key too, or it cannot update a device that installed the bundled default first.
#
# Tools (found or fetched into ~/.cache/analogplus/bin, override with the variables):
#   PACK_CLI       host build of Google's `pack-cli` (github.com/google/pack, commit 7b60931). Built with cargo if missing.
#   VALIDATOR_JAR  validator-push-cli 1.1.0-alpha01 from Google Maven. Downloaded if missing.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

FACE_KEY_PEM="${FACE_KEY_PEM:-$HOME/.config/analogplus/default-face.pem}"
BIN_DIR="${ANALOGPLUS_BIN_DIR:-$HOME/.cache/analogplus/bin}"
PACK_CLI="${PACK_CLI:-$BIN_DIR/pack-cli}"
VALIDATOR_VERSION=1.1.0-alpha01
VALIDATOR_JAR="${VALIDATOR_JAR:-$BIN_DIR/validator-push-cli-$VALIDATOR_VERSION.jar}"
PACK_COMMIT=7b60931

FACE_PACKAGE="com.seyedjafari.analogplus.watchfacepush.analog"
MARKETPLACE_PACKAGE="com.seyedjafari.analogplus"
WFF_VERSION=3
MIN_SDK=33

VERSION_CODE=1
OUT_APK="$ROOT/watch/src/main/assets/default_watchface.apk"
TOKEN_XML="$ROOT/watch/src/main/res/values/default_token.xml"
TOKEN_FILE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --out) OUT_APK="$2"; shift 2 ;;
    --token-xml) TOKEN_XML="$2"; TOKEN_FILE=""; shift 2 ;;
    --token-file) TOKEN_FILE="$2"; TOKEN_XML=""; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
case "$VERSION_CODE" in ''|*[!0-9]*) echo "--version-code must be a positive integer" >&2; exit 2 ;; esac

mkdir -p "$BIN_DIR"

# --- tools ---------------------------------------------------------------------------------------------
if [ ! -x "$PACK_CLI" ]; then
  echo "pack-cli not found at $PACK_CLI; building commit $PACK_COMMIT with cargo"
  export PATH="$HOME/.cargo/bin:$PATH"
  SRC="$(mktemp -d)"
  git clone --quiet https://github.com/google/pack "$SRC/pack"
  git -C "$SRC/pack" checkout --quiet "$PACK_COMMIT"
  (cd "$SRC/pack" && cargo build --release -p pack-cli)
  cp "$SRC/pack/target/release/pack-cli" "$PACK_CLI"
  rm -rf "$SRC"
fi
if [ ! -f "$VALIDATOR_JAR" ]; then
  echo "downloading validator-push-cli $VALIDATOR_VERSION"
  curl -fsSL -o "$VALIDATOR_JAR" \
    "https://dl.google.com/dl/android/maven2/com/google/android/wearable/watchface/validator/validator-push-cli/$VALIDATOR_VERSION/validator-push-cli-$VALIDATOR_VERSION.jar"
fi

# --- signing key -----------------------------------------------------------------------------------------
if [ ! -f "$FACE_KEY_PEM" ]; then
  echo "creating signing key $FACE_KEY_PEM (keep it forever, never commit it)"
  mkdir -p "$(dirname "$FACE_KEY_PEM")"
  KT="$(mktemp -d)"
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$KT/k.pem" -out "$KT/c.pem" \
    -subj "/CN=analogplus-face" -days 10950 2>/dev/null
  cat "$KT/c.pem" "$KT/k.pem" > "$FACE_KEY_PEM"
  chmod 600 "$FACE_KEY_PEM"
  rm -rf "$KT"
fi

# --- assemble the pack input directory ---------------------------------------------------------------------
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FACE="$WORK/face"
RES_SRC="$ROOT/app/src/main/res"
mkdir -p "$FACE/res/drawable" "$FACE/res/raw" "$FACE/res/xml" "$FACE/res/values"
cp "$RES_SRC"/drawable-nodpi/*.png "$FACE/res/drawable/"
cp "$RES_SRC"/raw/watchface.xml "$FACE/res/raw/"
cp "$RES_SRC"/xml/watch_face_info.xml "$FACE/res/xml/"
cp "$RES_SRC"/values/strings.xml "$FACE/res/values/"
[ -f "$FACE/res/xml/watch_face_info.xml" ] && [ -f "$FACE/res/drawable/preview.png" ] || {
  echo "face needs res/xml/watch_face_info.xml and res/drawable/preview.png" >&2; exit 1; }

# No <service>: a pushed face is code-free and the system finds it through watch_face_info.xml.
cat > "$FACE/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$FACE_PACKAGE"
    android:versionCode="$VERSION_CODE"
    android:versionName="1.0">
    <uses-sdk android:minSdkVersion="$MIN_SDK" android:targetSdkVersion="35" />
    <uses-feature android:name="android.hardware.type.watch" />
    <application android:label="@string/app_name" android:hasCode="false">
        <property android:name="com.google.wear.watchface.format.version" android:value="$WFF_VERSION" />
    </application>
</manifest>
EOF

# --- build, sign, validate -------------------------------------------------------------------------------------
"$PACK_CLI" "$FACE" "$WORK/face.apk" "$FACE_KEY_PEM"

# Pushed APKs may only hold manifest, resources.arsc, res/** and META-INF/**.
BAD="$(unzip -Z1 "$WORK/face.apk" | grep -Ev '^(AndroidManifest\.xml|resources\.arsc|res/|META-INF/)' || true)"
if [ -n "$BAD" ]; then echo "unexpected entries in APK:"; echo "$BAD"; exit 1; fi

VALIDATION="$(java -jar "$VALIDATOR_JAR" --apk_path="$WORK/face.apk" --package_name="$MARKETPLACE_PACKAGE" 2>&1 || true)"
TOKEN="$(printf '%s\n' "$VALIDATION" | sed -n 's/.*generated token: //p' | head -1)"
if [ -z "$TOKEN" ]; then echo "validation failed:"; printf '%s\n' "$VALIDATION"; exit 1; fi
printf '%s\n' "$VALIDATION" | grep -E 'successful checks' || true

mkdir -p "$(dirname "$OUT_APK")"
cp "$WORK/face.apk" "$OUT_APK"
if [ -n "$TOKEN_FILE" ]; then
  mkdir -p "$(dirname "$TOKEN_FILE")"
  printf '%s\n' "$TOKEN" > "$TOKEN_FILE"
else
  mkdir -p "$(dirname "$TOKEN_XML")"
  cat > "$TOKEN_XML" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by scripts/build_default_face.sh together with assets/default_watchface.apk. Do not edit. -->
<resources>
    <string name="default_wf_token" translatable="false">$TOKEN</string>
</resources>
EOF
fi
echo "built $OUT_APK (versionCode $VERSION_CODE, $(wc -c < "$OUT_APK" | tr -d ' ') bytes)"
