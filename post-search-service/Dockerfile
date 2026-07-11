# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS contracts
WORKDIR /contracts
COPY platform/messaging-contracts/pom.xml .
COPY platform/messaging-contracts/src ./src
RUN mvn -B install -DskipTests

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY --from=contracts /root/.m2 /root/.m2
COPY platform/messaging-support/pom.xml /support/pom.xml
COPY platform/messaging-support/src /support/src
RUN mvn -B -f /support/pom.xml install -DskipTests
COPY post-search-service/pom.xml .
RUN mvn -B dependency:go-offline
COPY post-search-service/src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
