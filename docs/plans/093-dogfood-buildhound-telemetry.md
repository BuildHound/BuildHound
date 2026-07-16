# 093 — Dogfood BuildHound telemetry (own build + samples)

## 1. Source

Owner feature request (2026-07): BuildHound's own CI builds must produce real telemetry so
build data can be published to the review/staging/production environments (plan 094).
Dogfooding was explicitly deferred in plan 021 §2 ("worth doing, no plan yet — noted for the
phase retro"). Today the root build applies only the Foojay resolver
(`settings.gradle.kts`), while all three samples already consume the plugin from source via
`pluginManagement { includeBuild("../..") }`.

## 2. Scope

**In:** instrument the repo's own `build` CI job with the candidate plugin; add one
lightweight sample build (`samples/springboot-legacy`) to PR CI; upload each produced
`build-payload.json` as a workflow artifact (the input contract for plan 094). Collection
only — **no server URL and no credentials in this plan**; the `UploadGate` skips upload with
"no server configured" and everything lands in the artifact.

**Out:** routing/credentials/fan-out (plan 094); instrumenting the matrix jobs
(`build-floor`, macOS, Windows, `functional-cc-off`, `isolated-projects`); Android samples in
CI (`nowinandroid`, `android-legacy-agp` need an Android SDK — candidates for a nightly job
later); any plugin, schema, or server change; changing local-developer builds.

## 3. Design

**Own build — bootstrap + init-script injection (CI only).** The root build cannot
`includeBuild` itself, so the `build` job runs two invocations:

1. `gradle :buildhound-gradle-plugin:publishToMavenLocal` — reuses the bundled/shaded
   publication that plan 092 already proves consumable via TestKit.
2. `gradle build -I .github/buildhound-dogfood.init.gradle.kts` — the init script resolves
   the plugin from `mavenLocal()`, applies `dev.buildhound` to Settings via `beforeSettings`
   (the Develocity/CCUD CI-injection pattern), and sets `server.url`/`server.token` from
   `BUILDHOUND_SERVER_URL`/`BUILDHOUND_TOKEN` environment *providers* (architecture §6 —
   unset in this plan) plus low-cardinality `tags` (job name, trigger).

Local builds are untouched: no `settings.gradle.kts` change; a maintainer opts in by passing
the same `-I` flag.

**Sample in CI.** New `ci.yml` job `sample-springboot`: `gradle build` inside
`samples/springboot-legacy` (JVM-only, no SDK needs; the composite build supplies the
head-of-branch plugin from source — wiring already exists in its settings file).

**Artifacts.** Both jobs upload `build/buildhound/build-payload.json` as
`buildhound-payload-<job>` with `if: always()` (the payload is written even when the build
fails — spec §3.2) and short retention (7 days).

## 4. Test strategy

- A `functionalTest` TestKit case runs a synthetic project with the init script applied and
  asserts the plugin activates and writes a payload (guards the injection path against rot).
- `actionlint` + shellcheck already run in CI and cover the workflow edits.
- The `sample-springboot` job itself is the end-to-end integration test.

## 5. Risks

- **CI wall-clock:** the bootstrap publish adds an invocation to the `build` job; mitigated
  by the setup-gradle remote cache. The sample job compiles the plugin from source but runs
  in parallel with `build`.
- **Publication drift:** dogfooding consumes the same shaded publication the Portal release
  (plan 092) ships, so drift would be caught here first — a feature, not a risk.
- **Plugin invariant:** the plugin never fails a build (warn-only), so a candidate-plugin bug
  cannot redden CI; the failure marker + missing artifact make it visible.
- Known warm-daemon CC edge (plan 075) does not apply — CI daemons are cold.

## 6. Exit criteria

- A PR run uploads `buildhound-payload-build` and `buildhound-payload-sample-springboot`
  artifacts containing valid v1 payloads (each with a fresh `buildId`).
- `gradle build` locally, without `-I`, shows zero behavior change.
- No new secrets or variables are introduced.
