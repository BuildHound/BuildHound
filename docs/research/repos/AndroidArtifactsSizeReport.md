# AndroidArtifactsSizeReport

A single-purpose Gradle project plugin that measures the sizes of final Android artifacts (APK, AAB, AAR) per build variant and publishes them as Develocity Build Scan custom values.

Research date: 2026-07-03. Source: shallow clone of https://github.com/cdsap/AndroidArtifactsSizeReport.

## Overview

AndroidArtifactsSizeReport (root Gradle project name `BuildScanArtifactSizeReporter`; published plugin id `io.github.cdsap.android-artifacts-size-report`, group `io.github.cdsap`, version 0.2.1) is a small plugin by Iñaki Villar (cdsap), a well-known author of Gradle build-performance tooling. When applied to an Android application or library module alongside the Develocity Gradle plugin, it registers one measuring task per variant that runs after the final artifact is produced, records the artifact's byte size, and — at the end of the build — attaches each size as a Build Scan custom value whose key is the artifact file name plus `.size` (for example `app-debug.apk.size` → `7000096`). It is deliberately not a telemetry system: there is no server, no dashboard, no local report, and no output of its own. All downstream analysis is delegated to Develocity — the README documents querying the values through the Develocity REST API with curl and jq, and building time-series visualizations with Trino SQL in the Develocity Reporting Kit (DRK).

The repository doubles as its own fixture: the root build contains a demo Android app (`app/`) and library (`mylibrary/`) that apply the plugin via a composite build (`pluginManagement { includeBuild("plugin") }` in `settings.gradle.kts`), and CI exercises the whole pipeline against Gradle's public Develocity instance.

## Status & maturity

The last commit on the shallow clone is `506eeef`, dated 2025-11-26 ("Merge pull request #13 from cdsap/prepare_0_2_1"), roughly seven months before this research date, so the project was actively maintained through late 2025. It is MIT-licensed (copyright 2025 Iñaki Villar) and published to the Gradle Plugin Portal at 0.2.1.

The codebase is tiny but polished: seven Kotlin source files totalling 191 lines (including imports and blanks) under `plugin/build-scan-artifact-size-reporter/src/main/kotlin/`, with ktlint enforced across the build. Testing takes two complementary forms:

- **One TestKit end-to-end test**, `plugin/build-scan-artifact-size-reporter/src/test/kotlin/io/github/cdsap/agp/artifacts/ProjectIsolationE2ETest.kt`, parameterized over Develocity plugin versions 3.19.2, 4.1.0 and 4.2.2. It generates a synthetic Android app project (AGP 8.13.1, Kotlin 2.2.20), runs `:app:assembleDebug` twice on Gradle 9.2.1 with `-Dorg.gradle.unsafe.isolated-projects=true`, and asserts "Configuration cache entry stored" on the first build and "Reusing configuration cache." on the second — direct evidence of configuration-cache and isolated-projects compatibility.
- **Live end-to-end CI verification** in `.github/workflows/build.yaml` (JDK 21, Zulu): two jobs build the demo modules, publish a real build scan to `ge.solutions-team.gradle.com`, read the scan id from `temp_build_scan_id.txt`, query the Develocity REST API endpoint `/api/builds/{id}/gradle-attributes`, and assert with jq that every custom value matching `*.apk.size` or `*.aar.size` exists and is greater than 0. The second job matrixes this over Develocity plugin versions 4.0, 4.1 and 4.2.2 by sed-patching `settings.gradle.kts`. (Note the assertion regex does not cover `.aab.size`; `./gradlew build` does not produce bundles.)

Weaknesses: there are no unit tests of the task logic, no configuration DSL, and minor code roughness (a redundant `substringAfterLast("/")` applied to `File.name` in `SizeFileTask.kt`, and `/`-based path splitting on a raw path string in `SizeApkTask.kt` that is likely to misbehave on Windows). Documentation is a single README with usage, a short implementation note, query recipes, and screenshots; the `gradlePlugin` metadata still points at the repository's old name, `BuildScanArtifactSizeReporter`.

## Architecture

The repository is a two-level composite build. The root `settings.gradle.kts` includes the plugin build via `pluginManagement { includeBuild("plugin") }`, applies `com.gradle.develocity` version 4.0 pointed at `ge.solutions-team.gradle.com`, and includes the demo modules `:app` and `:mylibrary`, both of which apply the plugin (`app/build.gradle.kts`, `mylibrary/build.gradle.kts`). The root `build.gradle.kts` adds a `buildScanPublished { }` hook that writes the scan id to `temp_build_scan_id.txt`, which is the glue enabling the CI verification described above. The plugin itself is a single-module Gradle build at `plugin/build-scan-artifact-size-reporter`, built with `kotlin-dsl` + `java-gradle-plugin` + `com.gradle.plugin-publish`, Kotlin 2.1.0, and with AGP 8.8.0 and the Develocity Gradle plugin 3.19.1 as `compileOnly` dependencies.

The plugin flow, in four steps (all paths under `plugin/build-scan-artifact-size-reporter/src/main/kotlin/io/github/cdsap/agp/artifacts/`):

1. **`AndroidArtifactsInfoPlugin.kt`** — the `Plugin<Project>` entry point. It first probes `Class.forName("com.gradle.develocity.agent.gradle.DevelocityConfiguration")` and silently no-ops if the Develocity plugin classes are absent. Otherwise it reacts to `plugins.withType(AppPlugin)` and `plugins.withType(LibraryPlugin)` and unconditionally registers the end-of-build hook.
2. **`AndroidApplicationExtension.kt` / `AndroidLibraryExtension.kt`** — via `ApplicationAndroidComponentsExtension.onVariants` / `LibraryAndroidComponentsExtension.onVariants`, register per-variant tasks: `sizeApk<Variant>` (a `SizeApkTask`) and `sizeBundle<Variant>` (a `SizeFileTask`) for applications, and `sizeAar<Variant>` (a `SizeFileTask`) for libraries. Each is wired with the AGP Artifacts API listen pattern — `variant.artifacts.use(task).wiredWith { it.input }.toListenTo(SingleArtifact.APK | BUNDLE | AAR)` — so the task runs after the final artifact is produced without inserting itself into the artifact-transformation chain.
3. **`tasks/SizeApkTask.kt`** uses `BuiltArtifactsLoader` (obtained from `variant.artifacts.getBuiltArtifactsLoader()`) to enumerate all APK outputs, which handles splits and multi-output variants, and writes one `<fileName>.size` text file containing `File.length()` per artifact into `build/outputs/size/apk/<variant>/`. **`tasks/SizeFileTask.kt`** does the same for the single AAB or AAR file (output subdirectories `aab/` and `aar/`; the shared root constant lives in `Output.kt`). Inputs are declared with `@InputDirectory`/`@InputFile` + `@PathSensitive(RELATIVE)`, outputs with `@OutputDirectory`, and the loader as `@Internal`.
4. **`ProjectExtension.kt`** (`onBuildFinished`) — registers a Develocity `buildScan.buildFinished { }` callback that walks `build/outputs/size` as a file tree, calls `develocityConfiguration.buildScan.value(file.name, file.readText())` for each size file, then deletes the directory recursively. The callback captures only the `buildDirectory` `DirectoryProperty` and the `DevelocityConfiguration` extension reference — never the `Project` — which is what keeps it configuration-cache- and isolated-projects-safe.

## Data collected & how

The plugin collects exactly one kind of data: final artifact sizes in bytes, obtained by calling `File.length()` on the finished files.

- APK size per variant and per output/split, enumerated via `BuiltArtifactsLoader` over `SingleArtifact.APK` (`SizeApkTask.kt`).
- AAB (bundle) size per variant via `SingleArtifact.BUNDLE` (`SizeFileTask.kt`).
- AAR size per variant for library modules via `SingleArtifact.AAR` (`SizeFileTask.kt`).

Nothing else — no task timings, no cache data, no test results, no environment capture. The Gradle/AGP APIs involved are the AGP Variant API (`onVariants`), the AGP Artifacts API in its listen mode (`use().wiredWith().toListenTo()`), `BuiltArtifactsLoader`, lazy `tasks.register` with managed properties, the Develocity plugin's `buildScan.buildFinished` and `buildScan.value` APIs, and the `buildScanPublished` hook in the demo root build. Notably absent are `BuildService`, `OperationCompletionListener`, the Flow API, the Tooling API, and any process-level probing — the end-of-build work rides entirely on Develocity's proprietary callback.

## Outputs & integrations

The sole output is Develocity Build Scan custom values, one per artifact file, with key `<artifactFileName>.size` (e.g. `app-debug.apk.size`) and the decimal byte count as a string value. Intermediate `.size` text files under `build/outputs/size/{apk|aab|aar}/<variant>/` are transient: they are read and then recursively deleted in the build-finished callback. The plugin performs no HTTP upload of its own, produces no local report, and prints nothing to the console.

Downstream consumption is documented in the README: fetching the values for the last N builds via the Develocity API (`/api/builds?models=gradle-attributes` with a jq filter on names ending in `.apk.size`), and two DRK Trino-SQL recipes — a time series of `app-debug.apk.size` over build start times, and a per-module release-AAR size report that reverse-engineers the module name out of the key string with `SUBSTR`/`POSITION`/`REGEXP_REPLACE`.

## Techniques worth borrowing for BuildHound

This repository is the reference implementation for exactly one field of BuildHound's payload — spec §4's `"artifacts": {"apk": [{variant, sizeBytes, type}]}` — and its collection mechanics can be adopted wholesale.

1. **The `toListenTo()` artifact-listening pattern.** Attaching a reporting task to the final artifact (post-signing, post-merging) for every variant via `variant.artifacts.use(task).wiredWith { it.input }.toListenTo(SingleArtifact.APK|BUNDLE|AAR)` observes the output without modifying the artifact pipeline. This is the canonical, configuration-cache-safe way to measure Android outputs, and this repo's E2E test is concrete evidence it works under Gradle 9.2.1 with isolated projects — directly relevant to BuildHound's §3.1 compatibility contract.
2. **`BuiltArtifactsLoader` for APKs.** APK variants can produce multiple outputs (ABI/density splits); loading the built-artifacts metadata rather than globbing the directory is the correct enumeration approach.
3. **File-based handoff between task execution and end-of-build reporting.** Tasks write tiny size files into a well-known build directory; an end-of-build hook reads and publishes them. This decouples collection from the sink and maps cleanly onto BuildHound's architecture: replace the Develocity `buildScan.buildFinished` sink with the §3.2 `FlowAction` Finalizer reading the same directory, and emit structured `{variant, module, type, sizeBytes}` records instead of filename-encoded keys — fixing this repo's biggest data-model weakness.
4. **CC-safe callback capture.** The build-finished closure captures a `DirectoryProperty` and an extension reference, never the `Project` — the discipline BuildHound's Finalizer and collectors must follow everywhere.
5. **Graceful optional integration.** The `Class.forName` probe combined with `compileOnly` dependencies lets the plugin no-op harmlessly when its host integration is absent. BuildHound can use the same pattern for optional AGP/KGP-dependent collectors inside a plugin that must apply cleanly to non-Android builds.
6. **Live end-to-end CI verification.** Publishing real telemetry from CI, capturing the build id via a publish hook, then polling the query API and asserting on the ingested values tests the entire publish path rather than the plugin in isolation, matrixed across integration versions. This is a strong template for BuildHound's own E2E tests (§8): build a fixture with the plugin, POST to a real (Testcontainers-hosted) BuildHound server, then assert via `GET /v1/...`.
7. **The README's query recipes as requirements input.** The curl/jq and DRK SQL examples illustrate exactly the artifact-size trend and per-module queries that BuildHound's regression engine (§5, PR-vs-baseline APK-size delta) and dashboard should answer natively instead of forcing users through SQL string surgery.

One structural note: this plugin applies per-project (in each module's `build.gradle.kts`), whereas BuildHound is a settings plugin. BuildHound will need to react to AGP from its settings-plugin context (e.g. via `gradle.lifecycle.beforeProject` or per-project plugin application) or ship a small companion project plugin for this collector.

## Limitations & pitfalls

- **Hard-coupled to Develocity as the only sink.** Without the Develocity plugin on the classpath the entire plugin does nothing (`AndroidArtifactsInfoPlugin.kt` guard); there is no standalone or local output mode.
- **Sizes only.** No artifact composition breakdown (dex/resources/native libs), no download-size estimate, no baseline diffing, no budgets or alerts — analysis is delegated entirely to Develocity API consumers.
- **Proprietary end-of-build hook.** The `buildScan.buildFinished` callback belongs to the Develocity plugin API, so the end-of-build pattern is not directly portable to a Develocity-free plugin; BuildHound's equivalent is Gradle's Flow API.
- **Deletes its own task outputs.** The build-finished callback recursively deletes `build/outputs/size` (`ProjectExtension.kt`), and each task additionally wipes its output directory at the start of execution — so the size tasks can never be `UP-TO-DATE`, and outputs mutate outside task execution. BuildHound should not inherit this.
- **Windows-unsafe key derivation.** `SizeApkTask.kt` line 39 derives the file name with `artifact.outputFile.substringAfterLast("/")` on a raw path string; with backslash-separated Windows paths the whole path would likely become the custom-value key. `SizeFileTask.kt` line 28 carries a redundant (harmless) `substringAfterLast("/")` on `File.name`. Neither path is covered by tests.
- **Identity encoded in file names.** Variant, module, and artifact type must be reverse-engineered from key strings by regex (see the README's `REGEXP_REPLACE` gymnastics); there are no structured fields. BuildHound should emit structured records from the start.
- **No configuration surface.** There is no extension DSL — no variant filtering, no key customization, no enable/disable switch.
- **Thin automated coverage.** A single E2E test; task logic and Windows behavior are untested; both the TestKit fixture and CI hardcode Gradle's public Develocity server (`ge.solutions-team.gradle.com`), so forks cannot run the verification without changes and secrets.
