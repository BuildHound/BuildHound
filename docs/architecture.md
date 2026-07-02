# Architecture & Best Practices

> **Living document.** This is the working architecture design for BuildHound. It is
> expected to be updated and improved continuously during development:
> whenever an implementation, review, or retro produces a better insight, this document
> changes in the same PR. The product requirements live in
> [build-telemetry-spec.md](build-telemetry-spec.md); this document describes *how* we
> build it well.

## 1. System overview

```
 buildhound-gradle-plugin (settings plugin, runs inside every Gradle build)
        │  BuildPayload (schema v1, gzip JSON)
        ▼
 buildhound-server (Ktor, multi-tenant, OCI image) ──► Postgres + TimescaleDB
        │
        └─► dashboard SPA (phase 1) / query API
```

| Module | Type | JVM floor | Role |
|---|---|---|---|
| `buildhound-commons` | Kotlin Multiplatform (jvm today; js/native later) | 21 | Payload schema (kotlinx-serialization), `CiEnvironmentProvider` SPI — the contract everything builds against |
| `buildhound-gradle-plugin` | Kotlin/JVM + `java-gradle-plugin` | 21 | Settings plugin: collectors, finalizer, uploader |
| `buildhound-server` | Kotlin/JVM + Ktor, `application` | 21 | Ingest API, storage, rollups, regression engine, dashboard |
| `buildhound-report` | Kotlin/JVM (js candidate) | 21 | Standalone HTML artifact template + renderer, embedded into the plugin |
| `buildhound-ci-assets` | not a Gradle module | none | Azure YAML template, metric CLI (shell), profiler scenarios |

**Dependency rule:** `buildhound-commons` has no dependency on any other module and no Gradle API
types. The plugin and server never share code except through `buildhound-commons`. `buildhound-report`
depends on nothing but the payload JSON shape.

**JVM floors:** every module targets JVM 21 (owner decision, deviating from spec §3.1's
Java 11+; see decision log). Consequence for the compatibility matrix: the plugin
requires consumers to run Gradle on JDK 21+ — the TestKit matrix tests Gradle versions on
a 21+ daemon JVM only.

## 2. Gradle plugin best practices (binding)

These are the rules every plugin change is reviewed against:

1. **Settings plugin, apply once.** Applied in `settings.gradle.kts`; sees every project,
   registers services before any project evaluates. No per-module boilerplate.
2. **Configuration-cache safety is non-negotiable.** No `Project`/`Gradle` references at
   execution time. State flows only through `Provider`s, `ValueSource`s, and serializable
   `BuildService` parameters. Task completion via
   `BuildEventsListenerRegistry.onTaskCompletion(BuildService)`; build-finished via the
   Flow API (`FlowAction` + `FlowProviders.buildWorkResult`) — never `buildFinished {}`.
   The platform's own build keeps `org.gradle.configuration-cache=true` so regressions
   surface immediately.
3. **The plugin must never fail a build.** Every failure path logs at `warn`, writes a
   marker file, and returns. Each phase adds failure-injection tests for this.
4. **No internal Gradle APIs in v1.** The v1.x cache-origin feature gets an isolated
   `internal-adapters` module, feature-flagged per Gradle version, degrading gracefully.
5. **Laziness everywhere.** Extension properties are `Property`/`MapProperty`; conventions
   set via `convention()`, values read only at execution time. Nothing is resolved at
   configuration time that doesn't have to be.
6. **Compatibility is tested, not assumed.** TestKit functional tests live in a dedicated
   `functionalTest` source set so CI can run them as a matrix:
   {Gradle 8.14, 9.latest} × {config cache on/off} (roadmap phase 0). The floor is
   8.5+ (`BuildFeatures` injection) and in practice 8.14+ for the JDK-21 requirement.
   Isolated
   Projects runs as a non-blocking CI job from phase 1.
7. **Identity & hygiene:** plugin id `dev.buildhound`, Maven group `dev.buildhound`
   (decision #6); `gradlePlugin {}` metadata kept publish-ready; `validatePlugins` runs
   in `check`.
8. **Extension points are public contracts.** `CiEnvironmentProvider` lives in
   `buildhound-commons`, is documented, and loadable via `ServiceLoader` — "add your CI in ~30
   lines" is an advertised feature.
9. **File access in `apply()` is a CC fingerprint input.** Gradle tracks every
   configuration-phase file read (even `File.isFile`), and existence changes invalidate
   the next build's cache entry — creating a file at apply time guarantees a miss on the
   following build. External state (marker files, salts, spool dirs) is read/created
   inside a `ValueSource` or at execution time instead. (Found by the plan-003
   functional test: the identity salt created during apply invalidated CC reuse.)

10. **Plugin-classpath code runs on Gradle's embedded Kotlin stdlib.** The compiler
    ships stdlib 2.4 but Gradle 8.14 runs plugins on its embedded 2.0 stdlib, so a
    newer-stdlib API compiles fine and throws `NoSuchMethodError` at runtime (found
    when a 2.4-only `sequenceOf` overload broke CI detection). `buildhound-commons`,
    the plugin, and `buildhound-report` pin `apiVersion = 2.0` (the embedded stdlib of
    the oldest supported Gradle); bump it only when the support floor moves. The pin
    does NOT cover prebuilt dependencies (kotlinx-serialization declares stdlib 2.2) —
    only running the test suite on the floor Gradle does, so CI keeps a floor job.
    Shelf life: Kotlin 2.4 already deprecates `apiVersion 2.0`; when a future KGP drops
    it, the response is raising the Gradle floor to 9.x and moving the pin to 2.2 —
    the CI floor job survives that transition and remains the true backstop.

## 3. Kotlin Multiplatform best practices (binding)

1. **`buildhound-commons` is the only shared-code channel.** Models are pure data + 
   kotlinx-serialization; no platform types, no I/O, no Gradle/Ktor types leak in.
2. **Additive schema only.** New fields get defaults so old servers/plugins keep working;
   `ignoreUnknownKeys` on the shared `BuildHoundJson`. Golden-file tests pin every historical
   schema version and are never edited, only added to.
3. **Targets grow with need, not speculatively.** jvm-only today; `js()` when the report
   frontend moves to Kotlin/JS, native when the metric CLI justifies it. Hierarchical
   source sets from day one (`commonMain`/`jvmMain`).
4. **Single version catalog** (`gradle/libs.versions.toml`) governs every version in the
   repo. No hardcoded versions in build scripts.
5. **Planned:** convention plugins in an included `build-logic` once module count or
   config duplication grows (currently three small modules; duplication is acceptable and
   explicit).
6. Tests run on kotlin-test + JUnit Platform on all JVM targets.

## 4. OCI / container image best practices (binding)

The server ships as an OCI image (`buildhound-server/Dockerfile`, compose in `deploy/`):

1. **Multi-stage builds**: JDK + Gradle only in the build stage; runtime stage is JRE-only.
   Evaluate `jlink`/distroless once the dependency set stabilizes.
2. **Non-root runtime** (`USER 10001:10001`), no shell entrypoint tricks, exec-form
   `ENTRYPOINT`.
3. **No secrets in images or layers.** Configuration via environment variables; compose
   defaults are dev-only and say so.
4. **Deterministic and labeled**: OCI `org.opencontainers.image.*` annotations; base
   images pinned by digest before any published release; dependency layers cached
   separately from source layers.
5. **Small context**: `.dockerignore` keeps git metadata, docs, and build output out.
6. **Health**: `/health` endpoint; orchestrator-level checks (compose `healthcheck` on the
   DB now, on the server once it has real dependencies).
7. **Planned:** SBOM + image signing (syft/cosign) in the release pipeline; Testcontainers
   for server integration tests; image build in CI on every PR (already scaffolded).

## 5. Server architecture

- **Ktor** with plain function routing (`Routes.kt`), one module function (`buildHoundModule`)
  usable by both `main()` and `testApplication` — keep it that way so every route is
  testable without a socket.
- **Persistence boundary**: all storage behind `BuildStore` (and future stores). The
  scaffold is in-memory; phase 1 replaces it with Postgres + TimescaleDB behind the same
  interface, migrations via Flyway, tested with Testcontainers.
- **Multi-tenancy from the first real table**: every row carries `project_id`; queries are
  always tenant-filtered; tokens hashed at rest; ingest **and** query rate-limited per
  token (spec §8, plan 013) — buckets keyed by the token's SHA-256. Per-token buckets
  alone cannot stop a rotating-token flood (each garbage token mints a fresh bucket and
  reaches token resolution), so an outer **per-source-host** limiter caps everything a
  single source can send to `/v1/*` — including bucket-minting and pre-auth DB lookups.
  Residual risk (recorded in plan 013): floods distributed across many source IPs get
  one host budget each — that's an infra/WAF concern, not an application one. The host
  key is the direct TCP peer; installing `XForwardedHeaders` would make it
  attacker-controlled — don't, without revisiting the limiter key.
- **Idempotency**: ingest dedupes on `buildId` — already part of the `BuildStore` contract.
- **Stateless horizontally**: no local state outside the DB; the image can scale out.
  Deliberate exception: rate-limiter buckets are instance-local (a shared-store limiter
  adds a hot write per request), so N replicas mean an N× effective ceiling — revisit
  when the server actually scales out; the pilot runs one instance.

## 6. Security & privacy design rules

- Tokens: env-var providers only, never in DSL literals, hashed at rest server-side.
- Payloads never contain absolute paths outside the project, env dumps, or secrets; a
  scrubber strips secret-like patterns from execution reasons and failure text (spec §3.7).
- Local-build identity is pseudonymized by default (HMAC with per-project salt); `strict`
  mode sends nothing.
- The HTML artifact makes zero external requests (locked decision #4) — enforced by test.
- **Ingest tokens are wired from `providers.environmentVariable(...)` only.** A DSL
  literal (or `gradleProperty`) value would be serialized into the configuration-cache
  entry on disk (encrypted since Gradle 8.6, but still at rest); the env provider is
  stored as a reference and re-read at execution. Uploads over non-loopback plaintext
  http log a warning. Spool files carry only the (scrubbed) payload, never the token;
  anything that can write the spool dir already executes code in the build (same trust
  domain).
- Every feature PR gets a dedicated security **and** privacy review (see CLAUDE.md).

## 7. Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-07-02 | Version catalog + per-module plugin aliases; no `build-logic` yet | Three modules; convention plugins add classloader complexity before they pay off |
| 2026-07-02 | `buildhound-ci-assets` is not a Gradle module | Its consumers are CI steps without a JVM |
| 2026-07-02 | Flow API + `ServiceReference` validated against Gradle 8.14 + CC (incl. reuse) | TestKit functional tests green — riskiest assumption of the roadmap spike confirmed |
| 2026-07-02 | Wrapper `distributionUrl` kept on services.gradle.org | Standard, checksum-verifiable path |
| 2026-07-02 | JVM 21 floor for **all** modules, superseding spec §3.1's "Java 11+ runtime for the plugin" | Owner decision: build with at least Java 21. Plugin consumers must run Gradle on JDK 21+ |
| 2026-07-02 | Build toolchain is JDK 26 (foojay-provisioned), emitted bytecode/API stay Java 21 (`jvmTarget=21`, `-Xjdk-release=21`, plugin source/target 21); `buildhound.toolchain` property is the local escape hatch | Owner request (plan 011); consumer floor and JRE-21 server image unchanged |
| 2026-07-02 | Gradle support floor is 8.14 (JDK-21 requirement; `BuildFeatures` needs 8.5+), tested by a dedicated CI floor job | Supersedes spec §3.1's "Gradle 8.0+" |
| 2026-07-02 | Kotlin `apiVersion` pinned to 2.0 for commons/plugin/report | Plugin-classpath code executes on Gradle's embedded Kotlin stdlib (2.0 on Gradle 8.14); newer stdlib APIs are runtime `NoSuchMethodError`s |
| 2026-07-02 | Naming decision #6: product **BuildHound**, domain **buildhound.dev**, plugin id + Maven group `dev.buildhound`, modules `buildhound-*`, DSL `buildhound {}`, env prefix `BUILDHOUND_` | Owner decision; pre-release so renamed with no compatibility shim. Research doc + old plans keep the BTP working name as historical records |

*Add a row (or a docs/plans entry) whenever an architectural decision is made or reversed.*
