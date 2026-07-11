# syntax=docker/dockerfile:1

# ---- Stage 1: build the Spring Boot fat jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY platform/messaging-contracts/pom.xml /contracts/pom.xml
COPY platform/messaging-contracts/src /contracts/src
RUN mvn -B -f /contracts/pom.xml install -DskipTests
COPY platform/messaging-support/pom.xml /support/pom.xml
COPY platform/messaging-support/src /support/src
RUN mvn -B -f /support/pom.xml install -DskipTests
COPY auth-service/pom.xml .
RUN mvn -B dependency:go-offline

# Now copy sources and build.
COPY auth-service/src ./src
RUN mvn -B clean package -DskipTests

# ---- Stage 2: minimal runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as a non-root user for safety.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
