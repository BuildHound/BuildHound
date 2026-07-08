# buildhound-ci-assets

CI-facing assets that must be consumable **without a JVM** (spec §7). This directory is
deliberately not a Gradle module.

| Asset | Purpose |
|---|---|
| `azure-pipelines/buildhound-gradle-steps.yml` | Reusable Azure Pipelines steps template: token injection, Gradle build, optional `verdictGate` step polling `GET /v1/builds/{id}/verdict`, optional preventive `validateWrapper` wrapper-integrity step (plan 066) |
| `github/action.yml` | Composite GitHub Action: Gradle build with telemetry + optional `verdict-gate` (`::warning::`/`::error::`), plan 041 |
| `gitlab/buildhound-gradle.gitlab-ci.yml` | Includable GitLab CI template (`.buildhound-gradle` hidden job to `extends`): Gradle build + optional verdict gate, plan 041 |
| `bin/buildhound-metric` | Metric CLI for non-Gradle steps → `POST /v1/metrics` (Datadog tag/measure model) |
| `test/metric-cli-test.sh` | Stubbed-`curl` harness for the metric CLI (`sh buildhound-ci-assets/test/metric-cli-test.sh`); `shellcheck`-clean |
| `test/wrapper-integrity-test.sh` | Harness mirroring the Azure template's `validateWrapper` grep/sha256sum logic (`sh buildhound-ci-assets/test/wrapper-integrity-test.sh`); `shellcheck`-clean (plan 066) |
| `profiler-scenarios/buildhound.scenarios` | gradle-profiler scenarios for benchmark mode: `clean`/`no_op`/`incremental_non_abi`/`cc_hit` (plan 030) |
| `profiler-pipeline/{github,azure}-nightly-benchmark.yml` | scheduled gradle-profiler pipeline → `mode=benchmark` tagged series (plan 030) |
| `profiler-pipeline/isolation-modes.md` | the cache-isolation (`BUILDHOUND_BENCHMARK_ISOLATION`) mode table |
| `overhead/run-overhead.sh` | plugin-overhead self-benchmark: gradle-profiler on `overhead/fixture/` with the plugin toggled on/off → per-axis verdict vs the budget (plan 034); see [`docs/overhead-budget.md`](../docs/overhead-budget.md) |
| `overhead/overhead.scenarios` | the four overhead scenarios: `no_op`/`incremental`/`cc_hit`/`no_op_upload` |
| `overhead/bin/buildhound-overhead` | thin launcher over `OverheadCalculator` (buildhound-commons); exits non-zero on a budget breach |
| `overhead/bin/loopback-sink.py` | do-nothing HTTP sink (202s + discards) for the upload cell — no BuildHound server needed |
| `overhead/fixture/` | synthetic 3-module Kotlin/JVM project (no Android) with the `buildhound.overhead.plugin` toggle |
| `sharding/shard-matrix-examples.md` | GitHub Actions + Azure DevOps shard-matrix snippets for the `dev.buildhound.test-sharding` addon (`BUILDHOUND_SHARD_INDEX`/`_TOTAL`/`_REFERENCE`), plan 040 |
| `agent-skill/SKILL.md` | First-party agent skill: diagnose a build privately from the HTML report / query API / `buildhound-mcp` tools, no `scans.gradle.com` upload, plan 071 |

## Metric CLI

```sh
export BUILDHOUND_SERVER_URL="https://buildhound.example.com"
export BUILDHOUND_TOKEN="…"          # an ingest-scoped token, from a secret — never a flag
buildhound-metric --name release.size --value 84.2 --unit MB --scope apk
```

Correlation (which build the measure attaches to) is derived from CI env vars using the same
markers as the plugin's `CiEnvironmentProvider` SPI — Azure (`TF_BUILD` → provider `azure-devops`,
run id `BUILD_BUILDID`), GitHub Actions (`GITHUB_ACTIONS` → `github-actions`, `GITHUB_RUN_ID`), or
generic (`CI` truthy). `BUILDHOUND_BUILD_ID` overrides with an explicit build correlation. The
server + token come **only** from the environment, so the token never lands on the command line or
in `ps`. The CLI never fails the step by default (transport errors / missing config warn to stderr
and exit 0); pass `--strict` to make those non-zero.

## Verdict gate

Set `verdictGate: warn|fail` on the steps template to poll the per-build regression verdict after
the Gradle build. `warn` logs an Azure warning on a `FAIL` verdict; `fail` fails the pipeline. The
verdict is BuildHound's rolling-baseline comparison (spec §5, plan 025).

## Wrapper-integrity check (plan 066, research F16)

Set `validateWrapper: warn|fail` on the steps template for a **preventive** check that runs
*before* `./gradlew`: it greps `gradle/wrapper/gradle-wrapper.properties` for
`distributionSha256Sum=` (an absent key means the wrapper isn't pinned — drift risk) and, when
`expectedWrapperJarSha256` is also set, compares `sha256sum gradle/wrapper/gradle-wrapper.jar`
against it. `warn` logs an Azure warning; `fail` fails the pipeline before Gradle ever runs; `off`
(the default) skips the step entirely. Network-free, no token — this is the preventive half of the
finding; the plugin's own `payload.wrapper.wrapperJarSha256` telemetry is the detective half,
computed *inside* the JVM the wrapper already launched (so it can never catch a wrapper that
actively subverts the read — a genuine gradle.org checksum cross-check is a separate, deferred
server-side slice).

## Azure DevOps CI connector (server-side, plan 028)

The **server** can enrich each ingested Azure build with its pipeline timeline — the stage → job →
step span tree, **queue time**, and **"Gradle share of pipeline"** (the Gradle build's wall-clock as
a fraction of the whole pipeline). This is entirely server-side: no plugin or pipeline change is
needed beyond having the plugin run under Azure (which already sets `ci.provider=azure-devops`).

**Enable it** by giving the server a Personal Access Token (env-only — never a flag, code, or image
layer). PAT scope: **Build (Read)**.

```sh
BUILDHOUND_CONNECTOR_AZURE_PAT="<pat>"              # Build:Read; unset ⇒ connector inert (UNCONFIGURED)
BUILDHOUND_CONNECTOR_AZURE_HOSTS="dev.azure.com"    # SSRF allowlist; default dev.azure.com (SaaS)
BUILDHOUND_CONNECTOR_AZURE_BASEURL="https://dev.azure.com/your-org"   # optional org override
BUILDHOUND_CONNECTOR_AZURE_PROJECT="your-project"                     # optional project override
```

Security posture (see the architecture decision log): outbound calls are **https-only** and the host
must be in `_HOSTS` — an ingested build URL can only pick a configured org, never a new host. A
**self-hosted Azure DevOps Server** host MUST be named in `_HOSTS` explicitly. The PAT is sent as
Basic auth, never logged, and never stored in the span rows. A connector failure never affects
ingest: the run is recorded `FAILED`/`PENDING`/`UNCONFIGURED` and the build still renders.

Enrichment normally runs on ingest (polling until the build finishes). To push completion instead,
configure an Azure **Service Hook** for the `build.complete` event pointing at:

```
POST {server}/v1/connectors/azure-devops/hook      # Authorization: Bearer <ingest-scoped token>
```

The tenant is taken from the token, never the hook body; an unrecognized or oversized body is
rejected `400`/`413`.

The enriched timeline appears on the dashboard **build detail** page as a "CI pipeline" section with
queue-time and Gradle-share chips; `GET /v1/builds/{id}/ci-run` (read scope) returns the same data as
JSON. Builds with no connector configured show an honest amber "not available" notice.

## GitHub Actions & GitLab CI connectors (server-side, plan 041)

Same server-side model as Azure, for two more providers. Both are **poll-only** in v1 (no webhook)
and share the identical security posture: **https-only**, host must be in the `_HOSTS` allowlist, the
token is env-only and never logged, and a connector failure never affects ingest.

**GitHub Actions** (`ci.provider=github-actions`) — pulls the workflow-run + jobs REST APIs into a
JOB → STEP span tree, with **queue time** (`run_started_at − created_at`) and per-job runner names. A
re-run is enriched against its own attempt. Token: a fine-grained PAT with **Actions: read**.

```sh
BUILDHOUND_CONNECTOR_GITHUB_TOKEN="<token>"           # Actions:read; unset ⇒ inert (UNCONFIGURED)
BUILDHOUND_CONNECTOR_GITHUB_HOSTS="api.github.com"    # SSRF allowlist; default api.github.com
BUILDHOUND_CONNECTOR_GITHUB_BASEURL="https://api.github.com"   # GitHub Enterprise: https://ghe.host/api/v3
```

**GitLab CI** (`ci.provider=gitlab`) — pulls the pipeline + jobs REST APIs into a STAGE → JOB span
tree (stages are synthesized from each job's `stage`, in order), with **queue time**
(`queued_duration`) and per-job runner descriptions. Token: a project/personal token with the
**read_api** scope.

```sh
BUILDHOUND_CONNECTOR_GITLAB_TOKEN="<token>"           # read_api; unset ⇒ inert (UNCONFIGURED)
BUILDHOUND_CONNECTOR_GITLAB_HOSTS="gitlab.com"        # SSRF allowlist; default gitlab.com
BUILDHOUND_CONNECTOR_GITLAB_BASEURL="https://gitlab.com/api/v4"   # self-managed: https://gitlab.host/api/v4
```

For **GitHub Enterprise** / **self-managed GitLab**, name the host in `_HOSTS` **and** point
`_BASEURL` at its API root — an ingested build URL supplies only the owner/repo (or project path) and
run id, never the outbound host. See [`docs/extending-ci-provider.md`](../docs/extending-ci-provider.md)
to add a new provider.
