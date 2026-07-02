# 002 — CI environment providers: Azure DevOps + GitHub Actions + detection order

## Source

Roadmap Phase 1: "CI env SPI with Azure + GitHub + generic providers". Spec §3.3
(provider SPI, discovery order, Azure env mapping).

## Scope

**In:**

- `AzureDevOpsCiEnvironmentProvider` (id `azure-devops`) in `buildhound-commons`
  `commonMain`: `TF_BUILD` → detected; `BUILD_BUILDID` → runId, `SYSTEM_DEFINITIONID` /
  `SYSTEM_DEFINITIONNAME` → pipelineId/Name, `SYSTEM_JOBID` → jobId, `SYSTEM_STAGENAME` →
  stageId, `BUILD_SOURCEBRANCH` (ref-stripped) → branch, `BUILD_SOURCEVERSION` → sha,
  `SYSTEM_PULLREQUEST_PULLREQUESTID` → pullRequestId,
  `SYSTEM_PULLREQUEST_TARGETBRANCH` (ref-stripped) → targetBranch, `AGENT_NAME` →
  agentName, buildUrl composed from `SYSTEM_COLLECTIONURI` + `SYSTEM_TEAMPROJECT` +
  buildId.
- `GitHubActionsCiEnvironmentProvider` (id `github-actions`): `GITHUB_ACTIONS=true` →
  detected; `GITHUB_RUN_ID` → runId, `GITHUB_WORKFLOW` → pipelineName, `GITHUB_JOB` →
  jobId, branch from `GITHUB_HEAD_REF` (PRs) else `GITHUB_REF_NAME`, `GITHUB_SHA` → sha,
  PR id parsed from `refs/pull/<n>/merge`, `GITHUB_BASE_REF` → targetBranch,
  `RUNNER_NAME` → agentName, buildUrl `<server>/<repo>/actions/runs/<runId>`.
- `CiEnvironment.detect(env, extraProviders)` in `commonMain`: built-ins in fixed order
  (Azure, GitHub), then caller-supplied extras (the plugin will pass `ServiceLoader`
  results — JVM-only, so it stays out of commons), then `GenericCiEnvironmentProvider`.
  First non-null wins. Explicit `BUILDHOUND_CI_PROVIDER` does **not** shadow built-ins
  (generic stays last), matching spec order.

**Out (later chunks):** plugin wiring + `ServiceLoader` discovery (lands with the
EnvironmentCollector chunk, when `CiInfo` enters the payload); GitLab/Bitrise/Jenkins/
CircleCI built-ins (v1 backlog, spec lists them but Phase 1 names Azure + GitHub +
generic); DSL override `ci.provider`.

## Divergences from the first draft (review findings, fixed pre-merge)

- Azure `pipelineName`: prefer documented `BUILD_DEFINITIONNAME`, fall back to
  `SYSTEM_DEFINITIONNAME` (spec §3.3 corrected in the same PR).
- Azure PR builds: logical branch from `SYSTEM_PULLREQUEST_SOURCEBRANCH` (ref-stripped),
  so `refs/pull/N/merge` never surfaces as the branch; `pullRequestId` falls back to
  `SYSTEM_PULLREQUEST_PULLREQUESTNUMBER` (set for GitHub-hosted repos built on Azure).
- GitHub `pipelineId`: mapped from `GITHUB_WORKFLOW_REF` with the unstable `@<ref>`
  suffix stripped (not in the first plan draft).
- Composed build URLs: base URL must be http(s) (an env-controlled `javascript:` scheme
  must never reach a payload) and path/query segments are percent-encoded (Azure project
  names contain spaces).

## Design

Pure functions over `Map<String, String>` — no Gradle/JVM APIs, KMP-common, ~30 lines
per provider (the advertised extension-point budget). New file
`CiEnvironmentProviders.kt` + `CiEnvironment` detector object in
`dev.buildhound.commons.ci`.

## Test strategy

`commonTest` unit tests: realistic env fixtures per provider (detection, full field
mapping, ref-name stripping, PR vs push branch selection, URL composition), non-CI env
returns null everywhere, detection order (Azure beats generic when both match; extras
beat generic; generic last).

## Risks

- Env mappings must match real agents — values taken from Azure/GitHub documented
  variables (spec §3.3 pins the Azure list).
- No secrets: providers only read the named non-secret variables, never dump `env`.
  `attributes` stays empty in this chunk.
- `agentName` (`AGENT_NAME`/`RUNNER_NAME`) can be a personal hostname on self-hosted
  agents. In-spec (§3.3), but the payload-wiring chunk must decide how it behaves under
  `identity { pseudonymize/strict }` and record that decision.

## Exit criteria

`./gradlew :buildhound-commons:build` green; providers + detector covered by unit
tests; no schema change (CiContext unchanged).
