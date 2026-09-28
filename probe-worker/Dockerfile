# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-alpine@sha256:3fd2d245c4e0eba615fe366a71b8bd25f5db7104f53e4026b24bf508b880bd2a AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY monitoring-events/pom.xml monitoring-events/pom.xml
COPY probe-worker/pom.xml probe-worker/pom.xml
COPY demo-services/payment-service/pom.xml demo-services/payment-service/pom.xml
COPY demo-services/order-service/pom.xml demo-services/order-service/pom.xml
COPY demo-services/notification-service/pom.xml demo-services/notification-service/pom.xml
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -pl probe-worker -am dependency:go-offline
COPY monitoring-events/src monitoring-events/src
COPY probe-worker/src probe-worker/src
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -pl probe-worker -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
ARG APP_VERSION=0.8.0-SNAPSHOT
ARG GIT_SHA=unknown
ARG BUILD_TIMESTAMP=unknown
LABEL org.opencontainers.image.title="LaunchGuard probe-worker" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_TIMESTAMP}"
WORKDIR /app
RUN addgroup -S probeworker && adduser -S probeworker -G probeworker
USER probeworker
COPY --from=build /workspace/probe-worker/target/probe-worker-0.8.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
