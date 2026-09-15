#!/usr/bin/env bash
# Entrypoint du relais :
# 1. normalise le token d'abonnement et fait un preflight d'auth visible dans les logs,
# 2. pré-acquitte l'onboarding de Claude Code (sinon le CLI bloque au 1er lancement),
# 3. lance le relais HTTP.
set -u

log() { echo "[entrypoint] $*"; }
log "démarrage — $(date -u +%FT%TZ)"

# --- Token d'abonnement (claude setup-token, ~1 an) --------------------------
CLAUDE_ENV="$HOME/.claude/.env"
if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -f "$CLAUDE_ENV" ]; then
  # shellcheck disable=SC1090
  . "$CLAUDE_ENV"
fi
if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
  export CLAUDE_CODE_OAUTH_TOKEN
  log "auth: CLAUDE_CODE_OAUTH_TOKEN présent (abonnement, token longue durée)."
  if [ -n "${CLAUDE_TOKEN_EXPIRES:-}" ]; then
    now=$(date -u +%s); exp=$(date -u -d "${CLAUDE_TOKEN_EXPIRES}" +%s 2>/dev/null || echo 0)
    if [ "$exp" -gt 0 ]; then
      days=$(( (exp - now) / 86400 ))
      log "auth: expiration prévue le ${CLAUDE_TOKEN_EXPIRES} (~${days} j restants)."
      [ "$days" -le 30 ] && log "auth: ⚠️  EXPIRATION PROCHE (<= 30 j) — 'claude setup-token' puis ./relayctl.sh settoken."
    fi
  fi
else
  unset CLAUDE_CODE_OAUTH_TOKEN 2>/dev/null || true
  if [ -n "${ANTHROPIC_API_KEY:-}${OPENAI_API_KEY:-}" ]; then
    log "auth: pas de token d'abonnement — mode clé API (passthrough)."
  else
    log "auth: ⚠️  ni CLAUDE_CODE_OAUTH_TOKEN ni clé API — le relais va s'arrêter. Voir .env.example."
  fi
fi

# --- Onboarding Claude Code pré-acquitté ---------------------------------------
WORK="${RELAY_WORKDIR:-$HOME/work}"
mkdir -p "$WORK" "$HOME/.claude"
node -e '
  const fs = require("fs"); const p = process.argv[1]; const work = process.argv[2];
  let c = {}; try { c = JSON.parse(fs.readFileSync(p, "utf8")); } catch (_) {}
  c.hasCompletedOnboarding = true;
  delete c.bypassPermissionsModeAccepted;   // jamais de mode bypass-permissions pour le relais
  c.projects = c.projects || {};
  c.projects[work] = Object.assign(c.projects[work] || {}, { hasTrustDialogAccepted: true });
  fs.writeFileSync(p, JSON.stringify(c, null, 2));
' "$HOME/.claude.json" "$WORK"
log "claude: $(claude --version 2>/dev/null || echo 'version inconnue') — modèle ${ANTHROPIC_MODEL:-sonnet}"

exec python3 /opt/relay/relay.py
