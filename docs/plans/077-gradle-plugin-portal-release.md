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

- Pin the current `com.gradle.plugin-publish` and `com.gradleup.shadow` versions in the version
  catalog. Apply both only to `buildhound-gradle-plugin`.
- Complete `gradlePlugin {}` with the BuildHound website, GitHub VCS URL, and Configuration Cache
  compatibility declaration. Keep plugin id/group `dev.buildhound`.
- Publish Shadow's JAR so the three unpublished runtime projects (`commons`, `report`, and
  `internal-adapters`) and serialization runtime resolve from one Portal artifact. Preserve service
  descriptors, relocate the bundled serialization implementation, and exclude Kotlin stdlib classes:
  the supported Gradle floor supplies embedded Kotlin 2.0 (architecture §2 rule 10).
- Resolve the build version from `-Pbuildhound.version`, then `BUILDHOUND_VERSION`, with the existing
  `0.1.0-SNAPSHOT` fallback for development. The deploy workflow derives a validated release version
  from a `vX.Y.Z` GitHub release tag or manual input.
- Add a `gradle-plugin-portal` GitHub Environment job. Only its publish step receives
  `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` from same-named environment secrets; validation and
  tests receive neither. Document the matching `gradle.publish.*` keys in user-home
  `~/.gradle/gradle.properties`; no credential is committed or passed on the command line.

## Test strategy

- Run plugin `check`, build the shadow JAR, and run `publishPlugins --validate-only` with a release
  version and no credentials.
- Inspect the generated publication/POM and JAR: marker metadata, internal module classes, report
  resources, and service descriptor are present; project dependencies and Kotlin stdlib classes are
  not leaked as unresolved/runtime duplicates.
- Parse/lint the GitHub workflow and run the full repository build with Configuration Cache enabled.

## Risks

Shading can drop service resources or accidentally bundle a newer Kotlin runtime; artifact inspection
guards both. Release versions are immutable on the Portal, so the workflow validates SemVer and uses a
non-cancelling concurrency group. Both Portal values are secrets (never GitHub configuration variables),
and a protected environment can require approval before they are exposed. The first Portal publication
still needs Gradle's manual namespace/content review.

## Exit criteria

`dev.buildhound` validates for a non-SNAPSHOT release, its publication is self-contained and metadata
complete, local credential/setup commands are documented, the deploy workflow exposes secrets only to
the upload step, and the build/review suites are green.
