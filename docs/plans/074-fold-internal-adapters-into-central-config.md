# 074 — One plugin, one config block: fold internal-adapters behind `buildhound { internalAdapters { } }`

## 1. Source

Owner request: *the internal-adapters capture (cache origin/keys + critical-path, Gradle
deprecations, `WARN` log lines) should be configured from the **single** `buildhound { }` block and
need **one** plugin applied — not a second `id("dev.buildhound.internal-adapters")`. Keep it a
**separate module** in the repo (do not move the code into the core plugin's source). Bundling it is
**not** blanket consent to use internal Gradle APIs: everything stays off by default and dormant;
enabling a specific toggle is the per-feature consent.*

Reverses the plan-038 decision that *applying a separate plugin* is the internal-API consent. Touches
spec §3.1 (the internal-API exception) and §3.4 (the DSL), and adds an architecture §7 decision-log row.
Also: document every config option (README + docs) and set the sample to all-options-at-defaults.

## 2. Scope

**In.**
- (A) `buildhound { internalAdapters { } }` — a nested spec on `BuildHoundExtension` with four toggles,
  **all off by default**: `collectCacheOrigins`, `collectDeprecations`, `collectLogWarnings`,
  `perFileHashes` (reserved, no-op — kept for continuity, warns if set).
- (B) Bundle the module: core `implementation(projects.buildhoundInternalAdapters)`; drop the module's
  `dev.buildhound.internal-adapters` plugin id + `gradlePlugin{}` block; `InternalAdaptersSettingsPlugin`
  (a `Plugin<Settings>`) becomes a plain wiring entry point core invokes. The module keeps its own
  package, source set, and unit tests.
- (C) **Consent-by-toggle, made structural.** Core registers the internal-API listeners only from its
  post-DSL `whenReady` path, and only when the matching toggle is on. All toggles off ⇒ zero
  `BuildOperationListener`/`OutputEventListener` registered ⇒ no internal Gradle API class is ever loaded.
- (D) **Cache capture becomes toggle-gated** (was unconditional-on-apply): the `BuildOperationAdapter`
  cache data paths gate on `collectCacheOrigins`; the `whenReady` dependency-edge walk (critical path)
  runs only when `collectCacheOrigins` is on.
- (E) Docs: README config table (every option + default), spec §3.1/§3.4, architecture §7 reversal,
  reword CLAUDE.md's "No internal Gradle APIs" hard constraint; `samples/nowinandroid/settings.gradle.kts`
  lists **all** options at their defaults.
- (F) Dockerfile + settings.gradle.kts: internal-adapters becomes an **unconditional** include and a core
  dependency (below).

**Out.** The other opt-in modules (`test-sharding`, `mcp`) stay separate plugins — unchanged. No payload
schema change: `extensions.internalAdapters` keeps its shape and key (the collector is untouched). No
server/report change — plan 048 already renders the block. Per-input-file hashing stays a reserved no-op.

## 3. Design

**Merge shape = Option B (bundle, don't inline).** The internal-API code stays quarantined in
`buildhound-internal-adapters` (one module to audit for `org.gradle.internal.*` /
`org.gradle.api.internal.*`); core gains a compile dependency on it and drives it. This satisfies
"one plugin, one config block, separate module" without moving any internal-API code into core's source.

**Track 1 — DSL (`BuildHoundExtension`, core).**
- New `abstract class InternalAdaptersSpec { collectCacheOrigins; collectDeprecations; collectLogWarnings;
  perFileHashes }` (all `Property<Boolean>`), wired like the other nested specs
  (`val internalAdapters = objects.newInstance(...)` + `fun internalAdapters(Action)`).
- `apply()` sets conventions **false** and threads `ConfigOverrides` for each:
  `buildhound.internalAdapters.collectCacheOrigins` / `BUILDHOUND_INTERNALADAPTERS_COLLECTCACHEORIGINS`, etc.

**Track 2 — wiring entry point (internal-adapters module).**
- `InternalAdaptersSettingsPlugin : Plugin<Settings>` → `object InternalAdaptersWiring` with
  `fun install(settings, collectCacheOrigins, collectDeprecations, collectLogWarnings, perFileHashes)` —
  a signature of `Settings` + plain booleans only (no internal Gradle types in the signature). Drop the
  `extensions.create("internalAdapters")` (the block now lives on core's extension) and the
  "is core present?" shared-service probe (core is the caller — always present).
- **Load-on-consent stays intact:** the internal-type references (`GradleInternal`,
  `BuildOperationListenerManager`, `LoggingOutputInternal`) live only inside the registration branches,
  which run only when a toggle is on. Importing the class does not link those types; first execution of a
  guarded branch does — so all-off never loads them (the property the all-off TestKit test pins).
- Registration moves fully into the gated post-DSL path (core's `whenReady`): the build-op listener is
  registered once per daemon when `collectCacheOrigins || collectDeprecations`; the WARN-log listener once
  per daemon when `collectLogWarnings`. Preserve **every** existing property: `claimRegistration()` /
  `releaseRegistration()` register-then-confirm, daemon-static persistence across CC hits, read-and-clear
  in the collector, composite-build root-only guard, `runCatching` everywhere (never fail the build).

**Track 3 — data-path gating (`BuildOperationAdapter` + `InternalAdaptersState`).**
- `started()`/`finished()` cache paths early-return unless `state.collectCacheOrigins()` — so turning on
  *only* `collectDeprecations` never accumulates cache telemetry (advisor trap 2). `progress()` already
  gates on `collectDeprecations()`.
- `InternalAdaptersState.configure(...)` gains `collectCacheOrigins`; the edge map is passed **empty**
  when `collectCacheOrigins` is off (so `criticalPath` degrades to null exactly as on a CC hit). Add
  `collectCacheOrigins` to `resetForTest()`.
- The internal-API risk notice (config-time, once) lists whichever of the three real toggles are on.

**Track 4 — build wiring.**
- `buildhound-gradle-plugin/build.gradle.kts`: `implementation(projects.buildhoundInternalAdapters)`.
- `buildhound-internal-adapters/build.gradle.kts`: drop `gradlePlugin{}`; keep the module + `functionalTest`
  removed if empty (see Test strategy) or repurposed. It no longer depends on the core plugin in production.
- `settings.gradle.kts`: move `buildhound-internal-adapters` from the **conditional** opt-in list into the
  **unconditional** `include(...)` list; rewrite the "off the core classpath / applying is the consent"
  comment to "bundled with core; enabling a toggle is the consent."
- **Dockerfile (blocker):** the server image builds from repo root against the full `settings.gradle.kts`
  but copies only the unconditionally-included modules' build scripts; internal-adapters is absent today
  because it is conditionally included. Once it is unconditional, Gradle 9 rejects the missing project dir.
  Fix: add `COPY buildhound-internal-adapters/build.gradle.kts buildhound-internal-adapters/` to the early
  build-script COPY block. The server does not depend on the plugin, so **no source** is needed — only the
  build script, so settings evaluation succeeds. (`:buildhound-server:installDist` compiles neither the
  plugin nor internal-adapters.)

## 4. Test strategy

- **All-off dormancy (new, core functionalTest) — the keystone for the §3.2 reversal:** apply only
  `dev.buildhound`, no `internalAdapters {}` block; assert the build succeeds, the payload has **no**
  `extensions.internalAdapters` key, and (best-effort) that no internal-API registration ran. This is the
  proof that "applying ≠ consent."
- **Warning capture (relocate `WarningCaptureFunctionalTest` → core functionalTest):** apply only
  `dev.buildhound`, set `buildhound { internalAdapters { collectDeprecations = true; collectLogWarnings =
  true } }`; assert a real `logger.warn` + a real deprecation land scrubbed in `extensions.internalAdapters`,
  and the internal-API risk notice prints. A toggles-off variant asserts the block is absent.
- **Cache-origins gating (new):** with `collectCacheOrigins = true` a cacheable task's origin/key lands;
  with only `collectDeprecations = true`, `extensions.internalAdapters` carries the deprecation but **no**
  task cache rows (trap-2 guard). CC-reuse asserted on the toggles-on path.
- **Remove `CoreAbsentFunctionalTest`:** there is no longer a standalone plugin to apply alone — the
  scenario is gone. Its intent (never fail, never capture without opt-in) is subsumed by the all-off test.
- **Module unit tests unchanged** (`InternalAdaptersUnitTest`: collector scrub/emit, accumulator
  dedup/bound/count). Add `collectCacheOrigins` to the state-reset/accumulator coverage.
- **Golden + derived:** `build-payload-v1-internal-adapters.json` / `InternalAdaptersDerivedTest` feed the
  extension explicitly (not "produced by default"), so they stay green; confirm the base golden
  (`build-payload-v1.json`) never asserted the extension present.
- `./gradlew build` green end-to-end; a Docker image build from repo root still succeeds (settings evaluates).

## 5. Risks

- **Privacy / precedent (the §3.2 focus).** The reversal makes internal-API capture reachable from the core
  plugin's classpath. Mitigation is structural, not just behavioral: off by default, load-on-consent,
  per-data-path gating, and the all-off TestKit proof. Enabling a toggle still prints the internal-API risk
  notice. No new data shape — the collector and its scrubbing are untouched.
- **CC-safety (the delicate part).** Moving the build-op listener registration from `apply()` into the
  gated `whenReady` collides with daemon-static once-per-daemon registration + CC-replay. Preserve
  `claim/release`, read-and-clear, and the "edges empty on a CC hit ⇒ null criticalPath" invariant exactly;
  the CC-reuse assertions in the functional tests are the guard.
- **Default-behavior change.** Builds that applied the old separate module for cache data now capture
  nothing until `collectCacheOrigins` is set — intended, called out in the changelog/docs.
- **Build-graph coupling.** Unconditional include + core dependency + Dockerfile COPY must land together or
  the image build breaks at settings evaluation; covered by an image build in exit criteria.

## 6. Exit criteria

`./gradlew build` green (relocated + new functional tests, unchanged goldens). Applying **only**
`dev.buildhound` with no `internalAdapters {}` produces a payload with no `extensions.internalAdapters` and
touches no internal Gradle API; flipping each toggle in the single `buildhound { internalAdapters { } }`
block captures the matching signal, scrubbed. `docker build -f buildhound-server/Dockerfile .` from repo
root succeeds. README config table, spec §3.1/§3.4, architecture §7 decision-log, CLAUDE.md hard-constraint
wording, and `samples/nowinandroid/settings.gradle.kts` (all options at defaults) are updated. Two
clean-context reviews (kotlin-gradle + mandatory §3.2 security/privacy) pass or findings are addressed.
