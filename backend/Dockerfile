FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY demo-services/payment-service/pom.xml demo-services/payment-service/pom.xml
RUN chmod +x mvnw && ./mvnw -B -pl backend -am dependency:go-offline
COPY backend/src backend/src
RUN ./mvnw -B -pl backend -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S launchguard && adduser -S launchguard -G launchguard
USER launchguard
COPY --from=build /workspace/backend/target/launchguard-backend-0.3.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
