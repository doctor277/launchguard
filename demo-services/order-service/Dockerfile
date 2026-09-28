# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY demo-services/payment-service/pom.xml demo-services/payment-service/pom.xml
COPY demo-services/order-service/pom.xml demo-services/order-service/pom.xml
COPY demo-services/notification-service/pom.xml demo-services/notification-service/pom.xml
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -pl demo-services/order-service -am dependency:go-offline
COPY demo-services/order-service/src demo-services/order-service/src
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -pl demo-services/order-service -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S order && adduser -S order -G order
USER order
COPY --from=build /workspace/demo-services/order-service/target/order-service-0.5.0-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
