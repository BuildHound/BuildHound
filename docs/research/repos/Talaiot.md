# Talaiot

An extensible Gradle project plugin by Iñaki Villar (cdsap) that records per-task and whole-build timing plus environment metadata during a build and publishes it at build end to pluggable backends (InfluxDB 1.x/2.x, Elasticsearch, Prometheus PushGateway, RethinkDB, local JSON, console).

Research date: 2026-07-03. Source: shallow clone of https://github.com/cdsap/Talaiot.

## Overview

Talaiot is the closest open-source prior art for BuildHound's plugin-side collector. It is a Kotlin/JVM Gradle plugin (current version `2.1.1-SNAPSHOT`, defined in `buildSrc/src/main/kotlin/io/github/cdsap/talaiot/buildplugins/Constants.kt`) that hooks into the build via a shared `BuildService` registered with `BuildEventsListenerRegistry`, accumulates one record per finished task, enriches the resulting report with environment, Gradle-switch, git, and JVM-process metrics, and fans the report out to a configurable list of publishers when the build ends. It deliberately has no server, API, or dashboard of its own: teams point it at an existing time-series database and build Grafana dashboards on top. A demo Docker image bundling InfluxDB 1.2 and Grafana 5.4.3 with two provisioned dashboards lives under `docker/`, together with a `scripts/populate.sh` script that seeds it by running gradle-profiler benchmarks against the bundled sample project.

Distribution is notably modular. One "standard" plugin (`io.github.cdsap.talaiot`) aggregates every publisher, while per-backend slim plugins (`io.github.cdsap.talaiot.plugin.influxdb`, `.elasticsearch`, `.rethinkdb`, `.pushgateway`, and a `base` plugin with only JSON/console output) let users pull in only the backend client they need. All of them are thin shells around a single generic core class.

## Status & maturity

The project is long-lived — the MIT license carries a 2019 copyright for Iñaki Villar — and still maintained as of December 2025, though in low-activity maintenance mode. The last commit in the clone is `4af8034` ("Update docker Docker tag to v29.1.2 (#474)", 2025-12-08), a bot-style dependency bump of the Docker service image used in CI; the clone is shallow, so only this one commit is visible.

Quality signals are strong for a community plugin. Test code (~5,700 lines of Kotlin) slightly exceeds main code (~5,000 lines). Functional tests use Gradle TestKit against a version matrix of Gradle 8.14.3, 9.1.0, and 9.2.0, including a configuration-cache-hit scenario (`library/plugins/talaiot-standard/src/test/kotlin/io/github/cdsap/talaiot/ConfigurationCacheHit.kt`) and an isolated-projects scenario run with `-Dorg.gradle.unsafe.isolated-projects=true` (`IsolatedProjectsHit.kt`). Every storage backend has Testcontainers-based integration tests, backed by custom container wrappers in `library/core/talaiot-test-utils` (InfluxDB 1/2, Elasticsearch, PushGateway, RethinkDB, plus a Redis remote-cache container). Four GitHub Actions workflows run ktlint, assembly, two test-collection jobs (`collectUnitTest`, `collectUnitTestLibs`), cross-OS sample builds, and snapshot publishing to Maven Central via `publishAndReleaseToMavenCentral`. The sample workflow (`.github/workflows/sample.yaml`) runs on ubuntu, macOS, and Windows in two jobs: the Gradle 9 sample on JDK 17/21/25 and the Gradle 8 sample on JDK 11/15/17/21, each with `--configuration-cache` runs — evidence of active compatibility maintenance through late 2025.

Weaker signals exist as well. The tests use the long-deprecated kotlintest framework, and functional tests rely on fixed `Thread.sleep(5000)` waits (publisher integration tests sleep 2–10 seconds). The README advertises a Timeline publisher that does not exist in the code, and the base plugin exposes a `timelinePublisher` flag that is never wired to anything (`library/plugins/base/base-plugin/src/main/java/io/github/cdsap/talaiot/plugin/base/BaseConfiguration.kt:14` and `BaseConfigurationProvider.kt`). The report data model is stringly typed, and the core leans on several internal Gradle APIs (detailed below). Documentation is a single long README that is generally thorough but shows drift in places; the `MetricsConfiguration` KDoc omits `processMetrics` from its list of defaults even though it defaults to `true`.

## Architecture

The build is a multi-module Gradle project under `library/`, built with Gradle 9.2.0 (wrapper) and convention plugins in `buildSrc` compiled with Kotlin 2.2.21, published via vanniktech maven-publish and the Gradle plugin-publish plugin, and linted with ktlint.

- `library/core/talaiot` — the collection engine: the generic `Talaiot<T : TalaiotExtension>` class, the `TalaiotBuildService`, metrics, filters, and entities.
- `library/core/talaiot-logger` — a small logging abstraction.
- `library/core/talaiot-request` — a thin HTTP POST helper built on kohttp 0.10.0 (`SimpleRequest.kt`).
- `library/core/talaiot-test-utils` — Testcontainers wrappers used by publisher integration tests.
- `library/plugins/{base, influxdb, influxdb2 (under influxdb/), elastic-search, pushgateway, rethinkdb, hybrid}` — each backend split into a `-publisher` library and a `-plugin` Gradle plugin.
- `library/plugins/talaiot-standard` — aggregates all publishers under plugin id `io.github.cdsap.talaiot` via `TalaiotConfigurationProvider`.

The runtime flow is compact. Each plugin's `Plugin<Project>.apply` instantiates `Talaiot` with a `PublisherConfigurationProvider` (`library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/Talaiot.kt`). Inside `gradle.taskGraph.whenReady` the plugin: (1) builds a task-path-to-class-name dictionary from `taskGraph.allTasks`, stripping Gradle's `_Decorated` suffix, and deliberately substitutes an empty map when isolated projects is active (checked through `BuildFeatures.isolatedProjects`, gated on Gradle >= 8.5); (2) evaluates all configured `Metric` objects into a mutable `ExecutionReport`; (3) registers the shared `TalaiotBuildService`, packing everything the service will need into its `BuildServiceParameters` — the serialized `TalaiotPublisherImpl`, the task-type dictionary, flags, and lazy `ValueSource` providers for git branch, build id, and jstat/jinfo probes; and (4) hooks the service to task events with `serviceOf<BuildEventsListenerRegistry>().onTaskCompletion(serviceProvider)` (Talaiot.kt:102).

`TalaiotBuildService` (`library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/TalaiotBuildService.kt`) implements `OperationCompletionListener`, appending a `TaskLength` entry per finish event, and `AutoCloseable`: `close()` is the end-of-build hook. On close it computes configuration time and the configuration-cache-hit flag, conditionally resolves the git/buildId/jstat/jinfo providers, and calls `TalaiotPublisherImpl.publish(...)`, optionally on a fresh single-thread executor when `publishOnNewThread` is set (default false). `TalaiotPublisherImpl` (`publisher/TalaiotPublisherImpl.kt`) applies task filters (include/exclude regex on tasks and modules, min/max duration threshold — `filter/TaskFilterProcessor.kt`) and build-level publish gates (success flag, requested-task regex — `configuration/BuildFilterConfiguration.kt`), consolidates the jstat/jinfo output into per-process statistics, and fans the finished `ExecutionReport` out to every configured `Publisher`. There is no settings plugin, no use of the Flow API (`FlowAction`/`FlowProviders` do not appear anywhere in the codebase), and no server-side component.

## Data collected & how

**Per task** (`TalaiotBuildService.onFinish`, `entities/TaskLength.kt`): duration plus start/stop timestamps from `FinishEvent.result`, task name and full path from the event descriptor, a derived module (path minus the last segment; tasks of the root project are labeled `no_module`), the outcome state (`EXECUTED`, `UP_TO_DATE`, `FROM_CACHE`, `NO_SOURCE`, `SKIPPED`, `FAILED`) parsed by splitting `event.displayName` and inspecting the third token, the task class from the configuration-time dictionary (empty under isolated projects), and a `rootNode` flag marking whether the task name matches a requested task.

**Per build** (`entities/ExecutionReport.kt`, `metrics/SimpleMetrics.kt`): total, configuration, and execution durations; begin/end timestamps; success (defined as no `FAILED` task); requested tasks with abbreviation expansion (`util/TaskAbbreviationMatcher.kt`) and a `gradleSync` label when every requested task ends with `generateDebugSources`; root project name; a derived cache ratio (`FROM_CACHE` tasks over all tasks); a configuration-cache-hit boolean; an opt-in random-UUID build id (`generateBuildId`, off by default because of InfluxDB cardinality concerns); and an optional Gradle `buildInvocationId` obtained through the internal `BuildScanScopeIds` service.

**Environment and switches** (`metrics/GradleMetrics.kt`, `metrics/SimpleMetrics.kt`): OS name and version (via the internal `org.gradle.internal.os.OperatingSystem`), CPU count, Gradle max workers, `java.runtime.version`, locale (from the `user.language` system property), username (`user.name`), hostname (`InetAddress.getLocalHost()`), default charset, and Gradle version. Xms/Xmx/MaxPermSize are parsed from the `org.gradle.jvmargs` project property (`metrics/base/JvmArgsMetric.kt`) — the configured daemon arguments, not the live process's actual flags. Gradle switches (build cache, build scan, parallel, configure-on-demand, dry run, offline, rerun tasks, refresh dependencies) come from public `gradle.startParameter` getters; the configuration-cache switch is read by casting to the internal `StartParameterInternal` with a `NoSuchMethodError` fallback (GradleMetrics.kt:66-75).

**Git** (`Talaiot.kt:72-74`, `metrics/GitUserMetric.kt`): current branch via `git rev-parse --abbrev-ref HEAD` and git `user.name` via `git config --get user.name`, both executed through `CommandLineWithOutputValue` ValueSources from the author's external `io.github.cdsap:commandline-value-source` library. A code comment referencing issue #408 explains that the branch provider is created eagerly but resolved only at publish time, so a changed branch does not invalidate the configuration-cache entry.

**JVM daemon processes at end of build** (`publisher/TalaiotPublisherImpl.kt:63-70`): `jstat -gc` and `jinfo` are run against all `GradleDaemon` and `KotlinCompileDaemon` processes via ValueSources, and the output is consolidated into per-process heap/GC statistics and JVM-flag maps (process counts, multi-process detection, `gradleJvmArgs`/`kotlinJvmArgs`) by the external `io.github.cdsap:jdk-tools-parser` library.

**Custom metrics** (`configuration/MetricsConfiguration.kt`): user-defined build/task key-value pairs, arbitrary `Metric` implementations, and an experimental configuration-cache-compatible mechanism (`@ExperimentalMetricsApi`) where the DSL functions `initialProviderMetrics(...)` and `finalProviderMetrics(...)` register `Provider`-backed metrics evaluated at build-service creation and at publish time respectively.

## Outputs & integrations

The standard plugin's `TalaiotConfigurationProvider` wires up to eight publisher kinds, all implementing a one-method `Publisher` interface:

- **InfluxDB 1.x** (`library/plugins/influxdb/influxdb-publisher/.../InfluxDbPublisher.kt`): line-protocol batch points for a build measurement and per-task measurements, per-backend tag-versus-field selection through `TagFieldProvider`, automatic database and retention-policy creation, and configuration validation with a helpful error message. Uses influxdb-java 2.25.
- **InfluxDB 2.x** (`influxdb2-publisher/.../InfluxDb2Publisher.kt`): token/org/bucket writes via influxdb-client-java 6.12.0.
- **Elasticsearch** (`elastic-search-publisher/.../ElasticSearchPublisher.kt`): separate build and task indexes via the (deprecated) RestHighLevelClient 7.3.0.
- **Prometheus PushGateway** (`pushgateway-publisher/.../PushGatewayPublisher.kt`): build/task job pushes via simpleclient_pushgateway 0.16.0.
- **RethinkDB** (`rethinkdb-publisher/.../RethinkDbPublisher.kt`): table writes via rethinkdb-driver 2.3.3.
- **Local JSON** (`base-publisher/.../JsonPublisher.kt`): the full `ExecutionReport` serialized with Gson to `<root build dir>/reports/talaiot/json/data.json`.
- **Console** (`OutputPublisher.kt`): the slowest tasks rendered with proportional ASCII bars.
- **Hybrid** (`hybrid-publisher/.../HybridPublisher.kt`): routes build metrics and task metrics to two different backends; plus arbitrary user-supplied `CustomPublisher` instances.

The `docker/` image (InfluxDB 1.2.0 + Grafana 5.4.3, with provisioned dashboards `docker/grafana/dashboards/talaiot.json` and `taskCache.json`) and the gradle-profiler-based `scripts/populate.sh` form a demo analytics stack, not a product.

## Techniques worth borrowing for BuildHound

1. **The collector shape itself.** Talaiot validates BuildHound's spec §3.2 design with battle-tested code: a shared `BuildService` implementing `OperationCompletionListener`, registered with `BuildEventsListenerRegistry.onTaskCompletion`, with `close()` as the end-of-build trigger. Everything the service needs is captured into serializable `BuildServiceParameters` at configuration time, which is exactly what makes it configuration-cache-safe.

2. **Configuration-cache HIT detection without internal APIs.** `util/ConfigurationPhaseObserver.kt` is an elegant, directly reusable trick: a `ValueSource<Boolean>` reads a static `AtomicBoolean` that only configuration-phase code sets. On a configuration-cache hit the configuration phase never runs, so `obtain()` returns false — and configuration duration is reported as 0. The TestKit test `ConfigurationCacheHit.kt` proves the flag flips to true on the second run across three Gradle versions. This maps cleanly onto BuildHound's `HIT | MISS_STORED | ...` state field.

3. **Lazy ValueSources for side-effectful reads.** Git branch, build id, and jstat/jinfo probes are wrapped in `ValueSource` providers created at configuration time but resolved only at publish time, explicitly to avoid invalidating the configuration cache when values change (issue #408 comment in `Talaiot.kt`). BuildHound's EnvironmentCollector should adopt the same discipline.

4. **Task-type enrichment at configuration time.** Tooling-API finish events do not expose task classes, so Talaiot snapshots a taskPath-to-class dictionary from `taskGraph.allTasks` into the service parameters, stripping the `_Decorated` suffix — and degrades to an empty map under isolated projects, detected via the public `BuildFeatures` API with a Gradle-version gate. BuildHound's spec already calls for this exact pattern, including the degradation.

5. **End-of-build JVM probing.** The jstat/jinfo probe of `GradleDaemon` and `KotlinCompileDaemon` processes matches spec §3.6's ProcessProbe. The reusable pieces live in cdsap's standalone libraries `io.github.cdsap:commandline-value-source` (a ValueSource that shells out, with jStat/jInfo helpers) and `io.github.cdsap:jdk-tools-parser` (output parsing and per-process consolidation) — both worth evaluating for direct reuse.

6. **Test strategy.** Version-matrix TestKit tests asserting the actual emitted JSON report (build-id uniqueness, configuration-cache-hit flips, isolated-projects run), plus Testcontainers integration tests per storage backend, mirror spec §8. The cross-OS × multi-JDK sample-build CI jobs are a cheap, high-value compatibility canary.

7. **Modular publisher packaging.** One generic core (`Talaiot<T : TalaiotExtension>` + `PublisherConfigurationProvider`) instantiated by many thin plugin artifacts keeps backend clients off users' classpaths; the Hybrid publisher's build-versus-task routing and the `TagFieldProvider` tag/field mapping are useful references for keeping BuildHound's exporter/CI SPI modular. Talaiot's scar of shipping `generateBuildId = false` by default because raw TSDB writes cannot absorb the cardinality also supports BuildHound's decision to build a real ingest server rather than write to a TSDB directly.

## Limitations & pitfalls

- **Project plugin, not a settings plugin.** Talaiot is applied in `build.gradle(.kts)` and misses buildSrc, included builds, and early lifecycle — coverage BuildHound's spec (§3.1) explicitly requires from a settings plugin.
- **No server half.** There is no ingest API, auth, tenancy, retention, schema versioning, or dashboard; the "backend" is whatever TSDB the user operates. The Grafana/InfluxDB Docker image is a stale demo (InfluxDB 1.2.0, Grafana 5.4.3).
- **Fragile outcome parsing.** Task outcome is derived by string-splitting `event.displayName` (`"UP-TO-DATE"`, `"FROM-CACHE"`, etc.) instead of using typed `TaskFinishEvent`/`TaskSuccessResult`, and consequently there is no cacheable flag, no incremental flag, no execution reasons, and no failure details. BuildHound should use the typed event API.
- **Fire-and-forget publishing.** Publishers swallow all exceptions into logs (see `InfluxDbPublisher.publish`), there is no spool/retry/idempotency, and `publishOnNewThread` spawns an executor during `BuildService.close()` — i.e., during daemon shutdown — with no flush guarantee, risking silently dropped data.
- **Internal Gradle APIs.** `org.gradle.internal.extensions.core.serviceOf`, `StartParameterInternal.configurationCache`, `(gradle as GradleInternal).services[BuildScanScopeIds]`, and `org.gradle.internal.os.OperatingSystem` are all internal — exactly the portability hazard BuildHound's spec forbids for v1.
- **Scope gaps relative to BuildHound.** No test-result collection, no Kotlin build-report/compiler metrics (only daemon-level jinfo/jstat), no critical-path/parallel-utilization/avoided-time derivations (only a simple cache ratio), no artifact sizes, and no CI-provider detection or CI metadata.
- **Weakly typed data model.** `ExecutionReport` stores durations and timestamps as `String?`, is mutable and `java.io.Serializable`, and carries no schema version — a poor foundation for a versioned ingest contract, and an instructive contrast for BuildHound's typed `schemaVersion` payload.
- **Privacy.** Username, hostname, git branch, and git user are sent in plaintext with no pseudonymization or opt-in machinery.
- **Correlation off by default.** `generateBuildId` defaults to false (InfluxDB cardinality), so builds are not individually correlatable out of the box, and the alternative `buildInvocationId` relies on an internal build-scan API.
- **Docs drift and test hygiene.** The README advertises a nonexistent Timeline publisher and the `timelinePublisher` flag is dead config; the `MetricsConfiguration` KDoc omits `processMetrics` from the documented defaults; tests use deprecated kotlintest with fixed `Thread.sleep` waits; and `getModule()` labels root-project tasks `no_module`.

## Notable files

- `library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/Talaiot.kt` — core wiring: task-graph hook, task-class dictionary, BuildService registration with CC-safe ValueSource parameters, listener hookup, internal `serviceOf` usage, issue-#408 workaround.
- `library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/TalaiotBuildService.kt` — the collector: per-task capture with displayName parsing, `close()` publish trigger with optional background thread.
- `library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/util/ConfigurationPhaseObserver.kt` — internal-API-free configuration-cache-hit detection.
- `library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/publisher/TalaiotPublisherImpl.kt` — filtering, process-stats consolidation, publisher fan-out.
- `library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/entities/ExecutionReport.kt` — the full shipped data model.
- `library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/metrics/GradleMetrics.kt` — start-parameter metrics, internal `StartParameterInternal` cast, isolated-projects-aware requested-task resolution.
- `library/core/talaiot/src/main/kotlin/io/github/cdsap/talaiot/configuration/MetricsConfiguration.kt` — metric grouping/opt-out DSL and experimental provider-based metrics.
- `library/plugins/talaiot-standard/src/test/kotlin/io/github/cdsap/talaiot/ConfigurationCacheHit.kt` — TestKit matrix test for CC-hit behavior.
- `library/plugins/influxdb/influxdb-publisher/src/main/kotlin/io/github/cdsap/talaiot/publisher/influxdb/InfluxDbPublisher.kt` — representative publisher with swallow-all error handling.
- `library/core/talaiot/build.gradle.kts` — the two external cdsap helper dependencies (`commandline-value-source` 0.1.0, `jdk-tools-parser` 0.1.1).
- `.github/workflows/sample.yaml` — cross-OS sample builds with `--configuration-cache` on Gradle 8 and 9 sample projects.
