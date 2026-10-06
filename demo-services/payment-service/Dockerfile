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
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -pl demo-services/payment-service -am dependency:go-offline
COPY demo-services/payment-service/src demo-services/payment-service/src
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -pl demo-services/payment-service -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
ARG APP_VERSION=1.0.0
ARG GIT_SHA=unknown
ARG BUILD_TIMESTAMP=unknown
LABEL org.opencontainers.image.title="LaunchGuard payment-service" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_TIMESTAMP}"
WORKDIR /app
RUN addgroup -S payment && adduser -S payment -G payment
USER payment
COPY --from=build /workspace/demo-services/payment-service/target/payment-service-1.0.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
