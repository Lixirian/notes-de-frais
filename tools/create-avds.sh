#!/usr/bin/env bash
# Crée les trois émulateurs du projet (téléphone, tablette, pliable) sur l'image système Android 16 (API 36.1).
set -euo pipefail
cd "$(dirname "$0")/.."
source ./tools/env.sh

IMG="system-images;android-36.1;google_apis_playstore;x86_64"
if [ ! -d "$ANDROID_HOME/system-images/android-36.1/google_apis_playstore/x86_64" ]; then
  echo "▶ Téléchargement de l'image système $IMG…"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" "$IMG" "emulator" "platform-tools"
fi

create() { # nom, device
  if "$EMULATOR" -list-avds | grep -qx "$1"; then echo "• $1 existe déjà"; return; fi
  echo no | "$AVDMANAGER" create avd -n "$1" -k "$IMG" -d "$2" >/dev/null
  echo "✔ $1 créé ($2)"
}
create Medium_Phone_API_36.1 medium_phone
create Tablet_API_36.1 pixel_tablet
create Pliable_API_36.1 pixel_9_pro_fold
