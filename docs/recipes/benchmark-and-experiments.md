# Benchmark mode & cache-isolation experiments (plan 030)

BuildHound's **benchmark mode** turns a scheduled [gradle-profiler](https://github.com/gradle/gradle-profiler)
run into a per-scenario percentile series, and its **experiment pairs** feed the build-input
comparison page (plan 022). Both are driven by env + tags — no plugin DSL change on the pilot.

## Scenarios

The nightly pipeline runs four scenarios (`buildhound-ci-assets/profiler-scenarios/buildhound.scenarios`):

| Scenario | What it measures |
|---|---|
| `clean` | full clean assemble — the worst case |
| `no_op` | fully up-to-date assemble — task-graph + up-to-date-check overhead |
| `incremental_non_abi` | a method-body change — incremental compilation effectiveness |
| `cc_hit` | assemble on a configuration-cache hit — config-time savings |

Each measured build the profiler runs uploads as `mode=benchmark` with `scenario`/`iteration`/
`isolationMode` recorded both in a typed `benchmark` block and mirrored into `tags`.

## Running the series

Wire the scheduled pipeline (`profiler-pipeline/{github,azure}-nightly-benchmark.yml`) with an
ingest-scoped `BUILDHOUND_TOKEN` secret and your server URL. It runs one job per
`(isolation, scenario)` pair, exporting `BUILDHOUND_BENCHMARK_{SCENARIO,ITERATION,ISOLATION,SEED_REF}`
per invocation. v1 wires two isolation modes — `full_cache` (baseline) and `no_build_cache` — see
[isolation-modes.md](../../buildhound-ci-assets/profiler-pipeline/isolation-modes.md).

**One scenario per job, not a loop.** The plugin reads `BUILDHOUND_BENCHMARK_*` inside the measured
Gradle daemon and gradle-profiler reuses daemons, so a serial loop can leak the previous scenario's
label into the next one — and the plugin would mislabel the series rather than fail. A job per
scenario gives each label a fresh daemon. For the same reason the seed ref is one value for the whole
matrix (the CI run id), not a per-job timestamp.

### BuildHound's own nightly (plan 105)

This repository runs the pipeline against its own sample pilots:
[`.github/workflows/nightly-benchmark.yml`](../../.github/workflows/nightly-benchmark.yml), with
per-pilot scenario files under `buildhound-ci-assets/profiler-scenarios/samples/`. It publishes to
**production** via `vars.BUILDHOUND_PROD_SERVER_URL` + `secrets.BUILDHOUND_PROD_INGEST_TOKEN`.

The samples themselves are not modified for CI: they keep `server.url = "http://localhost:8080"` and
the local-dev token. The workflow installs
[`.github/buildhound-sample-benchmark.init.gradle.kts`](../../.github/buildhound-sample-benchmark.init.gradle.kts)
into the `init.d/` of the Gradle user home it then passes to `gradle-profiler --gradle-user-home`,
and it re-points `server.url`/`server.token` from
`BUILDHOUND_SAMPLE_SERVER_URL`/`BUILDHOUND_SAMPLE_TOKEN` in `settingsEvaluated` — *after* the
sample's own `buildhound { }` block. `beforeSettings` (what the dogfood script uses for the root
build, which has no DSL of its own) is too early here: the sample's literal would overwrite it.

Two details of that install are load-bearing, and getting one wrong is silent (plan 109).
gradle-profiler does not run builds against `~/.gradle`; it uses a dedicated Gradle user home, and
Gradle reads `init.d` only from the home in use — so the script has to go into the home the profiler
is *told* to use, which is why the workflow names it explicitly instead of relying on the profiler's
default. And because the plugin never fails a build, a script that never applies costs you the whole
series without reddening anything: the workflow's `Verify telemetry published` step therefore greps
gradle-profiler's `profile.log` for `[buildhound] payload uploaded` and fails the cell without it.
Checking that the ingest credentials are *set* is not the same check, and does not substitute.

`springboot-legacy` runs `no_build_cache` only — its committed config has the build cache off, so
`full_cache` would be a false label.

Caveat when reading these series: gradle-profiler does not export `BUILDHOUND_BENCHMARK_ITERATION`,
and warm-up builds upload too, so rows carry `iteration=null` for both warm-ups and measured runs.

What does *not* appear is gradle-profiler's scaffolding. It runs a `:help` build to inspect the
project before a scenario starts, and one `cleanup-tasks` build before each measured build; all of
them inherit the job's `BUILDHOUND_BENCHMARK_*` env, so all of them would otherwise publish as
benchmark rows the server cannot tell apart from a measurement — a majority of a `clean` cell's
payloads (5 of 9 at `iterations = 3`, 4 of 7 for `nowinandroid` at 2), and the `min` of every cell
would be the `:help` build. The init script disables the upload for
any invocation whose requested tasks are all scaffolding (plan 109 §4.5).

## Reading a low-noise series

Same-machine runs are noisy, so the `#/benchmark` view shows **percentiles over N iterations
(p50/p90/min), never a single run** (Telltale/Bagan). Read it like this:

- Compare a scenario's **p50 across days**, not two individual builds.
- **Never compare across isolation modes** — cache-off vs cache-on is apples-to-oranges. The view
  labels the isolation on every chart and groups by `(scenario, isolationMode)`.
- A rising `clean` p50 with a flat `no_op` p50 points at compilation/cache work, not configuration.

## Experiment pairs → the comparison page

Beyond the time series, three **build-validation pairs** ([research §3](../research/cache-miss-input-fingerprints.md))
diagnose *why* two builds differ. Run each pair, tag **both** builds with the same experiment id, then
open them on the comparisons page (plan 022), which diffs their salted input fingerprints:

```kotlin
buildhound { tags.put("experiment", "exp05") }   // or BUILDHOUND_TAGS if wired
```

| Pair | Run | Diagnoses |
|---|---|---|
| **same-sha CI↔CI** | the same commit twice on CI | non-determinism / volatile inputs (timestamps, ordering) |
| **CI↔local** | the same commit on CI and on a dev machine | environment drift (JDK home, locale, absolute paths) |
| **two-checkout relocatability** | the same commit in two different directories | cache **relocatability** (absolute paths leaking into keys) |

The compare endpoint keys on the shared `experiment` tag + the build ids; the handoff is by tag, not
code coupling. This is the roadmap phase-3 exit signal: same-sha builds with different JDK homes show
up as a fingerprint diff on the comparisons page.
