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
ingest-scoped `BUILDHOUND_TOKEN` secret and your server URL. It loops over `(isolation, scenario)`
pairs, exporting `BUILDHOUND_BENCHMARK_{SCENARIO,ITERATION,ISOLATION,SEED_REF}` per invocation.
v1 wires two isolation modes — `full_cache` (baseline) and `no_build_cache` — see
[isolation-modes.md](../../buildhound-ci-assets/profiler-pipeline/isolation-modes.md).

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
