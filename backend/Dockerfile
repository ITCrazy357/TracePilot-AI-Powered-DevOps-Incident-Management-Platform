# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/main src/main
# Tests run separately against real Testcontainers in CI; image builds need no Docker socket.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269 AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 tracepilot \
    && useradd --uid 10001 --gid 10001 --no-create-home tracepilot
WORKDIR /app
COPY --from=build /build/target/tracepilot-backend-0.0.1-SNAPSHOT.jar app.jar
ARG SOURCE_REVISION=worktree
LABEL org.opencontainers.image.title="TracePilot backend" \
      org.opencontainers.image.revision=$SOURCE_REVISION
ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8080
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
