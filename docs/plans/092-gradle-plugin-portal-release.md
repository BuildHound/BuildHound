# 092 — Gradle Plugin Portal release

## Source

Owner request to prepare `dev.buildhound` for the Gradle Plugin Portal using local user-home
credentials and protected GitHub Actions secrets. Spec §8 defines the Portal as the distribution
target.

## Scope

**In:** publish-ready plugin metadata and versioning, a self-contained Portal artifact, local release
instructions, dependency verification, and a protected release workflow. **Out:** publishing addons,
Maven Central, artifact signing, credential creation, and changing the development version.

## Design

- Apply Plugin Publish and a Gradle-8.14-compatible Shadow plugin only to the core plugin. Publish a
  reproducible Shadow JAR containing the unpublished runtime projects and serialization runtime,
  while excluding Gradle-provided Kotlin stdlib and preserving service descriptors. Keep
  `kotlinx.serialization` unrelocated because the public addon SPI exposes `JsonElement`; document
  the resulting shared-plugin-classpath compatibility risk.
- Complete the Portal metadata with `https://buildhound.dev`, the public repository, and
  Configuration Cache compatibility. A real upload must fail if the public HTTPS URL is unhealthy;
  CI checks it before artifact assembly and again after protected-environment approval.
- Resolve the release version from `-Pbuildhound.version`, then `BUILDHOUND_VERSION`, retaining the
  SNAPSHOT fallback for development. Release and manual-dispatch inputs must normalize an optional
  `v` prefix, validate SemVer, reject snapshots, and point to a commit on the default branch.
- Split CI into a credential-free check/assembly job and a protected `gradle-plugin-portal` publish
  job. The Environment must require non-self review, disable administrator bypass, and allow only the
  default branch plus release tags. Pin actions, serialize immutable releases, and never cancel an
  upload. Rebuild after approval and compare a canonical digest of all publication files with the
  pre-approval build.
- Portal validation requires credentials, so only the final protected step receives
  `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`; it runs `publishPlugins --validate-only`, verifies
  the digest after validation and again inside the upload task, then publishes. The matching local
  keys remain only in
  `~/.gradle/gradle.properties`; repository and organization secret scopes must not define the CI
  names.
- Pin the Wrapper checksum and strict SHA-256 dependency metadata. Signer authentication remains an
  accepted supply-chain residual until release repositories support a curated trusted-key policy;
  release-critical checksums require independent corroboration.

## Test strategy

- Run the core plugin checks, assemble the complete publication twice, and prove identical canonical
  digests. Run credentialed `publishPlugins --validate-only` for a fixed release version; never upload
  during validation.
- Inspect the publication metadata and JAR for marker metadata, bundled internal classes/resources,
  service descriptors, and absence of unresolved project dependencies or Kotlin stdlib duplicates.
- Resolve the generated marker from a temporary Maven repository in TestKit, run report finalization,
  and load the published core with a separately compiled addon to guard the public SPI descriptor.
- Lint the workflow and shell scripts, then run the repository build with Configuration Cache enabled.

## Risks

Shading can lose resources, break the addon ABI, or bundle Gradle-provided Kotlin. Tests cover the
supported boundary but cannot isolate BuildHound from every incompatible dependency co-loaded by
another settings plugin. Portal versions are immutable, the first publication requires manual Portal
review, and misconfigured Environment protections could expose credentials. Verification metadata
spans the supported floor/current/test graphs; prune old entries only after the complete cold matrix.

## Exit criteria

`dev.buildhound` validates for a fixed non-SNAPSHOT version; its metadata and self-contained artifact
are correct; local and CI credential setup is documented; secrets reach only the approved final step;
pre-approval and publish digests match; and build, workflow, architecture, and security reviews pass.
