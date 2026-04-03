FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/kafka-producer-0.1.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
