# Grafana recipe (plan 070)

BuildHound ships a per-project **Prometheus scrape** endpoint — `GET /v1/metrics/prometheus` — instead
of a bespoke Grafana companion. The flow is:

```
Grafana  --(queries)-->  Prometheus  --(scrapes, ~1/min)-->  BuildHound  GET /v1/metrics/prometheus
```

This directory ships the two static assets for that flow. Wiring a live Prometheus + Grafana stack is
optional infra and out of scope here — this is the recipe, not a bundled compose profile.

## 1. Mint a scrape-only token

Use a token with the `metrics` scope (or an existing `read`/`all` token — both are supersets). A
`metrics`-scoped token can **only** reach this one endpoint for its own project; it cannot read build
history, ingest, or reach any other `/v1` route. Never reuse an `ingest` token here — it has no `read`/
`metrics` scope and gets a `403`.

## 2. Add the scrape job to `prometheus.yml`

```yaml
scrape_configs:
  - job_name: buildhound-<project-key>
    metrics_path: /v1/metrics/prometheus
    scheme: https
    scrape_interval: 1m
    static_configs:
      - targets: ["<your-buildhound-server-host>:8443"]
    authorization:
      # Replace with the metrics-scoped token minted in step 1. Never commit a real token —
      # inject it via Prometheus's secret file mechanism or your secret manager, not this file.
      credentials: <METRICS_TOKEN>
    params:
      # Optional: narrows the KPI window (default 30, clamped to [1, 365] server-side).
      days: ["30"]
```

One job per BuildHound project — a token is scoped to exactly one tenant, so scraping N projects needs
N jobs (each with its own token and, if you want per-tenant Grafana filtering, its own `project` label
value already carried in every sample).

Metrics are windowed aggregates, not a live tail — a 1-minute scrape interval is already far more
frequent than the data changes; there is no benefit to scraping faster.

## 3. Import the dashboard

Import [`buildhound-dashboard.json`](./buildhound-dashboard.json) into Grafana (Dashboards -> Import ->
Upload JSON file) and point its `DS_PROMETHEUS` input at the Prometheus datasource from step 2. Set the
`project` template variable to the `projectKey` your scrape token belongs to.

## Notes

- **`buildhound_builds{outcome}` and `buildhound_flaky_tests` are `gauge`, not `counter`.** Both are
  windowed (non-monotonic) values — they fall as builds/records age out of the window. Do not wrap
  either in `rate()` or `increase()`; those PromQL functions assume a monotonically increasing counter
  and treat any drop as a process restart, which would corrupt the result. Chart them directly, as the
  shipped dashboard does.
- **Omit, never zero.** A KPI with no underlying samples in the window (a brand-new project, no flaky
  evidence yet, no plan-038 origin timings for avoided time) is absent from the scrape entirely — it
  will not appear as a data point, rather than showing a misleading `0`. The scrape target itself still
  reports `200`/up.
- **Multi-tenancy.** There is no global, unauthenticated `/metrics` — every scrape is tied to one
  project via its token. A leaked scrape token exposes only that project's aggregate KPIs, never
  another tenant's data or this project's raw build history.
