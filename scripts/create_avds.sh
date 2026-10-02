#!/usr/bin/env bash
# Create one Wear OS 6 AVD per screen shape/size that the face must support.
# wearos_large_round already exists as GalaxyWatch7_API36 and is not recreated.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
IMAGE="system-images;android-36;android-wear-signed;arm64-v8a"
existing="$("$EMULATOR" -list-avds)"
for profile in wearos_small_round wearos_square wearos_rect; do
  name="AnalogWF_${profile#wearos_}"
  if grep -qx "$name" <<<"$existing"; then echo "$name exists"; continue; fi
  echo no | "$AVDMANAGER" create avd -n "$name" -k "$IMAGE" -d "$profile"
done
