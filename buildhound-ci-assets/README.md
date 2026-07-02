# buildhound-ci-assets

CI-facing assets that must be consumable **without a JVM** (spec §7). This directory is
deliberately not a Gradle module.

| Asset | Purpose |
|---|---|
| `azure-pipelines/buildhound-gradle-steps.yml` | Reusable Azure Pipelines steps template: token injection, `gradle.properties` validation, HTML-artifact publishing, optional verdict gate |
| `bin/buildhound-metric` | Metric CLI for non-Gradle steps → `POST /v1/metrics` (Datadog tag/measure model) |
| `profiler-scenarios/` | gradle-profiler scenarios for benchmark mode (`mode=benchmark` tagged series) — arrives phase 3 |

The metric CLI reads correlation ids (provider, run id) from the same environment-variable
mappings the plugin's `CiEnvironmentProvider` SPI uses — one shared spec, reimplemented in
shell so non-JVM pipeline steps stay cheap.
