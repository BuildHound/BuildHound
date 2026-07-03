# Telltale (cdsap/Telltale)

A GitHub Actions-based experimentation framework that runs controlled A/B benchmarks of Gradle builds across two branches under selectable caching modes, correlates the resulting builds through Develocity scan tags, and publishes comparison reports to a Hugo website.

Research date: 2026-07-03. Source: shallow clone of https://github.com/cdsap/Telltale.

## Overview

Telltale is orchestration tooling, not a telemetry product. Its unit of work is an experiment: a user dispatches a workflow naming a target repository, two branches ("variant A" and "variant B"), a Gradle task, an iteration count, and one of twelve caching modes. The framework then fans out builds across ephemeral GitHub Actions runners, tags every build in Develocity so it can be found again, and finally runs a comparison tool that pulls the tagged builds from the Develocity API and emits CSV, HTML, and step-summary reports. Optionally, the results are committed to the `gh-pages` branch and published to https://cdsap.github.io/Telltale/ with an OpenAI-generated analysis and title.

The repository contains no Gradle plugin or JVM source code at all. It consists of two dispatchable workflows, four local composite actions, one Hugo deployment workflow, a single Python script, and documentation. All build-data collection is delegated to the Develocity Gradle plugin running inside the target project, and all analysis logic lives in external cdsap binaries and actions (CompareGEBuilds/BuildExperimentResults, build-process-watcher, InfoKotlinProcess, InfoGradleProcess). Telltale itself is the glue that arranges controlled conditions around those tools.

For BuildHound, Telltale is a neighboring-problem reference: it solves one-shot comparative benchmarking, which maps onto the benchmark mode in spec section 7 and the Comparisons view in section 6, while being exactly the kind of Develocity-dependent tooling that BuildHound's self-hosted ingest path is meant to replace.

## Status & maturity

The repository is a personal/community tool by Iñaki Villar (cdsap), a well-known Gradle build-performance specialist. The last commit on `main` is `8495beb` ("Update action.yaml"), dated 2026-06-16, seventeen days before this research date, so the project is clearly alive. Because the clone is shallow (depth 1), commit frequency and contributor history could not be verified locally; only the latest commit is visible.

Documentation quality is good for a tool of this size: the `README.md` explains the phases, all secrets, and the report options, and `docs/actions-inputs.md` documents every action input individually. Renovate is configured (`.github/renovate.json`). However, several signals cap the maturity assessment:

- There are zero automated tests, and no CI validates the workflows or the bash embedded in them. Verification during this research found real latent bugs that tests would have caught (detailed under Limitations).
- The report step downloads a pinned binary release (`CompareGEBuilds` v1.0.5) over `curl` with no checksum or signature verification (`.github/workflows/report/action.yaml`, line 109).
- Deployment targets are hardcoded to `cdsap/Telltale`: the `gh-pages` push URL (line 327 of `report/action.yaml`) and the `repository_dispatch` API call (lines 333-337). Reusing the framework requires forking and editing these.
- The README has drifted from the workflows: it lists only eleven caching modes and omits `dependencies cache - javaCompile cache`; it documents the JDK default as 17 while `experiment.yaml` defaults to 23; its default for `open_ai_request` is `'true'` while the workflow default is `'false'`; and its License section links to a `LICENSE` file that does not exist on `main`.

## Architecture

The main pipeline, defined in `.github/workflows/experiment.yaml`, is a fan-out matrix with four phases.

First, an `iterations` job converts the requested iteration count into a JSON array of indices using inline bash (`experiment.yaml`, lines 76-94), which later jobs consume as a matrix dimension.

Second, a `seed` job runs once per variant (skipped entirely for the `no caching` mode). It checks out the target repository at the variant branch, sets up the requested JDK, and executes the Gradle task via `gradle/actions/setup-gradle@v4` in `cache-write-only: true` plus `cache-overwrite-existing: true` mode, so that a fresh GitHub Actions cache entry is populated for that variant (`.github/workflows/runner-seed/action.yaml`, lines 117-126). The heart of the framework is the step immediately before it (lines 63-115): a case statement mapping each named caching mode to a set of `GRADLE_HOME_CACHE_EXCLUDES` patterns over the Gradle user home — `caches/build-cache-1` for the local task-output cache, `caches/**/transforms` for the artifact-transform cache, `caches/modules-*/*` and `caches/jars-*/*` for the dependency cache, and `caches/*/javaCompile` for the Java compile caches. By excluding specific directories from what the seed saves, each mode controls exactly which cache types survive into the measurement phase. Seed builds are tagged in Develocity with `seed`-prefixed scan tags.

Third, an `execution` matrix job runs iterations x 2 variants, each on a fresh runner. It restores the seed's cache entry via `setup-gradle` with `cache-read-only: true` and `GRADLE_BUILD_ACTION_CACHE_KEY_JOB: 'seed'` so every iteration sees identical starting cache state, then runs the same Gradle task tagged with `-Dscan.tag.<experiment-id>_<variant-prefix><variant>`, `-Dscan.tag.<mode>`, `-Dscan.tag.experiment`, and `-Dscan.tag.<experiment-id>` (`.github/workflows/runner/action.yaml`, lines 61-88). The experiment id is `<repository_owner>-<run_number>`. Remote-cache modes feed a cache node URL to the target build through the `CI_URL_CACHE_NODE` environment variable.

Fourth, a `report` job (`.github/workflows/report/action.yaml`) downloads the CompareGEBuilds binary, translates the workflow's report toggles into CLI flags, and invokes it with the Develocity URL, API key, and `--variants=<tag>` filters (up to `--max-builds=200`). The binary queries the Develocity API for the tagged builds and writes the comparison outputs into the workspace. A Python script, `scripts/experiment_snapshot_from_csv.py`, then distills the CSV (rows keyed by `Category`/`Metric`, columns named `<variant-slug> Mean/P50/P90`) into Hugo front matter with build-time and configuration-time mean/p50/p90 per variant. If `deploy_results` is true, the action checks out `gh-pages`, writes a post embedding the HTML report and any OpenAI analysis, pushes, and fires a `repository_dispatch` (`deploy-hugo`) that triggers `.github/workflows/hugo-deployment.yaml`, which builds the site with Hugo 0.145.0 and the PaperMod theme and deploys to GitHub Pages.

A second dispatchable workflow, `.github/workflows/experiment-with-gradle-profiler.yaml`, replaces phases one through three with gradle-profiler 0.23.0 (installed via `sdkman/sdkman-action`) running on one runner per variant. The composite action `.github/workflows/runner-gradle-profiler/action.yaml` generates a scenario file on the fly (lines 100-116): the requested task, `cleanup-tasks = ["clean"]` or `[]` depending on a clean-between-iterations toggle, optional `apply-abi-change-to` classes for incremental-compilation scenarios, and `gradle-args` carrying the same Develocity scan tags. The workflow hardcodes one warm-up build (the action input exists but is not exposed as a dispatch input), runs the requested iterations, renders `profile-out/benchmark.csv` as an HTML table in the step summary, and uploads the profiler output as an artifact. The same report action then runs with `profile: true`.

Finally, `versions_to_monitor.json` (currently AGP 9.2.0, Kotlin 2.3.20, Gradle 9.4.1) combined with custom Renovate regex managers makes Renovate open PRs labeled `telltale-experiment` whenever new AGP/KGP/Gradle versions ship — effectively a notification system for "there is a new version worth running an experiment against."

## Data collected & how

Telltale collects almost nothing first-party; it arranges for other tools to collect, then correlates by tag.

- Develocity build scans are the primary data source. The Develocity Gradle plugin in the target project captures build time, configuration time, task outcomes and durations, and more; Telltale's contribution is the `-Dscan.tag.*` system properties on each `./gradlew` invocation that make builds queryable per experiment, variant, and mode.
- The CompareGEBuilds binary retrieves those builds via the Develocity API and computes the comparison. The report action maps toggles to its flags: `--task-type-report`, `--task-path-report` with `--threshold-task-duration` (default 1000 ms), `--kotlin-build-report` (requires the target project to enable Kotlin Build Reports), `--process-report` (README states this requires cdsap/InfoKotlinProcess and cdsap/InfoGradleProcess in the target project), `--gc-report`, `--resource-usage-report` (requires Develocity 2024.2+ scans), and `--only-cacheable-outcome`.
- Runner-level process monitoring comes from `cdsap/build-process-watcher@v0.6.2`, invoked in all three runner actions with `remote_monitoring: 'true'` and `export_to_bigquery: 'true'`. The implementation and BigQuery destination are external to this repository and opaque from it.
- The profiler workflow additionally produces gradle-profiler's `benchmark.csv` per-iteration timings.

No Gradle APIs are used directly by this repository. The Gradle-facing surface consists of the Develocity plugin's scan-tag system properties, gradle-profiler scenario files, Kotlin Build Reports consumed downstream, and — most interestingly — the exploitation of the Gradle user home directory layout through `gradle/actions/setup-gradle@v4` cache controls (`cache-read-only`, `cache-write-only`, `gradle-home-cache-excludes`, and the `GRADLE_BUILD_ACTION_CACHE_KEY_JOB*` environment variables).

## Outputs & integrations

- GitHub Step Summary tables: the report binary writes `experiment_results_summary_gha`, which is appended to `$GITHUB_STEP_SUMMARY`; the profiler action renders `benchmark.csv` as an HTML table.
- Workflow artifacts: `experiment_results*` files, all CSVs, the HTML report, `experiment_snapshot.yaml`, and `profile-out/*` for profiler runs.
- A static Hugo site on `gh-pages` (published at cdsap.github.io/Telltale) with one post per experiment embedding the HTML report, a home-page mean/p50/p90 snapshot chart driven by the Python-generated front matter, and optional OpenAI-generated analysis text and title (the OpenAI call is gated so it only runs when `deploy_results` is also true).
- BigQuery rows from build-process-watcher.
- The Develocity server itself, where every experiment build remains queryable by its tags.

## Techniques worth borrowing for BuildHound

1. **Tag-based build correlation.** Telltale never uploads anything from the build; it stamps each build with `-Dscan.tag.<experiment-id>_<variant>`, mode, and experiment tags, and re-queries by tag later. This independently validates BuildHound's `tags`/`values` design and maps directly onto the `mode=benchmark, scenario, iteration` tagging planned in spec section 7.
2. **CI-orchestrated gradle-profiler runs.** The `runner-gradle-profiler` action is a working reference for BuildHound's benchmark pipeline template: sdkman installation, generated scenario files, warm-ups, `apply-abi-change-to` for incremental-compilation scenarios, and a `cleanup-tasks` toggle for clean-versus-incremental measurement.
3. **The cache-mode exclusion table.** The case statement in `runner-seed/action.yaml` (lines 63-115) is condensed operational knowledge of which Gradle-home directories embody which cache type (`caches/build-cache-1`, `caches/**/transforms`, `caches/modules-*`, `caches/jars-*`, `caches/*/javaCompile`). This is valuable both for interpreting cache telemetry and for any future experiment feature.
4. **The seed-then-measure protocol.** Populating cache state once per variant with `cache-write-only`, then measuring N iterations on fresh runners with `cache-read-only` and a pinned cache key, is the correct methodology for cache experiments and would apply directly if BuildHound ever ships comparison pipelines feeding its Comparisons view.
5. **Report-menu corroboration.** Telltale's report options (task type, task path with a duration threshold, Kotlin build reports, process, GC, resource usage, cacheable-outcome filter) independently corroborate BuildHound's payload choices; the `threshold_task_duration` flag mirrors `minTaskDurationMs` in the plugin DSL.
6. **Renovate as an experiment trigger.** Custom regex managers watching a small `versions_to_monitor.json` turn new AGP/KGP/Gradle releases into labeled PRs — a cheap, clever prompt for "run a new baseline."
7. **CSV-to-front-matter distillation.** `scripts/experiment_snapshot_from_csv.py` shows a pragmatic pattern for surfacing mean/p50/p90 headline numbers from a richer report into a static site.

Nothing in the repository is reusable as code for the BuildHound plugin or server; the reusable asset is methodology. The companion repos it depends on (CompareGEBuilds/BuildExperimentResults, InfoKotlinProcess, InfoGradleProcess, build-process-watcher) contain the actual collection and analysis code and would be worth analyzing separately.

## Limitations & pitfalls

- **Not a telemetry system.** Telltale runs one-shot A/B experiments. There is no continuous per-build collection, no database, no baselines, trends, or alerts — the closest thing to a dashboard is a static blog.
- **Hard Develocity dependency.** All build data flows through Develocity (`DV_ACCESS_KEY`, `DV_URL`, `DV_API_KEY` secrets); the resource-usage report additionally requires Develocity 2024.2+. Without a Develocity server there is essentially no data path.
- **Fork-to-reuse.** GitHub Actions only, with the `gh-pages` push and `repository_dispatch` hardcoded to `cdsap/Telltale` in `report/action.yaml`.
- **Untested inline bash, with real latent bugs.** Verification found three concrete defects the draft profile had missed. (1) The seed action's declared `cache-excludes` output is never wired: composite-action outputs need a `value:` mapping, the step writes `cache-excludes=...` to `$GITHUB_ENV` instead of `$GITHUB_OUTPUT` (`runner-seed/action.yaml`, line 114), and `experiment.yaml` line 155 reads `steps.seed.outputs.cache-excludes` at job level where no such step exists — so the measurement jobs always receive an empty excludes input. The experiments still behave correctly only because exclusions are applied when the seed saves the cache entry. (2) `report/action.yaml` line 295 contains `DESCRIPTION= "..."` (space after `=`), which bash treats as running the string as a command; because composite `shell: bash` runs with `-e`, deploying results without an OpenAI-generated title should fail this step. (3) The `cache-exclude-script` input of `runner-seed` is declared but never used, and the workflow passes it an undefined `env.cache_exclude_content`.
- **Supply-chain and secret-handling weaknesses.** The report binary is fetched by `curl` from a pinned release with no integrity check, and the OpenAI API key is passed as a plain CLI argument (`--open-ai-key ...`), making it visible to process listings on the runner.
- **Limited statistics.** Exactly two variants; mean/p50/p90 are computed downstream by the external report tool; matrix iterations run on ephemeral shared VMs with inherent noise unless the gradle-profiler path is used.
- **Documentation drift.** README missing the twelfth caching mode, JDK default mismatch (17 vs 23), `open_ai_request` default mismatch, and a referenced LICENSE file absent from `main` despite the README claiming MIT.
- **Opaque side channel.** build-process-watcher exports monitoring data to BigQuery configured outside this repository — not self-hostable as-is and invisible to a fork.
