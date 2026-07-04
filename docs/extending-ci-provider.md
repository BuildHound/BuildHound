# Extending BuildHound: a new CI provider

A **provider** teaches the *plugin* to recognize a CI system from its environment variables and stamp a
normalized `ci` block onto the payload — `ci.provider`, `ci.runId`, `ci.buildUrl`, branch/commit/PR,
and so on. Every build then carries which pipeline produced it, and the server can later enrich it (see
[`extending-ci-connector.md`](extending-ci-connector.md)).

Most CI systems need **no code at all** — the generic fallback and 11 built-ins already cover the
common ones. Reach for a custom provider only for a CI not in the built-in list whose env you cannot
remap.

Built-ins (`buildhound-commons/.../ci/CiEnvironmentProviders.kt`): `azure-devops`, `github-actions`,
`gitlab`, `jenkins`, `teamcity`, `circleci`, `bamboo`, `travis`, `bitrise`, `gocd`, `buildkite`.

## Option 1 — no code (the generic provider)

The `GenericCiEnvironmentProvider` maps `BUILDHOUND_CI_*` env vars with zero code:

```sh
export BUILDHOUND_CI_PROVIDER="my-ci"
export BUILDHOUND_CI_RUN_ID="$MY_CI_RUN_ID"          # correlation key for a future connector
export BUILDHOUND_CI_BUILD_URL="$MY_CI_RUN_PAGE_URL" # must be http(s) — used as a link + connector input
export BUILDHOUND_CI_BRANCH="$MY_CI_BRANCH"
# …commit sha, PR id, target branch, pipeline id/name, agent name — see the SPI
```

A bare `CI` variable (set by most systems) already yields provider `"generic"` with no fields. A falsy
`BUILDHOUND_CI` (`false`/`0`) is the kill switch: it forces not-CI.

## Option 2 — a `CiEnvironmentProvider` SPI implementation

Implement the SPI (`buildhound-commons/.../ci/CiEnvironmentProvider.kt`) and register it via
`ServiceLoader` on the settings classpath. Discovery order: built-ins → your `ServiceLoader`
implementations → generic. First non-null `detect` wins.

```kotlin
interface CiEnvironmentProvider {
    val id: String                                   // stable, e.g. "my-ci"
    fun detect(env: Map<String, String>): CiContext? // null when env is not this provider's
}
```

```kotlin
class MyCiEnvironmentProvider : CiEnvironmentProvider {
    override val id = "my-ci"
    override fun detect(env: Map<String, String>): CiContext? {
        if (env["MY_CI"].isNullOrEmpty()) return null   // a provider-unique detection marker
        return CiContext(
            provider = id,
            runId = env["MY_CI_RUN_ID"],                 // the connector's correlation key
            buildUrl = env["MY_CI_RUN_URL"]?.takeIf { it.isHttpUrl() },
            branch = env["MY_CI_BRANCH"],
            commitSha = env["MY_CI_SHA"],
            // pullRequestId, targetBranch, pipelineId/Name, jobId, stageId, agentName, attributes…
        )
    }
}
```

Rules that keep a provider safe and useful:

- **Detection marker must be provider-unique** — never key off the bare `CI` variable (that is the
  generic fallback's job).
- **`buildUrl` must be a real `http(s)` origin** — gate it with `isHttpUrl()`. It ends up as a
  hyperlink in the HTML artifact and dashboard *and* as the connector's outbound correlation input, so
  a `javascript:`-scheme or attacker-shaped value must never survive into a payload.
- **`runId` is the correlation key** — a backend connector reads it (plus `buildUrl`) to pull the
  provider's timeline. Set it to the provider's stable run/pipeline id.
- **Never capture secrets or command lines** — e.g. Buildkite deliberately drops `BUILDKITE_COMMAND`
  (a shell line — scrub risk). Only map identifiers, refs, and URLs.
- **`detect` must not throw** — detection is wrapped in `runCatching`, but return `null` on anything
  unexpected rather than relying on it.

A new provider is ~15–30 lines. Add a unit test (mirror
`buildhound-commons/.../ci/CiEnvironmentProvidersTest.kt`): the positive detect, a negative (marker
absent → null), and any non-trivial field derivation (ref stripping, PR-vs-branch, URL gating).
