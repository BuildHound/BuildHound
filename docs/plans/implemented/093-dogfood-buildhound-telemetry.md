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
   `BUILDHOUND_DOGFOOD_SERVER_URL`/`BUILDHOUND_DOGFOOD_TOKEN` environment *providers*
   (architecture §6 — unset in this plan) plus low-cardinality `tags` (job name, trigger).
   The dogfood contract is deliberately namespaced (`_DOGFOOD_`, §3.2 review, applied with
   plan 094): the bare `BUILDHOUND_SERVER_URL` name is the plugin's plan-027 convention
   fallback for `server.url`, read by any instrumented build that leaves it unset in DSL —
   job-level env under that name would arm uploads in the TestKit fixture builds nested
   inside `gradle build`. The dogfood namespace must not collide with the plugin's
   documented convention-fallback namespace.

Local builds are untouched: no `settings.gradle.kts` change; a maintainer opts in by passing
the same `-I` flag.

**Init-script failure posture (implementation decision, 2026-07-16).** The init script must
never redden a build, mirroring the plugin's own invariant. Its `initscript {}` stage
declares the mavenLocal classpath only when the plugin JAR is actually present (same
location logic `mavenLocal()` uses: `maven.repo.local` system property, else
`~/.m2/repository`), and the `beforeSettings` body is reflection-based (no static
`dev.buildhound` reference) and fully `runCatching`-guarded — a missing bootstrap publish
degrades to a warn log and a telemetry-less build, and any apply/configure failure degrades
the same way. Residual accepted edge: a *corrupt* artifact that exists on disk but fails
resolution still fails the invocation loudly — in CI that can only follow a successful
`publishToMavenLocal` in the same job, where a loud failure is the publication-drift signal
this plan wants.

**Dependency verification (investigated, no change needed).** The repo runs with
`gradle/verification-metadata.xml` (`verify-metadata=true`), and a locally-built artifact
has a fresh checksum every build — but no `trusted-artifacts` entry is required: Gradle
exempts `mavenLocal()` from dependency verification. Verified empirically on Gradle 9.6.1
with a negative probe (an empty-`<components/>` verification file resolves
`dev.buildhound:buildhound-gradle-plugin:0.1.0-SNAPSHOT` from mavenLocal without error, on
both an init-script classpath and a regular configuration). Should a future Gradle start
verifying mavenLocal, the failure is loud and the fix is a narrowly scoped
`<trust group="dev.buildhound" name="buildhound-gradle-plugin" version="0.1.0-SNAPSHOT"/>`.

**Sample job upload noise (observed, accepted).** `samples/springboot-legacy` deliberately
points `server.url` at `http://localhost:8080` (its local-demo deviation). In the
`sample-springboot` CI job that inline upload fails soft (warn + spool) — collection, the
payload file, and the job outcome are unaffected, and no credential exists in the job (the
committed local-dev token fallback goes nowhere). Accepted rather than changing the sample:
the plan's scope excludes changing local-developer builds, and plan 094 will route these
payloads properly from the workflow side.

**Sample in CI.** New `ci.yml` job `sample-springboot`: `gradle build` inside
`samples/springboot-legacy` (JVM-only, no SDK needs; the composite build supplies the
head-of-branch plugin from source — wiring already exists in its settings file).

**Artifacts.** Both jobs upload `build/buildhound/build-payload.json` as
`buildhound-payload-<job>` with `if: always()` (the payload is written even when the build
fails — spec §3.2) and short retention (7 days). The repository is public, so these
artifacts are world-readable — assessed as acceptable (§3.2 review, 2026-07-16): the
payload is pseudonymized by design (spec §3.7) and retention is 7 days.

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

## 6. Review acceptances (2026-07-16)

Recorded per CLAUDE.md §3 (findings fixed or explicitly accepted):

- **Silent total-collection failure:** the payload-artifact steps use
  `if-no-files-found: warn`, so a build where the plugin never wrote a payload uploads
  nothing and stays green. Accepted for 093 (collection-only, no consumer yet); revisit in
  094+, whose publish jobs are natural detectors for a missing artifact.
- **Non-relocatable functionalTest input:** the `buildhound.dogfood.init-script` absolute
  path rides the `functionalTest` system properties, blocking cross-machine build-cache
  reuse of the task. Accepted: it follows the pre-existing `release-test-repository` /
  `testkit.root` pattern, and the suite is deliberately machine-specific (plan 049 §3.1
  rationale).
- **Committed demo token in CI:** the sample's `buildhound-local-dev-token` fallback
  (a documented, well-known non-secret for the local compose stack) now also runs in the
  `sample-springboot` job, where the localhost upload fails closed. Accepted; no credential
  value is exposed that was not already committed.

## 7. Exit criteria

- A PR run uploads `buildhound-payload-build` and `buildhound-payload-sample-springboot`
  artifacts containing valid v1 payloads (each with a fresh `buildId`).
- `gradle build` locally, without `-I`, shows zero behavior change.
- No new secrets or variables are introduced.
