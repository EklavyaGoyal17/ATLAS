FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache libexpat && apk add --no-cache git && addgroup -S atlas && adduser -S atlas -G atlas
USER atlas
WORKDIR /app
COPY --from=build /build/target/atlas-api-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
