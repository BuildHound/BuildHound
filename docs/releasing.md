# Releasing the Gradle plugin

BuildHound publishes `dev.buildhound` to the
[Gradle Plugin Portal](https://plugins.gradle.org/) through the `publishPlugins` task. Use a
unique, non-SNAPSHOT SemVer for every release; Portal versions cannot be overwritten.

## Local validation and publishing

Create a Plugin Portal account and API key, then store the two values in your user-level
`~/.gradle/gradle.properties` file:

```properties
gradle.publish.key=<portal-api-key>
gradle.publish.secret=<portal-api-secret>
```

Keep that file outside the repository and never commit either value. From the repository root,
replace `0.1.0` with the release version and run:

```bash
./gradlew :buildhound-gradle-plugin:check -Pbuildhound.version=0.1.0
./gradlew publishPlugins --validate-only -Pbuildhound.version=0.1.0
```

The validation command performs the Portal checks without uploading. After it succeeds, publish
the same version:

```bash
./gradlew publishPlugins -Pbuildhound.version=0.1.0
```

Do not pass Portal credentials on the command line. See Gradle's
[plugin publishing guide](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html)
for account and API-key setup.

The Wrapper distribution is integrity-pinned by `distributionSha256Sum`, and Gradle enforces the
checked-in `gradle/verification-metadata.xml` before executing plugins or resolving bundled runtime
artifacts. Generate dependency-verification metadata from an empty Gradle user home so parent POMs and
BOMs that a warm cache would skip are included:

```bash
COLD_GRADLE_HOME="$(mktemp -d)"
GRADLE_USER_HOME="$COLD_GRADLE_HOME" ./gradlew \
  :buildhound-gradle-plugin:check publishPlugins --validate-only \
  -Pbuildhound.version=0.1.0 \
  --write-verification-metadata sha256
```

Do not generate the metadata from a warm dependency cache: Gradle may reuse already-resolved parents
without recording every file a clean GitHub Actions runner downloads. Review the metadata diff, then
repeat the credential-free command with a second empty `GRADLE_USER_HOME` and without
`--write-verification-metadata` to prove strict verification works cold. Independently compare
release-critical checksums with the authoritative repository; never disable verification to make a
release pass.

The Portal metadata advertises `https://buildhound.dev`. Do not publish while that URL has an invalid
certificate or does not return a direct successful HTTPS response. `publishPlugins` enforces this for
every real local/CI upload while leaving `--validate-only` available. GitHub also checks before
validation and again after Environment approval, immediately before the secret-scoped upload step.

## Serialization classpath compatibility

The Portal artifact intentionally keeps `kotlinx.serialization.*` unrelocated because the public addon
SPI exposes `JsonElement`; relocating it would change the method descriptor and break separately compiled
addons. The trade-off is a known compatibility risk: Gradle can co-load dependencies from settings
plugins, and another plugin carrying an incompatible serialization runtime can cause linkage failures
such as `NoSuchMethodError`, `ClassNotFoundException`, or `AbstractMethodError`.

Dependency verification confirms that BuildHound's intended runtime was downloaded, and the publication
test confirms the supported BuildHound/addon boundary. Neither isolates BuildHound from versions supplied
by an arbitrary consumer plugin set. If a failure stack contains `kotlinx.serialization` classes, record
the Gradle, BuildHound, and other settings-plugin versions, then reproduce with BuildHound as the only
third-party settings plugin and add the others back one at a time. Align the conflicting plugin versions
where possible; otherwise report the minimal reproducer before publishing or recommending an upgrade.

## GitHub Actions setup

Create a GitHub Environment named `gradle-plugin-portal`. Before adding either credential, configure
all of these Environment/repository protections:

- require a release-maintainer reviewer, enable **Prevent self-review**, and disable **Allow
  administrators to bypass configured protection rules**;
- select **Selected branches and tags**, add the exact default branch as a branch rule, and add
  `*.*.*` as a tag rule (the workflow separately enforces SemVer and trusted-source ancestry);
- protect release tags matching `*.*.*` with a repository ruleset so a writer cannot move a tag to
  unreviewed code.

Only after those protections are active, add both credentials as **environment secrets** (not GitHub
configuration variables):

- `GRADLE_PUBLISH_KEY`
- `GRADLE_PUBLISH_SECRET`

These controls are required release setup, not optional hardening. The validation job reads the live
Environment configuration and fails closed if required-reviewer presence, self-review prevention,
administrator bypass, branch selection, or tag selection drifts. Reviewer identity remains a repository
administration responsibility, as does the repository tag-protection ruleset. The job also rejects manual
runs from a non-default branch and requires every release commit to be contained in the default branch,
but those in-repository checks do not replace Environment controls. The protected publish job starts only
after the credential-free check/validation job succeeds, and maps the two secrets into the environment
only for the upload step.

## Automated release behavior

Publishing a GitHub Release runs `.github/workflows/publish-gradle-plugin.yml`. Its release tag must
be SemVer, optionally prefixed with `v`, for example `v0.1.0` or `0.1.0`. The workflow removes the
optional prefix, rejects invalid SemVer and any `SNAPSHOT` version, then exports the result as
`BUILDHOUND_VERSION` for the build. The tag must point to a commit already on the default branch. A
credential-free job verifies `https://buildhound.dev`, checks the plugin, and validates the Portal
publication; only its successful, exact commit/version pair reaches the protected publish job, where
the website is checked again before upload.

For an intentional manual release, open **Actions**, select **Publish Gradle plugin**, choose **Run
workflow** on the default branch, and enter the version. Manual input follows the same version checks
and may also use the optional `v` prefix; selecting another branch is rejected. Publication runs are
serialized, queued without dropping pending releases, and an in-progress upload is never cancelled
by a newer run.

## First Portal release

The first publication of a plugin goes through the Portal's manual metadata and ownership review,
so a successful upload can remain pending for several days before `dev.buildhound` becomes visible.
Watch the email address associated with the Portal account for approval or requested changes. A
plugin ID or Maven group change triggers review again.

Every Portal version is immutable once uploaded. Never try to replace an already-published version;
make the fix, choose a new SemVer, and publish that version instead.
