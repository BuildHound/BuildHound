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

The Wrapper distribution is authenticated by `distributionSha256Sum`, and Gradle enforces the
checked-in `gradle/verification-metadata.xml` before executing plugins or resolving bundled runtime
artifacts. When a dependency changes, regenerate only the necessary verification entries, review the
metadata diff, and independently compare release-critical checksums with the authoritative repository;
never disable verification to make a release pass.

## GitHub Actions setup

Create a GitHub Environment named `gradle-plugin-portal`. Add both credentials as **environment
secrets** (not GitHub configuration variables):

- `GRADLE_PUBLISH_KEY`
- `GRADLE_PUBLISH_SECRET`

Before adding the secrets, configure all of these Environment/repository protections:

- require a release-maintainer reviewer, enable **Prevent self-review**, and disable administrator
  bypass where the repository plan supports it;
- allow deployments only from the default branch and the protected release-tag patterns used here;
- protect those release tags with a repository ruleset so a writer cannot move a tag to unreviewed
  code.

These controls are required release setup, not optional hardening. The workflow also rejects manual
runs from any non-default branch and requires every release commit to be contained in the default
branch, but those in-repository checks do not replace Environment controls. The protected publish job
starts only after the credential-free check/validation job succeeds, and maps the two secrets into
the environment only for the upload step.

## Automated release behavior

Publishing a GitHub Release runs `.github/workflows/publish-gradle-plugin.yml`. Its release tag must
be SemVer, optionally prefixed with `v`, for example `v0.1.0` or `0.1.0`. The workflow removes the
optional prefix, rejects invalid SemVer and any `SNAPSHOT` version, then exports the result as
`BUILDHOUND_VERSION` for the build. The tag must point to a commit already on the default branch. A
credential-free job checks the plugin and validates the Portal publication; only its successful,
exact commit/version pair reaches the protected publish job.

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
