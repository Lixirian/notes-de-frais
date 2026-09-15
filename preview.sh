#!/usr/bin/env bash
# Aperçu de l'app dans le navigateur, comme sur un téléphone / une tablette / un pliable Android.
# Démarre le serveur local (tools/web-preview) et ouvre http://127.0.0.1:8765/
# Depuis la page : démarrer un AVD, installer l'app, cliquer/taper, pivoter, plier/déplier.
set -euo pipefail
cd "$(dirname "$0")"
source ./tools/env.sh
exec python tools/web-preview/server.py "$@"
