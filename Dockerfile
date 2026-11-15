# Spring Boot backend (fire-backend). Build context = repo root.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/fire-backend-*.jar /app.jar

FROM eclipse-temurin:21-jre
COPY --from=build /app.jar /app.jar
ENV PORT=8080 \
    FIRE_MODEL_API_URL=http://fire-service:8000
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
