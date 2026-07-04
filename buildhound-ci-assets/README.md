# buildhound-ci-assets

CI-facing assets that must be consumable **without a JVM** (spec §7). This directory is
deliberately not a Gradle module.

| Asset | Purpose |
|---|---|
| `azure-pipelines/buildhound-gradle-steps.yml` | Reusable Azure Pipelines steps template: token injection, Gradle build, optional `verdictGate` step polling `GET /v1/builds/{id}/verdict` |
| `bin/buildhound-metric` | Metric CLI for non-Gradle steps → `POST /v1/metrics` (Datadog tag/measure model) |
| `test/metric-cli-test.sh` | Stubbed-`curl` harness for the metric CLI (`sh buildhound-ci-assets/test/metric-cli-test.sh`); `shellcheck`-clean |
| `profiler-scenarios/buildhound.scenarios` | gradle-profiler scenarios for benchmark mode: `clean`/`no_op`/`incremental_non_abi`/`cc_hit` (plan 030) |
| `profiler-pipeline/{github,azure}-nightly-benchmark.yml` | scheduled gradle-profiler pipeline → `mode=benchmark` tagged series (plan 030) |
| `profiler-pipeline/isolation-modes.md` | the cache-isolation (`BUILDHOUND_BENCHMARK_ISOLATION`) mode table |

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
