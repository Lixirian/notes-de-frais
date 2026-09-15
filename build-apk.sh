#!/usr/bin/env bash
# Produit l'APK release signé, prêt à installer : dist/notes-de-frais.apk
# Le keystore (keystore/release.jks) est auto-signé et HORS dépôt (git-ignoré) : suffisant
# pour installer l'app sur ses propres appareils (pas pour le Play Store).
set -euo pipefail
cd "$(dirname "$0")"
source ./tools/env.sh

if [ ! -f keystore/release.jks ]; then
  echo "▶ Génération du keystore de signature (mot de passe aléatoire, conservé dans keystore/keystore.properties, hors dépôt)…"
  mkdir -p keystore
  KS_PASS=$(python -c "import secrets; print(secrets.token_urlsafe(24))")
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore keystore/release.jks -storetype PKCS12 -alias notesdefrais \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Mes notes de frais, OU=Dev, O=Lixirian, L=Paris, C=FR"
  printf 'storeFile=keystore/release.jks\nstorePassword=%s\nkeyAlias=notesdefrais\nkeyPassword=%s\n' "$KS_PASS" "$KS_PASS" > keystore/keystore.properties
  echo "  ⚠ Sauvegardez keystore/ : sans cette clé, aucune mise à jour ne pourra être installée par-dessus l'app existante."
fi

[ -f .env ] || echo "ℹ Pas de fichier .env : l'APK fonctionnera en saisie manuelle (clé configurable dans les réglages ou via un relais NAS)."

echo "▶ Compilation release (R8)…"
./gradlew :app:assembleRelease --console=plain -q
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/notes-de-frais.apk
echo "✔ APK prêt : dist/notes-de-frais.apk ($(du -h dist/notes-de-frais.apk | cut -f1))"
echo "  Installation sur un appareil branché : $ADB install -r dist/notes-de-frais.apk"
