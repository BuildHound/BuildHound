# Plan 041 — GitHub Actions + GitLab CI connectors, composite action, provider docs

**Status: planned — roadmap phase 4** · 2026-07-03

## 1. Source

- [build-telemetry-roadmap.md](../build-telemetry-roadmap.md) Phase 4, item 4: "GitHub
  Actions + GitLab CI connectors; composite action / CI includes; public 'write your own
  provider/connector' docs." Phase-4 exit: "an outside team can … connect an unsupported
  CI via the SPI."
- [build-telemetry-spec.md](../build-telemetry-spec.md) §5 "CI connector SPI (backend
  side)": the `CiConnector` interface, `CiRun`/`CiSpan`/`queuedMs` normalized tree, "GitHub
  Actions / GitLab CI connectors are v1.x fast-follows implementing the same interface";
  §3.3 (plugin-side `CiEnvironmentProvider` SPI, "add your CI in 30 lines"); §7 "Equivalent
  GitHub Actions composite / GitLab include follow the connector order."
- [architecture.md](../architecture.md) §5 (server: `BuildStore`/`CiSpanStore` boundary,
  multi-tenancy, `buildHoundModule` route testability, instance-local enrichment queue),
  §6 (tokens env-only/hashed, SSRF host allowlist), §8 (`CiEnvironmentProvider` is a
  documented public contract), §2 rule 8.
- Research: [plugin-ecosystem-gap-analysis.md §4.4](../research/plugin-ecosystem-gap-analysis.md)
  (GitLab env mapping origin), §6 (server connector namespace/registration discipline),
  §4.5 (URL redaction, host-gating).

## 2. Scope

**In:**

- `GitHubActionsConnector` and `GitLabConnector` — two new server-side `CiConnector`
  implementations plugged into **the framework plan 028 builds** (`CiConnector`,
  `ConnectorRegistry`, `NoopConnector`, `ConnectorHttpClient`, `ConnectorConfigStore`,
  `CiSpanStore`, `EnrichmentQueue`, `GET /v1/builds/{id}/ci-run`, dashboard span-tree). No
  new interface — this plan only adds instances, so the same span tree / queue-time /
  Gradle-share views render for all three providers.
- **GHA**: pull the Actions REST API `GET /repos/{owner}/{repo}/actions/runs/{run_id}`
  (+ `?attempt_number=N` when `runAttempt > 1`, from plan 027) for the run envelope, and
  `.../runs/{run_id}/jobs` (or `/attempts/{N}/jobs`) for jobs+steps → `CiRun` tree
  (JOB→STEP; GHA has no stage layer), `queuedMs` from `run_started_at − created_at`.
  **Run-attempt correlation** (plan 027): the connector reads the attempt number so a
  re-run is fetched and stored distinctly rather than colliding with attempt 1.
- **GitLab**: pull `GET /projects/{id}/pipelines/{pipeline_id}` + `.../pipelines/{id}/jobs`
  (jobs carry `stage`, `queued_duration`, `started_at`, `finished_at`, `status`) →
  `CiRun` tree grouped STAGE→JOB (GitLab reports no per-step timings), `queuedMs` from the
  pipeline's `queued_duration`/first-job queue.
- A **composite GitHub Action** (`buildhound-ci-assets/github/action.yml`) and a **GitLab
  CI include template** (`buildhound-ci-assets/gitlab/buildhound-gradle.gitlab-ci.yml`),
  each mirroring the existing Azure steps template: inject `BUILDHOUND_TOKEN`, run Gradle,
  publish the HTML artifact, optional verdict-gate step. Secrets by reference only.
- Public docs: `docs/extending-ci-provider.md` ("write your own `CiEnvironmentProvider`",
  plugin-side SPI) and `docs/extending-ci-connector.md` ("write your own `CiConnector`",
  server-side), plus a doc index row. These are the phase-4-exit deliverable.
- Per-connector credential + host-allowlist config knobs in `deploy/compose.yaml`;
  architecture decision-log row noting the two new connectors and their outbound posture.

**Out (owned elsewhere):**

- The **connector framework itself** (`CiConnector` SPI, registry, HTTP client, span
  store + the `__ci_spans` span-store migration, enrichment queue, `ci-run` query route, dashboard
  span tree) — [plan 028](028-azure-devops-connector.md). Hard prerequisite; this plan is
  blocked on it and adds no new framework surface.
- **GitLab/GHA env-provider breadth, `runAttempt` field, source/PR links** on the plugin
  side — [plan 027](027-ci-env-breadth.md). This plan consumes `ci.provider ==
  "github-actions"|"gitlab"`, `ci.runId`, `ci.buildUrl`, and (for GHA) the `runAttempt`
  attribute those plans populate; it makes **no** `buildhound-commons`/plugin/schema change.
- **Lost-build / INTERRUPTED** via connector expected-build check —
  [plan 033](033-lost-build-accounting.md).
- **OSS self-host / API / MCP / retention docs** — [plan 042](042-oss-launch-hardening.md);
  this plan's two docs are the extension-point guides only.
- OAuth/GitHub-App flows: v1 auth is a **token via env/config only** (PAT / project-access
  token), same posture as plan 028's Azure PAT.

## 3. Design

**Modules touched:** `buildhound-server` (two connector classes + registration) and
`buildhound-ci-assets` (composite action + GitLab include) and `docs/` (two guides). **No
`buildhound-commons`, no plugin, no schema-v1 change** — golden files are untouched; the
connectors read correlation keys already in the ingested payload and write to plan 028's
`ci_runs` table.

**Correlation keys (verified in source).** GHA today: `ci.provider == "github-actions"`,
`ci.runId == GITHUB_RUN_ID`, `ci.buildUrl == {server}/{owner/repo}/actions/runs/{runId}`
([CiEnvironmentProviders.kt:47-78](../../buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/ci/CiEnvironmentProviders.kt));
`owner/repo` derivable from `buildUrl`, API host `api.github.com` or GHES `{server}/api/v3`).
The run-attempt number is not stored today — [plan 027](027-ci-env-breadth.md) adds
`runAttempt` to `ci.attributes` + an `/attempts/N` `buildUrl` suffix; this connector reads
that attribute (default 1) so re-runs correlate. GitLab (after plan 027 adds the provider):
`ci.provider == "gitlab"`, pipeline id in `ci.runId` (`CI_PIPELINE_ID`), `ci.buildUrl ==
CI_JOB_URL`, `ci.attributes.pipelineUrl == CI_PIPELINE_URL` (research §4.4). Project path +
instance host are parsed from `CI_PIPELINE_URL`/`CI_JOB_URL`
(`{host}/{group/project}/-/pipelines/{id}`) with a `ConnectorConfig` override — never guessed
from an attacker-controlled field for the outbound host (host allowlist, plan 028's SSRF rule).

**Reused framework types (plan 028, no changes):** `CiConnector`, `Capability`
(`TIMELINE_PULL`/`WEBHOOK`/`DEEP_LINKS`), `CiRunRef`, `CiRun`, `CiSpan`
(`kind`/`name`/`startMs?`/`finishMs?`/`result`/`workerName?`/`parentId?`/`id`), `SpanResult`,
`CiEvent`, `ConnectorConfig`/`Credential`, `ConnectorHttpClient` (timeout-bounded, 429/5xx
backoff, `MockEngine`-injectable), `ConnectorRegistry` (keyed by provider), `EnrichmentQueue`
(bounded single worker), `CiSpanStore` (idempotent `saveRun` on `(project_id, build_id)`).

**`GitHubActionsConnector`** (`id = "github-actions"`, caps `{TIMELINE_PULL, DEEP_LINKS}`).
`fetchRun`: parse `owner/repo` + attempt; GET the run (`created_at`/`run_started_at`/`status`/
`conclusion`) and the attempt's `jobs` (`started_at`/`completed_at`/`conclusion`/`runner_name`
+ `steps[]`) → a JOB→STEP `CiRun` (GHA has no stage layer); `queuedMs = run_started_at −
created_at`; `conclusion` (`success|failure|cancelled|skipped|null`) → `SpanResult`;
`runner_name` → `workerName` only (server-side, plan 028's PII rule). `buildLink` echoes
`ci.buildUrl`. No `parseWebhook`/`WEBHOOK` cap in v1 — enrichment is poll-only (framework
already supports it); `workflow_run` webhook receipt is a follow-up.

**`GitLabConnector`** (`id = "gitlab"`, caps `{TIMELINE_PULL, DEEP_LINKS}`). `fetchRun`: parse
host + project + pipeline id; GET the pipeline (`created_at`/`started_at`/`finished_at`/
`status`/`queued_duration`) and its `jobs` (`stage`/`name`/`started_at`/`finished_at`/`status`/
`queued_duration`/`runner.description`) → a STAGE→JOB `CiRun` (stages synthesized from distinct
`job.stage` in pipeline order); `status` → `SpanResult`; `queued_duration` (s→ms) → `queuedMs`;
`runner.description` → `workerName`. `buildLink` echoes `ci.buildUrl`. No webhook in v1.

**Registration + config.** Both are added to the `ConnectorRegistry` alongside
`AzureDevOpsConnector` + `NoopConnector`; `EnrichmentQueue` keys on `ci.provider`, so a
configured `github-actions`/`gitlab` build auto-enriches and an unconfigured one →
`UNCONFIGURED` (renders fully, spec §5). `ConnectorConfigStore` gains two env credentials read
like the Azure PAT — `BUILDHOUND_CONNECTOR_GITHUB_TOKEN` / `…_GITLAB_TOKEN` — each with a
per-connector **host allowlist** (`…_GITHUB_HOSTS` / `…_GITLAB_HOSTS`, default
`api.github.com` / `gitlab.com`) so GHES/self-managed GitLab are opt-in and a payload can't
redirect the fetch to a new host. Tokens env-only, never logged, never in span rows (arch
§4/§6). `GET /v1/builds/{id}/ci-run`, queue-time, Gradle-share, and the dashboard span tree
are provider-agnostic and unchanged.

**CI assets** (mirroring the Azure steps template's structure + usage-comment header,
secrets by reference only). `github/action.yml`: a **composite** action
(`runs.using: "composite"`) with inputs `gradle-tasks` (default `build`), `server-url`,
`token`; steps run `./gradlew ${{ inputs.gradle-tasks }}` with `BUILDHOUND_TOKEN`/
`BUILDHOUND_SERVER_URL` env, `actions/upload-artifact` for the HTML artifact, and an optional
verdict-gate step (commented until plan 025's endpoint). `gitlab/buildhound-gradle.gitlab-ci.yml`:
an `include`-able template (a `.buildhound` hidden job + a `buildhound_gradle` job
`extends: .buildhound`) running Gradle with `BUILDHOUND_TOKEN` from a masked CI/CD variable,
publishing the HTML artifact via `artifacts:paths`, optional verdict gate.

**Docs (the phase-4-exit deliverable).** `docs/extending-ci-provider.md` — the ~30-line
plugin-side `CiEnvironmentProvider` recipe: implement `id` + `detect(env): CiContext?`,
register via `META-INF/services` on the settings classpath, discovery order (built-ins →
`ServiceLoader` extras → generic, first-non-null wins,
[CiValueSource.kt:48-49](../../buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/CiValueSource.kt)),
the no-`javascript:`/no-PII rules, and how `runId` becomes the connector correlation key.
`docs/extending-ci-connector.md` — the server-side `CiConnector` recipe: implement
`fetchRun`/`buildLink` (+ optional `parseWebhook`), declare `capabilities`, add to the
`ConnectorRegistry`, wire an **env-only** credential + host allowlist in `ConnectorConfigStore`,
and the never-fail-ingest degradation contract — with `GitHubActionsConnector` as the example.

## 4. Implementation steps

1. **`GitHubActionsConnector`** in `dev.buildhound.server.connector`: `owner/repo`+attempt
   parse from `CiRunRef`/`ci.buildUrl`/`ci.attributes.runAttempt`; run + jobs GETs via the
   shared `ConnectorHttpClient`; JOB→STEP `CiRun` mapping; `queuedMs`; `conclusion`→`SpanResult`;
   `runner_name`→`workerName`; `buildLink` echoes `ci.buildUrl`; reject non-http(s)/off-allowlist
   hosts before dialing.
2. **`GitLabConnector`** in the same package: host+project+pipeline parse; pipeline + jobs
   GETs; STAGE→JOB `CiRun` (stages synthesized from `job.stage`); `queued_duration`→`queuedMs`;
   `status`→`SpanResult`; `runner.description`→`workerName`; same host-allowlist guard.
3. **Register** both in `ConnectorRegistry` (plan 028) so `byProvider("github-actions")` /
   `byProvider("gitlab")` resolve; `EnrichmentQueue` needs no change (keyed on provider).
4. **`ConnectorConfigStore`** (plan 028): add `BUILDHOUND_CONNECTOR_GITHUB_TOKEN` /
   `…_GITLAB_TOKEN` + `…_GITHUB_HOSTS` / `…_GITLAB_HOSTS` allowlists (defaults
   `api.github.com` / `gitlab.com`); return `null` (→ `UNCONFIGURED`) when absent.
5. **`buildhound-ci-assets/github/action.yml`** — composite action mirroring the Azure
   template (inputs, Gradle run, HTML-artifact upload, optional verdict gate, secret-by-ref).
6. **`buildhound-ci-assets/gitlab/buildhound-gradle.gitlab-ci.yml`** — includable job template
   with the same structure and a usage-comment header.
7. **`buildhound-ci-assets/README.md`** — add the two new assets to the table; note the shared
   env-mapping contract with the plugin SPI.
8. **`docs/extending-ci-provider.md`** — plugin-side `CiEnvironmentProvider` recipe.
9. **`docs/extending-ci-connector.md`** — server-side `CiConnector` recipe (GHA worked example).
10. **`deploy/compose.yaml`** — add the two connector token + host-allowlist env vars
    (commented, dev-only note; no default secret value).
11. **Architecture decision-log row** (§7): the GitHub Actions + GitLab connectors added on
    plan 028's framework, poll-only (no webhook v1), env-only tokens + per-connector host
    allowlist, `workerName` kept server-side. Note the two extension-point guides satisfy the
    phase-4 "connect an unsupported CI via the SPI" exit criterion.
12. Update this plan file in the same PR if implementation diverges, per the workflow.

## 5. Test strategy

- **Unit (`connector`, Ktor `MockEngine`):** `GitHubActionsConnectorTest` — a captured
  `runs/{id}` + `jobs` JSON fixture → correct JOB→STEP `CiRun`, `queuedMs = run_started_at −
  created_at`, `conclusion`→`SpanResult` (incl. `null`→in-progress), attempt>1 hits
  `/attempts/N/jobs`, token sent as the auth header, off-allowlist/GHES-not-allowed host
  rejected, non-http base rejected. `GitLabConnectorTest` — pipeline + jobs fixture → STAGE→JOB
  tree with stages synthesized in order, `queued_duration`→`queuedMs`, `status`→`SpanResult`,
  `runner.description`→`workerName`, host-allowlist guard.
- **Unit:** `ConnectorRegistryTest` extended — `byProvider` resolves `github-actions`/`gitlab`,
  Noop fallback for an unknown provider.
- **Failure-injection (phase guardrail):** each connector's `fetchRun` throwing / timing out /
  returning null / missing credential yields the right `status` (`FAILED`/`UNCONFIGURED`) and
  **never** propagates out of the ingest path (reuse plan 028's `EnrichmentQueueTest` harness
  with the new providers); a still-in-progress GHA run retries then gives up without erroring.
- **Route (`testApplication`, no socket):** ingest a `provider=github-actions` payload and a
  `provider=gitlab` payload, drain the queue, `GET /v1/builds/{id}/ci-run` returns the span
  tree + queue time + Gradle share for each; unconfigured connector → `UNCONFIGURED` and the
  build still renders; cross-tenant read is a 404, not a leak.
- **CI-assets lint:** `action.yml` and the GitLab include are parsed as YAML in a test (or a
  `yamllint`/`actionlint` CI check) to catch syntax drift; both assert the token is referenced,
  never a literal.
- **Golden files:** **none** — no schema-v1 change; commons contract test untouched. New
  connector JSON fixtures live under server test resources only.
- **Docs:** a link-check over the two new guides (relative links resolve; code snippets name
  real types) — reuse whatever doc CI exists, else a smoke test that the files exist and the
  index references them.

## 6. Risks

- **SSRF / outbound host control (security).** `ci.buildUrl` is ingested (attacker-influenced)
  data; deriving the GitHub/GitLab API host from it could point the server's authenticated
  fetch at an arbitrary URL. Mitigation: per-connector **host allowlist** (default
  `api.github.com` / `gitlab.com`; GHES/self-managed opt-in via config), https-only scheme, and
  the token sent only to allowlisted hosts — a payload selects *which* configured host, never a
  new one. Same posture and decision-log framing as plan 028's Azure allowlist.
- **Credential handling (security).** GitHub PAT / GitLab token via env only, never in
  code/DSL/logs/image layers, never echoed in warnings, not stored in span rows (arch §4/§6);
  compose defaults are commented and dev-only.
- **Timeline data = potential PII (privacy).** GHA `runner_name` and GitLab
  `runner.description` can name self-hosted machines; stored in `workerName` server-side only,
  not surfaced raw in cross-tenant/exported views (plan 028's `agentName`-parity rule). No
  absolute paths or log text are pulled from the API responses.
- **Politeness / rate (reliability).** Reuse the shared client's 429/5xx backoff and bounded
  poll budget; GitHub's REST rate limit and GitLab's are respected via `Retry-After` handling
  in `ConnectorHttpClient`; API versions pinned (GHA `X-GitHub-Api-Version` header, GitLab v4
  path) so a silent shape change surfaces as a parse failure → `status=FAILED`, not a crash.
- **CC hazards / isolated projects:** none — server-only and CI-assets/docs; no Gradle plugin
  code, no configuration-cache or isolated-projects surface.
- **Schema compatibility:** additive by construction — no `buildhound-commons`/plugin change,
  reuses plan 028's `ci_runs` table, adds no new route. A build with no connector configured
  renders exactly as today (spec §5).
- **Dependency ordering:** hard-blocked on plan 028 (framework) landing first, and depends on
  plan 027 for the GitLab env provider + GHA `runAttempt` attribute; if 027 slips, the GitLab
  connector is unreachable (no `provider=="gitlab"` payloads) and GHA falls back to attempt 1 —
  degraded, not broken. Noted so review sequences the merges.

## 7. Exit criteria

- Ingesting a `github-actions` build (with `BUILDHOUND_CONNECTOR_GITHUB_TOKEN` + allowlisted
  host) produces, after enrichment, a `ci_runs` row; `GET /v1/builds/{id}/ci-run` returns the
  JOB→STEP span tree, a non-null `queuedMs`, and a `gradleSharePct` in `[0,1]`; a re-run
  (attempt 2) is fetched from the attempt endpoint and stored distinctly.
- Ingesting a `gitlab` build similarly yields a STAGE→JOB span tree with per-stage grouping and
  a queue time; the dashboard span tree, queue-time, and Gradle-share chips render for both
  providers using the unchanged plan-028 UI.
- A build with **no** connector configured returns `status=UNCONFIGURED` and still renders
  fully; a `fetchRun` that throws/times out/hits an off-allowlist host never fails ingest and is
  recorded `FAILED`/`UNCONFIGURED` (failure-injection test green).
- `buildhound-ci-assets/github/action.yml` and `gitlab/buildhound-gradle.gitlab-ci.yml` exist,
  parse as valid YAML, run Gradle with the token by reference, and publish the HTML artifact —
  mirroring the Azure template; the ci-assets README lists them.
- `docs/extending-ci-provider.md` and `docs/extending-ci-connector.md` exist and are linked
  from the docs index; an outside reader can add a plugin-side provider and a server-side
  connector from them alone (phase-4 exit: "connect an unsupported CI via the SPI").
- `./gradlew :buildhound-server:test` green including the two `MockEngine` connector tests,
  the extended registry/route/failure-injection tests, and the CI-assets YAML lint; the commons
  golden-file contract test is unchanged (no schema edit); `docs/architecture.md` decision log
  carries the two-connectors row.
