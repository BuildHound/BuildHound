# syntax=docker/dockerfile:1
# OCI image for buildhound-server. Build from the REPOSITORY ROOT (the build needs buildhound-commons):
#
#   docker build -f buildhound-server/Dockerfile -t buildhound-server .
#
# Best practices applied (docs/architecture.md §OCI): multi-stage build, JRE-only runtime,
# non-root user, OCI annotations, no secrets in layers. Pin base images by digest before
# publishing release images.

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy the wrapper and build configuration first so dependency resolution layers cache
# independently of source changes.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY buildhound-commons/build.gradle.kts buildhound-commons/
COPY buildhound-server/build.gradle.kts buildhound-server/
COPY buildhound-gradle-plugin/build.gradle.kts buildhound-gradle-plugin/
COPY buildhound-report/build.gradle.kts buildhound-report/
# -Pbuildhound.toolchain=21: bytecode is release-21 either way; skipping the per-image
# JDK 26 download removes a network dependency (the other CI jobs prove the 26 path).
RUN ./gradlew --no-daemon -Pbuildhound.toolchain=21 :buildhound-server:dependencies --quiet > /dev/null || true

COPY buildhound-commons/ buildhound-commons/
COPY buildhound-server/ buildhound-server/
RUN ./gradlew --no-daemon -Pbuildhound.toolchain=21 :buildhound-server:installDist

FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="buildhound-server" \
      org.opencontainers.image.description="BuildHound ingestion service" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.url="https://buildhound.dev" \
      org.opencontainers.image.source="https://github.com/aegis123/Gradle-build-monitoring"

RUN groupadd --system --gid 10001 buildhound \
    && useradd --system --uid 10001 --gid buildhound --home-dir /nonexistent --shell /usr/sbin/nologin buildhound

COPY --from=build /workspace/buildhound-server/build/install/buildhound-server /app

USER 10001:10001
EXPOSE 8080
ENV BUILDHOUND_PORT=8080

ENTRYPOINT ["/app/bin/buildhound-server"]
