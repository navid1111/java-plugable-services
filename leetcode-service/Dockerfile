# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# The same image runs in API or worker mode. Only the worker deployment receives
# the Docker socket; the API has no access to the daemon.
RUN apk update && apk add --no-cache docker-cli

# We must run as a user that can access the mounted /var/run/docker.sock
# By default we'll run as root inside this container because socket ownership
# varies greatly between host OS (Docker for Mac, Linux, WSL2). 
# This matches our technical context decision for execution integration.
# RUN addgroup -S app && adduser -S app -G app
# USER app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
