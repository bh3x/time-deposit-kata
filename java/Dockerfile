# syntax=docker/dockerfile:1
# Application image (ADR-0010). The boot jar is built with the Gradle wrapper inside the image, so running the stack
# needs only Docker. Tests are not run here: `./gradlew check` needs Docker itself (Testcontainers).

FROM eclipse-temurin:25.0.5_8-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src/main src/main
# The cache mount keeps Gradle and its dependencies between builds. Exactly one boot jar is expected (the plain jar
# is not needed); cp fails the build if there is none or more than one.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain bootJar \
    && cp $(find build/libs -name '*.jar' ! -name '*-plain.jar') app.jar

FROM eclipse-temurin:25.0.5_8-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
