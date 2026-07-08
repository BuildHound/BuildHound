# syntax=docker/dockerfile:1
# OCI image for buildhound-server. Build from the REPOSITORY ROOT (the build needs buildhound-commons):
#
#   docker build -f buildhound-server/Dockerfile -t buildhound-server .
#
# Best practices applied (docs/architecture.md §OCI): multi-stage build, JRE-only runtime,
# non-root user, OCI annotations, no secrets in layers. Base images are **digest-pinned** (plan 042):
# the tag stays for humans, the @sha256 makes the base immutable. Refresh the digest (and re-scan) when
# bumping — resolve with `docker buildx imagetools inspect eclipse-temurin:21-jdk-jammy`.

FROM eclipse-temurin:21-jdk-jammy@sha256:9d8dcf999b0bce2453e913823595a5ff2a4e8e9e5d5241b45280d0ff069818ec AS build
WORKDIR /workspace

# Copy the wrapper and build configuration first so dependency resolution layers cache
# independently of source changes.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY buildhound-commons/build.gradle.kts buildhound-commons/
COPY buildhound-server/build.gradle.kts buildhound-server/
COPY buildhound-gradle-plugin/build.gradle.kts buildhound-gradle-plugin/
COPY buildhound-report/build.gradle.kts buildhound-report/
# buildhound-internal-adapters is unconditionally included since plan 051 (the core plugin bundles it),
# so settings.gradle.kts references it even in this minimal context. Only its build script is needed —
# the server depends on neither the plugin nor this module, so no source is compiled here.
COPY buildhound-internal-adapters/build.gradle.kts buildhound-internal-adapters/
# -Pbuildhound.toolchain=21: bytecode is release-21 either way; skipping the per-image
# JDK 26 download removes a network dependency (the other CI jobs prove the 26 path).
# The BuildKit cache mount keeps the Gradle user home (dependency downloads, wrapper
# dist) across builds even when these layers invalidate; requires BuildKit/buildx.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon -Pbuildhound.toolchain=21 :buildhound-server:dependencies --quiet > /dev/null || true

COPY buildhound-commons/ buildhound-commons/
COPY buildhound-server/ buildhound-server/
# The server bundles buildhound-report's timeline.js (served at /timeline.js) and the OpenAPI spec
# (served at /openapi.yaml, copied into resources from docs/api by processResources) — so their SOURCE,
# not just the build script, must be in the context or the runtime resources are missing and
# DashboardAssets/DocsAssets fail to initialize (only surfaces when the dashboard/docs are loaded, not
# by the /health smoke test).
COPY buildhound-report/ buildhound-report/
COPY docs/api/openapi.yaml docs/api/openapi.yaml
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon -Pbuildhound.toolchain=21 :buildhound-server:installDist

FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13

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
