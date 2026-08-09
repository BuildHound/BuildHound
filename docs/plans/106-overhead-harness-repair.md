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

**Out:** calibrating `OverheadBudget.DEFAULT` (docs/overhead-budget.md §"The budget" reserves that
for the first green *CI reference-runner* run + a decision-log row — an owner call, not a
side effect of this repair); promoting the job to blocking; bumping the pinned gradle-profiler
version/SHA; any plugin, commons or server code change.

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

## 4. Test strategy

No product code changes, so no unit/TestKit/golden work. The harness is verified by *running* it:

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
- No security/privacy surface: no payload, schema, token or endpoint change. The loopback sink
  stays a do-nothing local `127.0.0.1` server.

## 6. Exit criteria

1. `run-overhead.sh` completes a real gradle-profiler run and prints the four-axis table
   (Configuration / Per-task / Finalizer / Upload) with per-axis verdicts.
2. The toggle self-test passes in both directions.
3. `--dump-scenarios` shows a literal-free, correctly interpolated `gradle-args` for both variants.
4. The CI step fails when the harness fails (pipefail in place; `set -e`-only semantics proven to
   swallow it).
5. Plan 104's criterion-5 note updated with what was measured and what still needs a CI run.

> Numbering: 105 is taken by an unmerged sibling branch
> (`plan: composite action in CI + nightly sample benchmark to prod`), so this plan is 106.
