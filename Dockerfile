FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/record-locator-*.jar record-locator.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "record-locator.jar"]
