#!/bin/sh
set -eu

: "${OIDC_ISSUER_URI:=http://localhost:8085/realms/launchguard}"
: "${OIDC_CLIENT_ID:=launchguard-dashboard}"
: "${OIDC_AUDIENCE:=launchguard-api}"
export OIDC_ISSUER_URI OIDC_CLIENT_ID OIDC_AUDIENCE

envsubst '${OIDC_ISSUER_URI} ${OIDC_CLIENT_ID} ${OIDC_AUDIENCE}' \
  < /etc/launchguard/auth-config.json.template \
  > /tmp/launchguard-auth-config.json
