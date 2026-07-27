# Stage 1: Build. Needs the JDK, not the JRE — there is no compiler in a -jre image.
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app
COPY . .

# Belt and braces alongside .gitattributes: a tree checked out before that existed still has a CRLF
# wrapper, and the failure it produces ("./gradlew: not found", for a file that is plainly there) costs
# more to diagnose than the two commands cost to run.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

RUN ./gradlew --no-daemon clean test bootJar -PappVersion=local-test

# Stage 2: Run
FROM eclipse-temurin:25-jre

RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

COPY --from=builder /app/build/libs/app.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
