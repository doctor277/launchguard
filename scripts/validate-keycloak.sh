#!/bin/sh
set -eu

keycloak_url="${KEYCLOAK_URL:-http://localhost:8085}"
client_id="${OIDC_AUTOMATION_CLIENT_ID:-launchguard-automation}"
client_secret="${OIDC_AUTOMATION_CLIENT_SECRET:-local-automation-secret-demo-only}"

attempt=0
until curl -fsS "$keycloak_url/realms/launchguard/.well-known/openid-configuration" >/dev/null; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    echo 'Keycloak discovery did not become ready.' >&2
    exit 1
  fi
  sleep 2
done

token_response="$(curl -fsS -X POST "$keycloak_url/realms/launchguard/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "client_id=$client_id" \
  --data-urlencode "client_secret=$client_secret")"

# Check token claims without printing or persisting the bearer token.
printf '%s' "$token_response" | python3 -c '
import base64, json, sys
token = json.load(sys.stdin).get("access_token")
if not token:
    raise SystemExit("Token response did not include access_token")
payload = token.split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
required_roles = {"VIEWER", "OPERATOR", "ADMIN"}
if claims.get("iss") != "http://localhost:8085/realms/launchguard":
    raise SystemExit("Unexpected issuer claim")
audience = claims.get("aud", [])
if isinstance(audience, str):
    audience = [audience]
if "launchguard-api" not in audience:
    raise SystemExit("API audience is missing")
if not required_roles.issubset(set(claims.get("roles", []))):
    raise SystemExit("Automation client roles are incomplete")
print("OIDC_REALM_OK: discovery, client credentials, issuer, audience, and roles validated without exposing the token.")
'
