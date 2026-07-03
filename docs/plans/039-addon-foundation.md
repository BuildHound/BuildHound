# Plan 039 — Addon foundation: extensions payload map, collector registry, addon API namespace

**Status: planned — roadmap phase 4** · 2026-07-03

## 1. Source

- Roadmap [phase 4 item 3](../build-telemetry-roadmap.md): reserved `extensions` payload map,
  `BuildHoundCollectorRegistry` in commons, `/v1/addons/<id>/…` namespace.
- Research [plugin-ecosystem-gap-analysis.md §6](../research/plugin-ecosystem-gap-analysis.md)
  (the five-point addon architecture) and §2.4/§2.5 (the two first consumers).
- Research [test-distribution-addon.md §2.1/§2.6/§2.7](../research/test-distribution-addon.md)
  (sharding's demands on this foundation: `extensions["testSharding"]`, namespaced endpoint,
  unit-key ownership in commons).
- Spec [§4](../build-telemetry-spec.md) (payload, additive), [§5](../build-telemetry-spec.md)
  (tenancy, token scopes), [§8](../build-telemetry-spec.md) (size cap, hashed tokens),
  [§3.7](../build-telemetry-spec.md) (privacy).
- Architecture [§2](../architecture.md) (CC safety, never-fail, public contracts),
  [§3](../architecture.md) (additive schema), [§5](../architecture.md) (persistence boundary,
  tenancy), [§6](../architecture.md) (security/privacy), decision log [§7](../architecture.md).

This plan is the **sole owner** of the payload `extensions` map
(`extensions: Map<String, JsonElement>` on `BuildPayload`) and of `BuildHoundCollectorRegistry`
in commons — no other plan defines a second copy or a stub/fallback. Prerequisite for plan 037
(quarantine) and plan 040 (sharding); both must be **pure consumers**. Plan 038 **hard-depends**
on this plan for the `extensions` field and the registry and adds **no** stub/fallback copy of
either — **039 must land before 038**. Extensions respect the plan 019 payload-size budget.

## 2. Scope

**In:**

1. Additive schema field `extensions: Map<String, JsonElement> = emptyMap()` on `BuildPayload`
   (key = addon id, value = addon-owned JSON with its own `schemaVersion`); new golden file,
   existing goldens untouched.
2. `BuildHoundCollectorRegistry` + `BuildHoundExtensionContributor` SPI in `buildhound-commons`
   — the contract an addon implements to add an `extensions` entry without forking core;
   `ServiceLoader`-discovered, mirroring `CiEnvironmentProvider`.
3. Plugin wiring: core discovers contributors at execution time, evaluates them in the
   Finalizer, merges into `payload.extensions` (CC-safe, single-owner Flow action stays core).
4. Server `/v1/addons/<id>/…` route mount with a per-addon `TokenScope.ADDON` check, an
   `AddonStore` jsonb storage convention, and a migration; core server runs with no addon
   tables active.
5. Packaging conventions documented in `docs/architecture.md` (new "Addon architecture"
   section + decision-log row): plugin ids `dev.buildhound.<addon>`, applied alongside core,
   discover core via the registry, never-fail inherited, warn-and-no-op without core.

**Out (where it lives):** the `buildhound-addon-test-sharding` module (plugin id
`dev.buildhound.test-sharding`), shard-plan endpoint, LPT balancer, `BUILDHOUND_SHARD_INDEX` →
**plan 040**; the `buildhound-addon-test-quarantine` module (plugin id
`dev.buildhound.test-quarantine`) → **plan 037**; test-timing ingestion the balancer reads →
**plan 024**; any addon rollup/dashboard view → the consuming plan;
`TaskExecution.type`/`cacheable` capture → **plan 016**.

## 3. Design

Modules: `buildhound-commons` (field + SPI), `buildhound-gradle-plugin` (discovery + merge),
`buildhound-server` (route + store + migration), `docs/architecture.md`.

**Schema field.** `BuildPayload` today ends at `derived` (`BuildPayload.kt:29`). Add
`val extensions: Map<String, JsonElement> = emptyMap()`. `JsonElement` keeps commons decoupled
from addon types (gap-analysis §6.3). Under the shared config (`BuildHoundJson.kt:11` —
`ignoreUnknownKeys`, `explicitNulls=false`, `encodeDefaults=true`) an empty map re-encodes as
`"extensions":{}`; the `values` field already behaves this way and the round-trip golden test
compares *objects* not strings (`GoldenPayloadTest.kt:34-40`), so no existing golden changes.

**Registry SPI.** New `payload/BuildHoundCollectorRegistry.kt`, modeled on
`CiEnvironmentProvider` (`ci/CiEnvironmentProvider.kt:9`) — pure interface in commons, JVM
`ServiceLoader` kept in the plugin. `BuildHoundExtensionContributor` exposes `addonId: String`
and `contribute(ExtensionContributionContext): JsonElement?`. `ExtensionContributionContext`
carries only read-only collected facts (immutable `List<TaskExecution>` snapshot, `BuildMode`,
CI-derived correlation, projectKey) — no Gradle types, so the SPI is testable without Gradle
and CC-safe. `BuildHoundCollectorRegistry` is the ordered, dedup-by-`addonId` façade;
last-wins on clash logs a warn; contributor throws never escape (contract in KDoc).

**Plugin discovery + merge.** Discover at **execution time** in
`TelemetryFinalizerAction.execute` (`TelemetryFinalizerAction.kt:93`), not a ValueSource:
contributor outputs depend on the task snapshot only available after
`collector.get().snapshot()`. `ServiceLoader.load(...)` on `javaClass.classLoader` (sees the
settings classpath, as `CiValueSource.kt:48`) under `runCatching`; each contributor is
individually `runCatching`-guarded so one bad addon can't suppress another (as
`CiEnvironment.detect`, `CiEnvironmentProviders.kt:123`). The action's `onFailure`
(`TelemetryFinalizerAction.kt:170`) already guarantees the build never fails. Merge: pass the
collected map into `PayloadAssembler.assemble` as a new `extensions` parameter so assembly
stays the single, Gradle-free payload constructor; scrubber still runs last
(`PayloadAssembler.kt:75`). Cap the merged map against the plan 019 budget — drop the largest
offending entries with a warn, never the envelope (spec §3.9 overflow order).

**Server namespace.** `Routes.kt` mounts `route("/v1")` blocks and authenticates via
`authenticatedProject(tokens, scopeCheck)` (`Routes.kt:157`). Add `addonRoutes(store, tokens)`
under `route("/v1/addons/{addonId}")`:

- *Auth:* new `TokenScope.ADDON` + `allowsAddon` in `BuildStore.kt:63` (`ADDON`/`ALL` pass), so
  a leaked ingest/read token can't drive addon APIs (spec §5 least-privilege). `{addonId}` is
  validated against a server-side allowlist of registered addons (empty here) — unknown id is a
  flat 404, never a dynamic table name.
- *Storage:* new `AddonStore` behind the persistence boundary (arch §5), tenant-scoped like
  `BuildStore`, `get/put(projectId, addonId, key, jsonb)`; in-memory + Postgres impls mirroring
  `InMemoryBuildStore`/`PostgresBuildStore`. jsonb keeps ingest schema-stable — no per-addon DDL.
- *Migration* `V{n}__addon_data.sql` (claim the next free version integer at implementation
  time — plans 025/026/028/031/036/037/039 all add migrations, so the merge order determines
  numbering; renumber deterministically to the next free `V{n}` when merging):
  `addon_data(project_id uuid REFERENCES projects, addon_id
  text, key text, value jsonb, updated_at timestamptz, PRIMARY KEY (project_id, addon_id, key))`.
- Mount inside the nested per-host + per-token limiter block in `buildHoundModule`
  (`Application.kt:149`), reusing the query limiter — no new knob.

Ships empty of concrete addon logic; plan 040 adds the first sub-route as a consumer.

**Packaging (docs only).** Addon = separate plugin `dev.buildhound.<addon>` (module + artifact
`buildhound-addon-<name>`, group `dev.buildhound`), applied alongside core, discovering core via
the `ServiceLoader` registry, declaring a compatible core version range; applied-without-core ⇒
warn + no-op (never fail). The two first consumers follow this convention verbatim: quarantine
(plan 037) is `buildhound-addon-test-quarantine`, sharding (plan 040) is
`buildhound-addon-test-sharding` — no `buildhound-test-<name>` form.

## 4. Implementation steps

1. **commons schema:** add `extensions: Map<String, JsonElement> = emptyMap()` (last field) to
   `BuildPayload.kt`, importing `kotlinx.serialization.json.JsonElement`.
2. **commons SPI:** create `payload/BuildHoundCollectorRegistry.kt` with
   `BuildHoundExtensionContributor`, `ExtensionContributionContext` (pure data), and
   `BuildHoundCollectorRegistry` (ordered, dedup-by-id, never-throw); KDoc cross-links
   `CiEnvironmentProvider`.
3. **commons golden:** add `build-payload-v2ext.json` (schemaVersion 1) with a populated
   `extensions` block carrying two addon ids, one with a nested `schemaVersion`; extend
   `GoldenPayloadTest` to deserialize it, assert the `JsonElement` shape, and round-trip. **Do
   not edit `build-payload-v1.json`.**
4. **plugin discovery+merge:** in `TelemetryFinalizerAction.execute`, after
   `collector.get().snapshot()`, `ServiceLoader.load(BuildHoundExtensionContributor::class.java,
   javaClass.classLoader)`, build the context, call each contributor under its own
   `runCatching`, collect non-null `JsonElement`s into a map (last-wins + warn); pass the map
   into a new `extensions` parameter on `PayloadAssembler.assemble`, which sets it on the
   payload before the scrub.
5. **plugin size cap:** enforce the plan 019 payload budget on the merged map (in `assemble` or
   the action) — drop the largest offending addon entries with a warn until under budget; never
   drop the envelope; log dropped ids.
6. **server scope:** add `TokenScope.ADDON` + `allowsAddon` to `BuildStore.kt`.
7. **server store:** add `AddonStore` + `InMemoryAddonStore` (in `BuildStore.kt`) and
   `PostgresAddonStore` (in `PostgresStores.kt`), tenant-scoped jsonb get/put keyed
   `(project_id, addon_id, key)`; thread through `ServerStores` (`Application.kt:22`) and
   `storesFromEnvironment`.
8. **server migration:** add `V{n}__addon_data.sql` — claim the next free version integer at
   merge time (plans 025/026/028/031/036/037/039 all add migrations); never edit `V1`/`V2`.
9. **server routes:** add `addonRoutes(store, tokens)` under `route("/v1/addons/{addonId}")`,
   authenticating with `TokenScope::allowsAddon` and allowlist-checking `{addonId}` (empty
   allowlist ⇒ all ids 404 until a consumer registers one); mount in `buildHoundModule`
   (`Application.kt:149`) beside `queryRoutes`.
10. **docs architecture:** add an "Addon architecture" subsection (the five conventions) and a
    decision-log row (§7): "extensions map + ServiceLoader collector registry +
    `/v1/addons/<id>` namespace; addons mutate, core observes; jsonb keeps ingest schema-stable";
    reference plans 037/040 as first consumers.
11. **docs plan:** this file; committed before implementation per the workflow.

## 5. Test strategy

- **commons golden (jvmTest):** new `build-payload-v2ext.json` deserializes; `extensions` keys +
  nested addon `schemaVersion` read back; round-trip lossless; the **existing** v1 golden (no
  `extensions`) still deserializes and round-trips as an object (additive guarantee).
- **commons unit** `BuildHoundCollectorRegistryTest`: ordered evaluation; duplicate `addonId`
  last-wins + warn; a throwing contributor is swallowed and does not suppress siblings; a
  null-returning contributor contributes no key.
- **plugin unit** `PayloadAssemblerTest`: an `extensions` argument round-trips onto the payload
  and survives scrubbing untouched; empty map ⇒ `emptyMap()`; the size cap drops the largest
  entry, keeps the envelope + smaller entries.
- **plugin functionalTest (TestKit, CC on):** a fixture addon `ServiceLoader`-registered on the
  test classpath contributes `extensions["fixtureAddon"]` into `build-payload.json`.
  **Failure-injection** (arch §2.3): a contributor that throws / hangs ⇒ build still SUCCESS,
  payload written, no entry for it, warn logged. Assert config-cache **reuse** on the second run
  (discovery adds no CC input).
- **server (Testcontainers + testApplication):** `/v1/addons/{id}/…` on a scaffold sub-route:
  `ADDON`/`ALL` token succeeds; `INGEST`-only and `READ`-only get 403; missing token 401;
  unregistered `{addonId}` 404; tenant isolation (A can't read B); the `addon_data` migration
  runs on a clean DB; jsonb round-trips. Plus a `TokenScope.allowsAddon` unit matrix.

## 6. Risks

- **CC hazards.** Discovery stays at execution time (Flow action), reads no config-phase files,
  adds no CC fingerprint input (arch §2 rule 9) — the functional test asserts reuse. Context is
  pure data; no `Project`/`Gradle` leak into contributors.
- **Isolated Projects.** Discovery/contribution run in the single root Flow action (already the
  composite-safe single owner, `BuildHoundSettingsPlugin.kt:38`), so IP does not fan the registry
  per project. Addons needing per-project reach degrade via public `BuildFeatures` — an addon
  responsibility, tested when plan 040 lands.
- **Schema compatibility.** Additive only: `extensions` defaults empty; old servers
  `ignoreUnknownKeys`; old plugins never emit it. New golden added, none edited (arch §3 rule 2).
- **Security / authz.** New endpoint family: `{addonId}` is allowlisted, never names a table or
  route dynamically (no injection); a dedicated `ADDON` scope walls it off from ingest/read
  tokens; reuses the per-host + per-token limiters (no new flood vector); `addon_data` is jsonb
  and tenant-keyed, every query carries `project_id` (arch §5).
- **Privacy.** `extensions` is addon-authored JSON and can carry arbitrary strings; the core
  scrubber only touches declared free-text fields (`PayloadScrubber` KDoc) and cannot know an
  addon's shape. Decision (recorded in the architecture addon section + decision log): core does
  **not** deep-scrub opaque addon JSON; the contract makes addons responsible for the same spec
  §3.7 bar as core (no absolute paths, env dumps, PII, secrets). The two first consumers carry
  only test-class ids and shard indices (test-distribution-addon §2.7) — in-spec. Plan 019 size
  caps still apply, so an addon can't balloon the payload.

## 7. Exit criteria

- `BuildPayload` has additive `extensions: Map<String, JsonElement>`; `./gradlew build` green;
  v1 golden unchanged and passing; new populated-extensions golden passing.
- `BuildHoundExtensionContributor` + `BuildHoundCollectorRegistry` exist in commons, documented,
  `ServiceLoader`-discoverable, never-throw contract unit-tested.
- A TestKit fixture addon's `extensions` block appears in a real `build-payload.json`; a throwing
  fixture addon leaves the build SUCCESS with no entry + a warn; config cache is reused on run 2.
- `GET/POST /v1/addons/{id}/…` authenticates with an `ADDON`-scoped token, 403s ingest/read-only,
  404s unregistered ids, isolates tenants; the `addon_data` migration (next free `V{n}`) runs on a
  clean DB; the core server boots and serves builds with zero addons registered.
- `docs/architecture.md` has an "Addon architecture" section + decision-log row; plans 037 and
  040 can be written as pure consumers with no further foundation work.
