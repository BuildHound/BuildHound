# Plan 046 — collect AGP / Kotlin Gradle Plugin / KSP toolchain versions

**Status: planned** · 2026-07-06

## 1. Source

Follow-up filed by [plan 032](implemented/032-bottlenecks-landing-page.md) §6 (lines 66–68,
252–255): the server toolchain-adoption view already renders `agp`/`kgp`/`ksp` as an explicit
"not collected yet" degraded panel, because those `ToolchainInfo` fields
(`buildhound-commons/.../payload/BuildPayload.kt`, `ToolchainInfo`) exist in the schema but the
plugin never populates them. `PayloadAssembler` sets only
`ToolchainInfo(gradle = …, jdk = …)`. This plan implements the missing **plugin-side collection**
so the panel shows real distributions. Spec §3.2 (toolchain/environment snapshot).

The `samples/nowinandroid` dev harness ([plan 043](043-nowinandroid-dev-harness-docs.md)) is the
end-to-end validation target — a real multi-module build that applies AGP, KGP, and KSP.

## 2. Scope

**In:**
1. A `ToolchainDetection` object in `buildhound-gradle-plugin` that detects AGP, KGP, and KSP
   versions from the applied plugins, called from the settings plugin's `taskGraph.whenReady`
   callback (see §3 for why not `beforeProject`).
2. Flow of the detected versions through a **finalizer (Flow-action) parameter** to the Flow
   finalizer, collapsed to one `(agp, kgp, ksp)` triple, and mapped into `ToolchainInfo` in
   `PayloadAssembler`.
3. Golden coverage for a payload with populated `agp`/`kgp`/`ksp` (the v1 golden already carries
   them — assertions strengthened; no golden file edited or added).

**Out:** no schema change (fields already present, additive from plan 032); no server/dashboard
change (the view already consumes these dimensions and drops the degraded panel automatically once
data arrives); build-tool versions other than AGP/KGP/KSP; per-module version reporting (we report
one triple per build).

## 3. Design

> **Divergence from the pre-implementation plan** (updated during implementation, per the CLAUDE.md
> workflow; every point below was forced by an empirical result — several confirmed by building the
> `samples/nowinandroid` harness). The original draft proposed a `beforeProject` + `withPlugin`
> reaction recording into a BuildService, AGP via `com.android.Version`, KGP via
> `getKotlinPluginVersion()`. What shipped:
> 1. **Hook:** `gradle.lifecycle.beforeProject` runs its action **isolated** (may capture only
>    serializable state — why `AndroidArtifactCollector` is a top-level function holding one `File`),
>    so it cannot fill a settings-scope mailbox. The non-isolated `taskGraph.whenReady` callback — which
>    already builds the task dictionary — is the correct hook.
> 2. **AGP source:** `extensions.findByType(AndroidComponentsExtension::class.java)` with a raw generic
>    `Class` does **not** match AGP's parameterized extension registration (verified: returns null on a
>    real AGP project). Look the extension up by its stable name `androidComponents` and read
>    `pluginVersion` reflectively instead — which also frees the file of any compile-time AGP type.
> 3. **KSP source:** the jar manifest `Implementation-Version` is **null** for the KSP plugin (verified).
>    `KspGradleSubplugin.getPluginArtifact().version` carries it with no dependency resolution.
> 4. **Channel:** a `TaskEventCollector` **service** parameter is frozen when the service is first
>    instantiated — and in a composite build (nowinandroid uses `includeBuild`) an included build's task
>    completes during the root's *configuration*, instantiating the collector **before** `whenReady`
>    runs, so the store run recorded an empty toolchain. Moved to a **finalizer** (Flow-action)
>    parameter, whose providers resolve after configuration.

**Detection — `ToolchainDetection.detect(projects)`** (new file). Called from `whenReady` over
`settings.gradle.rootProject.allprojects` (every configured project, so a narrow request like
`:core:common:assemble` still sees AGP — not just projects with a realized task). Returns the first
non-null version per dimension; each probe individually `runCatching`-guarded. All probes are **pure
reflection** over the applied-plugin objects — no compile-time AGP/KGP/KSP type is referenced.

| Dim | Gate (string `hasPlugin`) | Version source |
|---|---|---|
| `agp` | `com.android.application` / `.library` / `.kotlin.multiplatform.library` / `.dynamic-feature` / `.test` | `extensions.findByName("androidComponents")` → reflect `pluginVersion.version` (AGP's own canonical string, formats releases and previews as AGP prints them); fallback `com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION` via AGP's classloader (older AGP / other extension names) |
| `kgp` | `org.jetbrains.kotlin.jvm` / `.android` / `.multiplatform` | reflect applied `org.jetbrains.kotlin…` plugin's no-arg `getPluginVersion()` (`KotlinBasePlugin.pluginVersion`) |
| `ksp` | `com.google.devtools.ksp` | reflect `getPluginArtifact().version`; fallback jar manifest `Implementation-Version` |

The **KMP-library** plugin (`com.android.kotlin.multiplatform.library`) is in the AGP gate because its
`KotlinMultiplatformAndroidComponentsExtension` also registers as `androidComponents` but applies none
of app/library. **Never-fail:** every probe is guarded → one dimension degrades to null; nothing links
an AGP type, so a non-Android build cannot `NoClassDefFoundError`. The detection object's static
initializer is kept Gradle-free (the logger is fetched inside `guarded`, not a field) so the reflection
helpers are unit-testable in the Gradle-free test source set.

**Data flow / CC-safety.** `whenReady` fills an `AtomicReference<DetectedToolchain>` mailbox (gated off
under **isolated projects**, where the cross-project walk is illegal — degrading to null like the task
dictionary); a provider over it is set as the **finalizer's** `toolchain` parameter, resolved after
configuration and **replayed verbatim on a config-cache hit**. The finalizer reads
`parameters.toolchain` and passes `agp`/`kgp`/`ksp` to `PayloadAssembler.assemble`, which emits
`ToolchainInfo` whenever *any* dimension (incl. Gradle/JDK) is known — no longer gated on the
environment snapshot.

**Test seam.** `buildhound.internal.toolchain.{agp,kgp,ksp}` gradle properties, when set, are reported
verbatim instead of walking the graph (mirrors the repo's other `buildhound.internal.*` failpoints).
This lets the TestKit suite exercise the whole channel + its CC replay without a heavy, version-coupled
real AGP/KGP/KSP build — the same tactic `KotlinReportFunctionalTest` uses (it seeds a fake KGP report
rather than compiling Kotlin). Absent in every real build.

## 4. Test strategy

- **Unit (`ToolchainDetectionTest`):** the pure reflective helper (`noArgStringGetter`: reads a
  declared no-arg String getter; null for absent/non-String) and `DetectedToolchain.isEmpty`. The
  per-project probes need a live project graph with real plugins → covered by functionalTest + sample.
- **Unit (`PayloadAssemblerTest`):** detected `agp/kgp/ksp` join `gradle/jdk` in the toolchain block;
  an undetected toolchain leaves them null without dropping `gradle/jdk`.
- **TestKit (`ToolchainFunctionalTest`, CC on):** seam-injected versions reach the payload **and
  survive config-cache reuse**; only seeded dimensions populate; a build applying none of the tools
  reports all-null and still succeeds (never-fail); and a **composite build** (a plugin-providing
  `includeBuild` whose compile runs during the root's configuration — the freeze condition) carries the
  toolchain on both the store run and a hit, guarding the finalizer-param channel choice. The full
  functionalTest suite passing confirms the `whenReady`/finalizer-param wiring adds no CC problem.
- **Golden:** the v1 golden already carries a populated `toolchain` (agp/kgp/ksp); `GoldenPayloadTest`
  is strengthened to assert those five fields (no golden file edited/added — the wire contract was
  already pinned).
- **Real extraction** (AGP `findByName`+reflect, KGP reflection, KSP `getPluginArtifact`) is validated
  against the `samples/nowinandroid` harness (§6) — real AGP/KGP/KSP builds are heavy and version-
  coupled, so this is a manual harness run, not a TestKit case. The composite-build store-run channel
  fix is proven both there (CC miss and hit both carry the triple) and by the composite TestKit case.

## 5. Risks

- **CC hazards:** detection runs at configuration time in `whenReady` and extracts only Strings into
  the mailbox — no `Project`/config state is captured into serialized state and no file IO happens at
  config time. The value rides a **finalizer parameter** (resolved after configuration, replayed on a
  hit — the earlier service-parameter wiring was frozen too early in composite builds, see §3). Covered
  by the CC-on functionalTest (store + reuse), the full suite passing, and the sample on CC miss + hit.
- **Classpath fragility:** AGP uses the public, stable `AndroidComponentsExtension.pluginVersion`
  Variant API; KGP a stable `getPluginVersion()` reflected off the applied plugin. KSP is the weak
  link — no public version API, so we read a jar manifest attribute that may be absent → `ksp` can
  legitimately stay null, honestly rendered by the existing degraded panel. Every probe is guarded, so
  any API/classpath drift degrades that one dimension to null rather than failing the build.
- **Schema compatibility:** additive only — fields already exist; no golden edits.
- **Security/privacy:** tool *version strings* only (e.g. `8.7.0`) — no PII, no paths, no secrets,
  already in-spec (§3.2 toolchain snapshot) and already surfaced by the server view. Scrubber
  unaffected; nothing enters an image layer. Low-risk new dimension, but §3.2 review still runs
  (plugin change).

## 6. Exit criteria

- Building `samples/nowinandroid` produces a payload with real `agp`, `kgp`, `ksp` versions. ✓
  **Verified:** `./gradlew help` on the sample yields `toolchain = {gradle 9.4.0, jdk 21.0.2, agp 9.0.0,
  kgp 2.3.0, ksp 2.3.4}` — matching the sample's version catalog — on **both** the CC store run and the
  CC hit. (`getPluginArtifact` gave a real KSP version, so KSP is not null here.)
- A non-Kotlin/non-Android build emits null for all three and does not fail. ✓ (functionalTest)
- functionalTest passes with configuration-cache on; golden assertions strengthened; no golden file
  edited or added. ✓
- Both §3 reviews pass: `kotlin-gradle-reviewer` (code & architecture) + the mandatory §3.2
  security & privacy review (**passed — no material findings**), findings addressed.
- `docs/plans/implemented/032-*.md` §6 follow-up marked done (in this PR or a sweep); plan 032's
  server view keeps working unchanged.
