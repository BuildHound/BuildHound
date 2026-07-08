# 072 — Server-side JVM parity: `springBoot` toolchain + `bootJar`/`jar` artifact sizes

## Source

- Research finding **F22**, `docs/research/ingest-corpus-analysis.md` §5 ("Server-side JVM (Spring
  Boot) parity: `springBoot` toolchain + `bootJar` size"). Source article:
  `docs/research/processed/Gradle Build Optimization for Large Spring Boot Apps.md` (Boot on Gradle 9 +
  CC/Java 17–25); corroborated by `State of the Configuration Cache - On the Road to Gradle 9.md`.
- Spec §3.2 (toolchain snapshot) and §4 (`artifacts`). Builds directly on plans
  [046](implemented/046-toolchain-agp-kgp-ksp-collection.md) (reflection-at-`whenReady` detection → finalizer
  param), [031](implemented/031-artifact-size-capture.md) (`artifacts` block + Flow-time `File.length`
  read + server cap), [016](implemented/016-task-type-cacheable-capture.md) (the non-isolated
  `whenReady` task dictionary, IP-gated), and the plan-[032](implemented/032-bottlenecks-landing-page.md)
  toolchain-adoption view.

## Scope

**In**

- Widen the market past Android: detect `org.springframework.boot` and measure JVM archive sizes, the
  server-service analogue of AGP + APK size. **Plugin-side collection + additive schema**, with the two
  existing server surfaces (toolchain adoption, HTML report) extended to render them.
- `toolchain.springBoot` (nullable version, honest-null) reported like `agp`/`kgp`/`ksp`.
- `artifacts.jvm`: `bootJar`/`bootWar`/`jar`/`war` `sizeBytes`, measured **only for the archive tasks
  that actually ran** in this invocation.
- Server toolchain-adoption gains a `springBoot` dimension (jsonb read — **no migration**); the
  standalone HTML report gains a JVM-artifacts table; `PayloadCapper` bounds `artifacts.jvm`.

**Out (deferred)**

- A `jvm_sizes` hot-projection table + `/v1/artifacts/trends` JVM series + a dashboard JVM-size panel
  (plan-031's Android path already has these; the JVM data rides in `builds.payload` jsonb and is
  queryable — a follow-up adds the hot rollup). This slice lands data + adoption + report only.
- A distinct Boot *presence* signal decoupled from version (see Risks) — a future additive field.
- Per-artifact composition (layered-jar / native-image / dependency split), non-archive outputs, and
  any measuring-task registration (unneeded — archive tasks are core Gradle types; §Design).
- No `SCHEMA_VERSION` bump (stays `1`).

## Design

**commons (additive, `BuildPayload.kt`).** `ToolchainInfo` (`:284`) gains `springBoot: String? = null`.
`ArtifactSizes` (`:83`) gains `jvm: List<JvmArtifactSize> = emptyList()` beside `android`; new
`enum JvmArtifactKind { BOOT_JAR, BOOT_WAR, JAR, WAR }` and `data class JvmArtifactSize(module: String?,
kind: JvmArtifactKind, sizeBytes: Long)` (byte size only, no path/contents — §3.7; `module` is a Gradle
path like `:app`). `SCHEMA_VERSION` stays `1`; a new golden is added, existing goldens untouched.

**Detection (`ToolchainDetection.kt`, plan 046).** `DetectedToolchain` (`:14`) gains
`springBoot: String? = null` (folded into `isEmpty()`). `detect()` adds a probe gated on
`hasPlugin("org.springframework.boot")`; the version comes from the applied plugin's jar manifest —
`plugin.javaClass.getPackage()?.implementationVersion`, the exact KSP-style fallback at
`ToolchainDetection.kt:132` — so **presence detects reliably, version is honest-null** when the manifest
carries none. Pure reflection, individually `guarded` → one dimension degrades to null, never the build.

**JVM archive capture (`BuildHoundSettingsPlugin.kt`).** Reuse the plan-046 mailbox→finalizer-param
channel (not plan-031's JSON-line files). In the existing non-isolated `whenReady` block (`:121`, the
same hook that builds the task dictionary and runs toolchain detection — IP-gated identically), filter
`graph.allTasks` (i.e. **only tasks scheduled this build** = "what's built") to core-Gradle
`org.gradle.api.tasks.bundling.AbstractArchiveTask` whose name ∈ {bootJar, bootWar, jar, war}, and
record a serializable `JvmArtifactLocation(module, kind, taskPath, archivePath)` per task —
`archivePath = task.archiveFile.orNull?.asFile?.absolutePath` (**location only, no file read**; mirrors
`testLocationOf` at `:326`) and `module = task.path.substringBeforeLast(':').ifEmpty { ":" }`. These go
into a new `AtomicReference<List<JvmArtifactLocation>>` mailbox (sibling to `toolchainHolder`), inside
its own `runCatching` after the task/test capture so a failure can't block them. A new finalizer
parameter `jvmArtifacts` is wired from `settings.providers.provider { holder.get() }` (resolved after
configuration, baked into the CC entry, replayed on a hit). The toolchain seam gains
`buildhound.internal.toolchain.springBoot`.

*Why core-Gradle types matter:* `AbstractArchiveTask` is public core API always on the plugin classpath,
so — unlike plan 031's AGP `SingleArtifact` wiring that needs the project-plugin classloader (the
boundary the F22 correction calls a *mitigated* risk, not a shipped defect) — there is no external
classloader, no measuring task, and no `NoClassDefFoundError` surface. No internal API (architecture §2
rule 4).

**Flow-time read (`TelemetryFinalizerAction.kt`).** New `@get:Input @get:Optional jvmArtifacts:
ListProperty<JvmArtifactLocation>`. In `execute`, after `tasks = collector.snapshot()`, compute
`jvmSizes` by cross-referencing each location's `taskPath` against `tasks.associate { it.path to
it.outcome }`: **measure only when the task's recorded outcome produced output** (skip
`SKIPPED`/`NO_SOURCE`/`FAILED`) *and* `File(archivePath).exists()`, then `File.length()`. This is the
load-bearing "measure-only-what-ran": Boot builds **both** `jar` (plain classifier) and `bootJar` by
default (Boot 2.5+), so a default Boot module contributes two rows; the outcome+exists filter also
correctly handles a user-disabled `jar`, whose declared `-plain.jar` path would otherwise report a
stale/absent artifact. The read sits inside the finalizer's
outer `runCatching` (→ warn + marker, never a failed build). `PayloadAssembler.assemble` gains
`jvmArtifacts: List<JvmArtifactSize> = emptyList()`; it emits `ArtifactSizes(android = …, jvm = …)`
whenever *either* list is non-empty (today: `artifacts.takeIf …` at `:183`), capping `jvm` largest-first
like `capArtifacts`.

**Server (`Bottlenecks.kt` / `PostgresStores.kt` / in-memory).** `ToolchainRollup` (`Bottlenecks.kt:74`)
gains `springBoot: ToolchainDimension = ToolchainDimension(available = false)` (additive default). Both
stores' `toolchainAdoption` add `payload->'toolchain'->>'springBoot'` to the existing jsonb select
(`PostgresStores.kt:737-741`) and feed `ToolchainCalculator.dimension` — no migration, no new route (the
tenant-scoped `GET …/rollups/toolchain`, `Routes.kt:485`, returns the rollup as-is). `PayloadCapper.cap`
bounds `artifacts.jvm` (defense-in-depth: a direct `POST /v1/builds` bypasses the plugin cap), reusing
`CapsSummary.droppedArtifacts` (doc broadened to android+jvm).

**Report.** `report-template.html` gains a current-build JVM-artifacts table (module/kind/size) reading
`payload.artifacts.jvm`, hidden when empty — same embedded, escaped, zero-network blob as the plan-031
Android table — plus a `springBoot` toolchain chip beside `agp`/`kgp`/`ksp` (`ReportAssetsTest` already
pins "a chip for every toolchain dimension").

**Dashboard (the "toolchain adoption" render surface — Scope "extended to render them").** The
`toolchainPanel` list in `dashboard.js` gains a `["Spring Boot", toolchain.springBoot]` entry so the new
rollup dimension actually renders (guarded `if (!dim || !dim.available)` so a missing dimension can't
throw); the `dashboard-smoke.js` toolchain fixture gains a `springBoot` dimension. This is the
adoption-render surface only — **no** dashboard JVM-artifact-*size* panel (that hot projection + panel is
the explicitly deferred slice under Scope "Out").

## Test strategy

- **commons golden/unit (`GoldenPayloadTest`):** new `build-payload-v1-jvm.json` carrying
  `toolchain.springBoot` + `artifacts.jvm` decodes; the existing `build-payload-v1-artifacts.json` still
  decodes with `artifacts.jvm == emptyList()` (backward-compat, **unedited**); `JvmArtifactKind` serial
  names are stable.
- **plugin unit:** `ToolchainDetectionTest` — the springBoot version helper (manifest `implementationVersion`
  present vs honest-null) over the existing reflective seam. `PayloadAssemblerTest` — `jvmArtifacts` flow
  into `artifacts.jvm`; `artifacts == null` when both lists empty; jvm cap largest-first.
- **plugin functionalTest (TestKit, CC on):** a real `java-library`/`application` fixture producing a
  `jar` (core Gradle — no heavy toolchain needed) asserts `artifacts.jvm` carries a non-zero size on the
  store run **and CC reuse**; a build whose archive task is not requested (or `NO_SOURCE`) carries **no**
  jvm record (only-what-ran); a non-JVM build and isolated-projects both yield `artifacts.jvm` empty and
  succeed (never-fail / IP degrade). `springBoot` detection is exercised through the
  `buildhound.internal.toolchain.springBoot` seam reaching the payload and surviving CC reuse (mirrors
  `ToolchainFunctionalTest`), avoiding a heavy real-Boot build.
- **server (Testcontainers + `testApplication`):** ingest a payload with `toolchain.springBoot`;
  `…/rollups/toolchain` renders a `springBoot` dimension `available=true` with the version share;
  in-memory store parity; read-scope (403 for ingest-only token) + tenant-scoping unchanged.

## Risks

- **Honest-null version (narrowing a) — no sentinel.** `springBoot` is the resolved version or `null`,
  exactly parallel to `agp`/`kgp`/`ksp`. A sentinel like `"unknown"` would sort **below every real
  version** under `ToolchainCalculator`'s `VERSION` comparator (non-numeric first segment) and mislabel
  present-but-unversioned Boot builds as "behind" — strictly worse than "not collected". Boot publishes
  proper jar manifests, so the version resolves in the common case; known limitation: an all-unversioned
  fleet shows the dimension as "not collected". A distinct presence field is out of scope (deferred).
- **Only-what-ran (narrowing b).** Capture is filtered to `graph.allTasks` at config time and gated at
  Flow time on produced-output outcome + file existence (see Design): Boot builds both `jar` and
  `bootJar` by default, so a default Boot module correctly yields two rows, while the same gate
  prevents a stale/absent row for a user-disabled `jar`.
- **CC safety.** Config time captures **locations only** (`archiveFile.orNull?.asFile?.absolutePath` — no
  read → no CC fingerprint input); state rides an `AtomicReference` mailbox → finalizer param (resolved
  after config, replayed on a hit — the plan-046 channel verbatim); `File.length()` runs in the Flow
  action at execution time (plan-031 precedent). Covered by the CC-on functionalTest (store + reuse).
- **Additive schema.** `ToolchainInfo.springBoot`, `ArtifactSizes.jvm`, `JvmArtifactSize/Kind`, and
  `ToolchainRollup.springBoot` are all new with defaults; `SCHEMA_VERSION` stays `1`; new golden only,
  existing goldens untouched; `ignoreUnknownKeys` lets older servers ignore the block. All four kinds are
  fixed up front because `ignoreUnknownKeys` covers unknown *keys*, not unknown *enum values* — a later
  kind-addition would not be safely additive for older readers.
- **Privacy (§3.7).** The archive absolute path rides the finalizer param and is baked into the **local**
  CC entry (local, not egress); it is used solely to `File.length()` and **never** enters the payload.
  Only `sizeBytes` + `module` (`:app`) + `kind` ship; `springBoot` is a version string. Scrubber
  unaffected; nothing new reaches logs or an image layer.
- **Isolated projects.** springBoot detection and JVM archive capture both gate off under IP exactly like
  the plan-016 task dictionary and the plan-046 toolchain triple (the `whenReady` graph walk is illegal
  under IP) → degrade to null/empty, never fail.
- **Multi-tenancy.** No new endpoint; the `springBoot` dimension reads the same tenant-scoped
  `builds.payload` jsonb behind the existing `authenticatedProject` gate (`Routes.kt:485`) — no
  cross-tenant surface.
- **Never-fail.** Every probe/read is guarded: springBoot + JVM capture each in their own `whenReady`
  `runCatching` (after the higher-stakes task/test capture); the Flow-time `File.length` inside the
  finalizer's outer `runCatching` → warn + marker.

## Exit criteria

- A real JVM build (a `java-library`/`application` fixture, and the pilot Boot app) produces
  `artifacts.jvm` with a non-zero size for each archive task that ran, under CC store **and** reuse; a
  build whose archive task did not run carries no jvm record; the standalone HTML report shows those
  sizes offline.
- `toolchain.springBoot` populates when the Boot plugin manifest carries a version, is `null` (honest)
  when it does not, and both degrade cleanly under isolated projects; the seam-driven functionalTest pins
  the channel + CC replay.
- A non-JVM build and this repo's own build emit `artifacts.jvm == []` / `toolchain.springBoot == null`
  and never fail; IP leaves both empty.
- On the compose stack, ingesting a springBoot-bearing build makes `…/rollups/toolchain` render a
  tenant-scoped `springBoot` dimension; no migration added.
- New `build-payload-v1-jvm.json` golden added, existing goldens unedited, `SCHEMA_VERSION` still `1`;
  architecture decision-log rows added (core-archive-type capture vs the plan-031 AGP path; additive
  `springBoot`/`artifacts.jvm` at v1).
- Manual validation against a real Boot build (`samples/springboot-legacy`, CI-skipped like the
  plan-031 AGP fixture — see `JvmArtifactSizeFunctionalTest`'s `@Disabled` test) is outstanding;
  track it as a follow-up rather than a store-blocking gap.
- Both §3 reviews pass (`kotlin-gradle-reviewer` + the mandatory §3.2 security & privacy review), findings
  addressed; `./gradlew build` green.
