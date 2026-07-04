# Cache-isolation modes for benchmark mode (plan 030)

Telltale's insight: a build's wall-clock is dominated by *which caches are warm*. To make a
benchmark series interpretable, each run declares an **isolation mode** — what was deliberately
cold — via `BUILDHOUND_BENCHMARK_ISOLATION`. The plugin allowlists these labels
(`BenchmarkActivation.ISOLATION_MODES`); a run whose isolation label isn't below is rejected so a
typo can't mint a spurious series.

**Never compare across isolation modes** (cache-off vs cache-on is apples-to-oranges). The dashboard
groups a series by `(scenario, isolationMode)` and the recipe view labels the mode on every chart.

The mechanism is Telltale's `GRADLE_HOME_CACHE_EXCLUDES` glob (relative to the Gradle user home)
plus, for some modes, a Gradle flag. Clear the excluded paths in the *seed* step, then measure.

| `BUILDHOUND_BENCHMARK_ISOLATION` | What is cold / excluded | Mechanism |
|---|---|---|
| `full_cache` | nothing — the warm baseline | (no exclusion) |
| `no_build_cache` | the local + remote build cache | `--no-build-cache` (or exclude `caches/build-cache-*`) |
| `no_configuration_cache` | the configuration cache | omit `--configuration-cache` / clear `configuration-cache` |
| `no_local_build_cache` | local build cache only | exclude `caches/build-cache-*` |
| `no_remote_build_cache` | remote build cache only | disable the remote cache node |
| `no_gradle_home_cache` | the whole Gradle user-home caches | exclude `caches/**` |
| `no_kotlin_cache` | Kotlin incremental/daemon caches | exclude `caches/*/kotlin*`, project `.gradle/kotlin` |
| `no_transforms_cache` | artifact-transform outputs | exclude `caches/transforms-*` |
| `no_project_cache` | the project `.gradle` dir | clear `<project>/.gradle` |
| `cold_daemon` | a warm daemon | `--no-daemon` seed, fresh daemon per measure |
| `no_daemon` | the daemon entirely | `--no-daemon` |
| `no_wrapper_dists` | the downloaded Gradle distribution | exclude `wrapper/dists/**` |

**v1 wiring.** The shipped pipelines wire two modes — `full_cache` (baseline) and `no_build_cache`
(the cache-effectiveness signal). The other ten are documented recipe opt-ins: add the mode to the
pipeline's `ISOLATIONS` list and its exclusion to the seed step.
