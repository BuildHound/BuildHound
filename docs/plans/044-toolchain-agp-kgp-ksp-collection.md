# Plan 044 — collect AGP / Kotlin Gradle Plugin / KSP toolchain versions

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
2. Flow of the detected versions through a `TaskEventCollector` service parameter to the Flow
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
> workflow). The original draft proposed a `beforeProject` + `withPlugin` reaction that records into a
> BuildService, AGP via `com.android.Version`, KGP via `getKotlinPluginVersion()`. Two facts changed
> it: (1) `gradle.lifecycle.beforeProject` runs its action **isolated** (it may capture only
> serializable state — that is why `AndroidArtifactCollector` is a top-level function capturing one
> `File`), so it cannot fill a settings-scope mailbox; the non-isolated `taskGraph.whenReady` callback
> — which already builds the task dictionary — is the correct hook. (2) `AndroidComponentsExtension`
> exposes `pluginVersion` (`com.android.build.api.AndroidPluginVersion`) directly in the Variant API
> we already link `compileOnly`, a cleaner source than the `com.android.Version` constant. The shape
> below is what shipped.

**Detection — `ToolchainDetection.detect(projects)`** (new file). Called from `whenReady` over
`settings.gradle.rootProject.allprojects` (every configured project, so a narrow request like
`:core:common:assemble` still sees AGP — not just projects with a realized task). Returns the first
non-null version per dimension; each probe individually `runCatching`-guarded.

| Dim | Gate (string `hasPlugin`, no type link) | Version source |
|---|---|---|
| `agp` | `com.android.application` / `com.android.library` | `AndroidComponentsExtension.pluginVersion` formatted `major.minor.micro(-previewTypePreview)` |
| `kgp` | `org.jetbrains.kotlin.jvm` / `.android` / `.multiplatform` | reflection: applied `org.jetbrains.kotlin…` plugin's no-arg `getPluginVersion()` (`KotlinBasePlugin.pluginVersion`) |
| `ksp` | `com.google.devtools.ksp` | best-effort: KSP plugin jar's `Implementation-Version` (package impl version); stays null if the jar omits it |

**Never-fail, no-eager-AGP-linking contract** (mirrors `AndroidArtifactCollector`): AGP types appear
*only* inside the private `agpVersion` method, which the loop enters *only* after the string
`hasPlugin` gate confirms AGP is applied — so the JVM never verifies/links an AGP type on a build
without AGP. KGP/KSP probes link no plugin type (pure reflection). The detection object's static
initializer is kept Gradle-free (the logger is fetched inside `guarded`, not a field) so the pure
`noArgStringGetter` helper is unit-testable in the Gradle-free test source set.

**Data flow / CC-safety.** `whenReady` fills an `AtomicReference<DetectedToolchain>` mailbox; a
provider over it is set as a new `TaskEventCollector.Params.toolchain` service parameter — the exact
mailbox→provider→service-param pattern that carries `taskMetadata`/`testResultLocations`, so the
detected value is **replayed verbatim on a config-cache hit** (the `whenReady` callback need not
re-run). Detection is gated off under **isolated projects** (the cross-project walk is illegal there),
degrading to null exactly like the task dictionary. The finalizer reads `collector.snapshotToolchain()`
and passes `agp`/`kgp`/`ksp` to `PayloadAssembler.assemble`, which now emits `ToolchainInfo` whenever
*any* dimension (incl. Gradle/JDK) is known — no longer gated on the environment snapshot.

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
  reports all-null and still succeeds (never-fail). The full functionalTest suite passing confirms the
  `whenReady`/service-param wiring adds no CC problem.
- **Golden:** the v1 golden already carries a populated `toolchain` (agp/kgp/ksp); `GoldenPayloadTest`
  is strengthened to assert those five fields (no golden file edited/added — the wire contract was
  already pinned).
- **Real extraction** (AGP `pluginVersion`, KGP reflection, KSP manifest) is validated against the
  `samples/nowinandroid` harness (§6) — it needs the Android SDK, so it is not a TestKit case.

## 5. Risks

- **CC hazards:** detection runs at configuration time in `whenReady` and extracts only Strings into
  the mailbox — no `Project`/config state is captured into serialized state and no file IO happens at
  config time. The value rides a service parameter (replayed on a hit). Covered by the CC-on
  functionalTest (store + reuse) and the full suite passing.
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

- Building `samples/nowinandroid` against the local stack produces a payload with real `agp`,
  `kgp`, and `ksp` versions (verified in the HTML report `build/buildhound/` and the dashboard
  toolchain-adoption section, which now shows distributions instead of "not collected yet").
  **Pending** a machine with the Android SDK — not runnable in the implementation sandbox
  (`ANDROID_HOME` unset); the KSP-manifest probe in particular is only confirmed here.
- A non-Kotlin/non-Android build emits null for all three and does not fail. ✓ (functionalTest)
- functionalTest passes with configuration-cache on; golden assertions strengthened; no golden file
  edited or added. ✓
- Both §3 reviews pass: `kotlin-gradle-reviewer` (code & architecture) + the mandatory §3.2
  security & privacy review, findings addressed.
- `docs/plans/implemented/032-*.md` §6 follow-up marked done (in this PR or a sweep); plan 032's
  server view keeps working unchanged.
