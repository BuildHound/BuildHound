# Plan 051 — two more dev-harness samples: suboptimal Spring Boot (50 modules) + legacy-AGP Android

**Status: planned** · 2026-07-08

## 1. Source

Feature request: "Add 2 more sample projects in `samples/`: (1) a multi-module Spring Boot
project with a **sub-optimal** Gradle configuration and ~50 modules with some nesting; (2) a
multi-module **Android** project on an **older AGP** (8.5). Wire both to report builds via the
BuildHound plugin."

Supporting context:
- `samples/nowinandroid` is the existing precedent: a real, checked-in Gradle build that applies
  BuildHound as an **included build** (`includeBuild("../..")`), configures the `buildhound {}`
  DSL against the local stack (`localhost:8080`, token `buildhound-local-dev-token`), and asserts
  a JDK 21+ floor in `settings`. The new samples follow the same wiring (`samples/README.md`, plan 043).
- Compatibility contract (spec §53): **Gradle 8.14+ and 9.x**, config cache on/off both green,
  JDK 21+ runtime. This bounds the version choices below.
- The plugin captures `parallel`, `configurationCacheRequested`, cache stats, and config time
  (`EnvironmentValueSource`, `FingerprintValueSource`, `BuildHoundSettingsPlugin`) — so a
  deliberately suboptimal build is *measurable* telemetry, not a cosmetic label.

## 2. Scope

**In (new checked-in sample trees + docs — no plugin/server/commons behaviour change):**

1. `samples/springboot-legacy/` — a **50-module** Spring Boot build, deliberately suboptimal:
   - Gradle **9.6.1** (latest — the point is "modern Gradle, bad *config*").
   - Spring Boot **3.5.16** + `io.spring.dependency-management` **1.1.7** (the legacy BOM style,
     applied via `buildscript` classpath + `apply plugin:`, not the `plugins {}` DSL).
   - `settings.gradle.kts` (applies `dev.buildhound`); **Groovy** `build.gradle` module files (a
     realistic legacy mix; keeps the suboptimal flavour, dodges Groovy-extension DSL risk).
   - Suboptimal, BuildHound-observable traits: config cache **off**, build cache **off**,
     `org.gradle.parallel=false`, a root `subprojects {}` cross-project-configuration block (blocks
     CC, inflates config time), the plain `java` plugin (no `java-library` api/implementation split —
     itself a legacy smell), hardcoded dependency versions (no catalog), per-subproject
     `repositories { mavenCentral() }` (needs a relaxed `repositoriesMode` — itself part of the
     anti-pattern), and a small `-Xmx`.

   > **Divergences from this plan (reconciled during implementation):**
   > - *`configureondemand`:* the plan proposed `org.gradle.configureondemand=true`, but combined with
   >   the cross-project `subprojects {}` block it **corrupts the task graph** (dependency modules are
   >   never built → app compiles fail). That is a correctness bug, not just a slow build, so the
   >   sample ships it **off** and keeps the other "slow but valid" anti-patterns. Documented inline
   >   in `gradle.properties`.
   > - *`java` vs `java-library`:* the plan said non-app modules would be `java-library`; they ship as
   >   plain `java` instead. Using `java` (no api/implementation separation) is a more authentic legacy
   >   anti-pattern and keeps every module's project dependency on the consumer's compile classpath,
   >   which the inter-module DAG relies on.
   > - Repeated leaf module names (`api`/`domain`/`persistence`/`web` across 10 services) forced two
   >   disambiguations in the root build that the plan did not anticipate: a path-qualified `group`
   >   (so identical coordinates don't collapse under conflict resolution) and a path-qualified
   >   `archivesName` (so app `bootJar`s don't hit duplicate `web-1.0.0.jar` entries). Both are
   >   documented inline.
   - 50 modules with 2–3 levels of nesting and a **real inter-module DAG**
     (`apps → services:<svc>:{api,domain,persistence,web} → libs:*`) so the timeline / critical-path
     telemetry is non-trivial: 6 `libs`, 10 `services` × 4 submodules (= 40), 4 `apps`.
   - Each module carries 1–2 trivial Java classes that reference a type from a dependency module,
     so compilation does real, ordered work. The 4 `apps` apply the Spring Boot plugin
     (`@SpringBootApplication` + `bootJar`); everything else is a plain `java` module (see the
     divergence note below).

2. `samples/android-legacy-agp/` — a multi-module Android build on **older AGP**:
   - Gradle **8.14.5** (the only line that satisfies *both* the plugin floor 8.14+ **and** AGP 8.5's
     min 8.7; AGP 8.5 will not run on Gradle 9). AGP **8.5.2**, Kotlin **2.0.21**,
     `compileSdk 34`, `minSdk 24`.
   - `plugins {}` DSL + version catalog (clean modern layout — the *only* deliberately old thing is
     AGP 8.5, per the request). `settings.gradle.kts` applies `dev.buildhound`.
   - ~10 modules with nesting: `:app`, `:core:{common,ui,data,network}`,
     `:feature:{home,profile,settings}`, `:library:{analytics,logging}`; Kotlin sources + minimal
     manifests, one `Activity` in `:app`. `:app → :feature:* → :core:* → :library:*`.

3. `samples/README.md` — extend the table + end-to-end section for both samples; state the Android
   SDK requirement and the expected "AGP 8.5 untested against Gradle 8.14" warning (good demo fodder
   for the warnings panel, plan 048). Per-sample `.gitignore` (`build/`, `.gradle/`, and Android's
   `local.properties`) and a per-sample wrapper (copied `gradlew`/`.bat`/jar, `distributionUrl` +
   `distributionSha256Sum` edited to the target version).

**Out:** any change to the plugin / server / commons / `buildhound-commons` schema; publishing;
making the samples part of the root Gradle build (they are standalone composite consumers, like
nowinandroid); exhaustively fleshed-out app logic (samples exist to exercise the plugin, not to ship).

## 3. Design

Both samples are standalone Gradle builds that pull BuildHound in via `includeBuild("../..")` and
configure the `buildhound {}` DSL exactly as nowinandroid does (`htmlReport.enabled`,
`server.url`/`server.token` env-var-first with the committed local-dev fallback,
`localBuilds { enabled = true; requireOptInFile = false }`). No values enter an image layer or the
plugin classpath. The 50-module Spring tree is generated by a throwaway script (scratchpad); only its
**output** is committed. Spring `settings.gradle.kts` uses a **relaxed** `repositoriesMode` (not
nowinandroid's `FAIL_ON_PROJECT_REPOS`) because the per-subproject repositories are a deliberate
anti-pattern that `FAIL_ON_PROJECT_REPOS` would reject.

## 4. Test / verification strategy

Vertical-slice-first: scaffold root + wrapper + `buildhound {}` + **one** leaf module and get it
green before generating the rest, so a version incompatibility costs a version bump, not 150 files.

- **Android linchpin (highest risk):** `./gradlew :app:help` must configure on Gradle 8.14.5 with
  AGP 8.5.2 (SDK present at `~/Library/Android/sdk`, `android-34`). A *warning* about the untested
  Gradle is expected and fine; a hard *failure* is a genuine plugin-floor-vs-AGP-8.5 tension to
  surface to the owner, not to paper over by bumping AGP.
- **Wiring (both):** `./gradlew help` fires the Flow finalizer regardless of compilation, so it must
  write a report under `build/buildhound/` — proves includeBuild → plugin apply → report end-to-end.
- **Compilation (both):** one real compile task on a leaf module builds (proves the DAG + sources).
- Re-runs pass `--no-configuration-cache`/`--rerun-tasks` to avoid CC replaying a stale payload.

## 5. Risks

- **AGP 8.5 ↔ Gradle window is one minor wide** (8.14.x only). Mitigation: prove `:app:help` first;
  no silent AGP bump.
- **Boot 3.5.16 on Gradle 9.6.1** — verify in the Spring slice; fall back to Gradle 8.14.5 if the
  Boot plugin complains (low stakes; documented).
- **Security/privacy:** the only credential present is the *already checked-in* local-dev token,
  reused verbatim from nowinandroid and labelled local-only; nothing new is collected, no image
  layer touched, env-var-first token wiring preserved. Samples collect no PII beyond what the plugin
  already scrubs.
- **Android SDK dependency:** the sample cannot even *configure* without an SDK; documented in
  prerequisites, not claimed as verified where absent.

## 6. Exit criteria

- `samples/springboot-legacy/` exists with 50 nested modules, a suboptimal-but-valid Gradle config,
  applies BuildHound, and `./gradlew help` writes an HTML report; a leaf module compiles.
- `samples/android-legacy-agp/` exists on AGP 8.5.2 / Gradle 8.14.5, applies BuildHound, and
  `./gradlew :app:help` configures (warning-only) on a machine with the Android SDK.
- `samples/README.md` documents both, including the Android SDK prerequisite and the expected AGP
  warning.
- Both §3 reviews (code & architecture via `kotlin-gradle-reviewer`; security & privacy) pass with
  findings addressed or noted.
