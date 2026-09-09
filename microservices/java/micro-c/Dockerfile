FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/*.jar app.jar

EXPOSE 8443

ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=8443"]
