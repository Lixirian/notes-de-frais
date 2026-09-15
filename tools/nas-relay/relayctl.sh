#!/usr/bin/env bash
# relayctl.sh — pilotage du relais « Mes notes de frais » sur le NAS (Docker / Container Manager).
#
# Vit dans le dossier d'installation du relais sur le NAS (celui où deploy.sh l'a copié) ; toutes
# les commandes Docker passent par sudo -n (règle sudoers NOPASSWD limitée à docker).
#
# Usage (sur le NAS)    : ./relayctl.sh <commande>
# Usage (depuis le PC)  : ssh <utilisateur>@<nas> "<dossier-du-relais>/relayctl.sh <commande>"
#
# Commandes :
#   start | stop | restart | rebuild | reload | status | logs [N] | auth | test
#   settoken [YYYY-MM-DD]   installe/renouvelle le token d'abonnement, lu sur STDIN (jamais en argument)
#   setrelaytoken           (re)génère le jeton présenté par les apps (affiché une fois)
#   showrelaytoken          affiche le jeton courant (saisie manuelle « avancée » dans l'app)
#   pair                    code d'appairage à 8 chiffres (10 min, usage unique) à saisir dans l'app
#   requests                demandes d'accès en attente (faites depuis l'app : « Demander l'accès »)
#   approve <code>          autorise la demande dont l'app affiche ce code ; deny <code> la refuse
#   setmail                 configure l'e-mail d'alerte (SMTP) : chaque demande d'accès envoie un
#                           message avec le code et des liens Autoriser / Refuser
#   setpublicurl <url>      URL HTTPS publique du relais (utilisée dans les liens des e-mails)
set -u

D="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$D/.env"
DC="sudo -n /usr/local/bin/docker-compose"
DOCKER="sudo -n /usr/local/bin/docker"
CNAME="notes-de-frais-relay"

cd "$D" 2>/dev/null || { echo "ERREUR: dossier introuvable: $D"; exit 1; }

env_get() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2-; }
env_set() { # clé valeur
  umask 077; touch "$ENV_FILE"
  if grep -qE "^$1=" "$ENV_FILE"; then sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"; else echo "$1=$2" >> "$ENV_FILE"; fi
  chmod 600 "$ENV_FILE"
}
# Outil interne du relais (mode --requests/--approve/...), exécuté DANS le conteneur : même code,
# mêmes fichiers de données, aucune dépendance Python à installer sur le NAS.
relay_tool() { $DOCKER exec "$CNAME" python3 /opt/relay/relay.py "$@"; }   # même utilisateur que le conteneur

cmd="${1:-}"
case "$cmd" in
  start)    $DC up -d ;;
  stop)     $DC down ;;
  restart)  $DOCKER restart "$CNAME" ;;
  rebuild)  $DC up --build -d ;;
  reload)   $DC up -d ;;

  status)
    $DOCKER ps -a --filter "name=$CNAME" --format 'table {{.Names}}\t{{.Status}}\t{{.RunningFor}}'
    echo "restart policy : $($DOCKER inspect "$CNAME" --format '{{.HostConfig.RestartPolicy.Name}}' 2>/dev/null || echo n/a)"
    exp="$(env_get CLAUDE_TOKEN_EXPIRES)"; [ -n "$exp" ] && echo "token abonnement expire le : $exp"
    [ -n "$(env_get RELAY_TOKEN)" ] && echo "jeton app : défini" || echo "jeton app : ABSENT (./relayctl.sh setrelaytoken)"
    [ -n "$(env_get SMTP_HOST)" ] && echo "e-mail d'alerte : $(env_get PAIR_MAIL_TO) via $(env_get SMTP_HOST)" || echo "e-mail d'alerte : non configuré (./relayctl.sh setmail)"
    echo "appairage : $(relay_tool --paired 2>/dev/null || echo 'conteneur arrêté')"
    ;;

  logs)     $DOCKER logs --tail "${2:-50}" "$CNAME" ;;
  auth)     $DOCKER exec "$CNAME" bash -lc 'claude auth status 2>&1 || true' ;;

  settoken)
    # Le token se lit sur STDIN (jamais en argument : il finirait dans l'historique shell et dans `ps`).
    #   claude setup-token | ssh NAS "relayctl.sh settoken [YYYY-MM-DD]"
    #   ou : ssh -t NAS "relayctl.sh settoken"  puis coller le token (saisie masquée)
    exp="${2:-}"
    if [ -t 0 ]; then read -rsp "Token d'abonnement (sk-ant-oat-…) : " tok; echo; else IFS= read -r tok; fi
    tok="$(printf '%s' "$tok" | tr -d '[:space:]')"
    [ -n "$tok" ] || { echo "Usage: $0 settoken [YYYY-MM-DD]  (token attendu sur stdin)"; exit 2; }
    [ -n "$exp" ] || exp="$(date -d '+365 days' +%F 2>/dev/null || true)"
    env_set CLAUDE_CODE_OAUTH_TOKEN "$tok"
    [ -n "$exp" ] && env_set CLAUDE_TOKEN_EXPIRES "$exp"
    echo "token d'abonnement écrit dans $ENV_FILE (expire: ${exp:-non renseigné}) — application…"
    $DC up -d
    ;;

  setrelaytoken)
    tok="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    env_set RELAY_TOKEN "$tok"
    echo "jeton app : $tok"
    echo "-> les apps l'obtiennent par appairage (Demander l'accès, ou code « pair ») ; saisie manuelle possible. Application…"
    $DC up -d
    ;;

  showrelaytoken)
    tok="$(env_get RELAY_TOKEN)"; [ -n "$tok" ] && echo "$tok" || { echo "RELAY_TOKEN absent"; exit 2; }
    ;;

  pair)
    [ -n "$(env_get RELAY_TOKEN)" ] || { echo "RELAY_TOKEN absent : lancez d'abord ./relayctl.sh setrelaytoken"; exit 2; }
    code="$(relay_tool --pair-code)" || { echo "conteneur arrêté ?"; exit 1; }
    echo "Code d'appairage : ${code:0:4} ${code:4:4}   (valable 10 min, usage unique, 5 essais)"
    echo "Dans l'app : Réglages > Relais NAS > Options avancées > Code d'appairage > Associer."
    ;;

  requests) relay_tool --requests ;;
  approve)  [ -n "${2:-}" ] || { echo "Usage: $0 approve <code affiché par l'app>"; exit 2; }; relay_tool --approve "$2" ;;
  deny)     [ -n "${2:-}" ] || { echo "Usage: $0 deny <code>"; exit 2; }; relay_tool --deny "$2" ;;

  setmail)
    # SMTP pour l'e-mail d'alerte des demandes d'accès. Mot de passe lu masqué (ou sur stdin).
    read -rp  "Serveur SMTP (ex. smtp.gmail.com) : " host
    read -rp  "Port [587] : " port; port="${port:-587}"
    read -rp  "Identifiant SMTP (adresse d'envoi) : " user
    if [ -t 0 ]; then read -rsp "Mot de passe SMTP (mot de passe d'application) : " pass; echo; else IFS= read -r pass; fi
    read -rp  "Destinataire des alertes [$user] : " to; to="${to:-$user}"
    env_set SMTP_HOST "$host"; env_set SMTP_PORT "$port"; env_set SMTP_USER "$user"; env_set SMTP_PASS "$pass"
    env_set SMTP_FROM "$user"; env_set PAIR_MAIL_TO "$to"
    echo "e-mail d'alerte configuré ($to via $host:$port). Application…"
    $DC up -d
    ;;

  setpublicurl)
    [ -n "${2:-}" ] || { echo "Usage: $0 setpublicurl https://<votre-domaine>"; exit 2; }
    env_set RELAY_PUBLIC_URL "${2%/}"
    echo "URL publique enregistrée (liens des e-mails). Application…"; $DC up -d
    ;;

  test)
    tok="$(env_get RELAY_TOKEN)"
    [ -n "$tok" ] || { echo "RELAY_TOKEN absent"; exit 2; }
    hdr="$(mktemp)"; printf 'header = "Authorization: Bearer %s"\n' "$tok" > "$hdr"
    port="$(env_get RELAY_PORT)"; port="${port:-8787}"
    curl -s --config "$hdr" "http://127.0.0.1:$port/relay/info"; echo
    rm -f "$hdr"
    ;;

  *)
    sed -n '2,22p' "$0"; exit 2 ;;
esac
