# Plan 037 — `dev.buildhound.test-quarantine` addon (skipped/muted modes)

**Status: planned — roadmap phase 4** · 2026-07-03

## 1. Source

- [Spec §5](../build-telemetry-spec.md) ("Quarantine API + plugin `excludeTestsMatching` loop
  only after detection precision is validated on the pilot — locked decision #3"),
  [§3.4](../build-telemetry-spec.md) (KGP precedent: core does **not** silently mutate other
  plugins' config), [§3.5](../build-telemetry-spec.md) (locked test granularity),
  [§8](../build-telemetry-spec.md)/[§9](../build-telemetry-spec.md) (token posture; decision #3
  traceability).
- [Roadmap phase 4 item 1](../build-telemetry-roadmap.md): "After precision is validated on the
  pilot: **`dev.buildhound.test-quarantine` addon** (skipped/muted modes via
  `excludeTestsMatching` / `ignoreFailures` + re-fail) — addon because it mutates Test tasks
  (locked gate #3 stands)."
- Research: [plugin-ecosystem-gap-analysis.md §2.4](../research/plugin-ecosystem-gap-analysis.md)
  (source-verified Tuist mechanics: skipped = `doFirst`+`excludeTestsMatching`; muted =
  `ignoreFailures=true`+`doLast`/`TestListener` re-fail only on non-quarantined failures;
  auto-on-CI, off locally; zero internal APIs) and §6 (addon architecture: separate plugin id,
  registry attachment, `extensions` payload channel, `/v1/addons/<id>/…` namespace,
  applied-without-core ⇒ warn+no-op, addons inherit never-fail);
  [test-distribution-addon.md §2.4/§2.5/§2.6/§2.8](../research/test-distribution-addon.md)
  (**invert Tuist's fail-on-unreachable**; fetch at execution time, no CC bake; **pin the
  `module/class` join key**; quarantine is the sharding addon's sibling, same gate).

## 2. Scope

**In:**

- New Gradle module **`buildhound-addon-test-quarantine`** publishing settings plugin id
  `dev.buildhound.test-quarantine` (coordinate `dev.buildhound:buildhound-addon-test-quarantine`,
  package `dev.buildhound.quarantine`). Applied explicitly in `settings.gradle.kts` — applying it
  *is* the consent to Test-task mutation that locked gate #3 forbids in core.
- Two enforcement modes on every `Test` task via `testQuarantine { mode = … }`:
  - **skipped** — `doFirst` calls `filter.excludeTestsMatching(pattern)` +
    `filter.isFailOnNoMatchingTests = false` for each quarantined unit; the test never runs.
  - **muted** (default) — set `ignoreFailures = true`, attach a `TestListener` recording
    outcomes, and a `doLast` that **re-fails the build only when a non-quarantined test failed**
    (quarantined failures are recorded then swallowed; real failures still fail).
- Quarantine list pulled from the server (`GET /v1/addons/test-quarantine/list`, plan 039's
  namespace), **cached locally**, and **failed open**: no server / no token / unreachable / non-2xx
  / timeout / bad body ⇒ empty list ⇒ a normal test run. The addon never throws, never fails a build.
- Auto-enable on CI (core's mode classification), off locally unless the DSL opts in.
- Quarantine unit key = plan 024's `TestUnitKey.of(module, classFqcn)` = `"${module ?: ""}/$classFqcn"`,
  converted to a `TestFilter` pattern via the class FQCN (+ optional `#method`); one golden test pins the form.
- `extensions["testQuarantine"]` contribution to the core payload via plan 039's
  `BuildHoundCollectorRegistry` (applied units, per mode, plus each quarantined test's observed
  outcome). Addon-owned versioned JSON; no core schema change.
- Server: `GET …/list` (read scope) + `POST …/units` / `DELETE …/units/{key}` (write scope) over a
  new tenant-scoped `quarantined_tests` table, plus a dashboard **Quarantine page** to list/add/remove.

**Out (and where it lives):**

- **Flaky detection** (the two-signal algorithm + flaky page producing candidates) — plan 036.
  Gate #3: 037 ships only after 036's precision is validated on the pilot.
- **Addon foundation** — `extensions: Map<String, JsonElement>` field, `BuildHoundCollectorRegistry`
  in commons, `/v1/addons/<id>/…` mount, addon versioning — plan 039 (hard dependency).
- **Test collection / `TestUnitKey.of`** and the ingested timing/outcome history 036 scores — plan 024
  (hard dependency for the join key).
- **Test-sharding addon** (`dev.buildhound.test-sharding`, sibling settings plugin, same
  registry/endpoint pattern) — plan 040.
- **Auto-release / auto-quarantine policy** (server promoting/retiring units automatically) — later
  plan; v1 is a human-curated list. **Configuring the Gradle Test Retry plugin** — not done; its
  re-runs are merely observed.

## 3. Design

**Nothing quarantine-related exists in code yet** — verified: no `addons`, `quarantine`,
`CollectorRegistry`, `excludeTestsMatching`, `ignoreFailures`, or `extensions` payload anywhere in
`buildhound-commons`/`-gradle-plugin`/`-server`/`-report` (grep, this session; the sole `extensions`
hit is Gradle's `settings.extensions.create(...)` at
[BuildHoundSettingsPlugin.kt:27](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)).
This plan builds entirely on the contracts plans 024 and 039 introduce.

**Why an addon, not core.** Core's rule "never *silently* mutate other tasks' config"
(architecture §2 rule 3 spirit; spec §3.4 KGP precedent — core only *validates* KGP wiring, never
flips it) forbids core flipping `ignoreFailures` or injecting filters on every `Test` task. A
separately-applied plugin makes the mutation explicit; applying it is the consent. Locked gate #3 stands.

**Module & attachment** (research §6). The module mirrors the core plugin build
([buildhound-gradle-plugin/build.gradle.kts](../../buildhound-gradle-plugin/build.gradle.kts)):
kotlin-jvm + `java-gradle-plugin`, JDK-26 toolchain → Java-21 bytecode, `apiVersion` pinned to 2.0
(architecture §2 rule 10 — addon code also rides Gradle's embedded stdlib), its own `functionalTest`
source set, `gradlePlugin {}` declaring the id. It `implementation(projects.buildhoundCommons)` (for
the registry interface and `TestUnitKey.of`) and does **not** depend on `buildhound-gradle-plugin` —
addons attach through the commons `BuildHoundCollectorRegistry` (plan 039), the single coupling
point, registering a named provider that core's Flow finalizer evaluates. If the registry is absent
at apply (core not applied, or too old), the addon logs `warn` and no-ops.

**List fetch — execution time, fail-open** (research §2.4/§2.5). A `QuarantineListValueSource`
fetches at **execution** time (not apply) through the JDK `HttpClient` with a 10 s timeout and
`followRedirects(NEVER)` — parity with core's `PayloadUploader`
([PayloadUploader.kt](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/PayloadUploader.kt))
and the git-exec 10 s rule (architecture §2 rule 11). Server URL/token come from the addon DSL,
token via env-var provider only (architecture §6, never a DSL literal). A successful response is
cached to `build/buildhound/quarantine/list.json`; any failure returns the cached list if present,
else empty. Execution-time fetch means **no list is baked into a CC entry** (inverting Tuist's CC
bug) and failures degrade per run.

**Applying to Test tasks** (research §2.4). At apply time, root-build only (same guard as core,
[BuildHoundSettingsPlugin.kt:38-41](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)),
register `gradle.taskGraph.whenReady`; for every task that is-a `org.gradle.api.tasks.testing.Test`
(name/superclass walk, no Gradle types in helpers — plan 016/024 precedent) wire the mode actions,
which read the fetched list lazily (provider) so content resolves at execution. *skipped*:
`doFirst` excludes each unit's pattern, `isFailOnNoMatchingTests = false`. *muted*: set
`ignoreFailures = true` **only when the list is non-empty at execution** (an empty list leaves the
task untouched); `TestListener` records `(className, name) → outcome`; `doLast` re-throws iff a
recorded failure's unit is **not** quarantined.

**Payload & server.** `extensions["testQuarantine"]` = `{ "schemaVersion": 1, "mode",
"applied": ["<module>/<fqcn>", …], "observed": [{ "unit", "outcome" }, …] }`, `unit` = `TestUnitKey.of`
verbatim; plan 039 already persists `extensions` jsonb, so no migration for the payload. New routes
under plan 039's `/v1/addons/test-quarantine` are token-authed via
[`authenticatedProject`](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt):
`GET /list` (read), `POST /units` + `DELETE /units/{key}` (write). A new `QuarantineStore`
(in-memory + Postgres, same pattern as
[BuildStore](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt)) backs
Flyway `V{n}__quarantine.sql` (claim the next free version integer at implementation time — plans
025/026/028/031/036/037/039 all add migrations, so the merge order determines numbering; renumber
deterministically to the next free `V{n}` when merging): `quarantined_tests(id, project_id, unit_key,
reason, created_at, UNIQUE(project_id, unit_key))`, every row tenant-scoped (architecture §5). Dashboard gains a
`#/quarantine` page (plan 012 CSP / `textContent` discipline) reading/writing the endpoints.

## 4. Implementation steps

1. **Gate check.** Confirm plan 036 shipped and its precision is validated on the pilot
   (decision #3), and plans 039 (`extensions`, registry, `/v1/addons`) and 024 (`TestUnitKey.of`) are
   merged. Record the gate decision in the PR.
2. **Commons:** ensure plan 024's `TestUnitKey.of(module, classFqcn)` is public and golden-pinned to
   `":app/com.example.FooTest"`; add a small `QuarantineUnit`/pattern helper if useful.
3. **New module:** create `buildhound-addon-test-quarantine/build.gradle.kts` (mirror core plugin;
   `apiVersion 2.0`; `functionalTest`; `gradlePlugin {}` id `dev.buildhound.test-quarantine`;
   `implementation(projects.buildhoundCommons)`) and `include(":buildhound-addon-test-quarantine")` in
   `settings.gradle.kts`.
4. **DSL:** `testQuarantine { mode = MUTED|SKIPPED; enabled; enabledOnLocal = false; server { url;
   token = providers.environmentVariable(...) } }` — token env-provider only; default MUTED,
   auto-on-CI, off locally.
5. **Plugin `Plugin<Settings>`:** root-build guard; `whenReady` Test-task walker; register the list
   value source; wire the two mode actions reading the list lazily. All `runCatching`-guarded — can
   only ever degrade to a normal run (architecture §2 rule 3, research §2.5).
6. **`QuarantineListClient`:** Gradle-type-free JDK `HttpClient`, 10 s timeout, Bearer token, cache
   file, fail-open to cache-or-empty → plain unit tests (`GitExec`/`PayloadUploader` precedent).
7. **Registry contribution:** look up plan 039's `BuildHoundCollectorRegistry`; register the named
   provider producing `extensions["testQuarantine"]`; warn-and-no-op if absent.
8. **Server store & migration:** `QuarantineStore` interface + in-memory + Postgres impls; Flyway
   `V{n}__quarantine.sql` (claim the next free version integer at implementation time — plans
   025/026/028/031/036/037/039 all add migrations, so the merge order determines numbering; renumber
   deterministically to the next free `V{n}` when merging); wire into `ServerStores`/`storesFromEnvironment`.
9. **Server routes:** mount `quarantineRoutes(store, tokens)` under `/v1/addons/test-quarantine`;
   `authenticatedProject` with the right scope per verb; URL-decode + validate `unitKey` against the
   pinned format; never free-text into SQL.
10. **Dashboard:** `#/quarantine` route in `dashboard.js` + nav link in `index.html`; list/add/remove;
    contextual empty state; all strings via `textContent`; recompute the pinned CSP hash if `<style>`
    changes.
11. **Docs & decision log:** add an `architecture.md` §7 row recording the sanctioned Test-task
    mutating addon (explicit opt-in keeps gate #3 intact); note it in the modules table and README;
    move plan 037 to `implemented/` on merge.

## 5. Test strategy

- **Commons golden/unit:** `TestUnitKey.of` exact-string golden (client↔server contract);
  `extensions["testQuarantine"]` round-trips through `BuildHoundJson` and an old-server
  `ignoreUnknownKeys` reader tolerates it.
- **Addon unit (Gradle-free):** `QuarantineListClient` — 200 caches+returns; 4xx/5xx/timeout ⇒
  cache-or-empty, never throws, token never logged; unit-key → filter-pattern derivation (+`#method`).
- **Addon TestKit `functionalTest`** (matrix {Gradle 8.14, 9.latest} × {CC on/off}), fixture with one
  passing + one failing JUnit-5 test:
  - *muted, failing test quarantined* ⇒ build **succeeds**; payload lists the unit applied+observed-FAILED.
  - *muted, failing test NOT quarantined* ⇒ build **fails** (re-fail fires).
  - *skipped, one test quarantined* ⇒ it does not run, build passes, `isFailOnNoMatchingTests` guarded.
  - **Failure-injection (never-fail):** server nowhere / 500 / timeout ⇒ behaves as if the addon
    were absent (all tests run, real failure still fails); only a `warn`, throws nothing — the
    inversion of Tuist's `GradleException` defect.
  - CC reuse across two `--configuration-cache` runs (nothing baked in); isolated-projects ⇒ no-op,
    no error; `enabledOnLocal=false` ⇒ no mutation on `mode=local`; addon applied **without** core ⇒
    warn+no-op, build green.
- **Server (`testApplication`+Testcontainers):** `/list` returns only the caller's tenant (isolation);
  read token refused `POST`/`DELETE` (403), write token allowed; absent/unknown token ⇒ 401; `POST`
  then `GET` round-trips; `DELETE` idempotent; malformed `unitKey` ⇒ 400; endpoint sits under the
  host + per-token limiters.
- **Dashboard smoke:** Quarantine view renders via `textContent`, issues authenticated fetches, no
  inline script (CSP intact).

## 6. Risks

- **CC — list baked into the entry.** Mitigation: fetch strictly at execution time via the value
  source; apply-time code only registers actions and captures the Test-task set. Functional test
  asserts CC reuse.
- **Never-fail rule, doubly load-bearing** (the addon's job is to change pass/fail): (a) infra
  failure must never fail a build → fail-open to empty; (b) muted must never *hide a real failure* →
  re-fail keys on the pinned unit key, swallowing only quarantined units. Both covered.
- **Join-key drift** (Tuist's silent degeneration, research §2.6): single `TestUnitKey.of` in commons,
  golden-pinned, used by add-endpoint validation, the value-source→filter mapping, and 036/040.
- **Mutation blast radius:** the addon touches *every* `Test` task. Mitigation: opt-in by
  application + off-locally default + muted sets `ignoreFailures` only when the list is non-empty, so
  a zero-quarantine build is untouched.
- **Isolated projects:** the `whenReady` walk degrades to a no-op rather than erroring (plan 016/024
  `BuildFeatures` precedent); non-blocking CI job watches it.
- **Schema compatibility:** no core-schema change — rides plan 039's `extensions` jsonb; golden files
  unedited, one new golden *added* for a populated `extensions` block.
- **Security/privacy:** list endpoint token-authed, tenant-scoped, write-scope-gated, rate-limited
  with `/v1`; addon token env-provider only, never logged; `unit_key`s are class FQCNs (already
  ingested plaintext, spec §3.5 — no new PII, no scrubber conflict) validated against the pinned
  format before SQL; decision-log row records the sanctioned mutation.

## 7. Exit criteria

- `./gradlew build` green including the new module's `functionalTest`; the core matrix
  {Gradle 8.14, 9.latest} × {CC on/off} passes with the addon applied on a fixture.
- **muted** + quarantined failing test ⇒ **passing** build; the same failing test **not**
  quarantined ⇒ **failing** build.
- **skipped** excludes the quarantined test from execution; build passes; report shows it excluded.
- No reachable server (or any fetch failure) ⇒ the build runs as if the addon were absent (all tests
  run, real failures still fail, only a `warn`); the addon throws nothing.
- A second `--configuration-cache` run reuses the entry; the isolated-projects leg runs without error.
- `GET /v1/addons/test-quarantine/list` returns only the caller's tenant's units; a read token is
  refused `POST`/`DELETE` (403); the dashboard Quarantine page lists/adds/removes.
- A quarantine-applying build carries a versioned `extensions["testQuarantine"]` block naming applied
  and observed units by the pinned `module/class` key; a schema-v1 reader without the addon tolerates it.
- `architecture.md` decision log records the sanctioned mutating addon; plan 037 is moved to
  `docs/plans/implemented/`.
