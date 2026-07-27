FROM eclipse-temurin:25-jre

RUN groupadd -r app && useradd -r -g app app

WORKDIR /app
COPY . .

RUN ./gradlew --no-daemon clean test bootJar -PappVersion=local-test

# Stage 2: Run
FROM eclipse-temurin:25-jre

RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

COPY --from=builder /app/build/libs/app.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
