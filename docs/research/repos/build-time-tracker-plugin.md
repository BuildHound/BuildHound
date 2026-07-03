# build-time-tracker-plugin

A 2014-era Gradle plugin that records per-task wall-clock execution times during a build and reports them locally — as a console bar chart, a CSV file, and cumulative daily/total build-time statistics — so developers can see how much time they spend waiting on Gradle.

Research date: 2026-07-03. Source: shallow clone of https://github.com/cdsap/build-time-tracker-plugin.

## Overview

This repository is a fork sitting on cdsap's GitHub account, but everything inspectable in the clone is Pascal Hartig (passy)'s original project. The single available commit (`6ea6f64`, 2015-10-29, "Merge pull request #54 from passy/gradle-28") is authored by Pascal Hartig, the POM block in `build.gradle` names passy as the sole developer, and all SCM URLs point at `github.com/passy/build-time-tracker-plugin`. No cdsap-authored change is visible; because the clone is shallow (one commit), byte-level identity with upstream cannot be proven, but there is no evidence of divergence. Its practical significance for BuildHound is provenance: this is the well-known early build-time plugin that preceded cdsap's later Talaiot, which is the more directly relevant ancestor to study.

The plugin itself is deliberately modest. It hooks Gradle's execution-phase listeners, times each task with a wall clock, and at the end of the build fans the collected timings out to one or more configured "reporters". Everything happens in-process and locally; there is no server, no upload, and no dashboard beyond an optional offline R Markdown script. The value today is archaeological — it shows the minimal viable shape of build-time telemetry and, in its listener-and-single-clock design, a catalogue of the exact problems modern Gradle APIs were built to fix.

## Status & maturity

The project is abandoned as a historical artifact: the last commit is from 2015-10-29, roughly 10.7 years before this research date. It targets the Gradle 2.8 wrapper (`gradle/wrapper/gradle-wrapper.properties`), publishes version 0.5.0 via the legacy `maven` and `nexus` plugins, and depends on two internal Gradle classes that were deleted from Gradle long ago. It is completely non-functional on modern Gradle and should be treated as reading material, not a dependency.

For its era, however, it was a well-kept project. The main source is small and clean: 12 files totalling 576 lines under `src/main/groovy/net/rdrei/android/buildtimetracker/`, all Groovy except one deliberate Java class (`reporters/MemoryUtil.java`, whose comment explains the JMX cast "doesn't work in Groovy"). Test discipline is real: 1,149 lines of JUnit 4 tests across seven files, using `groovy.mock.interceptor.MockFor` and Gradle's `ProjectBuilder`, including a 521-line `CSVReporterTest.groovy`. Documentation includes a thorough `README.markdown` (with an honest note that configuration time is not measured), a `CHANGELOG.md`, and a `report.Rmd` analysis script. CI ran on Travis with a JDK matrix of oraclejdk8, oraclejdk7, openjdk7, and openjdk6 (`.travis.yml`). License is Apache 2.0.

## Architecture

This is a single-module, project-level plugin. The id `build-time-tracker` maps to `BuildTimeTrackerPlugin` via `src/main/resources/META-INF/gradle-plugins/build-time-tracker.properties`.

The entry point is `src/main/groovy/net/rdrei/android/buildtimetracker/BuildTimeTracker.groovy`, which contains three classes. `BuildTimeTrackerPlugin.apply()` creates a `buildtimetracker` extension, attaches a `NamedDomainObjectContainer<ReporterExtension>` to it as a nested `reporters` extension via `project.container(...)`, and registers a `TimingRecorder` with `project.gradle.addBuildListener(...)`. Reporter options are captured stringly-typed: `ReporterExtension` implements Groovy `methodMissing` so that any `key value` line inside a reporter block becomes `options[key] = value.toString()` — the code itself carries the comment "I'm feeling really, really naughty." The plugin holds an instance-level map `REPORTERS = [summary:, csv:, csvSummary:]` from reporter names to classes; `getReporters()` instantiates a reporter for each configured block by name and silently drops names it does not recognize.

`TimingRecorder.groovy` does all collection. It extends `BuildAndTaskExecutionListenerAdapter.groovy` (a no-op combined `BuildListener` + `TaskExecutionListener`), starts a fresh `org.gradle.util.Clock` in `beforeExecute`, and in `afterExecute` appends a `Timing(ms, path, success, didWork, skipped)` to a plain `List`. At `buildFinished` it runs every configured reporter over the timings and then passes each the `BuildResult`.

Reporters share `reporters/AbstractBuildTimeTrackerReporter.groovy` (an options map, a Gradle `Logger`, a `getOption(name, default)` helper, and an overridable `onBuildResult`), with a typed `ReporterConfigurationError.groovy` for misconfiguration. The whole pipeline is strictly in-process, end-of-build, local-output-only.

## Data collected & how

Per task, the plugin records the full task path, wall-clock duration in milliseconds, a success flag (`TaskState.getFailure() == null`), the `didWork` flag, the `skipped` flag, and an execution-order index (assigned at CSV-write time). Timing uses the internal `org.gradle.util.Clock`, reset in `beforeExecute` and read in `afterExecute` (`TimingRecorder.groovy`). Per build, `reporters/CSVReporter.groovy` takes a single epoch timestamp from the internal `org.gradle.internal.TrueTimeProvider` and formats a UTC date string (`yyyy-MM-dd'T'HH:mm:ss,SSS'Z'` — ISO-like, with a comma before milliseconds). Overall build success or failure comes from the `BuildResult` in `buildFinished`.

Environment data is collected only for the CSV output, once per build, in `reporters/SysInfo.groovy`: an OS identifier joined from the `os.name`/`os.version`/`os.arch` system properties; a CPU model string obtained by executing `sysctl -n machdep.cpu.brand_string` on macOS or parsing the `model name` line of `/proc/cpuinfo` on Linux (empty string on other platforms); and total physical RAM via a `com.sun.management.OperatingSystemMXBean` JMX call in `reporters/MemoryUtil.java`. A further non-Gradle probe lives in `reporters/TerminalInfo.groovy`, which detects terminal width from the `COLUMNS` environment variable or, when `TERM` is set, a `tput cols` subprocess, falling back to a caller-supplied default (80).

Notably absent: configuration time (explicitly acknowledged in the README), cache outcomes (no `UP_TO_DATE`/`FROM_CACHE` distinction — the plugin predates the build cache), git or CI context, user/host identity, test results, and any in-build memory or CPU sampling.

## Outputs & integrations

There are three built-in reporters and one offline script; there are no network paths, backends, or third-party integrations of any kind.

The `summary` reporter (`reporters/SummaryReporter.groovy`) prints an end-of-build horizontal bar chart: one line per task above a threshold (default 50 ms), with bar length proportional to the longest task's time, the task's percentage of total build time, and a formatted duration. Options cover `barstyle` (unicode/ascii/none), `ordered` (ascending sort by duration, default false), `threshold`, and `successOutput` (default true), which re-echoes a `== BUILD SUCCESSFUL ==` or `== BUILD FAILED ==` line after the chart so a long summary doesn't hide the outcome. Task names are ellipsized with a middle `…` to fit the computed bar width, which is derived from the measured terminal width. The bar logic is credited in a source comment to sindresorhus/time-grunt.

The `csv` reporter writes rows via opencsv with columns `timestamp, order, task, success, did_work, skipped, ms, date, cpu, memory, os`, with `append` and `header` options so a single CSV can accumulate history across many builds.

The `csvSummary` reporter (`reporters/CSVSummaryReporter.groovy`) re-reads such an accumulated CSV at build end, groups rows by build timestamp, and prints "Build time today" (builds since local midnight, computed with joda-time in `reporters/DateUtils.groovy`) and an all-time total annotated with prettytime ("measured since X ago"). It validates its `csv` option and throws `ReporterConfigurationError` if missing or invalid, but does not guard against header rows — the README instructs `append true` / `header false` for the shared file.

Finally, `report.Rmd` is an R Markdown script (ggplot2/plyr) that plots build history and build-time distributions and lists the slowest tasks from the CSV. It is the project's only "dashboard", and it is stale relative to the shipped schema: it names only the original seven columns (`timestamp` through `milliseconds`) and predates the `date`/`cpu`/`memory`/`os` columns added in v0.3.0.

## Techniques worth borrowing for BuildHound

Zero code here is portable — every collection API used is precisely what the BuildHound spec (docs/build-telemetry-spec.md §3.2) deliberately replaces with `BuildEventsListenerRegistry` + `BuildService` and a `FlowAction` finalizer. The value is conceptual.

First, the end-of-build fan-out to pluggable named reporters — a name-to-class map fed by a `NamedDomainObjectContainer` DSL, every reporter receiving the same `List<Timing>` at `buildFinished` — is the primitive ancestor of Talaiot's publishers and validates BuildHound's Finalizer fanning out to the HTML artifact and uploader. Second, the cheap environment fingerprint (OS/CPU/RAM gathered once per build from system properties, `/proc`, `sysctl`, and JMX, then denormalized onto every CSV row) maps directly onto what EnvironmentCollector should gather with ValueSources. Third, the UX ideas are cheap and effective: a threshold-filtered relative bar chart of tasks, and "build time today" / all-time-total aggregates, both good candidates for the standalone HTML artifact and dashboard. Fourth, its minimal CSV schema (timestamp, order, task, success, did_work, skipped, ms plus environment) is a useful sanity floor for BuildHound's far richer payload. Fifth, the testing pattern — reporters take `(Map options, Logger)` so tests inject a mock logger and assert on emitted lines — is a portable idea for testing BuildHound's console/report output. And as a negative example, the single shared clock is a compact case study in why per-event timestamps from `TaskFinishEvent` beat listener-side timing under parallelism.

## Limitations & pitfalls

The plugin is dead on modern Gradle. It imports the internal classes `org.gradle.util.Clock` and `org.gradle.internal.TrueTimeProvider`, both long deleted, and its entire collection path rests on `gradle.addBuildListener` with `BuildListener`/`TaskExecutionListener`, which is removed or banned under the configuration cache in Gradle 8.x+.

It is not thread-safe: `TimingRecorder` keeps one shared `Clock` field that is overwritten in every `beforeExecute`, plus an unsynchronized `ArrayList`, so with `--parallel` concurrent tasks clobber each other's start times and race on the list. It has no cache awareness — the outcome model is only success/didWork/skipped — and it measures the execution phase only, leaving configuration time invisible (acknowledged in the README). It is local-only, with no upload, aggregation service, CI or git context, or identity handling.

The environment probes are platform-fragile: CPU detection covers only macOS and Linux, and terminal-width detection via `tput` fails in daemon mode because `TERM` is unset (noted in a comment in `reporters/TerminalInfo.groovy`). Typing is weak throughout — every reporter option is a string coerced ad hoc via the `methodMissing` DSL, unknown reporter names are silently ignored, and one fallback error message in `ReporterConfigurationError.groovy` contains profanity ("Unknown error. Well, fuck."). `csvSummary` will crash on a CSV that contains header rows, and `build.gradle` declares two dependencies (commons-io, jarchivelib) that no source file imports. A charming detail: the build dogfoods the plugin, applying its own 0.2.+ release from the buildscript classpath.

Regarding the fork itself: nothing cdsap-authored is visible at the tip commit, so treat this repository purely as a pointer to the lineage that led to Talaiot rather than as an independent project.
