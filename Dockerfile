FROM eclipse-temurin:17-jre

RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

COPY build/libs/app.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
