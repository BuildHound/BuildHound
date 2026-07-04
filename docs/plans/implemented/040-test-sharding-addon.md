# Plan 040 — `dev.buildhound.test-sharding` addon: server-balanced LPT shard plans

**Status: delivered (851926a · b72b4b5) — reviewed & green** · 2026-07-03

> **Divergences from this plan (as built).** (1) *Model placement.* `TestShardingExtension` and the
> plan request/response types live in the **addon module** (`dev.buildhound.sharding`), not commons —
> consistent with plan 038's internal-adapters (commons stays addon-shape-agnostic; the golden is a
> raw-JSON `extensions["testSharding"]` block, deserialized as a `JsonObject`). (2) *Timing source.*
> plan 024 stores per-class durations in the payload jsonb, not a dedicated timing table, so
> `BuildStore.classTimings` reads windowed CI payloads' `tests` blocks (`classTimingsOf`, both stores)
> — an on-demand jsonb scan, fine for the non-hot `/plan` endpoint. (3) *Response `assigned` field.*
> Added `ShardPlanResponse.assigned` (the full plan's class set) so the last shard's client-side
> catch-all can find drift-unassigned suites. (4) *Core-optional filter.* The shard **filter works
> without core applied** — only the `extensions["testSharding"]` feedback needs core's finalizer to
> discover the ServiceLoader contributor. A deliberate softening of the strict addon "warn+no-op
> without core" contract, since sharding's value is the filter, not the telemetry. (5) *Whole-build
> single fetch.* The service discovers **all** Test tasks' suites (via an IP-gated `whenReady` walk
> captured into a daemon-static bridge) and POSTs once, so the plan covers the complete suite set —
> the composite end-to-end is exercised by a real-JUnit-fixture + stub-server functional test rather
> than a cross-plugin TestKit composite. (6) *CI examples location.* The shard-matrix examples are a
> new `buildhound-ci-assets/sharding/shard-matrix-examples.md` (Azure + GHA) rather than an extension
> of the reusable `buildhound-gradle-steps.yml` steps template — a shard matrix is a *pipeline*-level
> `strategy.matrix` construct, not a steps-template concern, so a standalone doc is the natural home.
> **Review fixes (2026-07-04):** the per-`Test`-task filter wiring uses `gradle.lifecycle.beforeProject`
> via a top-level installer capturing only a boolean + the service name (the IsolatedAction never holds
> a service reference — architecture §7), with an isolated-projects functional test; the endpoint
> bounds `total ≤ 1000` (a hostile `total` would `Array(total)`-OOM the balancer); the client warns on
> plaintext-http like the core uploader; the CC-reuse test now asserts the filter still applies on the
> reuse run.

## 1. Source

- Roadmap [Phase 4 item 3](../build-telemetry-roadmap.md) — "Addon foundation + `dev.buildhound.test-sharding`
  … server LPT plan over 30-day p90 per-class timings, `BUILDHOUND_SHARD_INDEX` interface, run-all-on-failure
  fallback, pinned `module/class` join key".
- Research: [research/test-distribution-addon.md](../research/test-distribution-addon.md) (whole doc — the Tuist
  model, both source-verified defects to invert, join-key contract, privacy interaction §2.7) and
  [research/plugin-ecosystem-gap-analysis.md §6](../research/plugin-ecosystem-gap-analysis.md) (addon packaging,
  server contract, mutation boundary).
- Spec [§3.5 test granularity](../build-telemetry-spec.md) (class-fqn keyed timings — the timing source this
  balances over) and [§3.7 privacy](../build-telemetry-spec.md) (pseudonymization interaction).
- Architecture [§2 rules 2/3/5/11](../architecture.md) (CC safety, never-fail, laziness, bounded subprocess/HTTP),
  [§5 server](../architecture.md) (tenant-scoped, token-authed endpoints), [§6](../architecture.md) (token wiring).

## 2. Scope

**In:**

- New Gradle module `buildhound-addon-test-sharding` publishing plugin id `dev.buildhound.test-sharding`
  (settings plugin — needs whole-build `Test`-task reach). Name follows the `buildhound-addon-<name>` convention
  declared by **plan 039**. Depends on `buildhound-commons` only, per the addon-packaging rule (gap-analysis §6.1).
- Suite discovery + shard-filter application over every `Test` task, at **execution time**, via public
  `Test.filter.includeTestsMatching(fqcn)` + `filter.isFailOnNoMatchingTests = false`.
- One server capability: `POST /v1/addons/test-sharding/plan` — idempotent plan-or-get over
  (project, reference), LPT bin-packing over 30-day p90 per-class CI-only timings, namespaced per the
  addon server contract (gap-analysis §6.5).
- Never-fail semantics: every failure path (server unreachable/timeout/non-2xx, missing/underivable index,
  no plan) ⇒ `warn` + **run all tests on every shard** (inverts Tuist's `GradleException`).
- Pinned join key `TestUnitKey.of(module, classFqcn)`, defined once in `buildhound-commons` by **plan 024**
  (this plan references it verbatim, never redefines it), with a golden test.
- Feedback loop: stamp `shardPlanId`/`shardIndex` into the addon's `extensions["testSharding"]` payload block.
- CI examples for Azure DevOps (static shard-count matrix + `BUILDHOUND_SHARD_INDEX`/`_TOTAL` env) and GHA
  in `buildhound-ci-assets`.
- Architecture decision-log entry recording the sharding addon + its never-fail inversion.

**Out (and where it lives):**

- The `extensions` payload map, `BuildHoundCollectorRegistry` in commons, and the addon-applied-without-core
  warn/no-op contract — **plan 039** (hard dependency; this plan consumes them, does not define them).
- Server storage of per-class test timings and the `(projectId, modulePath, classFqcn) → duration` rows this
  query reads — **plan 024** (hard dependency; 024 also defines the `TestUnitKey` join key this plan references).
- The `test-quarantine` addon (sibling, same packaging) — **plan 037**.
- A matrix-emitting `prepareTestShards` prepare task with provider-native fan-out artifacts — deferred (v1 is
  one idempotent plan-or-get endpoint; see §3). Not planned here; note for a later plan.
- Flaky detection / server flaky page — **plan 036**.
- Test collection itself + tests page — **plan 024**.

## 3. Design

Two-phase Tuist model, but CI-level (each shard is its own build) — **not** intra-task fan-out (that needs the
proprietary Develocity broker; research §1.1). v1 has **no** prepare task: each shard job discovers its own
suites deterministically and calls one idempotent endpoint.

**Addon module.** `buildhound-addon-test-sharding`, a settings plugin `TestShardingSettingsPlugin :
Plugin<Settings>`, added to [settings.gradle.kts:29](../../settings.gradle.kts) after the core modules and
excluded from the aggregate `build` the same way core is. It follows the same CC discipline as
[BuildHoundSettingsPlugin](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt):
composite guard (`settings.gradle.parent != null` ⇒ info + return, mirroring lines 38–41), then registers a
`ShardPlanService : BuildService` whose params carry serializable config only.

**Interface (env, CC-tracked).** Read via `settings.providers.environmentVariable(...)` (like the existing
value-source wiring, [plugin lines 55–73](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/BuildHoundSettingsPlugin.kt)):
`BUILDHOUND_SHARD_INDEX` (1-based), `BUILDHOUND_SHARD_TOTAL`, optional `BUILDHOUND_SHARD_REFERENCE`. **No
index present ⇒ addon fully inert** (Tuist got this right — the only case where doing nothing is correct).
Default reference is `CiContext.runId` from the core CI SPI ([CiEnvironmentProvider.kt:22](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/ci/CiEnvironmentProvider.kt))
so it is already provider-normalized; falls back to `BUILDHOUND_SHARD_REFERENCE`.

**Fetch + filter, at execution time.** Server url/token come from the same env the core reads (token env-only
per architecture §6). For each `Test` task a `doFirst` asks the `ShardPlanService` for this reference's class
list and applies `includeTestsMatching` per class + `isFailOnNoMatchingTests = false`. The HTTP call lives
**inside the BuildService** (not at apply time) so no shard slice is baked into a CC entry and failures degrade
per run — avoiding both Tuist CC defects (`Task.project` in an action; config-time HTTP) by construction. The
client reuses the proven `java.net.http.HttpClient` shape from
[PayloadUploader](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/PayloadUploader.kt)
(bounded timeout, `Redirect.NEVER`, `Bearer` header, class-name-only failure logging, token never logged).
Suite discovery walks each task's `testClassesDirs` (every `.class` without `$` → FQCN), sorted, as Tuist does.

**Server plan capability.** New `ShardPlanStore` behind the persistence boundary (architecture §5), same
tenant-from-token model as [ingest/query routes](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt:48).
`POST /v1/addons/test-sharding/plan` body `{reference, total, suites:[classKey…]}` → response
`{shardPlanId, index, classes:[classKey…]}`; auth reuses `authenticatedProject(tokens, allowsIngest)` (a CI
token already holds ingest scope). **Idempotent**: the first caller for (project, reference, total) computes and
memoizes the plan, later callers read it — protecting against inter-job discovery drift, with Tuist's catch-all
guard (the last shard also runs anything not explicitly assigned). Balancer: per-class duration = **p90** over
the trailing **30 days, CI runs only**, from plan 024's timing rows keyed on `TestUnitKey.of(module, classFqcn)`;
greedy **LPT** (sort descending, assign to the least-loaded shard); unknown class ⇒ **median of known
durations**, no history at all ⇒ **5 s/suite** floor (Tuist's verified defaults, research §1.2).

**Join key — the single most important contract.** `TestUnitKey.of(module, classFqcn)` — the object
`TestUnitKey` with `fun of(module: String?, classFqcn: String): String = "${module ?: ""}/$classFqcn"` — lives in
`buildhound-commons`, **defined by plan 024** (it lands first). This plan references that one symbol verbatim,
never redefining it, used identically by (1) plan 024's timing ingest/query, (2) this plan's request suite list,
and (3) the balancer's `Map` lookup; a null module degenerates to the empty-string prefix form. Tuist's Gradle
path degenerated to count-based round-robin precisely because the client sent bare FQCNs while the server keyed on
`module/class` (research §1.2 defect 2). Plan 024's golden/contract test pins the exact string.

**Payload feedback.** The addon contributes an `extensions["testSharding"]` block (via plan 039's
`BuildHoundCollectorRegistry`) carrying its own `schemaVersion`, `shardPlanId`, `shardIndex`, `shardTotal`, and
`appliedFilter: Boolean` (false when it fell back to run-all). Server stores `extensions` as jsonb (plan 039);
no core schema change here — the top-level `extensions` field is 039's additive change, not this plan's.

**Never-fail (inverts Tuist).** Any of: no `ShardPlanService` reachable, non-2xx, timeout, missing/invalid
index or total, empty/absent plan ⇒ log at `warn`, apply **no** filter (run all tests), stamp
`appliedFilter=false`. The addon never throws — it inherits the core never-fail rule, which addons also honor
(gap-analysis §6.1). "Run all on failure" is correctness over speed: slower, never wrong, never red.

**Privacy (spec §3.7).** v1 keys timings by plaintext class FQCN (spec §3.5 ingests FQCNs plaintext), so no
scrubber conflict today. Documented explicitly: if class names are ever pseudonymized, the same deterministic
pseudonym must be applied to the plan request/response and the client maps hashes back to FQCNs locally before
building filters (research §2.7). No absolute paths or PII enter the request — only module paths + class FQCNs,
which are already in the schema.

## 4. Implementation steps

1. **commons — join key (from plan 024).** Reference the existing `TestUnitKey.of(module, classFqcn)` object in
   `buildhound-commons/src/commonMain/.../payload/`, **defined by plan 024** (lands first). Do not redefine it here;
   the balancer, request suite list, and timing query all key on this one symbol.
2. **commons — addon payload model.** Add a serializable `TestShardingExtension(schemaVersion, shardPlanId,
   shardIndex, shardTotal, appliedFilter)` for the `extensions["testSharding"]` value. (Depends on 039's
   `extensions` map + registry existing.)
3. **commons — golden pin.** `TestUnitKey`'s own golden/consistency test lives with plan 024's single definition;
   this plan does not add it. Add a golden `extensions`-populated payload file **alongside** the existing
   [build-payload-v1.json](../../buildhound-commons/src/jvmTest/resources/golden/build-payload-v1.json) — never
   edit it (architecture §3.2).
4. **server — plan types, store, balancer.** Add `ShardPlanRequest`/`ShardPlanResponse`, a `ShardPlanStore`
   (in-memory + Postgres impls behind the persistence boundary, [Auth.kt](../../buildhound-server/src/main/kotlin/dev/buildhound/server/BuildStore.kt)
   pattern, memoized by (project_id, reference, total)), and a **pure** `lptPlan(suites, total, durations,
   medianFallback, floorMs=5000): List<List<classKey>>` (plain-unit-testable, no Ktor/DB).
5. **server — timing query.** `ShardPlanStore` reads plan 024's per-class timing rows: p90 over 30d, CI-only,
   grouped by `TestUnitKey.of(module, classFqcn)` (step 1); computes median-of-known + 5 s floor as balancer inputs.
6. **server — endpoint + migration.** `Route.testShardingRoutes(store, tokens)` mounting `POST
   /v1/addons/test-sharding/plan` under the same nested rate-limit + `allowsIngest` auth wrappers as ingest in
   [Application.kt](../../buildhound-server/src/main/kotlin/dev/buildhound/server/Application.kt), registered in
   `buildHoundModule`. Add `V{n}__shard_plans.sql` (new file, never edit V1/V2; claim the next free
   version integer at implementation time — plans 025/026/028/031/036/037/039/040 all add migrations, so
   merge order sets the numbering; renumber deterministically to the next free `V{n}`): `shard_plans` (project_id,
   reference, total, plan jsonb, created_at, `UNIQUE(project_id, reference, total)`), tenant-scoped like `builds`.
7. **addon module — scaffold.** New `buildhound-addon-test-sharding`; `include(...)` in settings; `gradlePlugin {}`
   metadata for id `dev.buildhound.test-sharding`; `apiVersion = 2.0` pin (architecture §2 rule 10); depends on
   `buildhound-commons` only.
8. **addon — plugin, service, filter.** `TestShardingSettingsPlugin` (composite guard, env via providers) +
   `ShardPlanService : BuildService` (HTTP client + fetched-plan cache). Suite discovery from `testClassesDirs`;
   `doFirst` on each `Test` task applies `includeTestsMatching` + `isFailOnNoMatchingTests=false`, or falls back
   to run-all on any failure. Register a named provider into 039's `BuildHoundCollectorRegistry` emitting the
   `TestShardingExtension` block (step 2). No core-plugin dependency.
9. **ci-assets — examples.** Extend [buildhound-gradle-steps.yml](../../buildhound-ci-assets/azure-pipelines/buildhound-gradle-steps.yml)
   with a commented static-shard matrix (`strategy.matrix` over N indices → `BUILDHOUND_SHARD_INDEX`/`_TOTAL`),
   add a GHA `matrix` example + short README note. Azure has no dynamic matrix, so a static shard count is the
   natural v1. No new dependency: the JDK `HttpClient` is reused; if any coordinate is ever needed, add it only
   to `gradle/libs.versions.toml` at its latest released version looked up at implementation time.
10. **docs.** Add the decision-log row (below) to `docs/architecture.md` **in this PR**; update this plan file
    if implementation diverges (workflow rule).

Decision-log entry to add: *"`dev.buildhound.test-sharding` is an opt-in addon (settings plugin) that mutates
`Test.filter` — kept out of core by the no-mutation rule (§2). It inverts Tuist's failure semantics: every
plan-fetch failure runs all tests (never-fail rule §2.3), correctness over speed. Join key
`TestUnitKey.of(module, classFqcn)` is pinned in commons by plan 024 and shared with its ingest — the contract
Tuist's Gradle path got wrong."*

## 5. Test strategy

- **commons unit:** golden `extensions`-populated payload deserializes at schemaVersion 1. (`TestUnitKey`'s
  exact-string/edge-module/round-trip test is owned by plan 024, not re-added here.)
- **server unit (pure):** `LptBalancerTest` — descending assignment, makespan sanity on a known set, unknown
  class ⇒ median, no-history ⇒ 5 s floor, `total=1` (all in one shard), `total > suites` (empty tail shards),
  duplicate suites deduped.
- **server route/Testcontainers:** `POST /plan` idempotency (two callers, same reference/total ⇒ same plan);
  wrong scope ⇒ 403; missing token ⇒ 401; unknown reference with no timings ⇒ 5 s-floor round-robin (not an
  error); migration V3 applies. Reuse the existing
  [PostgresStoresIntegrationTest](../../buildhound-server/src/test/kotlin/dev/buildhound/server/PostgresStoresIntegrationTest.kt)
  Testcontainers harness.
- **addon TestKit `functionalTest`:** on a two-`Test`-task fixture with `BUILDHOUND_SHARD_INDEX/_TOTAL` set +
  a stub server, the shard runs only its assigned classes; **no index ⇒ addon inert** (all tests run,
  no HTTP); CC entry reused across two runs with the addon applied (no config-time HTTP fingerprint).
- **failure-injection (phase guardrail):** server unreachable, 500, timeout, malformed plan JSON, index out of
  range, `total=0` ⇒ each runs **all** tests, logs one `warn`, `appliedFilter=false`, **build stays green**.

## 6. Risks

- **CC hazards.** Config-time HTTP or `Task.project` in an action would poison the cache (Tuist's two defects).
  Mitigated by construction: env via providers, fetch inside the `BuildService` at execution time, filter in
  `doFirst`. A functional test asserts CC reuse across runs.
- **Isolated projects.** `Test`-task enumeration must not reach across projects at config time; register the
  `doFirst` per project as tasks are realized (settings-plugin `allprojects`/lazy task-collection callbacks),
  degrading to no-op where isolation forbids cross-project visibility. Verify against the phase-2 isolated-projects
  CI job (plan 021).
- **Schema compatibility.** No core schema change here — the `extensions` map is plan 039's additive field. This
  plan only adds a new key/value under it; the existing golden file is untouched, a new one is added
  (architecture §3.2).
- **Join-key drift (the headline risk).** If plan 024 keys timings differently from `TestUnitKey`, LPT silently
  degenerates to round-robin (Tuist's exact bug). The shared commons key + golden test + a server test asserting
  a known-timing suite lands on the expected shard are the guardrails.
- **Security/privacy.** New authed endpoint reuses ingest-scope auth + rate limiting (no new authz surface);
  token env-only, never logged (architecture §6); request carries only module/class identifiers already in the
  schema — no paths/PII. Pseudonymization interaction documented (§3, spec §3.7); v1 has no conflict.
- **Never-fail regression.** A future refactor could let an exception escape the addon. The failure-injection
  suite is the standing guard; the addon's public entry points wrap in `runCatching` like the core finalizer.

## 7. Exit criteria

- `./gradlew build` green; `buildhound-addon-test-sharding` publishes plugin id `dev.buildhound.test-sharding`
  and depends only on `buildhound-commons`.
- With `BUILDHOUND_SHARD_INDEX`/`_TOTAL` set and a reachable server, each shard job runs a disjoint (plus
  catch-all tail) class subset; class lists across shards union to the full set.
- With **no** `BUILDHOUND_SHARD_INDEX`, the addon makes no HTTP call and does not alter any `Test` filter.
- Every failure path (unreachable/timeout/non-2xx/bad index/no plan) runs **all** tests, logs one `warn`,
  stamps `appliedFilter=false`, and the build succeeds — proven by failure-injection tests.
- `POST /v1/addons/test-sharding/plan` is idempotent per (project, reference, total), tenant-scoped, LPT-balanced
  over 30-day p90 CI-only per-class timings keyed on `TestUnitKey.of(module, classFqcn)`, with median + 5 s fallbacks.
- `TestUnitKey.of(module, classFqcn)` (defined once in commons by plan 024, golden-pinned there) is the same key
  this plan's request/balancer use and plan 024 ingests on — referenced verbatim, never redefined here.
- `extensions["testSharding"]` carries `shardPlanId`/`shardIndex`; a new golden payload covers it; the v1 golden
  file is unchanged.
- Azure + GHA shard-matrix examples exist in `buildhound-ci-assets`; the architecture decision log has the
  sharding-addon row.
