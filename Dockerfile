# Runtime image for the AIO Party advertising API.
#
# This image copies in a PRE-BUILT jar, so build it first:
#     ./gradlew bootJar          # -> build/libs/app.jar
# then build/run via compose:
#     docker compose up --build
FROM eclipse-temurin:17-jre

# Run as a non-root user.
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

COPY build/libs/app.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
