# syntax=docker/dockerfile:1
# OCI image for btp-server. Build from the REPOSITORY ROOT (the build needs btp-commons):
#
#   docker build -f btp-server/Dockerfile -t btp-server .
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
COPY btp-commons/build.gradle.kts btp-commons/
COPY btp-server/build.gradle.kts btp-server/
COPY btp-gradle-plugin/build.gradle.kts btp-gradle-plugin/
COPY btp-report/build.gradle.kts btp-report/
RUN ./gradlew --no-daemon :btp-server:dependencies --quiet > /dev/null || true

COPY btp-commons/ btp-commons/
COPY btp-server/ btp-server/
RUN ./gradlew --no-daemon :btp-server:installDist

FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="btp-server" \
      org.opencontainers.image.description="Build Telemetry Platform ingestion service" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/aegis123/Gradle-build-monitoring"

RUN groupadd --system --gid 10001 btp \
    && useradd --system --uid 10001 --gid btp --home-dir /nonexistent --shell /usr/sbin/nologin btp

COPY --from=build /workspace/btp-server/build/install/btp-server /app

USER 10001:10001
EXPOSE 8080
ENV BTP_PORT=8080

ENTRYPOINT ["/app/bin/btp-server"]
