# 077 — Gradle Plugin Portal release

## Source

Owner request to prepare `dev.buildhound` for the Gradle Plugin Portal, following Gradle's
current publishing guide and using user-home credentials locally plus GitHub Actions secrets
in CI. Spec §8 already fixes the distribution target as the Gradle Plugin Portal.

## Scope

**In:** publish-ready core-plugin metadata and version override; a self-contained portal artifact;
local release instructions; and a protected GitHub Actions deployment triggered only by a release
or an explicit manual dispatch. **Out:** publishing the test-sharding addon, Maven Central, signing,
creating Portal/GitHub credentials, and changing the default development version.

## Design

- Pin the current `com.gradle.plugin-publish` and newest Gradle-8.14-compatible
  `com.gradleup.shadow` versions in the version catalog. Apply both only to
  `buildhound-gradle-plugin`.
- Complete `gradlePlugin {}` with the public GitHub repository as both the landing page and VCS URL,
  plus a Configuration Cache compatibility declaration. This diverges from the planned
  `buildhound.dev` website because a live pre-release check found its TLS certificate does not match
  the hostname; switch the landing page back only after HTTPS is valid. Keep id/group `dev.buildhound`.
- Declare the two artifact-size tasks explicitly non-cacheable; Plugin Publish makes their previously
  latent `validatePlugins` cacheability-policy warnings release-blocking, while caching would retain
  local artifact telemetry and could replay stale output after a caught probe failure.
- Publish Shadow's JAR so the three unpublished runtime projects (`commons`, `report`, and
  `internal-adapters`) and serialization runtime resolve from one Portal artifact. Preserve service
  descriptors and exclude Kotlin stdlib classes: the supported Gradle floor supplies embedded Kotlin
  2.0 (architecture §2 rule 10). Keep serialization in its public package because the addon SPI
  exposes `JsonElement`; review proved blanket relocation rewrites the interface descriptor and makes
  separately compiled contributors fail with `AbstractMethodError`. Leave optional Kotlin module
  indexes out of the bundled JAR: the floor-compatible Shadow line parses metadata only through Kotlin
  2.3 while this build emits 2.4 metadata, and the plugin uses no Kotlin reflection.
  Feed Shadow a dedicated configuration derived only from `implementation`: Gradle 8.14's default
  `runtimeClasspath` includes Gradle's own runtime files. Replace internal-adapters' obsolete
  `java-gradle-plugin` application (it has declared no plugin since plan 074) with explicit
  compile-only/test-only `gradleApi()` so its runtime variant cannot reintroduce those files. Plugin
  Publish classifies the ordinary JAR as `main`, leaving Shadow's JAR unclassified/primary.
- Resolve the build version from `-Pbuildhound.version`, then `BUILDHOUND_VERSION`, with the existing
  `0.1.0-SNAPSHOT` fallback for development. The deploy workflow derives a validated release version
  from a `vX.Y.Z` GitHub release tag or manual input.
- Split deployment into a credential-free validation job and a protected `gradle-plugin-portal`
  Environment publish job. Pin every release action by full commit, require the event commit to be on
  the default branch, reject non-default manual refs, and queue every immutable release. External
  Environment setup must require non-self review plus selected protected branch/tag rules. Only the
  upload step receives `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` from same-named environment
  secrets; validation and tests receive neither. Document the matching `gradle.publish.*` keys in
  user-home `~/.gradle/gradle.properties`; no credential is committed or passed on the command line.
- Pin the Wrapper distribution to Gradle's official SHA-256 and check in strict dependency-verification
  metadata so the release build authenticates executable plugins and redistributed runtime artifacts.

## Test strategy

- Run plugin `check`, build the shadow JAR, and run `publishPlugins --validate-only` with a release
  version and no credentials.
- Inspect the generated publication/POM and JAR: marker metadata, internal module classes, report
  resources, and service descriptor are present; project dependencies and Kotlin stdlib classes are
  not leaked as unresolved/runtime duplicates.
- Resolve and apply the generated plugin marker from a temporary Maven repository in TestKit, with no
  included build or `withPluginClasspath()`, run a real task/report finalization, and load the published
  core alongside the separately compiled test-sharding contributor to guard the public SPI descriptor.
- Parse/lint the GitHub workflow locally, enforce actionlint in CI, and run the full repository build
  with Configuration Cache enabled.

## Risks

Shading can drop service resources, break a public type boundary, or accidentally bundle a newer Kotlin
runtime; artifact and separately compiled-addon tests guard those cases. Release versions are immutable
on the Portal, so the workflow validates SemVer and uses a non-cancelling, non-dropping queue. Both Portal
values are secrets (never GitHub configuration variables), and the protected environment must require
approval before they are exposed. The first Portal publication still needs Gradle's manual
namespace/content review.

## Exit criteria

`dev.buildhound` validates for a non-SNAPSHOT release, its publication is self-contained and metadata
complete, local credential/setup commands are documented, the deploy workflow exposes secrets only to
the upload step, and the build/review suites are green.
