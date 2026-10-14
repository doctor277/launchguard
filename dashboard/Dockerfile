# syntax=docker/dockerfile:1
FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --no-fund
COPY tsconfig.json vite.config.ts index.html ./
COPY public ./public
COPY src ./src
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.5-alpine@sha256:19c132c9ab02d3b783f478743dafc7a7f42e27aa7d2bdcbec1bb1128ca8f2a07
ARG APP_VERSION=1.0.0
ARG GIT_SHA=unknown
ARG BUILD_TIMESTAMP=unknown
LABEL org.opencontainers.image.title="LaunchGuard dashboard" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_TIMESTAMP}"
ENV OIDC_ISSUER_URI=http://localhost:8085/realms/launchguard \
    OIDC_CLIENT_ID=launchguard-dashboard \
    OIDC_AUDIENCE=launchguard-api \
    OIDC_CONNECT_SRC=http://localhost:8085 \
    DASHBOARD_HSTS="" \
    NGINX_ENVSUBST_FILTER="^(OIDC_CONNECT_SRC|DASHBOARD_HSTS)$"
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY auth-config.json.template /etc/launchguard/auth-config.json.template
COPY --chmod=755 40-launchguard-auth-config.sh /docker-entrypoint.d/40-launchguard-auth-config.sh
COPY --from=build /workspace/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=6 \
  CMD wget -q -T 2 -O /dev/null http://127.0.0.1:8080/healthz || exit 1
