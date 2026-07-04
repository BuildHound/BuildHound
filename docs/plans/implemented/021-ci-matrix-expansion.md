# Plan 021 - CI matrix: isolated-projects job, macOS leg, Windows evaluation, CC-off axis

**Status: planned - roadmap phase 2a** · 2026-07-03

## 1. Source

- [Roadmap phase 2a](../build-telemetry-roadmap.md): "CI: add the spec-promised non-blocking
  isolated-projects job; add macOS (and evaluate Windows) legs — plan 007's only field bug was
  macOS-only; decide the CC-off matrix axis", plus the cross-phase guardrail "isolated-projects
  job blocking-optional until phase 2a lands it, then watched".
- [Spec §3.1](../build-telemetry-spec.md): compatibility contract — "config cache on/off both
  green, `isolated-projects` best-effort (CI job tracks it, non-blocking)".
- [Architecture §2 rule 6](../architecture.md): the promised TestKit matrix
  {Gradle 8.14, 9.latest} × {CC on/off} and the isolated-projects CI job.
- Research: [comparison-to-spec.md](../research/comparison-to-spec.md) findings 4 ("Isolated
  Projects is tested by the ecosystem, only tracked by BuildHound … define the degradation
  contract") and 5 ("No Windows or macOS leg in CI");
  [Talaiot.md](../research/repos/Talaiot.md) (IP scenario via
  `-Dorg.gradle.unsafe.isolated-projects=true`, ubuntu/macOS/Windows sample canary);
  [InfoKotlinProcess.md](../research/repos/InfoKotlinProcess.md) (two-build store/reuse test
  template incl. the IP variant; {ubuntu, macos} test matrix).

## 2. Scope

**In:** an isolated-projects TestKit suite + a watched (non-blocking) CI job running it; the
per-collector IP degradation contract written into `docs/architecture.md`; a blocking macOS CI
leg; a watched Windows canary leg with explicit promote-or-defer criteria; the CC-off decision
realized as an injectable CC mode for the functional-test harness plus a blocking CC-off CI job;
architecture rule 6 rewritten to the realized matrix + decision-log rows.

**Out:** implementing any IP degradation path (the first one — the task-type/cacheable
dictionary gated on `BuildFeatures` — is plan 016; this plan defines the contract it must
satisfy); spec §1/§8 text repairs including the stale `{Gradle 8.0…} × {Kotlin 2.0/2.2}` matrix
sentence (plan 020 — it should copy the matrix decided here); multi-JDK-vendor axes and any
remaining pre-OSS-launch CI hardening (plan 042); OS-sensitive process-probe work (plan 029);
dogfooding the plugin on this repo's own build (worth doing, no plan yet — noted for the phase
retro). No schema change of any kind, so no golden files.

## 3. Design

**Current reality (verified in source).** CI is three ubuntu-only jobs: `build` on Gradle 9.6.1
/ Temurin 26 (`.github/workflows/ci.yml:13-36`), `build-floor` on Gradle 8.14.4
(`ci.yml:38-62`), and `server-image` (`ci.yml:64-95`). No macOS, no Windows, no
isolated-projects job, no CC-off leg. The repo's own build keeps CC on
(`gradle.properties:3`). The functional tests pass `--configuration-cache` explicitly at every
call site (e.g. `BuildHoundSettingsPluginFunctionalTest.kt:69`,
`UploadFunctionalTest.kt:94`); exactly one inner build runs `--no-configuration-cache` — the
`cc state tracks store then hit and disabled` case
(`BuildHoundSettingsPluginFunctionalTest.kt:102`). The `runner()` helper
(`BuildHoundSettingsPluginFunctionalTest.kt:50-54`) never calls `withGradleVersion`, so inner
TestKit builds run on the outer job's Gradle — the Gradle axis is already realized by the two
CI jobs, and every new job inherits it the same way. The `functionalTest` source set and task
live in `buildhound-gradle-plugin/build.gradle.kts:54,80-86`, wired into `check` (`:88-90`),
tests on a JDK-21 launcher (`:92-98`).

**Isolated-projects job.** A new `IsolatedProjectsFunctionalTest` class uses a multi-project
fixture (root + two subprojects — IP is about cross-project isolation; the existing
single-project `hello` fixture cannot surface it) and runs inner builds with
`-Dorg.gradle.unsafe.isolated-projects=true` (Talaiot's exact recipe, proven on Gradle 9.x).
Assertions follow InfoKotlinProcess's two-build store/reuse template, but pinned on BuildHound's
own signals (summary line `cc=HIT`, payload intact) rather than Gradle's console text. The class
is `@Tag("isolated-projects")`; the default `functionalTest` task excludes the tag, a new
`isolatedProjectsTest` task includes only it and is *not* wired into `check`. The CI job runs it
with `continue-on-error: true`: the workflow stays green, the job shows red — "watched", per the
guardrail. Today the job should pass: every current collector is IP-neutral — task events come
from `BuildEventsListenerRegistry.onTaskCompletion` (`BuildHoundSettingsPlugin.kt:50`),
environment/vcs/ci are ValueSources reading env and subprocesses (`:55-73`), CC state is the
settings-scoped `DaemonState` observer, and the finalizer is a Flow action (`:78`). The plugin
already injects `BuildFeatures` (`BuildHoundSettingsPlugin.kt:23`, used at `:87`), so IP
detection needs no new machinery.

**IP degradation contract (new architecture §2 rule).** Any field whose collection needs
configuration-time cross-project state must: (a) detect IP via public
`BuildFeatures.isolatedProjects.active`; (b) degrade to null/empty, never to partial data —
derived metrics computed from degraded inputs also go null (honest nulls, plan 005 precedent);
(c) log a single info line naming the degraded fields; (d) never warn-spam or fail the build;
(e) land a **blocking** TestKit degradation test in the same PR — a self-contained case that
enables IP itself and asserts the degraded payload shape plus build success. Promotion is
therefore test-by-test, not a job flip: the watched IP job runs the general suite under IP to
catch unknowns and stays `continue-on-error` while the flag is `unsafe.`-prefixed; per-collector
guarantees are enforced by the blocking degradation tests. First consumer: plan 016's
type/cacheable dictionary (empty map under IP → `type`/`cacheable` null → `cacheableHitRate`
null).

**macOS leg (blocking).** `build-macos` mirrors the `build` job on `macos-latest`
(Temurin 26, Gradle 9.6.1, `gradle build`). Full-suite, not a sample canary: plan 007's
macOS-only field bug was in scrubber path handling
([implemented/007-scrubber.md](007-scrubber.md)) and is covered by exactly these
unit + functional tests. The server's Postgres integration test self-skips where Docker is
absent (`PostgresStoresIntegrationTest.kt:22`, `@Testcontainers(disabledWithoutDocker = true)`),
so `gradle build` is viable on macOS/Windows runners, which ship no Docker. One macOS job only —
no floor cross-product (the floor job pins embedded-stdlib behavior, which is OS-independent;
this also keeps the 10× macOS billing multiplier bounded while the repo is private).

**Windows evaluation (watched canary).** `build-windows` runs `gradle build` on
`windows-latest` with `continue-on-error: true`. Known, accepted gaps: the hung-git fixture is
`@DisabledOnOs(OS.WINDOWS)` (`BuildHoundSettingsPluginFunctionalTest.kt:197`) and the `GitExec`
unit fixtures are POSIX-only (plan 015's recorded scope cut). Evaluation criteria, recorded in
the decision log: green for ~2 weeks of PRs → promote to blocking no later than plan 042;
red → file each failure as its own follow-up task and keep the canary watched. Scrubber, spool
paths, and VCS exec are the OS-sensitive surfaces the canary is for.

**CC-off axis (decision: one blocking leg, not a cross-product).** The `runner()` helper gains
an injected CC mode: system property `buildhound.testkit.cc` (`on` default / `off`) appends
`--configuration-cache` or `--no-configuration-cache`; the explicit flags disappear from
ordinary call sites. Tests that pin CC semantics themselves (store/hit/disabled, CC-reuse
survival) keep managing their own flags through an explicit-args variant and stay meaningful in
both modes. The `functionalTest` task forwards the mode from Gradle property
`buildhound.testkit.cc` (naming precedent: `buildhound.vcs.timeout.ms`,
`buildhound.optin.file`) via `providers.gradleProperty` — a legitimate CC input of the outer
build, which itself always keeps CC on (`gradle.properties:3`; the constraint "never disable
CC" governs the outer build, not TestKit inner builds, which default to CC off anyway). CI adds
one blocking `functional-cc-off` job (ubuntu, Gradle 9.6.1) running only
`:buildhound-gradle-plugin:functionalTest -Pbuildhound.testkit.cc=off`. A full
{OS} × {Gradle} × {CC} cross-product (12 jobs) is deliberately rejected: CC-off is the simpler
execution model, and its failure modes (mode-detection branches, `DaemonState` across daemon
reuse) do not vary by OS or floor.

## 4. Implementation steps

1. Refactor `BuildHoundSettingsPluginFunctionalTest.kt` and `UploadFunctionalTest.kt`: `runner()`
   reads `buildhound.testkit.cc` and injects the CC flag; strip explicit `--configuration-cache`
   from default-path call sites; CC-semantics cases use an explicit-args escape hatch.
2. `buildhound-gradle-plugin/build.gradle.kts`: forward `buildhound.testkit.cc` (Gradle property,
   default `on`) into the `functionalTest` task as a system property, provider-based.
3. Run `functionalTest` locally in both modes; fix any test that silently depended on CC-on.
4. Add `IsolatedProjectsFunctionalTest` (functionalTest source set): multi-project fixture,
   `-Dorg.gradle.unsafe.isolated-projects=true` (single constant), two runs; assert both
   subprojects' tasks in the payload, `cc=HIT` on the second run, build SUCCESS, no
   `[buildhound]` warn lines. Tag `@Tag("isolated-projects")`.
5. `build.gradle.kts`: `functionalTest` excludes the tag (`excludeTags`); register
   `isolatedProjectsTest` (Test) including only it; not a dependency of `check`.
6. `.github/workflows/ci.yml`: add `functional-cc-off` (ubuntu, Temurin 26, Gradle 9.6.1,
   blocking), `isolated-projects` (ubuntu, `isolatedProjectsTest`, `continue-on-error: true`),
   `build-macos` (macos-latest, `gradle build`, blocking), `build-windows` (windows-latest,
   `gradle build`, `continue-on-error: true`). Keep the existing setup-gradle caching per job.
7. `docs/architecture.md`: rewrite §2 rule 6 to the realized matrix (two Gradle versions ×
   {ubuntu blocking, macos blocking, windows watched} + CC-off leg + watched IP job); add the IP
   degradation-contract rule (§3 design above, items a–e, promotion policy).
8. `docs/architecture.md` §7 decision-log rows (dated): CC-off axis as one blocking leg with the
   rejected cross-product rationale; IP watched job + per-collector degradation contract with
   test-by-test promotion; macOS blocking + Windows watched canary with its promote-or-defer
   criteria.
9. Full `./gradlew build`; push and confirm all seven jobs run; confirm the two watched jobs
   report independently of workflow status.

## 5. Test strategy

- **New functional (IP):** `payload survives isolated-projects store and reuse` (two-build
  template) and `subproject tasks are captured under isolated projects` — these are the watched
  suite; per-collector *blocking* degradation tests are each future collector's obligation
  (first: plan 016).
- **CC-off axis:** the entire existing functional suite re-runs with inner-build CC off — this
  is the coverage, no new cases needed; the store/hit/disabled case self-pins its flags and
  keeps passing in both modes.
- **Harness checks:** `functionalTest -Pbuildhound.testkit.cc=off` and `isolatedProjectsTest`
  runnable locally; default `functionalTest` provably excludes the IP tag (fast check: test
  counts).
- **Failure-injection guardrail:** no new plugin runtime paths are added, so no new injection
  seams; the IP job re-exercises every existing failure path under a new execution model, and
  the degradation contract mandates injection-style tests per future collector.
- Golden files: none — no schema change.

## 6. Risks

- **The IP flag is incubating** (`org.gradle.unsafe.isolated-projects`); a rename or semantics
  shift breaks the watched job for tool reasons. Mitigation: flag isolated in one constant; the
  job is non-blocking by design; revisit at each Gradle bump.
- **`continue-on-error` hides red behind a green workflow** — "watched" requires looking.
  Mitigation: distinct job names, and the phase-2a retro (roadmap guardrail) explicitly reviews
  both watched jobs.
- **CC hazard in the harness seam:** the mode property becomes an outer-build CC input; wiring
  it eagerly outside providers would poison CC reuse. Mitigation: `providers.gradleProperty`
  only; the repo's own CC stays on, per CLAUDE.md.
- **Windows unknowns** (path separators, CRLF checkout mangling fixture scripts): exactly what
  the canary exists to find; failures become follow-up tasks, not scope creep here.
- **Runner cost/flakiness:** macOS bills 10×, Windows 2× while private. Mitigation: one job
  each, no cross-products, existing `cancel-in-progress` concurrency (`ci.yml:8-10`).
- **Security/privacy:** nothing new collected or uploaded; new jobs need no secrets; payload
  and scrubber untouched.

## 7. Exit criteria

- `.github/workflows/ci.yml` runs seven jobs; `build-macos` and `functional-cc-off` are green
  and blocking; `isolated-projects` and `build-windows` exist, run on every PR, and cannot fail
  the workflow.
- The full functional suite passes with `-Pbuildhound.testkit.cc=off` locally and in CI.
- `isolatedProjectsTest` passes on Gradle 9.6.1: payload survives IP store *and* reuse with
  both subprojects' tasks present.
- `docs/architecture.md` §2 rule 6 matches the realized matrix; the IP degradation contract
  (a–e + promotion policy) is a numbered §2 rule; §7 has the three new decision rows.
- Plan 016 can point at the contract for its `BuildFeatures`-gated degradation and at the
  blocking-degradation-test obligation without further CI work.

## 8. Divergences from the plan (recorded during implementation)

- **The IP degradation contract's first consumer already ships its blocking test.** Plan 016
  landed before this plan, so its `isolated projects degrades task metadata to null` case
  (in the default, blocking `functionalTest`) already satisfies contract clause (e); §2 rule
  13 and its decision-log row note this rather than leaving it as a future obligation.
- **`useJUnitPlatform()` moved out of `tasks.withType<Test>().configureEach`.** The old
  block applied a no-arg `useJUnitPlatform()` to every Test task, which would have reset the
  per-task `excludeTags`/`includeTags` filters. It is now applied per task: `test`
  (`tasks.named`), `functionalTest` (`excludeTags`), and `isolatedProjectsTest`
  (`includeTags`); `configureEach` keeps only the shared JDK-21 `javaLauncher`.
- **Seven jobs total** (`build`, `build-floor`, `server-image`, `functional-cc-off`,
  `isolated-projects`, `build-macos`, `build-windows`) — the pre-existing `server-image` job
  is counted in the seven the exit criteria name.
