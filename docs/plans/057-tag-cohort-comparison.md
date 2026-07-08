# 057 — Tag-cohort comparison: split a trend by a tag value

## Source

- Research finding **F7** (`docs/research/ingest-corpus-analysis.md` §4 — "Tag-cohort
  comparison"). Source article: the flagship organic-fleet workflow teams built on
  **Talaiot + Grafana** (`docs/research/processed/` corpus) — tag builds `R8=true/false`,
  chart mean task time grouped by the flag, conclude "R8 speeds builds N %". Related:
  `docs/research/dashboard-ux-research.md` rec-4 (dimension slicing over *fixed* dimensions —
  the new part here is **user-defined-tag** multi-series with delta stats).
- Spec [§6](../build-telemetry-spec.md) — the **Trends** dashboard view (today: p50/p95
  duration, hit rate, filter pipeline/branch/mode/env). Tag contract: spec §3/§4 (`tags` is a
  low-cardinality `Map<String,String>`, cardinality-capped by plan 019).
- Adapts (does **not** reuse) plan [025](implemented/025-regression-engine-v1.md)'s
  median+MAD robust-z. Builds on the on-read trend rollup (plan 010) and the raw-rows-→-pure-
  calculator store pattern (plan 032 `BottleneckCalculator`).

## Scope

**In (server + dashboard only — no plugin, no commons, no payload/golden change):**

- Extend `BuildFilter` (`BuildStore.kt:74`) with an additive `tags: Map<String,String> =
  emptyMap()` equality filter, threaded through `filterSql` (Postgres, `@>` containment) and
  `InMemoryBuildStore.matching` — so `/v1/builds`, `/v1/trends` gain tag filtering as the
  prerequisite querying/indexing work the finding names.
- New DB migration adding a **GIN index** over `payload -> 'tags'` (additive; no column/table
  change) to accelerate the containment filter.
- New read route `GET /v1/trends/cohorts?tag=<key>` — daily per-cohort trend series keyed by
  the tag's distinct values, plus a delta summary (median difference, % change, per-cohort
  sample counts, distinguishable/not verdict) from a new **pure `CohortComparator`**.
- New read route `GET /v1/tags` — distinct tag keys + top-N values each (capped), to populate
  the dashboard split picker.
- Dashboard `#/trends`: a "split by tag" picker; when set, a multi-series line chart + a
  per-cohort delta table with semantic (goodness) coloring.

**Out (deferred):** splitting by `values` (free-text, high-cardinality) or by fixed dimensions
(Gradle/JDK/agent — dashboard-ux rec-4, its own slice); cohort comparison on metrics other than
duration (hit rate / custom metrics — the same shape, later); statistical significance testing
beyond the robust-z candidate signal; benchmark-mode cohort series (plan 030 owns `#/benchmark`).

## Design

**Filter + index (server).** `BuildFilter.tags` (additive, default empty). `filterSql`
(`PostgresStores.kt:305`) appends, per entry, `AND payload -> 'tags' @> ?::jsonb` with a bound
`{"key":"value"}` JSON string param (key + value are **bound**, never interpolated).
`InMemoryBuildStore.matching` (`BuildStore.kt:402`) mirrors: `filter.tags.all { (k,v) ->
it.tags[k] == v }`. New migration `V11__tag_index.sql` (claim the next free `V{n}` at merge, per
the plan-025 renumber note): `CREATE INDEX builds_tags_gin_idx ON builds USING gin ((payload ->
'tags'));` — accelerates `@>` and key-existence; additive only, existing golden/payload untouched
(`tags` is already schema v1).

**Cohort trends (server).** New `BuildStore.tagCohortTrends(projectId, tagKey, filter, days,
nowMs): List<TagCohortSeries>` — the same daily bucketing as `trends` (`PostgresStores.kt:355`)
but `GROUP BY payload->'tags'->>?, day` (tag key **bound**), scoped by the existing
`(project_id, started_at)` window index; returns, per cohort value, oldest-first `TrendPoint`s
**plus** the raw SUCCESS/FAILED duration list. Cohorts capped **top-N (6) by sample count** for a
readable legend; benchmark excluded by default (`buildFilterOrNull`, plan 030). Both stores feed
one **pure `CohortComparator.compare(cohorts)`** (the `BottleneckCalculator` shape) so in-memory
and Postgres agree byte-for-byte.

**`CohortComparator` (pure, adapts plan 025).** Reuses only `RegressionEngine.median`/`mad`/
`MIN_BASELINE` (`RegressionEngine.kt:79-87`). Reference cohort = the one with the most builds
(most stable). For each other cohort: `medianDeltaMs = cohortMedian − refMedian`, `pctChange`,
and — when `refMad > 0` and **both** cohorts have `≥ MIN_BASELINE` builds — a robust
`z = 0.6745·(cohortMedian − refMedian)/refMad`; status ∈ `INSUFFICIENT_DATA` (< MIN_BASELINE
either side) / `DISTINGUISHABLE` (|z| ≥ warn threshold) / `INDISTINGUISHABLE`. This is a **ranked
candidate**, never a causal claim: it carries sample counts and a distinguishable flag, so a
confounded split (e.g. `R8=true` builds skewing to CI) reads as a candidate to investigate, not
"R8 saves 14 %". DTO `TagCohortComparison { tagKey, cohorts: [TagCohortSeries{ value,
sampleCount, medianDurationMs, points }], delta: { referenceValue, comparisons:[{ value,
medianDeltaMs, pctChange, robustZ?, status }] } }` (new server DTOs; commons untouched).

**Routes (server).** `GET /v1/trends/cohorts` and `GET /v1/tags` in `queryRoutes`, both
`authenticatedProject(tokens, TokenScope::allowsRead)` + tenant-scoped, `days` clamped `[1,365]`
like `/trends` (`Routes.kt:419-424`). `buildFilterOrNull` (`Routes.kt:674`) parses `tag.<key>=
<value>` params into `BuildFilter.tags`, rejecting over-cap keys/values (plan-019 char caps).

**Dashboard (`web/dashboard.js`).** `trendsView` (`dashboard.js:730`) gains a tag-key picker
populated from `/v1/tags`; when set it fetches `/v1/trends/cohorts` and renders a **multi-series**
line chart (generalize `trendChart` `:706` / reuse the artifact-sizes `bySeries` precedent
`:800-809` with a color cycle + legend) plus a delta table (cohort, n, median, Δ vs reference,
% change, distinguishable badge) using the existing semantic-goodness coloring. Best-effort like
the artifact panel: a fetch error omits the split, never blanks the page.

## Test strategy

- **`CohortComparatorTest` (pure unit):** median-delta / %change / robust-z; reference-cohort =
  largest; `INSUFFICIENT_DATA` when a cohort has < MIN_BASELINE builds; zero-MAD reference → no z
  (INDISTINGUISHABLE, not a crash); single-cohort tag → no comparison; direction is duration
  (higher = worse) for coloring.
- **`TagCohortRouteTest` (testApplication):** 401 no token, 403 read-scope gate, happy path
  splits by tag into ordered cohorts + delta; benchmark excluded by default; unknown tag key →
  empty; `/v1/tags` lists keys + capped values; over-cap `tag.` param → 400.
- **Multi-tenant:** a foreign read token cannot see another tenant's cohorts (`/v1/trends/cohorts`
  and `/v1/tags` scoped by `project_id`).
- **Testcontainers (`PostgresStoresIntegrationTest`):** `V11` migration applies on plain Postgres;
  `@>` tag filter + cohort `GROUP BY` parity with `InMemoryBuildStore` on the same fixtures.
- **Server DTO round-trip:** pin the `TagCohortComparison` JSON shape. **No commons schema change
  → existing golden files untouched.**
- **Dashboard smoke** (existing JS harness, cf. `ReportScriptTest`): the split control + multi-
  series chart + delta table render with no console error and no external request (CSP-safe).

## Risks

- **No existing tag filter → new querying/indexing (finding narrowing 1).** Mitigated by the
  additive `BuildFilter.tags` + a `payload->'tags'` **GIN index**; the filter is a parameterized
  `@>` containment (`?::jsonb`), the cohort split binds the tag key into `->>?`. **No string
  interpolation**, so no jsonb/SQL injection. Migration is additive-only (new index) — no column
  changed, no payload type edited, no golden file touched (`tags` is already schema v1).
- **Cohort stat is adapted, not reused (finding narrowing 2).** `CohortComparator` is
  cohort-vs-cohort (one cohort's median vs the reference cohort's median+MAD), **not** plan-025's
  single-value-vs-baseline verdict; it reuses only the pure `median`/`mad`/`MIN_BASELINE`
  primitives. Honest insufficient-data: < MIN_BASELINE builds in a cohort ⇒ `INSUFFICIENT_DATA`,
  never a claimed delta. Surfaced as a **distinguishable candidate with sample counts**, never a
  causal "N %" headline — the confounding/Simpson's-paradox trap (a cohort skewed to CI or a
  branch) reads as "investigate", not "confirmed fix".
- **Multi-tenancy.** Both new routes are token + tenant-scoped via `authenticatedProject(…,
  ::allowsRead)`; every store query is under `WHERE project_id = ?`. A Testcontainers test proves
  cross-tenant isolation.
- **Privacy (spec §3.7).** Cohort labels and `/v1/tags` only echo `tags` values already stored and
  already shown per-build — no new field, no absolute paths/PII (tags carry none by contract;
  plan-019 caps and the ingest scrubber applied identically at collection). Response is bounded:
  cohorts capped to top-6 by sample count, `/v1/tags` values capped per key, `days` clamped — a
  misused high-cardinality tag cannot blow up the payload or the legend.
- **Never-fail-build / configuration cache.** No plugin change: the tags DSL
  (`BuildHoundExtension`), `PayloadAssembler`, and the Flow-API finalizer are untouched, so the
  never-fail contract and CC-safety are unaffected — this slice is pure server + dashboard
  read-side.
- **Isolated-projects.** N/A: server-side; independent of the plan-016 `whenReady` task dictionary.
  `tags` come from the settings DSL and populate regardless of IP.
- **Store parity.** In-memory and Postgres must agree; both fetch raw rows and defer to the one
  pure `CohortComparator` (BottleneckCalculator precedent), pinned by the Testcontainers parity
  test.

## Exit criteria

- `GET /v1/trends/cohorts?tag=R8` returns one series per distinct `R8` value with per-cohort
  daily points, sample counts, and a median-delta / %-change / distinguishable verdict; a cohort
  under MIN_BASELINE builds is `INSUFFICIENT_DATA`, not a claimed delta.
- `GET /v1/builds` and `/v1/trends` accept `tag.<key>=<value>` and filter via the GIN-backed
  `@>` containment; both stores agree (Testcontainers parity).
- `GET /v1/tags` enumerates tag keys + capped values; both new routes are read-scope + tenant
  isolated (foreign token blocked).
- The `#/trends` split-by-tag picker renders a multi-series chart + delta table, CSP-safe, and
  degrades to the normal trends page on error.
- No commons/payload/golden change; new server DTOs round-trip-pinned. Clean-context code and
  security/privacy reviews completed with findings addressed.
- `./gradlew build` green.
