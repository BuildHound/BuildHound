# Bagan (cdsap/Bagan)

An experimental Kubernetes-based A/B experimentation framework for Gradle builds: it fans a target repository out into one pod per configuration variant, injects the author's Talaiot plugin to capture build times into InfluxDB, and generates a Grafana dashboard comparing the variants.

Research date: 2026-07-03. Source: shallow clone of https://github.com/cdsap/Bagan.

## Overview

Bagan, written by Iñaki Villar (cdsap), answers the question "which Gradle configuration is fastest for this project?" by brute force. Given a target Git repository and a `bagan_conf.json` describing experiment dimensions — `gradle.properties` values, Git branches, and Gradle wrapper versions — it computes the cartesian product of all variants, creates one Helm release containing one Kubernetes pod per variant (on a freshly created GKE cluster or any preconfigured cluster), runs the configured Gradle command N times inside each pod, and auto-generates a Grafana dashboard that compares the experiments using percentiles, minimum build times, and an automatic "winner" panel.

It is important to classify Bagan correctly: it is a benchmark orchestrator, not a telemetry collector. Bagan contains no Gradle plugin and no ingest service of its own. All in-build data capture is delegated to Talaiot 1.5.1 (`io.github.cdsap:talaiot`), which Bagan injects into the target project at runtime by appending an `apply from` line to the root build script and dropping a self-contained `talaiot.gradle.kts` file (`baganGenerator/src/main/java/com/cdsap/bagan/experiments/TalaiotInjector.kt`). Talaiot then publishes build and task metrics to an InfluxDB instance that Bagan has provisioned in the cluster, and Grafana visualizes them. This is the same author whose later work (Talaiot itself, InfoKotlinProcess, gradle-process-tracker) sits directly in BuildHound's problem space; Bagan is the experimentation-harness branch of that lineage.

## Status & maturity

The project is dormant. The last commit is `50db7ff`, "Merge pull request #35 from cdsap/release_0.1.5", dated 2021-07-13 (the clone is shallow, so only this commit is visible, but the 0.1.5 tag matches and there has been no release since). The README describes Bagan as "an experimental framework" in its opening line, and the code is consistent with that self-assessment.

On the positive side, the documentation is extensive and honest: the README contains a full configuration reference, three worked examples with real Android projects (Plaid, android-showcase) including screenshots and conclusions, a lifecycle explanation, and — unusually and commendably — a dedicated section on the GCP cost of running experiments. The Kotlin generator module has a real test suite: roughly 1,540 lines of kotlintest 3.4 `BehaviorSpec` tests across 13 spec files (`baganGenerator/src/test/java/com/cdsap/bagan/...`), covering experiment permutations, config parsing, generated YAML (pod, configmap, chart, values), dashboard JSON generation, property rewriting, wrapper switching, and Talaiot injection; two of the specs use mockito-kotlin.

On the negative side, there is no CI configuration anywhere in the repository, compiled `.class` files are committed under `baganGenerator/buildSrc/out/`, and the toolchain has rotted badly: Helm 2 (`helm init`, tiller-era cluster role bindings) mixed with Helm-3-style commands, Grafana 6.2.5, InfluxDB 1.7.6-alpine, Kotlin 1.3.31 for the Gradle build (with Kotlin 1.4.21 and kscript 3.1.0 installed in the Docker images), a Gradle 5.5 wrapper, `jcenter()` repositories, the long-deprecated `k8s.gcr.io/git-sync-amd64:v2.0.6` image, alpha seccomp annotations, and a PodSecurityPolicy template (an API removed in Kubernetes 1.25). There are also outright broken paths, detailed under Limitations.

## Architecture

Bagan executes in three stages.

**Stage 1 — host CLI (bash).** The entry point is the `bagan` script at the repository root, invoked as `./bagan MODE COMMAND` with modes `gcloud`, `gcloud_docker`, or `standalone`, and commands ranging from meta-commands (`cluster`, `infrastructure`, `experiment`) to single commands (`create_cluster`, `grafana`, `influxdb`, `secret`, `remove_experiments`, `grafana_dashboard`, and others; see `scripts/validations.sh`). Configuration is validated with jq in `scripts/validate_json.sh`, which also applies defaults (cluster name `bagan`, zone `us-west1-a`, machine `n1-standard-1`). After an interactive y/n confirmation (`bagan` lines 74–78), the script stages charts and kscript binaries into `tmp/` and dispatches to a mode script (`scripts/mode_gcloud.sh`, `scripts/mode_gcloud_docker.sh`, `scripts/mode_standalone.sh`). These compose shell command strings from functions in `scripts/command_*.sh` and `eval` them: `gcloud container clusters create`, `helm install bagan-grafana` / `bagan-influxdb`, `kubectl expose deployment bagan-grafana --type=LoadBalancer`, and `kubectl create secret generic git-creds` for private repositories. In `gcloud_docker` mode the whole provisioning sequence runs inside the `cdsap/bagan-init` container (openjdk 11 + gcloud SDK + kubectl + Helm v2.14.1; `docker/installer/Dockerfile`) with the host's gcloud config and SSH keys volume-mounted.

**Stage 2 — generator (Kotlin via kscript).** `baganGenerator/src/main/java/com/cdsap/bagan/generator/BaganGenerator.kt` parses `bagan_conf.json` with Moshi 1.8, generates a random 20-character session id, and orchestrates two providers. `ExperimentProvider.kt` first expands property experiments into permutations, then takes the cartesian product with the branch and wrapper-version sets (absent dimensions default to a singleton empty string), naming the results `experiment1..N`. `DashboardProvider.kt` builds a Grafana dashboard as typed Kotlin models (`Panel.kt`, `EntitiesGrafana.kt`, dashboard uid `IS3q0sSWz`) serialized with Moshi, writes it into the Grafana chart, and deploys it via `helm upgrade bagan-grafana`. `BaganFileGenerator.kt` then emits a complete Helm chart per experiment — `values.yaml`, `Chart.yaml`, and pod/configmap templates produced from Kotlin string templates in `K8Template.kt` — and runs `helm install <experimentN>` for each. All external commands go through `CommandExecutor.kt`, a thin `ProcessBuilder("bash", "-c", ...)` wrapper. Notably, the same sources are shipped as pre-converted kscript files under `docker/installer/bin/generator/` and `docker/pod/bin/experiments/`, produced by a custom Gradle task (see below) and checked into the repository.

**Stage 3 — in-pod execution.** Each experiment pod (`K8Template.kt`, `Pod`/`PodSecure`) uses a `git-sync` init container to clone the target repository (with the experiment's branch as `GIT_SYNC_BRANCH`; an SSH-secret variant handles private repos). The agent container, image `cdsap/bagan-pod-injector` (openjdk 11 + sdkman/kscript + a full Android SDK toolchain with build-tools 27–30; `docker/pod/Dockerfile`), runs the `ExecutorInPod` command sequence: `kscript ExperimentController.kt`, then `for i in $(seq 1 iterations); do <gradle command>; done`. `ExperimentController.kt` reads its parameters from environment variables (`id`, `properties`, `branch`, `gradleWrapperVersion`) injected from the per-experiment ConfigMap via `envFrom`, then: `TalaiotInjector.kt` appends the Talaiot apply-from line and writes `talaiot.gradle.kts`; `RewriteProperties.kt` merges experiment properties into the repository's `gradle.properties` using `java.util.Properties`; `GradleWrapperVersion.kt` rewrites `distributionUrl` in `gradle-wrapper.properties`. Grafana is pre-provisioned with the InfluxDB datasource (`k8s/grafana/values.yaml`, datasource `http://bagan-influxdb.default:8086`, database `tracking`).

A fourth, vestigial component exists: a `k8s/frontend` Helm chart and `docker/frontend/Dockerfile` referencing a Ktor application `bagan-monitor.jar` whose source is absent from the repository and which nothing in the CLI ever deploys. It appears to be an abandoned web-frontend experiment.

## Data collected & how

Bagan's own code collects nothing from inside the Gradle process; every build-time metric comes from the injected Talaiot plugin. Concretely:

- **Build duration per build**, tagged with the experiment id, published by Talaiot's `InfluxDbPublisher` into InfluxDB database `tracking`, measurement `build`. The generated dashboard queries `min("duration")`, `percentile("duration", 80)`, and `percentile("duration", 99)` from `"tracking"."rpTalaiot"."build"` grouped by the `experiment` tag (`DashboardProvider.kt`, lines 135–175).
- **Per-task metrics** into measurement `tasks` (`taskMetricName = "tasks"` in the injected configuration, `TalaiotInjector.kt` lines 73–81). These are written to InfluxDB but no generated dashboard panel visualizes them.
- **The experiment tag itself**, attached via Talaiot's `customBuildMetrics("experiment" to id)` and `customTaskMetrics(...)` (`TalaiotInjector.kt` lines 69–72), with the id sourced from the ConfigMap environment variable.
- **Experiment metadata** (properties, branch, wrapper version per experiment) rendered as a markdown legend panel via `DashboardProvider.getContentLegend`.

In terms of Gradle APIs, the repository is almost empty: there is no `BuildService`, no `OperationCompletionListener` or `BuildEventsListenerRegistry`, no Flow API, no Tooling API, no JMX, and no `/proc` parsing anywhere (verified by grep). The only Gradle-API code is a build-time task for Bagan's own build: `TaskHeaderReplacer` in `baganGenerator/buildSrc/src/main/java/com/cdsap/kscript/TaskHeaderReplacer.kt`, an abstract `DefaultTask` with `DirectoryProperty` inputs/outputs annotated `@InputDirectory`/`@OutputDirectory` and a `@TaskAction` that rewrites the Kotlin sources into kscript format (prepending `//DEPS` and `@file:Include` headers from `LookupFilesDependencies.kt`), wired as the `convertFiles` task in `baganGenerator/build.gradle.kts`. Gradle itself is manipulated purely externally, through files (`gradle.properties`, `gradle-wrapper.properties`, `build.gradle(.kts)`) and shell invocations of `./gradlew`.

## Outputs & integrations

- **A live Grafana dashboard**, generated as code and hot-deployed via `helm upgrade bagan-grafana`, reachable at `http://<load-balancer-ip>:3000/d/IS3q0sSWz` (default credentials admin/admin). It contains five panels: an experiments legend, a p99 duration graph, a minimum-build-times table, a percentile(80) table, and an "Experiment Winner" singlestat whose InfluxQL query selects the experiment with the lowest p80 duration.
- **InfluxDB time series** in database `tracking` (measurements `build` and `tasks`), populated by Talaiot from every experiment pod and available for ad-hoc panels beyond the generated dashboard.
- **Generated per-experiment Helm charts on disk** under `tmp/` (`values.yaml`, `Chart.yaml`, `templates/pod<name>.yaml`, `templates/configmap<name>.yaml`), which double as an audit record of what was run.
- **Console and pod logs** through a trivial `[TAG]: message` logger.

There is no HTML report, no ingest API, no persisted structured build payload, and no notification/alerting integration. The storage and visualization layer is hardwired to InfluxDB 1.x and Grafana 6.x, including a hardcoded InfluxDB URL inside the injected Talaiot configuration.

## Techniques worth borrowing for BuildHound

Bagan's direct code is not reusable, but several of its ideas map cleanly onto BuildHound's spec, particularly §7 (benchmark mode):

1. **The experiment matrix as a cartesian product over configuration dimensions** (`ExperimentProvider.kt`): properties × branches × wrapper versions, with property options first expanded into permutations. A future BuildHound "experiment matrix" feature — comparing `gradle.properties` variants or Gradle versions — could adopt this model directly, and it is complementary to the gradle-profiler-based benchmark pipeline in the spec.
2. **Hermetic per-variant execution.** Each variant runs in a fresh container with its own checkout, daemon, and caches, eliminating cross-contamination between variants — a stronger isolation guarantee than gradle-profiler's same-machine scenarios, at the cost of losing warm-daemon realism.
3. **N-iteration repetition with percentile-based comparison** (p80/p99/min) and an automatic winner query, i.e., statistical treatment of noisy build timings rather than single-run comparison. BuildHound's benchmark mode should similarly report percentiles per scenario, never single runs.
4. **Session/experiment tagging.** Every metric carries an `experiment` tag and every Kubernetes object carries a random 20-character `session` label used for bulk cleanup (`BaganGenerator.getSessionExperiment`, `K8Template.kt` labels). This maps directly onto BuildHound's `mode=benchmark`, `scenario`, and `iteration` tags, and validates using one shared time-series store for concurrent experiments distinguished only by tags.
5. **Zero-fork plugin injection** (`TalaiotInjector.kt`): benchmarking an arbitrary third-party repository by appending one `apply from` line and dropping a self-contained buildscript file, with Groovy/KTS detection. Useful should BuildHound ever benchmark repositories it does not control (no `settings.gradle` access required).
6. **Dashboards-as-code**: the Grafana dashboard is built from typed, Moshi-serialized Kotlin models and redeployed idempotently via `helm upgrade` (`DashboardProvider.kt`, `Panel.kt`, `EntitiesGrafana.kt`) — the same philosophy as BuildHound's self-owned dashboard, applied to a third-party tool.
7. **The git-sync init-container pattern** with an SSH-secret variant for private repositories (`Pod` vs `PodSecure` in `K8Template.kt`, `scripts/command_secret.sh`) as a clean way to provision source into ephemeral build environments.

Bagan's failure modes are equally instructive as negative lessons: hardcoded plugin wiring (Talaiot version and InfluxDB URL baked into an injected string) versus BuildHound's DSL and versioned payload schema; duration-only analysis despite richer data existing in the store; no upload resilience of any kind versus BuildHound's spool-and-retry; and severe stack rot from binding tightly to specific Grafana/InfluxDB/Helm versions, which reinforces the spec's choice of provider-agnostic SPIs and a self-owned dashboard.

## Limitations & pitfalls

- **Dormant and obsolete.** No activity since July 2021. Helm 2 tiller-era assumptions, Grafana 6.2.5, InfluxDB 1.7, Gradle 5.5, `jcenter()`, deprecated git-sync image, alpha seccomp annotations, and PodSecurityPolicy (removed in Kubernetes 1.25) mean it would need a substantial rewrite to run today.
- **Internally inconsistent Helm usage.** The scripts run Helm 2's `helm init` (`scripts/command_helm.sh`), yet both the bash provisioning (`scripts/command_infra.sh`) and the Kotlin generator (`BaganFileGenerator.kt`) use Helm-3-style positional release names (`helm install bagan-grafana ...`), which Helm v2.14.1 — the version baked into the `cdsap/bagan-init` image — does not support.
- **Broken commands.** `helmClusterRoleBinding` is invoked by all three mode scripts but defined nowhere in the repository, so the `helm_clusterrolebinding` command (and the README-documented behavior of `helm`) fails. Standalone mode answers "Not implemented" for `cluster`, `create_cluster`, and `credentials`.
- **Version skew between components.** The CLI pins `cdsap/bagan-init:0.1.7` while `deploy/deployment.sh` and `Versions.kt` build and reference 0.1.5 images; the checked-in kscript copies under `docker/*/bin` can drift from `baganGenerator/src`.
- **No error propagation.** `CommandExecutor.kt` catches all exceptions, only logs them, and never checks exit codes; the in-pod iteration loop does not separate failed builds from successful ones, so failures silently pollute the timing data.
- **Not a telemetry system.** No plugin, no ingest server, no schema; entirely dependent on what Talaiot 1.5.1's `InfluxDbPublisher` emits, with the version and URL hardcoded.
- **Analysis gaps.** The dashboard handles only the first non-`clean` token of the Gradle command (`DashboardProvider.getCommands` explicitly logs "more than one command found..."); only build duration is compared; the `tasks` measurement is collected but never visualized; there is no cache, configuration-cache, test, or memory analysis.
- **Operational cost and friction.** Requires GCP/GKE or a preconfigured cluster, prompts interactively before every run, and is expensive enough that the README dedicates a section to cost management.
- **Security hygiene.** `allowPrivilegeEscalation: true` in experiment pods, SSH keys mounted into containers, Grafana admin/admin defaults, and arbitrary property strings propagated through ConfigMap environment variables.
- **Correctness edges.** `ExperimentProvider.cartesianProduct` returns an empty set for fewer than two input sets (masked only because absent experiment dimensions default to `setOf("")`, so three sets are always passed); experiment properties are merged into, not isolated from, the repository's existing `gradle.properties`; branch experiments rely on git-sync rather than the in-pod controller. There is no CI and no integration or end-to-end test of the Kubernetes path.

## Notable files

| Path | Why it matters |
|---|---|
| `bagan` | Bash CLI entry point: mode/command dispatch, config echo, interactive confirmation, `tmp/` staging |
| `baganGenerator/src/main/java/com/cdsap/bagan/generator/BaganGenerator.kt` | Main generator: config parsing, session id, orchestration |
| `baganGenerator/src/main/java/com/cdsap/bagan/generator/ExperimentProvider.kt` | Permutations + cartesian product experiment matrix |
| `baganGenerator/src/main/java/com/cdsap/bagan/generator/BaganFileGenerator.kt` | Per-experiment Helm chart emission + `helm install` |
| `baganGenerator/src/main/java/com/cdsap/bagan/generator/K8Template.kt` | All Kubernetes YAML as Kotlin string templates, including the in-pod executor loop |
| `baganGenerator/src/main/java/com/cdsap/bagan/generator/DashboardProvider.kt` | Grafana dashboard-as-code with raw InfluxQL and `helm upgrade` deploy |
| `baganGenerator/src/main/java/com/cdsap/bagan/experiments/ExperimentController.kt` | In-pod controller reading experiment env vars |
| `baganGenerator/src/main/java/com/cdsap/bagan/experiments/TalaiotInjector.kt` | The telemetry hookup: Talaiot 1.5.1 injection with hardcoded InfluxDB URL and experiment tags |
| `baganGenerator/src/main/java/com/cdsap/bagan/experiments/RewriteProperties.kt` | Merges experiment properties into the target's `gradle.properties` |
| `baganGenerator/src/main/java/com/cdsap/bagan/experiments/GradleWrapperVersion.kt` | Switches Gradle versions by rewriting `distributionUrl` |
| `baganGenerator/buildSrc/src/main/java/com/cdsap/kscript/TaskHeaderReplacer.kt` | Only Gradle-API code: DefaultTask converting sources to kscript format |
| `scripts/mode_gcloud_docker.sh` | Full provisioning composition and the dockerized execution path |
| `scripts/validate_json.sh` | jq-based config validation and defaulting |
| `k8s/grafana/values.yaml` | Provisioned InfluxDB datasource wiring Grafana to Talaiot output |
| `docker/pod/Dockerfile` | Experiment pod image with full Android SDK toolchain (reveals the Android focus) |
| `deploy/deployment.sh` | Release pipeline: gradle build → kscript conversion → docker build/push |
