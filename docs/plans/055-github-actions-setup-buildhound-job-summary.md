# 055 — GitHub Actions job summary + `cache-provider: basic` positioning

## Source

- Research finding **F5** (`docs/research/ingest-corpus-analysis.md`), from
  `Choice, Clarity, and the Future of Caching in Gradle Actions.md` and
  `You Are Probably Already Running gradleactions.md`: `gradle/actions` v6 moved
  `setup-gradle` caching toward a proprietary Develocity-lineage component with an opt-out
  `basic` provider — a positioning gift for "the open-source Develocity alternative."
- Spec §3.2 (Flow finalizer, never-fail), §3.3 (CI provider detection), §3.7 (privacy);
  builds on the composite action shipped by **plan 041**.

## Scope

**In**

- **Plugin:** when the detected CI provider is `github-actions`, the Flow finalizer appends a
  small Markdown block (outcome, wall-clock duration, cacheable hit rate, requested tasks,
  dashboard deep link) to the file named by `$GITHUB_STEP_SUMMARY`. Generalized to Azure
  (`azure-devops`): write the same Markdown to a build-local file and emit the
  `##vso[task.uploadsummary]` logging command. Never-fail, bounded, silent-degrade.
- A `ci { }` DSL block with `jobSummary` (default `true`) + `dashboardUrl` (optional), plus
  the `buildhound.ci.jobSummary` / `buildhound.ci.dashboardUrl` property overrides wired
  through `ConfigOverrides` precedence (explicit → override → default), matching plan 027.
- **CI-assets:** extend `buildhound-ci-assets/github/action.yml` to run
  `gradle/actions/setup-gradle` pinned to the open-source **`basic`** cache provider (input
  name pinned from `gradle/actions` v6 docs at implementation time), toggleable, plus a
  positioning note ("no cache-key metadata leaves your infrastructure") in the ci-assets
  README.

**Out (deferred / owned elsewhere)**

- **Full drop-in `setup-buildhound` via init-script auto-apply** (apply the settings plugin
  from a shipped `--init-script` so consumers never edit `settings.gradle.kts`). Finding
  narrowing 1 lists only the `cache-provider: basic` pairing and the STEP_SUMMARY block as
  *new*; the existing action already assumes the plugin is applied. Named follow-up.
- **No schema change, no new payload field, no golden file touched** — the summary is a
  side-effect render over already-assembled payload fields, not collected data.
- Server-side CI connectors / span trees (plans 028, 041); verdict-gate step (plan 025,
  already in the action).

## Design

- **New `CiJobSummary.kt`** (`dev.buildhound.gradle`): a pure `render(payload, dashboardBaseUrl): String`
  (unit-testable Markdown) + a side-effecting `write(payload, provider, env, dashboardBaseUrl, warn)`.
  Rows derive from the assembled `BuildPayload`: `outcome`, `finishedAt − startedAt`,
  `derived?.cacheableHitRate` (→ "n/a" **when `derived` is null**, not on CC hit — the rate is
  computed from task outcomes and survives a hit; only `configurationMs` is 0/absent there),
  `requestedTasks`, and a deep link `"$base/#/build/$buildId"` (the dashboard route in
  `dashboard.js:254`). `base` is `dashboardUrl` else `serverUrl`, gated by an `isHttpUrl`
  check (reuse the `CiEnvironmentProviders.kt:295` guard posture) so a `javascript:` base
  can never form a link; if no http(s) base, omit the link and still write the rows.
- **Finalizer wiring:** `TelemetryFinalizerAction.Parameters` gains `jobSummary: Property<Boolean>`
  and optional `dashboardUrl: Property<String>`. After `writePayload(...)` in `execute()`,
  gated on `parameters.jobSummary.getOrElse(true)`, call `CiJobSummary.write(...)` inside its
  own `runCatching` (already nested under the finalizer's outer swallow). Provider comes from
  the already-read `parameters.ci.orNull?.provider`.
- **Env read at Flow (execution) time** via `System.getenv("GITHUB_STEP_SUMMARY")` — the same
  posture as `CiValueSource.obtain()` (`System.getenv()`), **not** `providers.environmentVariable`
  (which would bake a CC fingerprint input and replay stale on a hit). GHA path = append to
  the file; Azure path = write `build/buildhound/job-summary.md` then raw `println(
  "##vso[task.uploadsummary]<path>")` (a `##vso` stdout command must start the line — raw
  stdout, not the Gradle logger, per finding narrowing 3).
- **DSL/props:** add `CiSpec` to `BuildHoundExtension`; wire in `BuildHoundSettingsPlugin`
  alongside `serverUrl`/`serverToken` (finalizer block, ~L308). `dashboardUrl` default falls
  back to `serverUrl` — the server already distinguishes ingest URL from dashboard URL
  (`Application.kt:147` `BUILDHOUND_DASHBOARD_URL`, consumed by `VerdictEvaluator`), which is
  the precedent that justifies the optional override for split-hosting deployments.

## Test strategy

- **Unit (`CiJobSummaryTest`, JVM):** `render()` for a sample payload → expected rows; `derived
  = null` → "n/a" hit-rate; non-http `base` → link omitted; verify no token/absolute path in
  the output. `write()` with an empty env map (no `GITHUB_STEP_SUMMARY`, not `TF_BUILD`) →
  no-op, no throw.
- **Functional (TestKit):** extend the **`CiEnvironmentBreadthFunctionalTest`** env-injection
  harness (it already proves `env → System.getenv` reaches the TestKit daemon via
  `GITHUB_ACTIONS` detection — same code path, so this is the reliability guarantee): set
  `GITHUB_STEP_SUMMARY` to a temp file, run with `GITHUB_ACTIONS=true`, assert the file gains
  the BuildHound block; no env var → build green, no write; `-Pbuildhound.ci.jobSummary=false`
  → suppressed. Azure: `TF_BUILD=true` → stdout contains `##vso[task.uploadsummary]`. Use
  `invariantSeparatorsPath` for any injected path (Windows; MEMORY note).
- **CC reuse:** run twice with `--configuration-cache`; assert CC is reused (no new input) and
  the summary is still written on the replay.
- **CI-assets:** extend the `action.yml` YAML-parse test to assert the `cache-provider` input
  defaults to `basic` and no secret is inlined.
- **Golden files:** none — schema v1 unchanged; commons contract test untouched.

## Risks

- **Never-fail (§3.2):** the write is one bounded Markdown append (capped well under GitHub's
  ~1 MiB/step) inside a nested `runCatching`; no subprocess. An unwritable/absent path →
  `logger.info` and continue.
- **Warm-daemon misattribution (narrowing 2, accepted limitation — *not* solved by
  silent-degrade):** a daemon reused across multiple Gradle steps sees its **launch-env**
  `GITHUB_STEP_SUMMARY`, so on multi-Gradle-step warm-daemon jobs the block can append to the
  first step's file. The file still exists, so the degrade path does not catch it. Fine on the
  typical single-Gradle-step ephemeral CI job; documented as a known limitation alongside the
  distinct "env absent → skip" degrade.
- **CC safety:** env read at Flow time via `System.getenv` (no config-phase file read, no
  `providers.environmentVariable` CC input); `jobSummary`/`dashboardUrl` are Provider params.
  No new CC fingerprint; the CC-reuse test pins it.
- **Privacy (§3.7):** the summary carries only outcome/duration/hit-rate/Gradle task paths
  (already in the payload) and an operator-configured dashboard URL — **never** the token, an
  absolute filesystem path, or a scrubbable value. `isHttpUrl` gates the link (no
  `javascript:` scheme, per plan 005/041 URL hardening). The Azure `##vso` line prints the
  CI worker's own local summary-file path to the CI console (CI-local, not a payload field and
  not user PII) — an accepted, bounded disclosure distinct from payload privacy.
- **Isolated projects:** unaffected — the summary derives from the assembled payload + env,
  with no dependency on the plan-016 `whenReady` dictionary (which is empty under IP).
- **Multi-tenancy:** N/A — no server route added; the deep link is an unauthenticated UI route
  the operator already exposes.
- **Positioning honesty (narrowing 4):** README copy says "v6's `basic` provider keeps
  cache-key metadata on your infrastructure," not "Develocity-backed" — the proprietary cache
  logic is v6 roadmap, not today's default data path.

## Exit criteria

- A `github-actions` build appends a BuildHound block (outcome, duration, hit rate, requested
  tasks, deep link) to `$GITHUB_STEP_SUMMARY`; an `azure-devops` build prints
  `##vso[task.uploadsummary]` referencing a written Markdown file; a non-CI build writes
  nothing and stays green — all pinned by tests.
- `buildhound.ci.jobSummary=false` suppresses the write; `dashboardUrl` overrides the link
  base, falling back to `serverUrl`.
- CC is reused across builds with the summary on; no new CC input; no golden file changed.
- `buildhound-ci-assets/github/action.yml` runs `setup-gradle` with the `basic` cache
  provider (toggleable, no secret inlined); ci-assets README carries the positioning note;
  YAML-parse test green.
- `./gradlew build` green.
