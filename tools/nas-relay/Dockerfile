# syntax=docker/dockerfile:1
# Image du relais « Mes notes de frais » :
# node:22-bookworm + Claude Code CLI (binaire glibc -> Debian, pas Alpine), utilisateur non-root.
FROM node:22-bookworm

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 ca-certificates curl bash \
    && rm -rf /var/lib/apt/lists/*

# Claude Code CLI : c'est lui qui lit les tickets avec l'abonnement (token setup-token)
# Version épinglée (celle validée avec le relais) : un `rebuild` ne tire pas silencieusement un
# nouveau binaire qui recevra le token d'abonnement. Mettre à jour volontairement.
ARG CLAUDE_CODE_VERSION=2.1.270
RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}"

# Utilisateur non-root aligné sur le propriétaire du dossier sur le NAS (RELAY_UID/RELAY_GID du .env)
ARG RELAY_UID=1000
ARG RELAY_GID=100
RUN useradd -u ${RELAY_UID} -g ${RELAY_GID} -m -d /home/claude -s /bin/bash claude

COPY relay.py /opt/relay/relay.py
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh && chmod -R a+rX /opt/relay

USER ${RELAY_UID}:${RELAY_GID}
ENV HOME=/home/claude
WORKDIR /home/claude
EXPOSE 8787

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
