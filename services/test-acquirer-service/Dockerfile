FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/test-acquirer-service-*-exec.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
