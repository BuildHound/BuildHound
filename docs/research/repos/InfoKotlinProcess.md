# InfoKotlinProcess

A tiny Gradle plugin (`io.github.cdsap.kotlinprocess`, v0.3.0, by Iñaki Villar / cdsap) that snapshots JVM memory and GC statistics of running Kotlin compile daemon processes at the end of a build and reports them either as Develocity Build Scan custom values or as an ASCII table in the console.

Research date: 2026-07-03. Source: shallow clone of https://github.com/cdsap/InfoKotlinProcess.

## Overview

InfoKotlinProcess answers one narrow question: "how much memory did my Kotlin compile daemons actually use during this build, and how much time did they spend in GC?" It does so with a single end-of-build snapshot rather than continuous sampling. When the Develocity plugin is present, the numbers land as six custom values per process in the Build Scan; otherwise a formatted table is printed to the console at the end of the build. The plugin is deliberately minimal — just under 200 lines of main-source Kotlin across six files — because all of the actual process discovery, `jstat`/`jinfo` execution, and output parsing is delegated to two external libraries by the same author: `io.github.cdsap:jdk-tools-parser` (parses tool output into `Process` models) and `io.github.cdsap:commandline-value-source` (wraps the JDK-tool invocations as Gradle `ValueSource` providers). What remains in this repository is the wiring: a `Plugin<Project>`, a `BuildService`, a Develocity branch, and two output renderers.

For BuildHound, this repository is prior art for exactly one spec component — the §3.6 process probe — and it is valuable chiefly because it demonstrates, with real functional tests, a configuration-cache-safe and isolated-projects-safe recipe for shelling out to JDK tools at the end of a build.

## Status & maturity

The last commit is `fa45644` ("Merge pull request #78 from cdsap/renovate/com.gradle-develocity-gradle-plugin-4.x", 2026-07-01 22:09:10 +0000), two days before this research. Recent activity consists of Renovate dependency bumps; `.github/renovate.json` enables automerge for Maven dependencies, so the repository stays green with little manual attention. The clone is shallow, which prevents judging historical velocity, but the picture is consistent with a mature-for-its-scope side project that is feature-frozen at v0.3.0 while being kept up to date. The Gradle wrapper is at 9.3.1 and the build uses a JVM toolchain of 17.

Testing is the strongest signal of quality. There are no unit tests, but the TestKit functional suite is genuinely good:

- `src/test/kotlin/io/github/cdsap/kotlinprocess/InfoKotlinProcessPluginTest.kt` runs against Gradle 8.7, 8.12.1 and 8.14.1, asserting the console table appears, and exercises `kotlin.daemon.jvmargs` variants and GC configurations (asserting "G1", "UseParallelGC" and "Z"/ZGC appear in the output). It also asserts configuration-cache "entry stored" on the first build and "entry reused." on the second.
- `src/test/kotlin/io/github/cdsap/kotlinprocess/InfoKotlinProcessNoDVTest.kt` asserts config-cache store/reuse and isolated-projects (`-Dorg.gradle.unsafe.isolated-projects=true`) store/reuse on Gradle 8.14.2.
- `src/test/kotlin/io/github/cdsap/kotlinprocess/InfoKotlinProcessPluginWtihBuildScanTest.kt` (note the "Wtih" typo) covers the Develocity path on Gradle 8.14.2 and 9.2.1 — but these tests skip via `Assume.assumeTrue` unless `GE_URL`/`GE_API_KEY` environment variables are set. Consequently, Gradle 9.x coverage exists only in the secret-gated tests; the unconditional suite tops out at Gradle 8.14.2.

CI (`.github/workflows/build.yaml`) runs a ktlint job plus two test jobs: one over {ubuntu, macos} × JDK {17, 21} (temurin), and an integration job over {ubuntu, macos} × JDK {17, 19, 21} × vendors {temurin, zulu, liberica}. Notably, CI dogfoods process telemetry by running the author's `cdsap/build-process-watcher@v0.6.1` GitHub Action with BigQuery export enabled.

Documentation is the weak spot. The README claims "Requirements: Gradle 7.5" while tests target 8.7–9.2.1; its sample console output lacks the GC Type column that the current `ConsoleOutput.kt` prints; and its legacy-application snippet references `io.github.cdsap:infokotlinprocess` while the Maven publication in `build.gradle.kts` uses artifactId `kotlinprocess`. There is no CHANGELOG. Naming sloppiness extends into the tests: the first test in `InfoKotlinProcessNoDVTest.kt` is called `testPluginIsCompatibleWithConfigurationCacheWithDevelocity` even though it exercises the no-Develocity path. Licensing is consistent (MIT in both `LICENSE.md` and the POM).

## Architecture

The plugin is a single Gradle module with six main source files. The entry point, `src/main/kotlin/io/github/cdsap/kotlinprocess/InfoKotlinProcessPlugin.kt`, is a `Plugin<Project>` applied in the root `build.gradle(.kts)`. Its `apply` defers all work into a `target.gradle.rootProject {}` callback, inside which it probes for Develocity via `Class.forName("com.gradle.develocity.agent.gradle.DevelocityConfiguration")` and branches:

**Path A — Develocity present.** `src/main/kotlin/io/github/cdsap/kotlinprocess/DevelocityWrapperConfiguration.kt` looks up the `DevelocityConfiguration` extension with `findByType`. If found, it creates the `jStat`/`jInfo` `ValueSource` providers at configuration time and registers a `buildScan.buildFinished {}` callback; only inside that callback are the providers resolved with `.get()`, consolidated via `ConsolidateProcesses().consolidate(jstat, jinfo, TypeProcess.Kotlin)`, and pushed to the Build Scan by `src/main/kotlin/io/github/cdsap/kotlinprocess/output/DevelocityValues.kt`.

**Path B — no Develocity.** The plugin registers a shared build service, `src/main/kotlin/io/github/cdsap/kotlinprocess/InfoKotlinProcessBuildService.kt`, via `gradle.sharedServices.registerIfAbsent`, storing the `jInfo`/`jStat` providers in the service parameters. The service implements `BuildService`, `AutoCloseable` and `OperationCompletionListener`; the `onFinish` implementation is empty, and the listener exists purely so that `project.serviceOf<BuildEventsListenerRegistry>().onTaskCompletion(service)` (InfoKotlinProcessPlugin.kt:37) pins the service alive until the end of the build. All real work happens in `close()`: resolve the two providers, consolidate, and print a picnic-rendered table via `src/main/kotlin/io/github/cdsap/kotlinprocess/output/ConsoleOutput.kt` (nothing is printed when no process is found — InfoKotlinProcessBuildService.kt:28).

`src/main/kotlin/io/github/cdsap/kotlinprocess/Constants.kt` hardcodes the single probed main-class name, `KotlinCompileDaemon`. Deferring provider `.get()` calls into `close()`/`buildFinished` — combined with the fact that the process probing itself lives behind `ValueSource` implementations in the external library — is what makes the design configuration-cache and isolated-projects compatible.

Two smaller design notes: the plugin uses `org.gradle.kotlin.dsl.support.serviceOf` (an internal-ish Kotlin DSL helper) to obtain `BuildEventsListenerRegistry` on a `Project` plugin, and the Develocity dependency is `compileOnly("com.gradle:develocity-gradle-plugin:4.5.0")` so the plugin works whether or not Develocity is on the build classpath.

## Data collected & how

Per Kotlin compile daemon JVM (matched by main class name `KotlinCompileDaemon`), the plugin records: PID, max heap (GB), heap usage at the end of the build (GB, obtained via `jstat`), heap capacity (GB), GC time (minutes), GC type (e.g. G1, UseParallelGC, Z — asserted in tests), and process uptime (minutes). Nothing else is collected: no task data, no build duration, no Gradle daemon or worker processes, and no time series — it is one snapshot taken at build end.

The Gradle APIs involved are:

- `BuildService` + `BuildServiceParameters` with `AutoCloseable.close()` as the end-of-build hook.
- `BuildEventsListenerRegistry.onTaskCompletion` with a no-op `OperationCompletionListener` used solely for lifecycle pinning, not event collection.
- `ValueSource`-backed `Provider<String>`s from `io.github.cdsap:commandline-value-source` (`project.jInfo(...)` / `project.jStat(...)` extension functions) that shell out to JDK tools in a config-cache-safe way. The execution and parsing logic is not in this repository.
- `Gradle.rootProject {}` deferred configuration, `Class.forName` reflection for optional-classpath detection, and the Develocity plugin API (`DevelocityConfiguration`, `buildScan.buildFinished`, `buildScan.value(k, v)`).
- Gradle TestKit (`GradleRunner.withPluginClasspath().withGradleVersion(...)`) for the cross-version functional tests.

## Outputs & integrations

There are exactly two output surfaces and no others — no file, JSON, or network output of any kind from the plugin itself:

1. **Develocity Build Scan custom values**, six per process: `Kotlin-Process-<pid>-max`, `-usage`, `-capacity`, `-uptime`, `-gcTime`, `-gcType` (`src/main/kotlin/io/github/cdsap/kotlinprocess/output/DevelocityValues.kt`). Values are formatted strings ("1.2 GB", "0.5 minutes"), which makes trend analysis over custom values awkward.
2. **Console ASCII table** (picnic 0.7.0) printed at build end when Develocity is absent, with columns PID / Max / Usage / Capacity / GC Time / GC Type / Uptime (`src/main/kotlin/io/github/cdsap/kotlinprocess/output/ConsoleOutput.kt`).

The plugin is published to the Gradle Plugin Portal (via `com.gradle.plugin-publish` 1.0.0-rc-1) and to Sonatype (snapshot and staging repositories declared in `build.gradle.kts`).

## Techniques worth borrowing for BuildHound

1. **The CC-safe external-process probe recipe** (spec §3.6). Wrap JDK-tool invocations (`jstat`, `jinfo`) as `ValueSource`s, store the resulting lazy `Provider<String>`s in `BuildService` parameters at configuration time, and call `.get()` only in `close()` at execution end. This is the exact pattern BuildHound's ProcessProbe needs, proven here against config cache and isolated-projects by real tests.
2. **The store/reuse functional-test template.** `InfoKotlinProcessNoDVTest.kt` runs the same build twice and asserts "Configuration cache entry stored" then "Configuration cache entry reused." — a compact, copyable template for BuildHound's compatibility contract (§3.1 and §8), including the isolated-projects variant.
3. **Service-lifecycle pinning idiom.** Registering a no-op `OperationCompletionListener` via `BuildEventsListenerRegistry` purely to keep an `AutoCloseable` `BuildService` alive to build end. This is the pre-Flow-API idiom; BuildHound's spec already chooses `FlowAction`/`FlowProviders.buildWorkResult` as the modern replacement for what `close()` does here, but the pattern matters as a fallback for older Gradle versions.
4. **Optional-integration pattern.** `compileOnly` dependency plus a `Class.forName` probe lets one artifact work with or without Develocity on the classpath — directly applicable to BuildHound's optional integrations and graceful-degradation requirements.
5. **Cross-matrix functional testing and CI dogfooding.** TestKit across five Gradle versions, JDKs 17/19/21, three vendors and two OSes; plus CI that monitors its own build processes with `cdsap/build-process-watcher` exporting to BigQuery — a useful reference for BuildHound's own benchmark/telemetry pipelines (§7).
6. **The upstream libraries.** `io.github.cdsap:commandline-value-source` and `io.github.cdsap:jdk-tools-parser` contain the actual `jstat`/`jinfo` execution and parsing that BuildHound's ProcessProbe needs. Evaluate reusing or forking those libraries rather than this thin wrapper.

## Limitations & pitfalls

- **Scope: Kotlin daemon only.** `Constants.kt` hardcodes one main-class name; Gradle daemons and worker JVMs (which BuildHound's §3.6 requires via `GradleDaemon`/`GradleWorkerMain`) are not covered.
- **Single snapshot, coarse units.** One measurement at build end; no time-series sampling, no RSS from `/proc`, no configured-vs-used `-Xmx` delta. Values are stringly-typed GB/minutes, losing precision.
- **No structured output.** Console text or Build Scan custom-value strings only — nothing machine-readable, no upload path.
- **Silent no-output path.** If Develocity classes are on the classpath but the `DevelocityConfiguration` extension is not registered (plugin on classpath but not applied), `DevelocityWrapperConfiguration.configureProjectWithDevelocity` falls through and the plugin produces no output at all — there is no console fallback in that branch. Similarly, if the Kotlin daemon exits before build end or compilation runs in-process, the snapshot is simply empty.
- **Measurement logic is external.** This repo alone does not show how `jps`/`jstat`/`jinfo` are executed or parsed; it also depends on JDK tools being present on the host.
- **Design choices BuildHound should not copy.** A `Project` plugin applied in `build.gradle` with `gradle.rootProject {}` deferral and `Class.forName` sniffing is messier than the settings-plugin design BuildHound specifies; `org.gradle.kotlin.dsl.support.serviceOf` is internal-ish; and the Develocity path resolves providers inside `buildScan.buildFinished`, a Develocity-specific hook.
- **Test-coverage gaps.** No unit tests; the Develocity integration tests silently skip without `GE_URL`/`GE_API_KEY` secrets, so Gradle 9.x is only exercised when those secrets are available. The README is stale (Gradle 7.5 requirement claim, outdated sample output, inconsistent legacy artifact coordinates).
