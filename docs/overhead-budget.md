# Plugin overhead budget (plan 034)

"Never fail the build" is necessary but not sufficient for adoption — "never slow the build
*noticeably*" is the real bar. This is the stated, **measured** overhead budget for the BuildHound
Gradle plugin, and the self-benchmark harness + CI job that enforce it.

## The four axes

gradle-profiler drives a fixed synthetic fixture from outside the JVM twice — plugin applied vs not —
and reports the mean total build time per scenario. **Overhead = `mean(plugin-on) − mean(plugin-off)`**
for the matching scenario. Each roadmap cost axis maps to one scenario:

| Axis | Scenario | What it isolates |
|---|---|---|
| **Configuration** (steady state) | `cc_hit` | Config-phase cost on a configuration-cache hit (`--measure-config-time`). |
| **Per-task** | `incremental` | `TaskEventCollector.onFinish`'s marginal cost on a task-dense, non-ABI incremental build. |
| **Finalizer** | `no_op` | The Flow finalizer's fixed build-end share (assemble → HTML render → file writes) on an up-to-date build. |
| **Upload** | `no_op_upload` vs `no_op_ci` | The synchronous-with-spool upload path, isolated by a loopback do-nothing HTTP sink — both plugin-on **and both CI mode**, so only the `server.url` (upload) differs, not the mode switch's context cost. |

## The budget

| Axis | Provisional cap |
|---|---|
| Configuration | ≤ 40 ms **or** ≤ 3 % of the plugin-off mean |
| Per-task | ≤ 5 % of the plugin-off mean |
| Finalizer | ≤ 150 ms **or** ≤ 8 % of the plugin-off mean |
| Upload | ≤ 250 ms over the CI-mode, no-server `no_op_ci` mean |

Each cap is the **looser** of its absolute floor and its percentage, so a fast fixture on a fast runner
never trips a percentage cap on sub-millisecond noise, and a slow runner never lets a large absolute
regression hide inside a percentage. A percentage breach counts **only when the plugin-on/off means are
statistically separated** — the delta must exceed the combined stddev — so same-machine timing noise
can't mint a false breach.

> **These are provisional.** The committed caps in `OverheadBudget.DEFAULT`
> (`buildhound-commons`) are **calibrated on the CI reference runner** from the first green harness
> run and updated with headroom (recorded in the architecture decision log). The shapes above are the
> starting point; the budget is a *guardrail against regressions*, not a microbenchmark.

## Running it locally

```sh
# Requires gradle-profiler and python3 on PATH.
buildhound-ci-assets/overhead/run-overhead.sh
```

It runs gradle-profiler on `buildhound-ci-assets/overhead/fixture/` twice (`-Pbuildhound.overhead.plugin=on|off`),
runs the **toggle self-test** (plugin-on must emit `build/buildhound/` telemetry, plugin-off must not —
an anti-rot check so a broken toggle can't report a spuriously tiny overhead), and evaluates the two
`benchmark.csv` files against the budget. It exits non-zero on a breach and prints a Markdown table:

```
| Axis | Baseline (ms) | Plugin (ms) | Δ (ms) | Δ (%) | Allowance (ms) | Separated | Verdict |
```

The verdict math is `OverheadCalculator` in `buildhound-commons` (unit-tested on every `./gradlew
build`); the shell is glue. To evaluate two existing CSVs directly:

```sh
buildhound-ci-assets/overhead/bin/buildhound-overhead <plugin-on>/benchmark.csv <plugin-off>/benchmark.csv
```

## The CI job

`.github/workflows/ci.yml` → `overhead-budget`: Ubuntu, installs a pinned gradle-profiler, runs the
harness, uploads the Markdown table + raw CSVs as the `overhead-budget` artifact, and **fails on a
breach**. It is **watched (non-blocking)** to start — same-machine timing on shared CI is noisy.

**Promote-to-blocking criterion:** once ~2 weeks of runs show the plugin-on/off deltas are
consistently separated from run-to-run variance on the reference runner (no false breaches, and real
regressions caught by a deliberately-tightened-budget check), drop `continue-on-error` and record the
promotion in the decision log — the same promote-or-defer discipline as the macOS/Windows/IP jobs
(plan 021).

## Reading the artifact

Each CI run publishes `overhead-table.md` (the verdict table) and both `benchmark.csv` files. A
`⚠ MISSING` verdict means a required scenario was absent from a CSV (a garbled profiler run) — it is
counted as a breach so a broken measurement never reports a false pass.
