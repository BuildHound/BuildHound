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
1. A `ToolchainCollector` in `buildhound-gradle-plugin` that detects AGP, KGP, and KSP versions
   per project via `pluginManager.withPlugin(...)` reactions, wired under the existing
   `gradle.lifecycle.beforeProject` hook alongside `AndroidArtifactCollector`.
2. Flow of the detected versions to the Flow finalizer, collapsed to one `(agp, kgp, ksp)` triple,
   and mapped into `ToolchainInfo` in `PayloadAssembler`.
3. New golden fixtures for a payload with populated `agp`/`kgp`/`ksp` (additive — never edit
   existing goldens).

**Out:** no schema change (fields already present, additive from plan 032); no server/dashboard
change (the view already consumes these dimensions and drops the degraded panel automatically once
data arrives); build-tool versions other than AGP/KGP/KSP; per-module version reporting (we report
one triple per build).

## 3. Design

**Never-fail, no-AGP-linking contract** — mirror `AndroidArtifactCollector` exactly: BuildHound is a
*settings* plugin, so AGP/KGP/KSP are not on its classpath. `ToolchainCollector.install(project)`
references no plugin symbol itself; it only registers `withPlugin` reactions whose bodies detect a
version inside `runCatching(Throwable)`. On a non-Android/non-Kotlin build nothing links; an
unresolvable type degrades to a null dimension, never a failed build (architecture §2).

Detection, each guarded and best-effort:

| Dim | React on plugin id(s) | Version source |
|---|---|---|
| `agp` | `com.android.application`, `com.android.library` | `com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION`; fallback `com.android.builder.model.Version.ANDROID_GRADLE_PLUGIN_VERSION` |
| `kgp` | `org.jetbrains.kotlin.android`, `.jvm`, `.multiplatform` | `project.getKotlinPluginVersion()` (`org.jetbrains.kotlin.gradle.plugin`) |
| `ksp` | `com.google.devtools.ksp` | best-effort: `KspGradleSubplugin`'s JAR manifest `Implementation-Version` (package impl version) via reflection; stays null if unresolvable |

**Data flow / CC-safety.** Versions are known at configuration time, so record them into a
build-scoped **shared `BuildService`** (thread-safe map, `recordToolchain(agp/kgp/ksp)`) — the same
CC-safe channel the task/aggregation collectors use — *not* by writing files during configuration.
The Flow finalizer reads the service state at build end and collapses to a single triple (first
non-null per dimension; all modules in one build share the same versions in practice). If the
BuildService route proves awkward, the fallback is the artifact-record channel
(`gradle.lifecycle.beforeProject` → per-project JSONL under `build/buildhound/artifacts`, read by
`TelemetryFinalizerAction`), matching plan 031 — the implementer confirms which during the build and
notes the choice here.

`PayloadAssembler` gains `agp`/`kgp`/`ksp` args and emits
`ToolchainInfo(gradle, jdk, agp, kgp, ksp)`.

## 4. Test strategy

- **Unit:** version-parsing/collapse helper (first-non-null across modules, all-null → null triple).
- **TestKit (`functionalTest`):** a fixture project applying the Kotlin JVM plugin asserts `kgp`
  lands in the emitted payload; a no-Kotlin/no-Android build asserts all three stay null and the
  build still succeeds (never-fail). AGP/KSP full coverage is expensive in TestKit (needs the
  Android SDK) → validated in the sample instead (§6).
- **Golden:** new fixture pinning a payload with populated `agp`/`kgp`/`ksp`; existing goldens
  untouched.
- **CC:** functionalTest runs with configuration-cache on (store + reuse), asserting no CC problems
  from the `withPlugin`/BuildService wiring.

## 5. Risks

- **CC hazards:** the `withPlugin` reaction must not capture `Project`/configuration state into
  serialized state or perform config-time file IO — hence the BuildService channel. Covered by the
  CC-on functionalTest.
- **Classpath fragility:** AGP/KSP version constants are internal-ish; the guarded reflection +
  fallback chain is required so an AGP/KSP version bump that moves a constant degrades to null, not a
  crash. KSP has no public version constant → `ksp` may legitimately stay null on some versions;
  acceptable and honestly rendered by the existing degraded panel.
- **Schema compatibility:** additive only — fields already exist; no golden edits.
- **Security/privacy:** tool *version strings* only (e.g. `8.7.0`) — no PII, no paths, no secrets,
  already in-spec (§3.2 toolchain snapshot) and already surfaced by the server view. Scrubber
  unaffected; nothing enters an image layer. Low-risk new dimension, but §3.2 review still runs
  (plugin change).

## 6. Exit criteria

- Building `samples/nowinandroid` against the local stack produces a payload with real `agp`,
  `kgp`, and `ksp` versions (verified in the HTML report `build/buildhound/` and the dashboard
  toolchain-adoption section, which now shows distributions instead of "not collected yet").
- A non-Kotlin/non-Android build emits null for all three and does not fail.
- functionalTest passes with configuration-cache on; new golden fixture committed; existing goldens
  unchanged.
- Both §3 reviews pass: `kotlin-gradle-reviewer` (code & architecture) + the mandatory §3.2
  security & privacy review, findings addressed.
- `docs/plans/implemented/032-*.md` §6 follow-up marked done (in this PR or a sweep); plan 032's
  server view keeps working unchanged.
