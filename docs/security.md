# Security model

LaunchGuard uses standards-based OpenID Connect for human and automation access. The dashboard is a public browser client; the backend is a stateless OAuth 2.0 resource server. LaunchGuard does not store users, passwords, sessions, or access tokens in PostgreSQL.

## Authentication architecture

```text
Browser -> OIDC Authorization Code + PKCE -> identity provider
Browser -> Bearer access token -> LaunchGuard /api
Automation -> client credentials -> identity provider -> Bearer access token -> LaunchGuard /api
Backend -> issuer, signature, expiry, and audience validation
```

The provider must expose standard OIDC discovery and JWK metadata, issue JWT access tokens with audience `launchguard-api` (configurable), and place application roles in a `roles` claim (also configurable). The dashboard client must be public, use Authorization Code flow, and require PKCE S256. It must not have a client secret.

Configuration:

| Component | Values |
|---|---|
| Backend | `OIDC_ISSUER_URI`, optional `OIDC_JWK_SET_URI`, `OIDC_AUDIENCE`, `OIDC_ROLES_CLAIM` |
| Dashboard | `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_AUDIENCE`, `OIDC_CONNECT_SRC` |
| Automation scripts | `OIDC_TOKEN_ENDPOINT`, `OIDC_AUTOMATION_CLIENT_ID`, `OIDC_AUTOMATION_CLIENT_SECRET`, or an explicit `LAUNCHGUARD_ACCESS_TOKEN` |

`OIDC_JWK_SET_URI` lets a container use an internal network address for keys while still validating the token's public issuer. Cloud deployments can omit it when issuer discovery is reachable from the backend.

## Roles and authorization

Roles are additive in the local realm. The backend maps only the recognized values `VIEWER`, `OPERATOR`, and `ADMIN` to Spring Security authorities; unrelated token claims do not become application roles.

| Request | VIEWER | OPERATOR | ADMIN |
|---|:---:|:---:|:---:|
| `GET /api/**` | Yes | Yes | Yes |
| `POST /api/services/{id}/check` | No | Yes | Yes |
| `POST /api/services/{id}/check/async` | No | Yes | Yes |
| `POST /api/services/{id}/deployments` | No | Yes | Yes |
| `POST /api/services` | No | No | Yes |
| `DELETE /api/services/{id}` | No | No | Yes |

The dashboard hides unavailable controls for usability, but Spring Security enforces every rule. Anonymous API requests receive structured HTTP 401 responses; authenticated requests without sufficient roles receive structured HTTP 403 responses.

Anonymous access is limited to backend liveness/readiness/health and Prometheus endpoints. Only `health` and `prometheus` are exposed by Actuator, health details remain hidden, and the AWS ALB routes only `/api` to the backend—never `/actuator`.

## Local Keycloak

Compose imports `identity/keycloak/launchguard-realm.json` automatically. Keycloak is bound to host loopback on port 8085 and is not routed through the application dashboard or AWS ALB.

The checked-in users and passwords are intentionally weak local demonstration credentials:

| Role | Username | Password |
|---|---|---|
| VIEWER | `launchguard-viewer` | `viewer-demo-only` |
| OPERATOR | `launchguard-operator` | `operator-demo-only` |
| ADMIN | `launchguard-admin` | `admin-demo-only` |

The local automation client uses a demo-only secret from `.env.example`. Replace every default outside the disposable local lab. `scripts/validate-keycloak.sh` checks discovery and automation claims without printing or persisting the token.

## Browser token handling

`oidc-client-ts` performs Authorization Code flow with PKCE. Access tokens and transient OIDC state use `sessionStorage`, so they are scoped to the browser tab lifecycle and are not retained across browser restarts as `localStorage` tokens would be. The application keeps the active token only in memory while attaching it to API requests. It does not request offline access or store refresh tokens intentionally.

An expired token clears the in-memory session and returns the user to an explanatory sign-in state. HTTP 401 invalidates the dashboard session; HTTP 403 preserves the session and displays the authorization failure.

This remains a browser-token architecture: JavaScript can access the token, so an XSS vulnerability could steal it. A strict Content Security Policy, no inline scripts, short provider token lifetimes, dependency scanning, and avoiding third-party browser scripts reduce that risk. A production system with a stronger threat model should evaluate a backend-for-frontend with server-side sessions and hardened cookies.

## HTTP, CORS, and CSRF

The Nginx dashboard applies:

- `Content-Security-Policy` limited to same-origin assets/API plus the configured OIDC origin.
- `X-Content-Type-Options: nosniff`.
- `Referrer-Policy: same-origin`.
- `Permissions-Policy` disabling camera, geolocation, and microphone.
- `frame-ancestors 'none'` and Spring `X-Frame-Options: DENY`.
- No-store caching for runtime authentication configuration and API responses.
- Optional HSTS only when HTTPS is explicitly configured.

Production browser calls are same-origin through Nginx or the ALB. Backend CORS is empty by default; Compose permits only `http://localhost:5173` for an explicitly configured Vite development origin. Credentials are not enabled and wildcard origins are not used.

CSRF is disabled for the backend API because authentication is stateless and uses an `Authorization: Bearer` header. Browsers do not attach bearer tokens automatically as they do cookies. OIDC callback correlation and PKCE validation are handled by the OIDC client library. This reasoning would no longer apply if cookie authentication were added.

## Validation and error handling

Existing inputs use Jakarta Bean Validation and bounded pagination. Service targets must be absolute HTTP(S) URLs without embedded credentials, query strings, or fragments; health paths must begin with `/`. Deployment fields have length and format limits. Structured errors do not return stack traces, decoder failures, database exception details, authorization details, or secrets.

Registering arbitrary service URLs is intentionally an administrative capability because monitoring private services is a core requirement. It also creates an SSRF capability for administrators through the probe worker. Production operators must restrict ADMIN assignment and constrain worker network egress to approved monitoring destinations.

## Abuse protection and audit logs

Authenticated mutation requests are limited per token subject to 60 requests per minute by default. Rejections return HTTP 429 and `Retry-After`. This is a bounded, in-memory, per-instance safeguard—not a distributed global rate limiter—and can be tuned with `SECURITY_RATE_LIMIT_*` settings.

Successful and rejected mutation attempts that pass authentication emit structured audit-style log events with HTTP action, path, subject ID, recognized roles, and status. JWTs, authorization headers, passwords, client secrets, and unnecessary identity claims are never logged or persisted.

## Secrets and infrastructure

- `.env` is ignored; `.env.example` contains local/demo values only.
- The dashboard receives only public OIDC configuration and never a client secret.
- External deployment reporting reads automation credentials from GitHub environment variables/secrets.
- AWS database credentials remain in Secrets Manager and are injected only into the backend task.
- GitHub AWS access uses short-lived OIDC credentials; no static AWS key is stored.
- CI has read-only repository permission. Only CodeQL receives `security-events: write`, GHCR delivery receives `packages: write`, and AWS deployment receives `id-token: write` in their scoped jobs.

Application images run as non-root users. Compose drops Linux capabilities, sets `no-new-privileges`, and uses read-only root filesystems plus bounded tmpfs mounts for the six application containers. Keycloak keeps the filesystem behavior required by its development runtime but drops capabilities and is loopback-only.

## AWS considerations

Terraform injects generic issuer, audience, role-claim, and public SPA client settings. Guardrails prevent backend/dashboard tasks from running without OIDC configuration or an ACM certificate. The external provider must use HTTPS, allow the deployed dashboard origin for callback and logout, and expose a JWK endpoint reachable from private backend tasks through private connectivity or NAT. Keycloak is not provisioned in AWS and Cognito is not required.

HTTPS and an ACM certificate are required for any running AWS browser/API deployment; HTTP OIDC issuers are accepted only on loopback for local development. HSTS is enabled for dashboard/backend responses only when HTTPS is configured. Continue to restrict ALB ingress even though application APIs require authentication; authentication is not a substitute for network minimization.

## Known limitations

- Local Keycloak runs in development mode with an embedded database and demo credentials.
- Browser tokens are accessible to JavaScript; there is no backend-for-frontend session layer.
- Rate limiting is per process and resets on restart.
- No token revocation list, tenant isolation, identity administration, or audit database is implemented.
- Prometheus and local Grafana are anonymously readable on host loopback; protect them separately if exposed.
- Administrator-configured probe targets require network-level egress controls in a production environment.
