#!/usr/bin/env bash
# Compile l'app, démarre un émulateur si aucun appareil n'est connecté, installe et lance l'app.
#
#   ./run.sh                 -> téléphone (AVD Medium_Phone_API_36.1 ou premier AVD disponible)
#   ./run.sh --tablet        -> AVD Tablet_API_36.1
#   ./run.sh --fold          -> AVD Pliable_API_36.1 (déplié)
#   ./run.sh --release       -> installe l'APK release signé au lieu du debug
#   ./run.sh --preview       -> ouvre aussi l'aperçu web (tools/web-preview) dans le navigateur
set -euo pipefail
cd "$(dirname "$0")"

source ./tools/env.sh

AVD="Medium_Phone_API_36.1"
VARIANT="debug"
PREVIEW=0
for arg in "$@"; do
  case "$arg" in
    --tablet) AVD="Tablet_API_36.1" ;;
    --fold|--foldable) AVD="Pliable_API_36.1" ;;
    --release) VARIANT="release" ;;
    --preview) PREVIEW=1 ;;
    *) echo "Option inconnue : $arg" >&2; exit 1 ;;
  esac
done

echo "▶ Compilation ($VARIANT)…"
if [ "$VARIANT" = "release" ]; then
  ./gradlew :app:assembleRelease --console=plain -q
  APK="app/build/outputs/apk/release/app-release.apk"
  PKG="com.lixirian.notesdefrais"
  mkdir -p dist && cp "$APK" dist/notes-de-frais.apk
else
  ./gradlew :app:assembleDebug --console=plain -q
  APK="app/build/outputs/apk/debug/app-debug.apk"
  PKG="com.lixirian.notesdefrais.debug"
fi

SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$SERIAL" ]; then
  if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
    AVD="$("$EMULATOR" -list-avds | head -1)"
    [ -n "$AVD" ] || { echo "Aucun AVD : créez-en un avec ./tools/create-avds.sh" >&2; exit 1; }
  fi
  echo "▶ Démarrage de l'émulateur $AVD…"
  "$EMULATOR" -avd "$AVD" -no-boot-anim -gpu auto -no-audio >/dev/null 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
  if [ "$AVD" = "Pliable_API_36.1" ]; then "$ADB" -s "$SERIAL" emu unfold >/dev/null 2>&1 || true; fi
fi

echo "▶ Installation sur $SERIAL…"
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
"$ADB" -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "✔ Application lancée ($PKG) sur $SERIAL"

if [ "$PREVIEW" = "1" ]; then
  exec python tools/web-preview/server.py
fi
