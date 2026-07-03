# Plan 022 — Input fingerprints tier (a) + build comparison endpoint + comparisons page

**Status: planned — roadmap phase 2b** · 2026-07-03

## 1. Source

- Roadmap [phase 2b, first bullet](../build-telemetry-roadmap.md): fingerprints tier (a) +
  compare tier (c)-lite — "the feature the research rates the product's biggest near-term
  differentiator".
- Research: [cache-miss-input-fingerprints.md](../research/cache-miss-input-fingerprints.md)
  §5 (tiered design), §1 (key format), §3 (diff methodology);
  [plugin-ecosystem-gap-analysis.md](../research/plugin-ecosystem-gap-analysis.md) §1 row 1.
- Spec: [§3.4](../build-telemetry-spec.md) (DSL), [§3.7](../build-telemetry-spec.md)
  (pseudonymization/salt), [§4](../build-telemetry-spec.md) (payload), [§5](../build-telemetry-spec.md)
  (query API), [§6](../build-telemetry-spec.md) (Comparisons page).

## 2. Scope

**In:**

- Additive schema field `fingerprints` (build-level map + per-task map) in commons, new golden file.
- Build-level fingerprint capture (built-in key set + allowlist DSL) in the plugin, salted hashes.
- `fingerprints {}` DSL block: `systemProperties(...)`, `envVars(...)`, `gradleProperties(...)`,
  `testTaskSystemProperties` (opt-in, default **false**).
- Opt-in per-`Test`-task system-properties capture (the ACF/Develocity-sample pattern, salted hashes).
- `GET /v1/builds/{a}/compare/{b}` with differing-key ranking + a small known-volatile-key catalog.
- Comparisons page in the dashboard (`#/compare`, `#/compare/{a}/{b}`).
- Spec amendments for the sections above; architecture decision-log row for the action-injection boundary.

**Out:** tier (b) per-task fingerprints/cache keys via internal build operations, per-property
cause ranking, `avoidedMs`/`criticalPathMs` (plan 038) · per-file path/content hashes (038,
gated even there) · `plaintext()` opt-in for boring values (post-pilot, additive) · CC miss-*reason*
capture (plan 035) · experiment recipes/benchmark pairs (plan 030) · task `type`/`cacheable`
population (plan 016) · generic tag/value cardinality caps (plan 019 — this plan enforces its own
fingerprint caps and reuses 019's helpers where they exist) · HTML-artifact rendering of
fingerprints (comparison is inherently a two-build server view).

## 3. Design

**Schema (commons).** `BuildPayload` today ends at `derived` with no fingerprint field
(`buildhound-commons/.../BuildPayload.kt:12-29`). Add, additively:

```kotlin
@Serializable
data class FingerprintInfo(
    val build: Map<String, String> = emptyMap(),                 // key -> 16-hex hash + "…"
    val tasks: Map<String, Map<String, String>> = emptyMap(),    // task path -> (key -> hash)
)
```

and `val fingerprints: FingerprintInfo? = null` on `BuildPayload`. Defaults keep every old
payload/golden file valid; `BuildHoundJson.payload` already sets `ignoreUnknownKeys`
(`BuildHoundJson.kt:11-15`), so old servers tolerate new plugins. New golden file
`build-payload-v1-fingerprints.json`; `build-payload-v1.json` is not touched.

**Hashing (plugin).** New internal `FingerprintHashing`: HMAC-SHA256 keyed with the per-project
identity salt, domain-separated input `"fp:" + value` (so fingerprint hashes can never collide
with the `user:`/`host:` identity families, `IdentityHashing.kt:19-23`), truncated to **16 hex
chars + `…`** — the screenshot-compatible format (research §1.1). Salted, unlike the Develocity
sample; equality within a project still holds, which is all diffing needs. No salt available ⇒
the fingerprint block is **omitted**, never plaintext (same rule as `IdentityHashing.kt:30-48`).
One `Mac` instance per call — the sample's shared-`MessageDigest` bug is explicitly not copied.
The salt read/create logic currently lives inside `EnvironmentValueSource.readOrCreateSalt`
(`EnvironmentValueSource.kt:73-98`); extract it behavior-preserving into an internal
`IdentitySalt` object so the two fingerprint capture sites share it.

**Build-level capture (plugin).** New `FingerprintValueSource` following the established
execution-time ValueSource pattern (`BuildHoundSettingsPlugin.kt:55-73`) so probes never become
CC inputs. Inside `obtain()`, per-probe guarded like `EnvironmentValueSource`: built-in keys
`jdk.home`, `jdk.vendor`, `jdk.version` (full runtime version — Gradle keys only on the major,
a documented miss cause), `file.encoding`, `user.language`, `user.country`, `timezone`,
`os.name`, `os.arch`, plus `gradle.parallel`/`gradle.maxWorkers` passed in as parameters from
`startParameter` at apply time (CC-safe scalar, same as `requestedTasks` at
`BuildHoundSettingsPlugin.kt:90`). Allowlisted extras use prefixed keys `sysProps-<name>`,
`env-<name>`, `gradleProp-<name>`; system properties and env vars are read inside `obtain()`,
gradle-property *values* are wired into the ValueSource parameters via `providers.gradleProperty`
(gradle properties are CC fingerprint inputs by definition already — no new invalidation).
Everything is hashed inside `obtain()`; the result is a `Serializable` `CollectedFingerprints`.

**Per-Test-task capture (plugin, opt-in).** New `TaskFingerprintCollector` BuildService
(sibling of `TaskEventCollector`, `TaskEventCollector.kt:21-39`) holding a concurrent
taskPath→map store, parameters carrying the salt file path and caps. A **static named class**
`CaptureTestSystemPropertiesAction : Action<Task>` (no lambdas — cache-key stability + CC, per
CCUD's lesson) is added with `tasks.withType(Test::class.java).configureEach { doFirst(action) }`
from `settings.gradle.lifecycle.beforeProject { ... }` (Gradle 8.8+, the isolated-projects-safe
hook; floor is 8.14), registered **only when** `testTaskSystemProperties` is true — the default-off
path injects nothing and perturbs no cache key. The action reads public
`Test.getSystemProperties()` (`JavaForkOptions`) and public `Task.getPath()` (not internal
`identityPath`), hashes values via the shared salt, records `sysProps-<name>` keys into the
service, and wraps its whole body in `try/catch → warn` (never-fail rule). Documented
consequence (DSL KDoc + spec): enabling or upgrading the capture changes Test tasks' action
list and causes a one-time cache miss on them.

**Caps, in code:** ≤ 32 names per allowlist, ≤ 100 keys per task map, ≤ 500 task entries,
keys truncated at 64 chars; overflow drops the remainder and logs one `warn`. Values are
17-char hashes by construction. Key names pass through `PayloadScrubber.scrubText`
defensively — it is deterministic, so cross-build key equality survives.

**Assembly.** `TelemetryFinalizerAction` gains `fingerprints: Property<CollectedFingerprints>` +
`@ServiceReference` to the task collector (`TelemetryFinalizerAction.kt:29-91`);
`PayloadAssembler.assemble` (`PayloadAssembler.kt:29-76`) merges both into
`BuildPayload.fingerprints` before the existing scrub-at-assembly step (`PayloadAssembler.kt:75`).

**Compare endpoint (server).** New pure `BuildComparator` object (plain unit-testable, like the
store logic) + `GET /v1/builds/{a}/compare/{b}` added to `queryRoutes` (`Routes.kt:107-135`),
read-scope authenticated via the existing `authenticatedProject` helper (`Routes.kt:157-176`),
two `store.findById` lookups (`BuildStore.kt:51`; Postgres reads the jsonb payload,
`PostgresStores.kt:69-81`) — no migration, no new table, 404 when either build is missing or
foreign-tenant, 400 when `a == b`. Response (all `@Serializable` DTOs):
per-build summary header; `requestedTasksMatch` flag (mismatched invocations get a UI warning,
research §3); `missesToExplain` = task paths whose outcome is `EXECUTED`/`FAILED` in B but
`FROM_CACHE`/`UP_TO_DATE` in A; per-key diff entries `{key, scope: BUILD|TASK, valueA?, valueB?,
differingTaskCount, coverage, note?}` where coverage = |misses whose task map has K differing|
/ |misses| (build-level differing keys cover 1.0); plus declared-field diffs of `toolchain` and
selected `environment` fields. `note` comes from a static known-volatile catalog in the server
(JDK home/vendor/version, encoding, locale, timezone, parallelism — explanations sourced from
research §4/§5, e.g. the android-cache-fix `JdkImageWorkaround` finding), keeping the plugin dumb.

**Comparisons page (dashboard).** Extend `dashboard.js` routing (`dashboard.js:257-271`):
`#/compare` renders a picker over `/v1/builds` rows (started/outcome/branch/sha/mode so same-sha
CI↔local pairs are findable); `#/compare/{a}/{b}` fetches the endpoint and renders: side-by-side
header chips, a "changed inputs" table ranked by coverage with catalog notes, the
misses-to-explain list with each task's differing keys, and explicit empty states ("no
fingerprint data — enable `fingerprints {}`", "no cache misses to explain"). All content via
`textContent` only (`dashboard.js:1-2`), CSS classes stay allowlisted (`dashboard.js:21-22`).
Nav link added in `index.html:38-44`; the CSP style hash is computed from served bytes
(`DashboardRoutes.kt:29-40`) so style edits stay safe.

## 4. Implementation steps

1. **commons**: add `FingerprintInfo` + `BuildPayload.fingerprints` (`BuildPayload.kt`); add
   golden file `build-payload-v1-fingerprints.json` and new `GoldenPayloadTest` cases
   (deserialize, round-trip, absent-field default); existing golden file untouched.
2. **plugin**: extract `IdentitySalt` (read-or-create, atomic move, gitignore guard) out of
   `EnvironmentValueSource` with no behavior change; existing tests stay green.
3. **plugin**: add `FingerprintHashing` (HMAC-SHA256, `"fp:"` domain, 16-hex + `…`) + unit tests.
4. **plugin**: add `FingerprintsSpec` to `BuildHoundExtension` (`BuildHoundExtension.kt:13-37`)
   with `SetProperty` allowlists, vararg helpers, and `testTaskSystemProperties` convention
   `false`; conventions set in `BuildHoundSettingsPlugin.apply`.
5. **plugin**: add `FingerprintValueSource` + `CollectedFingerprints`; wire in `apply()`
   (allowlists, gradle-property value providers, startParameter parallel/worker scalars,
   salt path) and pass into the flow action parameters.
6. **plugin**: add `TaskFingerprintCollector` BuildService + static `CaptureTestSystemPropertiesAction`;
   register via `gradle.lifecycle.beforeProject` gated on the opt-in flag; enforce caps in the
   action and the service.
7. **plugin**: extend `TelemetryFinalizerAction.Parameters` + `PayloadAssembler.assemble` to merge
   build- and task-level maps into `payload.fingerprints`; scrub key names via `scrubText`.
8. **plugin tests**: unit (assembler merge, caps, hashing) + functionalTest additions (step §5 below).
9. **server**: `BuildComparator` + response DTOs + unit tests for the ranking.
10. **server**: `GET /v1/builds/{a}/compare/{b}` in `queryRoutes` (auth, 404/400, storage-outage
    503 path via the existing `runQuery` helper) + `ApplicationTest` route tests.
11. **dashboard**: picker + comparison views in `dashboard.js`, nav link in `index.html`,
    extend the node smoke harness (`dashboard-smoke.js`, run by `DashboardScriptTest`).
12. **docs, same PR**: spec §3.4 gains the `fingerprints {}` block (with the one-time-miss
    warning), §4 the `fingerprints` payload field, §5 the compare endpoint, §6's Comparisons
    entry updated (tier-(a) input diff now; per-property causes remain phase 4/plan 038);
    architecture decision-log row: opt-in `doFirst` capture is the sanctioned, default-off
    exception to "core never mutates other tasks' config", with the micro-addon fallback noted.

## 5. Test strategy

- **Golden/contract**: new golden file deserializes with populated `build` and `tasks` maps;
  v1 file without the field still parses (`fingerprints == null`).
- **Plugin unit**: `FingerprintHashingTest` (deterministic per salt, differs across salts,
  17-char format, domain separation from identity hashes); caps tests (33rd allowlist name
  dropped + warned, 101st task key dropped); `PayloadAssemblerTest` merge + null-when-no-salt.
- **functionalTest (TestKit)**: allowlisted sysprop `-Dbuildhound.test.prop=x` appears as a
  hashed `sysProps-buildhound.test.prop` key whose value is not the plaintext, is stable across
  two identical runs, and changes when the value changes; `testTaskSystemProperties = true` on a
  fixture with a `Test` task yields a `tasks[":test"]` map; **default off** ⇒ payload has no task
  map and the Test task's cache key is unchanged across enabling-unrelated runs; CC-reuse run
  still emits fingerprints (ValueSource re-executes); **failure injection**: salt path pointing
  at a directory ⇒ build succeeds, fingerprints omitted, single warn (never-fail rule).
- **Server unit**: `BuildComparatorTest` — build-level differing key ranks 1.0; task key covering
  2 of 4 misses ranks 0.5; zero misses returns build diffs unranked; key present on one side
  only counts as differing; missing fingerprint maps degrade to empty diff, not error.
- **Server routes** (`testApplication`): 401 no token, 403 ingest-scope token, 404 foreign-tenant
  or unknown id, 400 for `a == b`, happy path over two ingested payloads names the changed key.
- **Dashboard**: node smoke harness covers `#/compare` and `#/compare/{a}/{b}` render paths
  including the empty states.

## 6. Risks

- **Action injection vs the no-mutation rule.** `doFirst` on Test tasks is the one deliberate
  boundary crossing: mitigated by default-off, explicit DSL consent, static action class, and
  the documented one-time miss. If clean-context review rejects even opt-in injection in core,
  the flag moves to a `dev.buildhound.fingerprints` micro-addon (research §5a fallback) —
  build-level capture and the compare endpoint stay core either way (open question below).
- **CC / isolated projects.** All probes run in ValueSources or at task execution — no new CC
  inputs; `lifecycle.beforeProject` is the IP-safe hook, and the IP CI job (plan 021) watches it;
  worst-case degradation is an empty `tasks` map, never a failure.
- **Schema compatibility.** Purely additive with defaults; golden files only added. Old servers
  ignore the field (`ignoreUnknownKeys`); old plugins simply send no fingerprints and the compare
  endpoint answers with build-metadata diffs plus empty-state hints.
- **Privacy.** New data leaving the machine is exclusively salted 16-hex hashes of allowlisted
  or built-in values — strictly stronger than the unsalted Develocity sample; absolute paths
  (`jdk.home`) never leave unhashed; no plaintext escape exists in this plan; key names are
  length-capped and scrubbed. Honest limitation to document: tier (a) explains only
  allowlisted/built-in inputs — unknown volatile *file* inputs stay invisible until plan 038.
- **Cardinality/size.** Caps enforced in code (guardrail); expected payload growth is tens of
  KB pre-gzip at the caps, negligible against the task array.
- **Compare correctness.** Fingerprints are captured only when a task executes, so run A of a
  canonical pair may lack a task map (`doFirst` doesn't run on avoided tasks); the comparator
  treats absent maps as "no data" and leans on build-level keys — stated in the response and UI.

## 7. Exit criteria

- `./gradlew build` green, including new unit, functional, golden, and server tests.
- A repo build with `fingerprints { systemProperties("some.prop") }` produces a payload whose
  `fingerprints.build` contains built-in JDK/locale keys and the hashed allowlisted key.
- Two same-sha builds run with different JDK homes, ingested and opened on the comparisons page,
  rank `jdk.home` first with a catalog note (roadmap 2b exit criterion).
- `GET /v1/builds/{a}/compare/{b}` is tenant-scoped, read-scope-gated, and documented in spec §5.
- Default configuration injects nothing into Test tasks (verified by functional test).
- Spec §3.4/§4/§5/§6 amended; architecture decision-log row added; clean-context code and
  security/privacy reviews completed with findings addressed.
