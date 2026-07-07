# syntax=docker/dockerfile:1

# ---- Stage 1: build the Spring Boot fat jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first: copy only the POM, then resolve deps.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Now copy sources and build.
COPY src ./src
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
