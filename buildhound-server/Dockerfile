# syntax=docker/dockerfile:1
# OCI image for buildhound-server. Build from the REPOSITORY ROOT (the build needs buildhound-commons):
#
#   docker build -f buildhound-server/Dockerfile -t buildhound-server .
#
# Best practices applied (docs/architecture.md §OCI): multi-stage build, JRE-only runtime,
# non-root user, OCI annotations, no secrets in layers. Base images are **digest-pinned** (plan 042):
# the tag stays for humans, the @sha256 makes the base immutable. Refresh the digest (and re-scan) when
# bumping — resolve with `docker buildx imagetools inspect eclipse-temurin:25-jre-jammy`.

FROM eclipse-temurin:26-jdk-jammy@sha256:adafe495e9bded1d3585c9a12bb7bcdba746443337b84cdbdfc48d3350850c28 AS build

# The Gradle daemon JVM (gradle/gradle-daemon-jvm.properties, toolchainVersion=21) — digest-pinned
# and copied, never downloaded. See the note above the priming RUN.
COPY --from=eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8 /opt/java/openjdk /opt/java/jdk21
WORKDIR /workspace

# Copy the wrapper and build configuration first so dependency resolution layers cache
# independently of source changes.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY buildhound-commons/build.gradle.kts buildhound-commons/
COPY buildhound-server/build.gradle.kts buildhound-server/
COPY buildhound-gradle-plugin/build.gradle.kts buildhound-gradle-plugin/
COPY buildhound-report/build.gradle.kts buildhound-report/
# buildhound-internal-adapters is unconditionally included since plan 074 (the core plugin bundles it),
# so settings.gradle.kts references it even in this minimal context. Only its build script is needed —
# the server depends on neither the plugin nor this module, so no source is compiled here.
COPY buildhound-internal-adapters/build.gradle.kts buildhound-internal-adapters/
# No -Pbuildhound.toolchain override (plan 111): this module targets release 25, which a JDK 21
# toolchain cannot produce. The build base is a JDK 26, which satisfies the module's toolchain.
#
# TWO Java installations are needed here, and missing the second one broke CI once:
#   * the JDK 26 build base   -> satisfies the module's `jvmToolchain` (compiles to release 25)
#   * a JDK 21 at /opt/java/jdk21 -> satisfies gradle/gradle-daemon-jvm.properties, which pins
#     the Gradle DAEMON JVM at 21 repo-wide. On main the build base WAS a JDK 21, so this was
#     invisible; any base bump exposes it, and Gradle then fetches a ~200 MB JDK from an
#     api.foojay.io redirect that carries no checksum — an unverified binary that becomes the
#     JVM compiling the shipped artifact. Copying it from a digest-pinned image keeps the whole
#     build offline and immutable (security review).
#
# auto-download=false is the guard that keeps it that way: if either installation ever stops
# matching, the build fails loudly instead of silently reaching for foojay. Note this failure
# hides behind the BuildKit cache mount — a warm cache serves a previously-provisioned JDK, so
# a green rebuild proves nothing. Verify base-image changes with `docker build --no-cache`.
# The BuildKit cache mount keeps the Gradle user home (dependency downloads, wrapper
# dist) across builds even when these layers invalidate; requires BuildKit/buildx.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon -Dorg.gradle.java.installations.auto-download=false \
        -Dorg.gradle.java.installations.paths=/opt/java/jdk21 \
        :buildhound-server:dependencies --quiet > /dev/null || true

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
    ./gradlew --no-daemon -Dorg.gradle.java.installations.auto-download=false \
        -Dorg.gradle.java.installations.paths=/opt/java/jdk21 \
        :buildhound-server:installDist

FROM eclipse-temurin:25-jre-jammy@sha256:5bd5dbe00f40ea149de434a75029713765a2912cfc1fd770cc7c7aff007384ea

LABEL org.opencontainers.image.title="buildhound-server" \
      org.opencontainers.image.description="BuildHound ingestion service" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.url="https://buildhound.dev" \
      org.opencontainers.image.source="https://github.com/BuildHound/BuildHound"

RUN groupadd --system --gid 10001 buildhound \
    && useradd --system --uid 10001 --gid buildhound --home-dir /nonexistent --shell /usr/sbin/nologin buildhound

COPY --from=build /workspace/buildhound-server/build/install/buildhound-server /app

USER 10001:10001
EXPOSE 8080
ENV BUILDHOUND_PORT=8080

ENTRYPOINT ["/app/bin/buildhound-server"]
