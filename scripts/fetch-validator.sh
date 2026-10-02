#!/usr/bin/env bash
# Download Google's Watch Face Format validator into tools/bin (gitignored).
set -euo pipefail
source "$(dirname "$0")/_env.sh"
mkdir -p "$ROOT/tools/bin"
gh release download latest -R google/watchface -p wff-validator.jar -D "$ROOT/tools/bin" --clobber
