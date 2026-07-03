# buildhound-ci-assets

CI-facing assets that must be consumable **without a JVM** (spec §7). This directory is
deliberately not a Gradle module.

| Asset | Purpose |
|---|---|
| `azure-pipelines/buildhound-gradle-steps.yml` | Reusable Azure Pipelines steps template: token injection, Gradle build, optional `verdictGate` step polling `GET /v1/builds/{id}/verdict` |
| `bin/buildhound-metric` | Metric CLI for non-Gradle steps → `POST /v1/metrics` (Datadog tag/measure model) |
| `test/metric-cli-test.sh` | Stubbed-`curl` harness for the metric CLI (`sh buildhound-ci-assets/test/metric-cli-test.sh`); `shellcheck`-clean |
| `profiler-scenarios/` | gradle-profiler scenarios for benchmark mode (`mode=benchmark` tagged series) — arrives phase 3 |

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
