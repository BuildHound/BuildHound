# 105 — Composite action in CI + nightly sample benchmark to production

## 1. Source

Feature request (owner, 2026-08-09):

1. Exercise `buildhound-ci-assets/github/action.yml` from this repository's own CI, so the
   shipped composite action can't rot unnoticed.
2. Bump that action to the latest `gradle/actions/setup-gradle` already used elsewhere in the
   repo.
3. Wire `buildhound-ci-assets/profiler-pipeline/github-nightly-benchmark.yml` up for real, so a
   nightly benchmark series lands on the **production** dashboard.
4. Run that nightly benchmark against the **sample projects** (`samples/*`).

Implements spec §7 (CI assets), plan 030 (benchmark mode) and plan 041 (composite action) as
running pipelines rather than shipped-but-undriven templates.

## 2. Scope

**In**

- `buildhound-ci-assets/github/action.yml`: pin `setup-gradle` to the commit SHA of `v6.3.0`
  (the pin `publish-gradle-plugin.yml` already uses).
- `.github/workflows/ci.yml`: new `composite-action` job that runs the action from the checkout
  (`uses: ./buildhound-ci-assets/github`), credential-free.
- `.github/buildhound-sample-benchmark.init.gradle.kts`: CI-injection script that re-points a
  sample's `server.url`/`server.token` at the real ingest server from
  `BUILDHOUND_SAMPLE_SERVER_URL`/`BUILDHOUND_SAMPLE_TOKEN`. **The samples themselves are not
  touched** — see §3.3 for why this is a `settingsEvaluated` sibling of the dogfood script rather
  than the dogfood script itself.
- New per-sample gradle-profiler scenario files under
  `buildhound-ci-assets/profiler-scenarios/samples/`.
- New `.github/workflows/nightly-benchmark.yml`: scheduled matrix over
  (pilot × isolation × scenario), uploading `mode=benchmark` builds to production.
- Modernize the shipped template `profiler-pipeline/github-nightly-benchmark.yml` to match what we
  actually run (matrix, pinned profiler download, prod env wiring).
- Docs: ci-assets README, `docs/recipes/benchmark-and-experiments.md`, `samples/README.md`.

**Out**

- Changing the eight `gradle/actions/setup-gradle@v6.3.0` tag refs in `ci.yml` to SHAs (unrelated
  posture change).
- A shell harness for the composite action's verdict-gate script (`buildhound-ci-assets/test/*.sh`
  is the honest home for its deeper branches) — follow-up.
- Promoting either new job to blocking. Both are advisory this round.
- Benchmarking the root BuildHound build itself (its CI dogfood series already exists, plan 093).

## 3. Design

### 3.1 Composite action self-test (`composite-action` job)

Cheap by construction: `gradle-tasks: help`, `verdict-gate: warn`, **no** `server-url`/`token`.
What it actually covers, and nothing more:

- composite `action.yml` validity — actionlint lints `.github/workflows/**` only, never a
  composite action file, so today nothing parses this file in CI;
- the input defaults and the new `setup-gradle` pin resolving;
- the env mapping (`GRADLE_TASKS`, `BUILDHOUND_*`) and the gate step's `if:`;
- the gate script's early exits (`no payload written` → exit 0).

It is also the only job that drives `./gradlew` rather than the `setup-gradle`-provisioned `gradle`
binary — incidental extra coverage of the wrapper path.

### 3.2 Nightly benchmark

One workflow, `.github/workflows/nightly-benchmark.yml`, cron `0 3 * * *` + `workflow_dispatch`,
`if: github.repository == 'BuildHound/BuildHound'` so forks never schedule it.

Matrix (explicit `include`, `fail-fast: false`, `continue-on-error: true` — watched, not blocking):

| Pilot | Isolations | Scenarios | Why |
|---|---|---|---|
| `springboot-legacy` | `no_build_cache` | 4 | its committed config has `org.gradle.caching=false`; labelling it `full_cache` would be a lie, and forcing caching on would defeat the sample's deliberately sub-optimal point |
| `android-legacy-agp` | `full_cache`, `no_build_cache` | 4 | caching on by default; small AGP-8.5/Gradle-8.14 floor build |
| `nowinandroid` | `full_cache`, `no_build_cache` | 4 | the realistic large Android build |

= 20 jobs, `max-parallel` capped.

Per-job wiring:

- `BUILDHOUND_BENCHMARK_SCENARIO` / `_ISOLATION` from the matrix; `_SEED_REF` = `github.run_id`, so
  every job in one nightly shares a single seed ref (the shipped template's per-loop `date` would
  split the series across a matrix).
- Isolation is applied by appending `org.gradle.caching=false` to the pilot's `gradle.properties`
  for `no_build_cache`; the checkout is fresh per job, so no pristine-copy dance.
- Upload target: `BUILDHOUND_SAMPLE_SERVER_URL` = `vars.BUILDHOUND_PROD_SERVER_URL`,
  `BUILDHOUND_SAMPLE_TOKEN` = `secrets.BUILDHOUND_PROD_INGEST_TOKEN` (the repo-level ingest slot plan 094
  already provisioned). The URL expression collapses to `''` when the token secret is absent, and
  `UploadGate` skips on an empty URL (`no server configured`) — so this YAML is safe before/without
  credentials and can never POST unauthenticated.
- The samples apply the plugin from source (`includeBuild("../..")`), so no `publishToMavenLocal`
  bootstrap is needed — unlike the root-build dogfood path.
- JDKs: 26 **and** 21 installed, 21 last so it stays `JAVA_HOME` (`android-legacy-agp` runs Gradle
  8.14.5, which cannot run on 26); 26 stays available for the included BuildHound build's toolchain.
- gradle-profiler is downloaded from `repo1.maven.org` with a pinned SHA-256, matching the
  `overhead-budget` job — not the shipped template's unpinned `repo.gradle.org` URL.

### 3.3 Injecting the ingest target without editing the samples

**Revision (owner question, same PR).** The first cut made each sample's `server.url` env-overridable.
That works, but it edits three files whose job is to be the committed *reference* configuration for
local development. The samples now stay exactly as they are, and CI injects instead — the pattern
plan 093 already established for the root build.

The existing `.github/buildhound-dogfood.init.gradle.kts` cannot be reused for this, for two
structural reasons:

1. It *applies* the plugin from mavenLocal (`initscript` classpath + `Class.forName`). The samples
   already apply the plugin themselves from source via `includeBuild("../..")`, so that path would
   mean two classloaders' copies of one plugin id — and would need a `publishToMavenLocal` bootstrap
   the samples otherwise don't need.
2. It overrides in `beforeSettings`, which runs **before** a `settings.gradle.kts` is evaluated.
   Correct for the root build (no `buildhound { }` block of its own), wrong for a sample: the
   sample's `server { url = "http://localhost:8080" }` literal is applied afterwards and wins.

So: a sibling script, `.github/buildhound-sample-benchmark.init.gradle.kts`, hooking
`settingsEvaluated` — the extension exists and is configured by then, and the plugin's
`parameters.serverUrl.set(extension.server.url)` is a lazy Property→Property link, so a later `set`
still propagates. Reflection-only and fully guarded (never fails a build); a build with no
`buildhound` extension (the included plugin build) is a logged no-op.

The workflow installs it into the runner's `~/.gradle/init.d/` rather than threading `-I` through
every scenario's `gradle-args`, so no scenario file carries a `--project-dir`-relative path.

Verified locally against `samples/springboot-legacy` with a logging HTTP sink: with the script + env
set, the payload is POSTed to the injected URL with an `Authorization` header (the sample's localhost
literal is overridden); with the script and no env, no upload is attempted at all.

Scenario files are per-pilot (`profiler-scenarios/samples/<pilot>.scenarios`) because tasks and the
non-ABI source path differ per sample. The shipped consumer file stays Android-pilot-shaped and
untouched. Scenario *names* stay inside `BenchmarkActivation.SCENARIOS`
(`clean|no_op|incremental_non_abi|cc_hit`) — the plugin rejects anything else. `warm-ups`/
`iterations` are set explicitly (profiler's 6/10 defaults would never finish).

Known and accepted: gradle-profiler does not export `BUILDHOUND_BENCHMARK_ITERATION`, and warm-up
builds upload too, so a series carries `iteration=null` rows for both warm-ups and measured runs.

## 4. Test strategy

- `actionlint` (already a CI step) covers both new/edited workflow files.
- `composite-action` job is itself the test for the composite action.
- The nightly workflow cannot be proven by CI: it is verified by a `workflow_dispatch` run **after
  merge** (a scheduled workflow must be on the default branch to be dispatchable), then by reading
  the production dashboard `#/benchmark` for rows tagged with the expected
  `(scenario, isolationMode)` pairs under one seed ref. Green jobs alone do not prove telemetry
  landed.
- No Kotlin/schema change ⇒ no golden-file or TestKit work.

## 5. Risks

- **Mislabelled series** — if `BUILDHOUND_BENCHMARK_SCENARIO` fails to reach the measured daemon,
  the plugin mislabels rather than errors. Mitigated by one scenario per job (fresh runner, fresh
  daemon); must be spot-checked on the dashboard.
- **Runner capacity** — `nowinandroid` asks for `-Xmx4g` plus a 4 GB Kotlin daemon on a 4-vCPU
  runner. `continue-on-error` keeps an OOM from reddening the nightly; if it recurs, the fix is a
  bigger runner label (needs an `actionlint.yaml` entry too).
- **Cost** — 20 nightly jobs of real Android builds. `max-parallel` caps concurrency; the matrix is
  trimmable per pilot.
- **Security/privacy** — the prod ingest token is a repo secret exposed to a scheduled job on the
  default branch only (no fork, no PR trigger). Token flows env → `Authorization` header, never
  argv, never echoed. Sample telemetry is synthetic/public code; no new payload field.
- **Silent non-publication** (security review, MEDIUM) — every layer of a non-publishing run is
  quiet: `UploadGate`'s skip logs at `info`, the plugin never fails a build, and the job is
  `continue-on-error`. A rotated-away credential would give 20 green jobs a night that publish
  nothing. Addressed by the `Report ingest wiring` step: a `::warning::` + job-summary line
  whenever the URL or token is empty, so absence of publication is visible without opening the
  dashboard.
- **`workflow_dispatch` as a token-exposure entry point** (security review, LOW) — a
  write-collaborator can dispatch an edited copy of this workflow from any branch, reaching the
  same prod ingest secret plan 094 already accepts as write-collaborator-exposed. Same actor class
  and same ingest-only blast radius; plan 094's risk section is updated to name this entry point.
- **`http://` ingest URLs** (security review, LOW, pre-existing) — `UploadGate` accepts `http://`
  and `PayloadUploader` only warns, so a mis-set `vars.BUILDHOUND_PROD_SERVER_URL` would put the
  Bearer token on the wire in cleartext. Not introduced here (this plan only routes an existing
  credential through the existing gate at nightly scale); tightening the gate is a separate change.
- **Sample dev loop** — untouched: the samples keep their committed localhost config and the
  redirection lives entirely in an init script CI applies (§3.3).

## 6. Exit criteria

1. `buildhound-ci-assets/github/action.yml` pins `setup-gradle` at the v6.3.0 commit SHA; README
   text agrees.
2. CI has a `composite-action` job and it is green on this PR.
3. `.github/workflows/nightly-benchmark.yml` exists, lints, and is credential-safe when secrets are
   absent.
4. Per-sample scenario files exist with real task and source paths, and the samples'
   `settings.gradle.kts` files are unchanged by this PR.
5. Shipped `github-nightly-benchmark.yml` reflects the same shape we run.
6. **Post-merge**: one `workflow_dispatch` run, and production `#/benchmark` shows rows for each
   `(pilot, scenario, isolation)` under a single seed ref. Until then this plan stays in
   `docs/plans/` (not `implemented/`).
