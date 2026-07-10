# 070 — Metrics egress: token-scoped Prometheus scrape + Grafana recipe

**Status: implemented** (`0fba0f7`, review fixes `ad2fd75`) — `GET /v1/metrics/prometheus`,
`TokenScope.METRICS`, `MetricsSnapshot`/`MetricsSnapshotCalculator`, `PrometheusExposition`, and the
`deploy/grafana/` recipe are all landed and tested; see "Divergences from this plan" below for the two
intentional deviations (server-local percentile, `gauge` typing) and the accepted `flakyTestCount`
limitation.

## Source

Research finding **F20** — *Metrics egress (Prometheus/OTLP + Grafana recipe) as the
Talaiot-migration wedge* (`docs/research/ingest-corpus-analysis.md` §5). Source articles: the
Talaiot deep-dives (publishers into InfluxDB/Grafana, Elasticsearch, Prometheus PushGateway; the
one-command Grafana image) and Talaiot's own scaling article (per-task label cardinality blowup).
Spec: query API + token scopes (§5). The novelty vs `build-telemetry-research.md` §6 /
`comparison-to-spec.md` §2.12 is direction: those float a hypothetical **inbound** read-only Grafana
*companion* that consumes BuildHound's data via its own service; this is an **outbound**
Prometheus stream into a team's *existing* Grafana/Prometheus stack, tied to the Talaiot-migration
story ("your Grafana dashboards keep working").

## Scope

**In** (server-only — no plugin, commons, or payload change):

- A per-tenant, **token-scoped** `GET /v1/metrics/prometheus` endpoint emitting the Prometheus text
  exposition format (`text/plain; version=0.0.4`) for **one** project's KPIs: p50/p95 build duration,
  cache hit rate, success rate, windowed build counts, flaky-unit count, and avoided time.
- A new `MetricsSnapshot` server rollup + pure `MetricsSnapshotCalculator` (both stores defer to it,
  plan-026/032 parity discipline).
- A new `METRICS` token scope (metrics-only; `READ`/`ALL` are supersets).
- A ready-made Grafana dashboard JSON + a Prometheus scrape-config snippet under `deploy/grafana/`.

**Out / deferred:**

- **OTLP push** (server pushing per-tenant metrics to a collector) — needs per-tenant endpoint config
  + a scheduler; deferred to a follow-up. This slice is pull/scrape only.
- No **per-task / per-module / per-branch** metrics (cardinality — see Risks). Per-project aggregates only.
- No global unauthenticated `/metrics`. No bundled Prometheus+Grafana compose profile (ship the JSON +
  scrape snippet; wiring a live stack is optional infra, out of this slice).

## Design

**Modules touched:** `buildhound-server` (Routes, BuildStore + both store impls, a new calculator,
`docs/api/openapi.yaml`); `deploy/grafana/` (static assets). No `buildhound-commons`, no plugin.

- **Route** — add `Route.metricsEgressRoutes(store, tokens)` in `Routes.kt`, registered under the
  **query** rate limiter in `Application.buildHoundModule` (scrape ≈ 1/min ≪ 120/min default). It
  calls `authenticatedProject(tokens, TokenScope::allowsMetrics)` and serves **only**
  `principal.project`. Window via the existing `daysParam()` (default 30, clamp 1..365; widen it to
  `internal` — it is `private` today at `Routes.kt:622`). Body via `call.respondText(..., ContentType
  "text/plain; version=0.0.4; charset=utf-8")`, **bypassing** the JSON `ContentNegotiation` plugin.
  Storage outage → 503 through the existing `runQuery` classifier (never a bare 500).
- **Scope** — extend the `TokenScope` object (`BuildStore.kt:348`, additive) with `const METRICS =
  "metrics"` and `allowsMetrics(scope) = scope == METRICS || scope == READ || scope == ALL`. Lets ops
  mint a narrow scrape-only token while existing `read`/`all` tokens keep working — mirrors the
  dedicated `ADDON`/`ADMIN` scopes (plans 039/042: "a leaked CI ingest token must not read history").
- **Snapshot** — add `fun metricsSnapshot(projectId, days, nowMs): MetricsSnapshot` to `BuildStore`.
  Both stores fetch the same windowed rows they already fetch for `bottlenecks`/`trends`
  (`BuildKpiRow(outcome, durationMs, hitRate)` + `derived.avoidedMs`) and defer to
  `MetricsSnapshotCalculator`, reusing the nearest-rank percentile from
  `BenchmarkSeriesCalculator` (currently private p50/p90 — extract/generalize to add p95, no new copy).
  Flaky-unit count reuses the existing `flaky(projectId, days)` detector output. Avoided time = sum of
  `derived.avoidedMs` over the window (present only with plan-038/internal-adapters origin timings).
- **Exposition** — a pure `PrometheusExposition` formatter renders `# HELP`/`# TYPE` + samples. Metric
  names (base units: seconds, ratios 0..1), all labelled `project="<projectKey>"` only, plus a low-card
  `outcome` enum on the build counter: `buildhound_build_duration_p50_seconds`,
  `_p95_seconds`, `buildhound_cache_hit_rate`, `buildhound_build_success_rate`,
  `buildhound_builds{outcome}`, `buildhound_flaky_tests`, `buildhound_avoided_seconds`, and an optional
  `buildhound_scrape_window_days`. The `project` label value is **Prometheus-label-escaped**
  (`\`, `"`, `\n`). **Empty-data rule:** a KPI with no underlying samples (new/empty project, no flaky
  data, `avoidedMs` absent) **omits its line** — never emits `0` (a spurious `0` reads as
  "0% / broken" in Grafana, the exact confusion F17 warns of). The scrape still returns **200 with
  valid exposition** so Prometheus never marks the target down. No `buildhound_up` (Prometheus
  synthesizes `up` per target).
- **Grafana recipe** — `deploy/grafana/buildhound-dashboard.json` (panels over the `buildhound_*`
  metrics) + `deploy/grafana/README.md` with a `prometheus.yml` scrape snippet
  (`metrics_path: /v1/metrics/prometheus`, `authorization.credentials: <metrics token>`, per-project
  scrape job). Clarifying the finding's loose "against the existing query API": the dashboard targets a
  **Prometheus datasource** that scrapes this endpoint (Grafana → Prometheus → BuildHound) — the
  idiomatic Talaiot-parity path, deliberately divergent from a direct query-API dashboard.

## Test strategy

- **Unit (`MetricsSnapshotCalculatorTest`):** percentiles (p50/p95 nearest-rank), hit/success-rate,
  windowed counts, avoided sum; empty window ⇒ null/omitted fields, not zeros.
- **Unit (`PrometheusExpositionTest`):** exposition shape (`# TYPE`), unit conversion (ms→s, ratio),
  label escaping of a `projectKey` containing `"`/`\`, and the omit-not-zero rule for absent KPIs.
- **Route (`MetricsEgressRoutesTest`, `testApplication` + `InMemoryBuildStore`/`InMemoryTokenStore`):**
  no token ⇒ 401; `ingest`/`read`-only token ⇒ 403 unless `read`/`metrics`/`all` (assert `allowsMetrics`
  matrix); a `metrics`-scoped token returns 200 `text/plain; version=0.0.4` with the seeded project's
  gauges and **no other tenant's** values (two projects seeded, cross-tenant isolation asserted);
  empty project ⇒ 200 with a valid (line-omitted) body.
- **Contract (`OpenApiContractTest`):** add `GET /v1/metrics/prometheus` to `docs/api/openapi.yaml`
  (single source, copied to the classpath) — the drift guard fails on any undocumented `/v1` route.
- **Store parity (`RollupStoresIntegrationTest`/Testcontainers):** `metricsSnapshot` agrees byte-for-byte
  between `InMemoryBuildStore` and `PostgresBuildStore` over the same seed (plan-026 discipline).

## Risks

- **Multi-tenancy (F20's load-bearing caveat — named, not hand-waved):** a stock `/metrics` is a single
  global unauthenticated scrape that would **cross-leak every tenant's KPIs**. Mitigation: the endpoint
  is `authenticatedProject(tokens, allowsMetrics)`-gated and returns **only** `principal.project`;
  missing/unknown/wrong-scope tokens 401/403. No global route exists. Cross-tenant isolation is pinned
  by a two-project route test.
- **Cardinality (F20):** per-task/per-module/per-branch labels are Talaiot's documented blowup.
  Mitigation: labels are `project` (one value per token) + a fixed low-card `outcome` enum only — no
  unbounded dimension.
- **Avoided-time is derived, not standing (F20):** `buildhound_avoided_seconds` is the windowed sum of
  `derived.avoidedMs`, which is null without the opt-in internal-adapters origin timings (plan 038).
  Mitigation: **omit** the metric when absent (never emit 0) — degrades honestly, no false "0 avoided".
- **Outbound-novelty (F20):** distinct from the hypothetical inbound "read-only Grafana companion"
  (research §6 / `comparison-to-spec` §2.12) — stated in Source so a reviewer doesn't flag it as covered.
- **Empty-data misread (ties to F17):** emitting `0` for a no-sample KPI reads as "broken". Mitigation:
  omit-not-zero, but always return 200 with valid exposition so the Prometheus target stays up.
- **Privacy (§3.7):** exposition labels carry only the operator-chosen `projectKey` (escaped) and enum
  outcomes — no absolute paths, no `hostnameHash`/`userId`, no branch names, no URLs. The endpoint reads
  aggregates, never raw payload fields.
- **Hard constraints — satisfied by construction (stated so it is visible):** server-only means there is
  **no payload field, no golden file, no plugin/commons code** — the additive-schema, CC-safety,
  never-fail-the-build, and no-internal-Gradle-APIs rules are all honored by **not touching**
  plugin/commons (`schemaVersion` stays 1; no new golden). Isolated-projects is irrelevant server-side.
- **Rate limiting / abuse:** the endpoint shares the per-token + per-host query limiters; a scrape token
  is read-only-narrow, so a leak exposes only aggregate KPIs for one tenant, never history or ingest.
- **Infra review:** `deploy/grafana/*` is an infra path → route to `infra-reviewer` in §3 review.

## Divergences from this plan (implementation notes)

- **Percentiles are a local, server-only nearest-rank function, not an extracted/generalized
  `BenchmarkSeriesCalculator`.** The Design section says to "extract/generalize" the commons
  `BenchmarkSeriesCalculator` to add p95 ("no new copy"), but the plan's own "Modules touched" line
  states this slice is server-only and lists no `buildhound-commons` change — the two statements
  contradict each other. Implementation honors the module-scope line: `MetricsSnapshotCalculator`
  (`buildhound-server`) carries its own private nearest-rank `percentile()` (p50/p95), matching the
  existing precedent of `LptBalancer.p90` — a server-local nearest-rank percentile that already lives
  outside commons for the same reason (a server-only feature has no business depending on/mutating a
  KMP-shared calculator). No `buildhound-commons` file is touched by this change.
- **`buildhound_builds` and `buildhound_flaky_tests` are exposed as Prometheus `gauge`, not `counter`.**
  The Design section's prose calls `buildhound_builds` "the build counter", but both metrics are
  *windowed* (the plan's own words: "windowed build counts") — non-monotonic values that fall as
  builds/records age out of the trailing window. A Prometheus `counter` is defined as monotonically
  increasing over the process lifetime; `rate()`/`increase()` treat any observed drop as a process
  restart. Typing a windowed value as `counter` would silently corrupt those PromQL functions for any
  consumer who (reasonably, given the type) tries to rate() it. `# TYPE ... gauge` is emitted for both;
  `deploy/grafana/buildhound-dashboard.json` and its README call this out and chart the raw values
  directly, with no `rate()`/`increase()` anywhere.
- **Accepted limitation (review finding, no code change): `flakyTestCount`'s omit-when-zero can't tell
  "0 flakes" apart from "no test data was ever reported" in the window** — `MetricsSnapshotCalculator`
  omits the field whenever `flaky(...)` returns an empty list, whether that is because every test passed
  cleanly or because the project never ingested a `tests` block at all; a `hasTestData` signal (e.g. a
  windowed test-row count alongside the flaky count) is a possible future enhancement if this ambiguity
  turns out to matter in practice.

## Exit criteria

- `GET /v1/metrics/prometheus` returns `200 text/plain; version=0.0.4` with the caller's per-project
  gauges; a `metrics`/`read`/`all` token succeeds, `ingest`-only 403s, no token 401s — all pinned.
- Two-tenant test proves a token sees only its own project's metrics; empty project returns a valid
  omit-not-zero body; storage outage returns 503 (never 500).
- `metricsSnapshot` agrees byte-for-byte across in-memory and Postgres stores (parity test green).
- `docs/api/openapi.yaml` documents the new route (`OpenApiContractTest` green) with the `text/plain`
  media type; `deploy/grafana/buildhound-dashboard.json` + scrape snippet committed.
- `TokenScope.METRICS`/`allowsMetrics` added additively; no schema, golden, plugin, or commons change.
- `./gradlew build` green.
