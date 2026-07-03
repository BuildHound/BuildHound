# Plan 025 — Regression engine v1: baselines, verdict endpoint, budgets, alerts, metric CLI

**Status: planned — roadmap phase 2b** · 2026-07-03

## 1. Source

- Roadmap [phase 2b](../build-telemetry-roadmap.md): "Regression engine v1: rolling
  default-branch baselines, PR-vs-baseline verdict endpoint, budgets, Slack/Teams alerts.
  Metric CLI + `POST /v1/metrics`." Exit criterion: "a deliberately slowed PR gets flagged
  against baseline".
- Spec [§5](../build-telemetry-spec.md) — regression engine (baseline key, median+MAD over
  last N, PR-vs-baseline delta, budget checks, alert dispatch, `GET /v1/builds/{id}/verdict`);
  `POST /v1/metrics` (Datadog tag/measure model, caps: 100 measures/run, key+value ≤ 300 chars);
  per-project settings (baseline config, budgets, alert channels).
- Spec [§7](../build-telemetry-spec.md) — metric CLI (curl-wrapper, correlation from provider
  env vars, shared with the plugin SPI mappings); [§8](../build-telemetry-spec.md) verdict gate step.
- Research: [comparison-to-spec.md §2.6/§5.6](../research/comparison-to-spec.md) — the guarded
  outlier rule (>2× median, min 3 builds, zero-baseline short-circuit) as the median+MAD
  precursor; the regression engine flagged as "the roadmap's least de-risked component".
  [dashboard-ux-research.md §4.2.2](../research/dashboard-ux-research.md) — semantic
  regression coloring (delta color encodes goodness, not sign).

## 2. Scope

**In:**

- `POST /v1/metrics` ingest endpoint: `{correlation:{provider,runId}|buildId, scope, name,
  value|text, unit?}`, ingest-scoped, cardinality caps enforced **in code** (spec §5).
- `custom_metrics` table (in the plan's schema migration) + `MetricStore`; correlation resolution to a build.
- `RegressionEngine` (pure, plain-unit-testable): rolling median+MAD baseline per key over the
  last N default-branch builds; PR-vs-baseline verdict (duration + hit rate + declared custom
  metrics) with the guarded outlier rule; budget checks against per-project absolute thresholds.
- Post-ingest evaluation hook in `POST /v1/builds` that computes and persists a per-build verdict.
- `GET /v1/builds/{id}/verdict` — read-scoped, pollable by the CI verdict gate (PASS/WARN/FAIL).
- Per-project settings table (in the plan's schema migration) + `SettingsStore`: baseline `N`,
  budgets, alert channels; read/written through a minimal internal admin surface (env-seeded for
  the pilot). **Plan 042 later `ALTER`s this same `project_settings` table with retention columns
  — it extends, never re-`CREATE`s; leave room for that.**
- Alert dispatch: Slack + Teams + generic incoming-webhook, on FAIL (and budget breach),
  behind an outbound-allowlist and a fire-and-forget never-block contract.
- Metric CLI: replace the `bin/buildhound-metric` placeholder with a working POSIX-sh
  `curl`-wrapper reading correlation from provider env vars (shared mappings with the SPI),
  posting to `POST /v1/metrics`; Azure steps-template verdict-gate step.
- Spec §5/§7/§8 amendments; architecture decision-log row for outbound webhooks.

**Out (recorded, with owning plans):** by-type / Project-Cost / negative-avoidance rollups
(plan 026) · CI-provider breadth for the CLI's correlation mappings beyond the current
Azure/GHA/generic set (plan 027 grows the shared mapping table; this plan reuses whatever
exists) · Azure Timeline connector / queue time (plan 028) · flaky detection (plan 036, which
**reuses this plan's alert-webhook dispatcher**) · APK-size budgets (plan 031 adds the metric;
budget mechanism here is metric-agnostic) · email alerts (spec §5 "email later") · scheduled
7-day trend/degradation alerts (spec §5 "trend alerts" — this plan does per-build verdicts only;
trend alerts land with the bottlenecks page, plan 032) · a full admin UI for settings
(plan 042 retention/admin UI; pilot seeds settings from env + a single write route).

## 3. Design

**Metrics ingest (server).** New `POST /v1/metrics` in `ingestRoutes` (`Routes.kt:48-100`),
ingest-scope-gated via the existing `authenticatedProject` helper (`Routes.kt:157-176`) and
covered by the ingest + per-host rate-limiters (`Application.kt:149-152`). DTO
`MetricSubmission(correlation, scope, name, value?, text?, unit?)` where `correlation = {buildId}`
or `{provider, runId}`. **Caps in code** (spec §5): ≤ 100 measures per correlation key,
`name` ≤ 150, `value`/`text` ≤ 300, `scope` ≤ 64 chars — over-cap ⇒ `422` (a foreign CLI is
rejected loudly, unlike the plugin assembler which drops-and-warns). Storage: `custom_metrics`
(project_id, build_id nullable, provider, run_id, scope, name, value/text, unit, created_at) with
a `UNIQUE(project_id, coalesce(build_id,''), provider, run_id, scope, name)` upsert so a retried
CI step is idempotent. `{provider,runId}` resolves to a build via new extracted
`ci_provider`/`ci_run_id` hot columns (added by the plan's migration, backfilled from the jsonb
payload); unresolved correlations store a null build_id and join lazily at verdict time.

**Baseline + verdict (server).** New pure `RegressionEngine` object (the ArtifactTransformReport
"pure extension functions + unit tests" shape, research §2.6). **Baseline key** (spec §5):
`(project, pipeline, requestedTasks-signature, branchClass, mode)` —
`requestedTasks-signature` = sorted-then-hashed `requestedTasks` (`BuildPayload.kt:20`),
`pipeline` = `ci.pipelineName` (jsonb; the plan's migration extracts it), `branchClass` ∈ {`main`,`pr`} from
default-branch config, `mode` from `payload.mode`. **Baseline** = median + MAD over the last N
(default 20, per-project) matching `SUCCESS` builds on the default branch. Per metric
(`durationMs`, `cacheableHitRate`, declared custom metrics) the guarded rule (research §2.6):
< **3** baseline builds ⇒ `INSUFFICIENT_DATA` (no false FAIL on a cold key); zero/degenerate
MAD ⇒ the `>2× median` fallback; else a robust z-score `0.6745·(value−median)/MAD` with
`WARN`/`FAIL` thresholds from settings (defaults 3.5 / 5.0). Direction is metric-aware — higher
duration bad, lower hit rate bad (semantic goodness, research §4.2.2). **Budgets**: per-key
absolute thresholds from settings, evaluated independently; a breach is always `FAIL`. Overall
verdict = worst of {baseline deltas, budgets}. Evaluation runs **after** a successful
`store.save` in `POST /v1/builds` (`Routes.kt:83-97`), wrapped so it never blocks or fails ingest,
and persists a `build_verdicts` row (created by the plan's migration). `GET /v1/builds/{id}/verdict` (read-scope, in
`queryRoutes`, `Routes.kt:107-135`) returns `{status, metrics:[{name, value, baselineMedian?,
mad?, z?, budget?, status}], evaluatedAt}`; `404` when unknown/foreign-tenant.

**Settings + alerts (server).** New `project_settings` table (in the plan's migration): `baseline_n`,
`default_branch`, `warn_z`/`fail_z`, `budgets` (jsonb key→threshold), `alert_channels` (jsonb
`{kind: slack|teams|webhook, url}[]`), behind a `SettingsStore` (in-memory + Postgres). This plan
`CREATE`s the table with the baseline/budget/alert columns; **plan 042 (retention/admin UI) `ALTER`s
this same table with retention columns — designed for extension, so 042 must not re-`CREATE` it**. A
`GET/PUT /v1/settings` pair (read/`all`-scope) plus env-seeded defaults in `storesFromEnvironment`
(`Application.kt:74-107`); per-project defaults when no row exists. `AlertDispatcher` fires from
the evaluation hook when a verdict turns `FAIL` **and** the previous verdict for the key was not
already `FAIL` (no repeat-spam): Slack/Teams get provider-shaped bodies, generic webhook the raw
verdict DTO. Dispatch is **fire-and-forget** on a bounded dispatcher with a short timeout — an
unreachable webhook logs `warn`, never delaying the `202`. Outbound URLs must be `https://`
(loopback allowed only in tests) and come only from stored settings, never the payload (no SSRF
via ingested data); bodies carry only the pseudonymized verdict (build id, key, deltas, dashboard
link) — no task detail, no identity, no secrets.

**Metric CLI (ci-assets).** Rewrite `bin/buildhound-metric` (`buildhound-metric:1-11`) as a
POSIX-sh script: parse `--name/--value/--text/--unit/--scope`, read correlation from provider env
vars using the **same mappings as the SPI** (`CiEnvironmentProviders.kt` — Azure `TF_BUILD`/
`BUILD_BUILDID`, GHA `GITHUB_ACTIONS`/`GITHUB_RUN_ID`, generic `CI`), read
`BUILDHOUND_SERVER_URL`/`BUILDHOUND_TOKEN` from env (never a flag — no token on the command
line/`ps`), `curl` `POST /v1/metrics`. Never fails the step by default (exit 0 + stderr warn on
transport error; `--strict` opts into non-zero). The Azure steps template gains the optional
verdict-gate step (`buildhound-gradle-steps.yml:11,21-27`): after the Gradle build, poll
`GET /v1/builds/{id}/verdict` and fail/warn the pipeline per a `verdictGate` parameter.

## 4. Implementation steps

1. **server migration** (`db/migration/V{n}__regression.sql` — claim the next free version
   integer at implementation time; plans 025/026/028/031/036/037/039 all add migrations, so the
   merge order determines numbering; renumber deterministically to the next free V{n} when
   merging): `custom_metrics`, `build_verdicts`, `project_settings` tables; add extracted
   `ci_provider`, `ci_run_id`, `pipeline_name`, `requested_tasks_sig` columns to `builds` with a
   backfill `UPDATE` from `payload` and supporting indexes. Additive only — no existing column
   changed.
2. **server stores**: extend `PostgresBuildStore.save` to populate the new hot columns
   (`PostgresStores.kt:37-67`); add `MetricStore`, `VerdictStore`, `SettingsStore` interfaces to
   `BuildStore.kt` with in-memory + Postgres impls; `ServerStores` (`Application.kt:22`) carries them.
3. **server `RegressionEngine`** (pure): baseline computation (median/MAD, key derivation,
   requestedTasks-signature hashing, branchClass), guarded verdict rule, budget checks — no I/O,
   plain unit tests.
4. **server metrics route**: `POST /v1/metrics` in `ingestRoutes` with DTO validation, caps →
   422, idempotent upsert, correlation resolution.
5. **server verdict route + hook**: post-`save` evaluation in `POST /v1/builds` (load baseline
   window via a new `BuildStore.baselineWindow(key, n)`, resolve custom metrics, persist verdict,
   invoke dispatcher); `GET /v1/builds/{id}/verdict` in `queryRoutes`.
6. **server settings route + seed**: `GET/PUT /v1/settings`; env-seed defaults in
   `storesFromEnvironment`; per-project default fallback.
7. **server `AlertDispatcher`**: Slack/Teams/webhook bodies, https-only outbound allowlist,
   bounded fire-and-forget, previous-verdict de-dup; injected (test double captures dispatches).
8. **ci-assets metric CLI**: rewrite `bin/buildhound-metric` (correlation mappings, env-only
   token, `--strict`); `shellcheck`-clean.
9. **ci-assets Azure template**: add the metric helper + verdict-gate step + `verdictGate`
   parameter to `buildhound-gradle-steps.yml`; update `buildhound-ci-assets/README.md`.
10. **docs, same PR**: spec §5 gains the concrete baseline formula (median+MAD, guarded rule,
    thresholds, INSUFFICIENT_DATA), the verdict DTO, and the metrics caps as enforced; §7 the
    working CLI contract; §8 the verdict gate. `docs/architecture.md` §5 gains an outbound-webhook
    bullet (https-only, settings-sourced, fire-and-forget, never blocks ingest) and a decision-log
    row (the server's first outbound network call — SSRF stance + no-repeat-spam rule).

## 5. Test strategy

- **`RegressionEngineTest` (pure unit):** median/MAD over odd/even/degenerate windows; robust
  z-score thresholds → WARN/FAIL; **INSUFFICIENT_DATA under 3 builds** (no false FAIL on cold
  key); zero-MAD `>2× median` fallback; direction (duration up = bad, hit rate down = bad);
  budget breach forces FAIL regardless of z; requestedTasks-signature order-invariance;
  branchClass excludes PR builds from the baseline.
- **`MetricsRouteTest` (testApplication):** 401 no token, 403 read-scope token, happy path
  stores + correlates, `>100` measures / oversize name/value → 422, idempotent re-POST of the
  same measure, `{provider,runId}` correlation resolves to the right build.
- **`VerdictRouteTest`:** cold key → INSUFFICIENT_DATA; a **deliberately slowed build** (duration
  far above a seeded baseline window) → FAIL (**roadmap exit criterion**); read-scope gating;
  404 foreign-tenant/unknown id.
- **`AlertDispatcherTest`:** FAIL dispatches once, second FAIL for the same key does not
  re-dispatch, non-https URL refused, unreachable endpoint logs warn and does not throw; body
  carries no identity/secret fields.
- **Testcontainers** (`PostgresStoresIntegrationTest` pattern): the plan's migration applies on plain
  Postgres; hot-column backfill; metric upsert idempotency; baseline-window SQL correctness.
- **Golden/contract:** no commons schema change → existing golden file untouched; a server-DTO
  round-trip test pins the verdict/metric JSON shape.
- **Metric CLI:** a shell harness (stubbed `curl`, `shellcheck`-clean) asserts correlation env
  mapping, env-only token, and exit 0 on transport error without `--strict`.
- **Failure injection:** ingest still returns `202` when the evaluation hook or an alert dispatch
  throws (verdict simply absent) — the never-block contract.

## 6. Risks

- **Statistical robustness (the least de-risked component, research §5.6).** Real CI duration is
  noisy and multi-modal (cold vs warm daemon, cache hit vs miss). Mitigations: MAD (not stddev)
  for outlier resistance; the ≥3-build guard + INSUFFICIENT_DATA to avoid cold-start false FAILs;
  keying on requestedTasks-signature + mode so unlike invocations never share a baseline;
  thresholds in settings so the pilot can tune without a redeploy; benchmark mode (plan 030)
  later supplies a low-noise series to validate the thresholds.
- **SSRF / outbound network (server's first outbound call).** Alert URLs come only from stored
  settings, must be https, and dispatch is isolated from the request thread — an ingested payload
  can never steer a request. Decision-log row records the stance.
- **Privacy.** Custom metrics arrive from a foreign CLI; `name`/`text`/`scope` are stored as-is —
  document that the CLI must not send secrets, and that alert bodies carry only pseudonymized
  verdict data (no task detail, no identity, no `values`/`tags`). Token is env-only in the CLI.
- **Correlation ambiguity.** `{provider,runId}` can match multiple matrix legs; resolve to the
  most recent build. Verdicts key on a specific build id, so ambiguity never mis-attributes one.
- **Schema/compat.** Server + ci-assets only; `buildhound-commons` and the payload are untouched
  (no golden-file churn). The migration is additive; the backfill is a one-shot `UPDATE`. `POST /v1/metrics`
  rides the existing ingest + per-host limiters (`Application.kt:149-152`).

## 7. Exit criteria

- `./gradlew build` green, including the new pure-unit, route, and Testcontainers tests.
- A build ingested with duration far above a seeded default-branch baseline window yields
  `GET /v1/builds/{id}/verdict` = FAIL naming the regressed metric (roadmap 2b exit criterion:
  "a deliberately slowed PR gets flagged against baseline").
- A cold baseline key returns INSUFFICIENT_DATA, not FAIL.
- `POST /v1/metrics` stores a custom measure, correlates it to a build by `{provider,runId}`,
  and rejects an over-cap submission with 422.
- `bin/buildhound-metric --name sign.duration --value 42 --unit s` posts a metric using
  env-sourced token + provider-derived correlation, and does not fail the CI step on transport error.
- The Azure steps template can poll the verdict endpoint and fail the pipeline when `verdictGate`
  is set to `fail`.
- A FAIL verdict dispatches exactly one Slack/Teams/webhook alert (https-only), and repeat FAILs
  do not re-spam.
- Spec §5/§7/§8 amended; architecture §5 + decision-log updated; clean-context code and
  security/privacy reviews completed with findings addressed.

## 8. Divergences from plan (2026-07-04, implementation)

- **Migration is `V3__regression.sql`** (the next free version). `requested_tasks_sig` is
  `md5(sorted tasks joined by \n)`, computed identically by `RegressionEngine.requestedTasksSignature`
  and the backfill SQL (pinned by a unit test) so old (backfilled) and new builds share a baseline.
- **v1 baselines cover duration + hit rate only.** Custom metrics are ingested, correlated, and
  **budget-checked**, but get no rolling-baseline z-score in v1 (that needs per-baseline-build custom
  values — the rollup family, plan 026). In the engine they surface as `INSUFFICIENT_DATA` on the
  z-axis (neutral) unless a budget breaches. Custom metrics default to `HIGHER_BAD` direction.
- **Settings are configured via `PUT /v1/settings` (all-scope token), not env-seeded.** Env-seeding
  needed a projectKey→id resolution that the authenticated route provides cleanly; defaults apply
  when no row exists, so nothing is lost. `GET /v1/settings` is read-scope.
- **Evaluation runs inline (synchronous) in the ingest request**, only on a fresh store
  (`stored == true`), wrapped in its own `runCatching` (never blocks/fails ingest). The queries are
  bounded + indexed; only the **alert HTTP** call is async (bounded executor). The baseline is
  always the default-branch window, so a PR build is judged against main.
- **Metric correlation storage:** an explicit `buildId` stores `build_id` with `provider`/`run_id`
  null; a `{provider,runId}` stores those and resolves `build_id` now (or null → lazily joined at
  verdict time by `MetricStore.correlate`). The ≤100-measures cap counts by logical run
  (`correlationKeys`) and rejects only a *new* measure beyond the cap (422); a re-post upserts.
- **Alert dispatcher is injectable** (`AlertDispatcher` interface): `HttpAlertDispatcher` (https-only,
  bounded fire-and-forget, injectable `HttpSend` for tests) in prod; `RecordingAlertDispatcher` in
  DB-less mode and tests. The de-dup (no repeat-spam) lives in `VerdictEvaluator`.
- Everything else as planned: V3 tables + hot columns + backfill, the three stores (in-memory +
  Postgres), the pure `RegressionEngine`, the metrics/verdict/settings routes, the metric CLI
  (env-only token, `--strict`, shellcheck-clean harness), and the Azure `verdictGate` step.

### Review-driven changes (2026-07-04, two clean-context reviews)

Both reviews returned **SHIP-WITH-FIXES**; core math/SQL/authz/never-block confirmed correct.

- **[security High] SSRF host filter.** `HttpAlertDispatcher.isAllowedUrl` now rejects any URL whose
  host resolves to a loopback/link-local/private/ULA/metadata address (169.254.169.254, RFC1918,
  ::1, fc00::/7, …) or is unresolvable (fail-closed), and rejects URLs with userinfo. The resolver
  is injectable so tests exercise the filter without real DNS. New tests cover internal-IP + userinfo
  refusal. (Payload-steered SSRF was already closed — URLs come only from stored settings.)
- **[security High] CLI `--value` numeric validation.** `buildhound-metric` rejects a non-numeric
  `--value` hard (it is spliced raw into JSON) — closes a client-side JSON-injection foothold. Harness
  case added. CLI header now warns not to put secrets in `--name`/`--text`/`--scope`.
- **[security Med] Alert URLs never logged** (only kind + scheme); **Teams gets a MessageCard** body
  (its webhook rejects a bare `{text}`); **bounded alert queue** with `DiscardPolicy` (the pool no
  longer queues unboundedly under a FAIL storm).
- **[code Med] `requested_tasks_sig` collation.** The backfill sorts `ORDER BY 1 COLLATE "C"` so the
  SQL code-point order matches Kotlin's `sorted()` even under a locale/ICU DB collation — a latent
  baseline-split bug for non-ASCII/punctuation task paths. Pinned by a new signature test (colon-bearing).
- **[code Med] In-memory `evaluatedAt`** is now stamped in `InMemoryVerdictStore.save`, so dev/tests
  match Postgres; asserted by a route test.
- **[code Med] Shell harness wired into CI** — the `build` job runs `shellcheck` + the metric-CLI
  harness (ci-assets is not a Gradle module, so `./gradlew build` couldn't cover it).
- **[code Low] Robust-z end-to-end route test** (spread baseline → non-null `z` → persisted FAIL),
  complementing the exit-criterion test (which exercises the zero-MAD fallback); **tie-breaker**
  `ORDER BY evaluated_at DESC, build_id DESC` in `latestStatusForKey` for strict de-dup parity.
