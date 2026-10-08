#!/usr/bin/env bash
# usage: mkface.sh <versionCode> <outname>   (throwaway spike helper)
set -euo pipefail
SP="$(cd "$(dirname "$0")" && pwd)"; BT=~/Library/Android/sdk/build-tools/36.0.0
cd "$SP/face" && ./gradlew -q assembleRelease -Pvc="$1" >/dev/null
cd "$SP" && rm -f out/tmp.apk out/tmp_aligned.apk out/"$2".apk
cp face/app/build/outputs/apk/release/app-release-unsigned.apk out/tmp.apk
zip -q -d out/tmp.apk classes.dex 'META-INF/version-control-info.textproto' || true
$BT/zipalign -f -p 4 out/tmp.apk out/tmp_aligned.apk
$BT/apksigner sign --ks face.jks --ks-pass pass:spikepass --key-pass pass:spikepass --out out/"$2".apk out/tmp_aligned.apk
rm -f out/tmp.apk out/tmp_aligned.apk out/"$2".apk.idsig
java -jar bin/validator-push-cli-1.1.0-alpha01.jar --apk_path="$SP/out/$2.apk" --package_name=com.seyedjafari.wfpspike 2>&1 | tee out/"$2".validation.txt | grep -E "unsuccessful|successful$|Failed|token|Token" || true
