#!/usr/bin/env bash
# Déploie le relais sur le NAS depuis le PC : copie des fichiers dans le dossier du relais puis
# build + démarrage. Les secrets ne transitent pas par ici (relayctl.sh : settoken, setrelaytoken…).
#
# Les coordonnées de VOTRE NAS ne sont pas dans le dépôt : copiez site.env.example en site.env
# (git-ignoré) à côté de ce script, ou passez-les en variables d'environnement :
#   NAS_SSH=utilisateur@nas NAS_DIR=/chemin/du/relais ./tools/nas-relay/deploy.sh
set -euo pipefail
cd "$(dirname "$0")"
[ -f site.env ] && { set -a; . ./site.env; set +a; }
NAS="${NAS_SSH:-}"; D="${NAS_DIR:-}"
[ -n "$NAS" ] && [ -n "$D" ] || { echo "NAS_SSH et NAS_DIR manquants : créez tools/nas-relay/site.env (voir site.env.example)" >&2; exit 2; }

echo "▶ Préparation de $NAS:$D"
ssh "$NAS" "mkdir -p '$D/home' && chmod 700 '$D/home' || true"
# tar via ssh plutôt que scp : certains NAS n'activent pas le sous-système SFTP qu'exige scp moderne
tar cf - Dockerfile docker-compose.yml entrypoint.sh relay.py relayctl.sh .env.example | ssh "$NAS" "tar xf - -C '$D'"
ssh "$NAS" "chmod +x '$D/relayctl.sh' '$D/entrypoint.sh'"
# Identité du conteneur = propriétaire du dossier (écrite dans .env si absente)
ssh "$NAS" "cd '$D' && touch .env && chmod 600 .env && grep -q '^RELAY_UID=' .env || echo \"RELAY_UID=\$(id -u)\" >> '$D/.env'; grep -q '^RELAY_GID=' '$D/.env' || echo \"RELAY_GID=\$(id -g)\" >> '$D/.env'"

if ssh "$NAS" "grep -q '^RELAY_TOKEN=.' '$D/.env'"; then
  echo "▶ Secrets présents : build + (re)démarrage"
  ssh "$NAS" "'$D/relayctl.sh' rebuild"
  ssh "$NAS" "'$D/relayctl.sh' status"
else
  cat <<EOF
▶ Fichiers copiés. Il reste à installer les secrets sur le NAS, puis à démarrer :

  claude setup-token | ssh $NAS "$D/relayctl.sh settoken"   # token d'abonnement (stdin, jamais en argument)
  ssh $NAS "$D/relayctl.sh setrelaytoken"                    # jeton des apps
  ssh $NAS "$D/relayctl.sh rebuild && $D/relayctl.sh test"
  ssh -t $NAS "$D/relayctl.sh setmail"                       # facultatif : e-mail à chaque demande d'accès
  ssh $NAS "$D/relayctl.sh setpublicurl https://<votre-domaine>"   # liens Autoriser / Refuser des e-mails

Puis dans l'app : Réglages > Relais NAS > URL du relais > « Demander l'accès ».
EOF
fi
