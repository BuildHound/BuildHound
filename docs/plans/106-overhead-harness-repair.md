# 106 — Repair the inert overhead-budget harness

## 1. Source

`docs/overhead-budget.md` and plan [034](implemented/034-plugin-overhead-budget.md) (the
"never slow the build *noticeably*" guardrail); plan
[104](104-machine-specs-and-resource-usage.md) exit criterion 5, which cannot be met while the
job measures nothing. Bug report: the `overhead-budget` job reports **success without running the
benchmark** (reproduced on `main` at `be8c255`, Actions run 31323612781 — green in ~17 s).

## 2. Scope

**In:** make the harness actually run and make a broken harness fail the job. Three defects, all
in CI assets:

1. `run-overhead.sh` passes `-Pbuildhound.overhead.plugin="$variant"` **to gradle-profiler**, which
   has no such option — it prints `P is not a recognized option`, dumps usage and exits non-zero.
   Verified against the pinned 0.24.0 `--help`: there is no `-P`, and **no `--gradle-argument`
   either** — only `-D`. The supported route is the scenario file's `gradle-args`.
2. `.github/workflows/ci.yml` runs the harness as `run-overhead.sh | tee overhead-table.md` under
   the Actions default shell `/usr/bin/bash -e {0}`, which has **no `pipefail`** — the pipeline
   exits with `tee`'s `0` and the failure is discarded. The comment at that step asserting the
   default shell sets `-o pipefail` is factually wrong and is what let the defect survive review.
3. (Found while fixing 1.) `overhead.scenarios` writes
   `"-Pbuildhound.server.url=${BUILDHOUND_OVERHEAD_SINK}"` — HOCON does **not** substitute inside a
   quoted string, so the literal text `${BUILDHOUND_OVERHEAD_SINK}` reaches the build.
   `--dump-scenarios` confirms it. The upload axis was measuring a bogus URL, not an upload.

Two further defects surfaced only once the harness actually ran — both make the verdict step
report `⚠ MISSING` for every axis, which is the same class of untruth as the inert job:

4. `ProfilerCsv` keys scenarios by the `scenario` row's cells, but gradle-profiler names a CSV
   column after the scenario's **`title`** when one is set (empirically confirmed: a scenario with
   no title is named by its id). `OverheadBudget` references ids (`no_op`, `cc_hit`, …), so no axis
   could ever find its data.
5. `--measure-config-time` emits **two** columns per scenario — `total execution time` and
   `task start`. `ProfilerCsv` indexes by column position with "last column wins", so it would
   silently read `task start` as the axis mean.

**Scope amendment (divergence from this plan's first commit, which put commons out of scope):**
fixing 4 and 5 requires a `buildhound-commons` change (`ProfilerCsv`) plus its unit tests. Taken in
because exit criterion 1 — a real four-axis table with verdicts — is unreachable without it: the
harness would run correctly and still report nothing. Scope stays limited to the parser; no schema,
payload, plugin or server code is touched.

**Out:** calibrating `OverheadBudget.DEFAULT` (docs/overhead-budget.md §"The budget" reserves that
for the first green *CI reference-runner* run + a decision-log row — an owner call, not a
side effect of this repair); promoting the job to blocking; bumping the pinned gradle-profiler
version/SHA; per-axis metric selection (see §5); any plugin, server or schema change.

## 3. Design

- `overhead.scenarios` — each of the five scenarios gets the toggle through HOCON *concatenation*
  (`"-Pbuildhound.overhead.plugin="${BUILDHOUND_OVERHEAD_PLUGIN}`), the one form empirically proven
  to interpolate. **Mandatory** substitution (`${VAR}`), never optional (`${?VAR}`): a missing
  variable must abort the run, because silently dropping the arg makes the fixture fall back to
  `off` and the plugin-on run would report ~0 overhead — a false pass of exactly the kind this plan
  removes. Same fix shape for the `no_op_upload` sink URL.
- `run-overhead.sh` — drop the bogus `-P` flag; set `BUILDHOUND_OVERHEAD_PLUGIN` as a
  **command-scoped** assignment on the gradle-profiler invocation (not an `export`) so the `on`
  value cannot leak into the `off` run. Warm-up/iteration counts stay profiler defaults unless the
  measured runtime forces a bound (see §5).
- `.github/workflows/ci.yml` — give the harness step `shell: bash` (Actions maps it to
  `bash --noprofile --norc -eo pipefail {0}`) and replace the wrong comment with what is actually
  load-bearing. Add `timeout-minutes` to the job: a real benchmark run is minutes-to-hours, not
  17 s, and the job currently rides the 360-minute platform default.
- `overhead.scenarios` — drop each scenario's `title`, so the CSV column carries the scenario **id**
  the budget references. The prose survives as a comment; the machine-readable key wins over the
  prettier `benchmark.html` label.
- `ProfilerCsv` (commons) — pick each scenario's column by the **`value` row** (`total execution
  time`) instead of by position, and name the metric in the failure message so a future profiler
  rename is diagnosable at a glance. Keep the existing drift tolerance: a CSV with no `value` row at
  all still parses positionally.
- `run-overhead.sh` — pre-clean `fixture/build/buildhound` before the first variant, so a stale
  directory from an earlier run cannot make the plugin-on self-test pass on someone else's output.

## 4. Test strategy

`ProfilerCsvTest` covers the parser change, and its two sample CSVs
(`jvmTest/resources/overhead/benchmark-{on,off}.csv`) are **replaced with output captured from a
real gradle-profiler run**. These are test fixtures, not payload goldens — the additive-only,
never-edit rule does not apply to them. The old ones were hand-authored and unfaithful in exactly
the two ways that hid defects 4 and 5 (ids where the real file has titles; one column per scenario
where `--measure-config-time` emits two), so keeping them would keep the tests green on a shape
that never occurs. The rest of the harness is verified by *running* it:

- `--dump-scenarios` shows the resolved `gradle-args` per variant (proves 1 and 3 by inspection).
- Full local harness run producing a real four-axis table with verdicts, plus the toggle self-test
  passing in both directions (plugin-on emits `build/buildhound/`, plugin-off does not).
- Failure injection (plan 034 §5 guardrail): `set -eo pipefail; false | tee /dev/null` → exit 1,
  versus `set -e` → exit 0. That one-liner *is* defect 2 and its fix.

## 5. Risks

- **Runtime.** gradle-profiler defaults are 6 warm-ups + 10 measured builds; five scenarios × two
  variants = 160 fixture builds, each carrying a composite build of this repo. With
  `cancel-in-progress: true` on the workflow, a multi-hour job that never finishes before the next
  push is a new flavour of inert. Measure locally, extrapolate to the 4-vCPU runner, and record the
  decision (explicit `timeout-minutes`, and bounded iterations if needed).
- **A first real run may report a breach.** The committed caps were never calibrated against a real
  measurement. A breach is a finding to report with numbers, not something to silence by widening
  `DEFAULT` — that is out of scope per §2.
- **Local ≠ CI.** A green macOS table proves the harness works; plan 104 criterion 5 says the *CI
  job* passes. The note added to plan 104 records "harness repaired + local measurements"; the
  criterion only closes on a green CI run with a real table.
- **The configuration axis measures total build time, not config-phase time.** `docs/overhead-budget.md`
  is internally inconsistent here: its formula (line 10) is `mean(on) − mean(off)` of *total* build
  time, while its axis table (line 15) describes `cc_hit` as isolating config-phase cost via
  `--measure-config-time` — which is the `task start` column. `AxisSample` carries no metric, so
  honouring the second reading means adding one and rewiring the budget. This plan selects
  `total execution time` **uniformly for all five scenarios**, consistent with the formula and the
  single-table shape; per-axis metric selection is a deliberate follow-up, not an oversight. Stated
  here so the config axis is not silently measuring something the docs table does not describe.
- No security/privacy surface: no payload, schema, token or endpoint change. The loopback sink
  stays a do-nothing local `127.0.0.1` server.

## 6. Exit criteria

1. `run-overhead.sh` completes a real gradle-profiler run and prints the four-axis table
   (Configuration / Per-task / Finalizer / Upload) with per-axis verdicts.
2. The toggle self-test passes in both directions.
3. `--dump-scenarios` shows a literal-free, correctly interpolated `gradle-args` for both variants.
4. The CI step fails when the harness fails (pipefail in place; `set -e`-only semantics proven to
   swallow it).
5. `ProfilerCsv` parses a **real** `benchmark.csv` into the five scenario ids with the
   `total execution time` mean; `./gradlew build` green.
6. Plan 104's criterion-5 note updated with what was measured and what still needs a CI run.

> Numbering: 105 is taken by an unmerged sibling branch
> (`plan: composite action in CI + nightly sample benchmark to prod`), so this plan is 106.

## 7. Result of the first real run (2026-08-09)

A sixth defect surfaced when the verdict step finally ran: gradle-profiler writes **no summary rows
at all**, so `ProfilerCsv`'s required `mean` row never existed and the run aborted with
`benchmark.csv has no 'mean' row`. Mean and sample stddev are now computed from the `measured build
#N` rows. The harness then completed: 160 builds, ~7 min wall clock on a 10-core M-series, toggle
self-test green in both directions, and a real table:

```
| Axis          | Baseline (ms) | Plugin (ms) |  Δ (ms) |   Δ (%) | Allowance (ms) | Separated | Verdict   |
| configuration |          90.9 |      1599.4 |  1508.4 |  1658.7 |           40.0 |    yes    | ❌ BREACH |
| per-task      |         648.3 |      2510.8 |  1862.5 |   287.3 |           32.4 |    yes    | ❌ BREACH |
| finalizer     |          87.8 |      1912.4 |  1824.6 |  2078.2 |          150.0 |    yes    | ❌ BREACH |
| upload        |        1592.6 |      1595.0 |     2.4 |     0.1 |          250.0 |    no     | ✅ ok     |
```

**These are not three independent regressions — they are one fixed cost seen three times.** The
deltas are near-identical (1508 / 1862 / 1825 ms) because the plugin's per-build cost is essentially
constant and every axis subtracts a plugin-off baseline from a plugin-on one. A corollary for the
follow-up: while a fixed cost of this size dominates, the per-task axis cannot measure anything
per-task, so fixing the cost has to come before trusting that axis's shape. The axes are also less
distinct than they look — the fixture sets `org.gradle.configuration-cache=true`, so `no_op` is
already a configuration-cache-hit build (a manual run without the flag prints "Configuration cache
entry reused"), and the `cc_hit` scenario differs from it only by passing `--configuration-cache`
explicitly. Separating the configuration axis from the finalizer axis therefore needs fixture work,
not just a cheaper probe.

The upload axis's `✅` is a real measurement, not an absent one — a fixture build in CI mode against
the loopback sink logs `payload uploaded (3037 bytes gzip)`. But `separated: no` means the honest
reading is "the upload's cost is not resolvable above this fixture's noise", not "upload is free".

**The breach is real, not a harness artifact**, and it is not plan 104's doing. Isolated on the same
fixture at steady state (fully up-to-date, configuration-cache hit):

| Variant | Wall clock |
|---|---|
| plugin not applied | ~0.53 s |
| plugin applied, `buildhound.enabled=false` | ~0.57 s |
| plugin on, `buildhound.processProbe.enabled=false` | ~0.70 s |
| plugin on, full | ~5.0–6.0 s |

So the composite `includeBuild` costs ~35 ms (it is not the explanation), the plugin's non-probe work
costs ~130–170 ms, and **the process probe accounts for the rest**. The probe spawns four JVM-based
tools per detected JVM (`jps` once, then `jstat -gc`, `jstat -capacity`, `jinfo` per PID, plus one
`ps`) at ~130 ms of JVM startup each; this machine had 15 live JVMs, hence ~60 subprocesses. The cost
therefore **scales with the number of JVMs on the machine**, which is why the profiler run (fewer
daemons alive) shows ~1.8 s while a laptop with several worktrees' daemons shows ~5 s.

Plan 104 is exonerated by its own diff: it *removed* one `ps` exec per PID (two collapsed into one).
The per-PID subprocess design predates it (plan 029).

Fixing the probe is out of scope here — this plan repairs the instrument, and the instrument's first
reading is a finding for its own plan. What must not happen is widening `OverheadBudget.DEFAULT` to
make the table green.

A seventh defect surfaced while verifying the upload axis: the script ended with
`exec bin/buildhound-overhead`, and `exec` replaces the shell, so the `EXIT` trap never fired and the
loopback sink was orphaned (verified both ways with a minimal reproduction). The stale sink keeps the
port bound, so the *next* run's readiness probe succeeds against it while its own sink fails to bind
— a masked failure on any machine that runs the harness twice. The `exec` is gone.
