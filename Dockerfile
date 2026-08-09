# Single-stage build: use pre-built jar
# Run "mvn clean package -DskipTests" first, then "docker compose build app"
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
