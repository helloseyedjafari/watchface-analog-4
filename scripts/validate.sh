#!/usr/bin/env bash
# Validate the generated face against the WFF version the manifest declares.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
JAR="$ROOT/tools/bin/wff-validator.jar"
[ -f "$JAR" ] || "$ROOT/scripts/fetch-validator.sh"
out="$(java -jar "$JAR" "$WFF_VERSION" "$ROOT/app/src/main/res/raw/watchface.xml" 2>&1)"
echo "$out"
echo "$out" | grep -q "PASSED"
