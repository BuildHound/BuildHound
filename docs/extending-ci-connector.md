# Extending BuildHound: a new server-side CI connector

A **connector** teaches the *server* to pull a CI provider's build timeline and normalize it into a
`CiRun` tree (stages → jobs → steps), so a build's dashboard shows queue time, per-stage timing, and
the "Gradle share of pipeline". It is entirely server-side — no plugin, schema, or payload change.

This is distinct from a **provider** ([`extending-ci-provider.md`](extending-ci-provider.md)), which
teaches the *plugin* to recognize a CI system and stamp `ci.provider` / `ci.buildUrl` onto the
payload. A connector consumes what a provider stamped: it keys off `ci.provider` and parses the
outbound correlation from `ci.buildUrl`. Ship a provider first; add a connector when you also want the
provider's own timeline.

Shipped connectors: `azure-devops` (plan 028), `github-actions`, `gitlab` (plan 041). Use them as
worked examples — `buildhound-server/src/main/kotlin/dev/buildhound/server/connector/`.

## The SPI

Implement `CiConnector` (`connector/Connector.kt`):

```kotlin
interface CiConnector {
    val id: String                       // matches BuildPayload.ci.provider, e.g. "github-actions"
    val capabilities: Set<Capability>    // TIMELINE_PULL, WEBHOOK, DEEP_LINKS

    suspend fun fetchRun(ref: CiRunRef, config: ConnectorConfig): CiRun?
    fun parseWebhook(headers: Map<String, String>, body: String, config: ConnectorConfig): CiEvent?
    fun buildLink(ref: CiRunRef, config: ConnectorConfig): String?
    fun refFrom(provider: String, runId: String, buildUrl: String?): CiRunRef = CiRunRef(provider, runId)
}
```

- **`capabilities`** — declare only what you implement. Poll-only connectors (GitHub, GitLab) declare
  `{TIMELINE_PULL, DEEP_LINKS}` and make `parseWebhook` a no-op. `TIMELINE_PULL` is what gates
  enrichment: without it the run is recorded `UNCONFIGURED`.
- **`refFrom`** — parse the *correlation* fields (owner/repo, project path, re-run attempt) out of the
  ingested `ci.buildUrl`. **Never** derive the outbound *host* from `buildUrl` — that is attacker
  data. The host comes only from `ConnectorConfig` (see SSRF below).
- **`fetchRun`** — do the REST calls and map to `CiRun`. Return `null` for *any* failure (a connector
  must never fail ingest; the enricher records `FAILED`/`PENDING`/`UNCONFIGURED` and the build still
  renders). Completion is read from `CiRun.finishedAt` — leave it null while the run is in progress and
  the enricher keeps polling with backoff.

### The `CiRun` model

```kotlin
data class CiRun(val spans: List<CiSpan>, val queuedMs: Long?, val startedAt: Long?, val finishedAt: Long?)
data class CiSpan(val id: String, val kind: SpanKind /* STAGE|JOB|STEP */, val name: String,
                  val startMs: Long?, val finishMs: Long?, val result: SpanResult?,
                  val workerName: String?, val parentId: String?)
```

Map the provider's tree onto STAGE → JOB → STEP by `parentId`. Not every provider has all three
levels: GitHub is JOB → STEP; GitLab has no per-step timing, so stages are *synthesized* from each
job's `stage` field (see `GitLabConnector.parseJobs`). Map the provider's status to `SpanResult`;
leave `result` null while a span is still running.

## Non-negotiable security posture (architecture §5/§6)

Every connector shares one SSRF guard, `isAllowedHost` in `connector/ConnectorNet.kt`:

1. **https-only** — reject any non-`https` base URL.
2. **host allowlist** — the outbound host must be in `ConnectorConfig.allowedHosts`. An ingested build
   URL can only *select which configured org* is dialled; it can never introduce a new host.
3. **no redirects** — the shared `ConnectorHttpClient` sets `followRedirects = false` so a 3xx cannot
   carry the `Authorization` header to an unvalidated host.
4. **tokens env-only** — the credential is resolved from environment variables only (never code, DSL,
   logs, or image layers) and is sent as a header, never logged, never written to the span jsonb.

Call `isAllowedHost(base, config.allowedHosts)` before the first outbound request and short-circuit to
`null` if it fails — verify with a test that `requestHistory` is empty on rejection.

## Wiring a new connector

1. **Config** — add a case to `EnvConnectorConfigStore.forProject` reading
   `BUILDHOUND_CONNECTOR_<NAME>_TOKEN` (gates enrichment; unset ⇒ `UNCONFIGURED`), a `_HOSTS` allowlist
   (default the SaaS host), and a `_BASEURL` (default the SaaS API root). Return `null` when the token
   is unset so the deployment never dials that provider.
2. **Register** — add the connector to the `ConnectorRegistry(listOf(...))` in `Application.kt`, sharing
   the one `ConnectorHttpClient`.
3. **Compose** — document the new env vars (commented) in `deploy/compose.yaml`.
4. **Tests** — a `MockEngine`-backed test (mirror `GitHubActionsConnectorTest`): timing/tree mapping,
   auth header, host-allowlist rejection (no request made), non-https rejection, no-credential, the
   "concluded-but-jobs-failed → retry" path, and `refFrom` correlation parsing.

A connector is ~150 lines plus its test. Keep it defensive: parse via `JsonElement` (tolerant of
provider schema drift), and let every unexpected shape fall through to `null`.
