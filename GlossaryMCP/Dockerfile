FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/glossary-mcp-java-0.1.0.jar app.jar
COPY ../glossary /app/glossary
ENTRYPOINT ["java", "-jar", "app.jar"]
