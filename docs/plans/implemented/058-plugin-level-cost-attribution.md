# 058 — Plugin-level cost attribution (owning-plugin rollup)

**Status: implemented** · 2026-07-08 — `PluginAttribution.owningPlugin` (with the
`DefaultTask` carve-out into `"(unattributed)"`), `RollupCalculator.pluginCost` +
`BuildStore.pluginCost` (in-memory/Postgres parity), `GET /v1/rollups/plugin-cost`
(`openapi.yaml` documented), `BottleneckCalculator`'s `topPlugins`/`topPluginsAvailable`,
and the dashboard's "By plugin" toggle + "Top plugins by time" card all landed as designed,
plus two review-fix commits (`topPluginsAvailable` availability gate, a toggle-click race
guard, and a benchmark-inclusion parity test). See `docs/architecture.md` decision log,
2026-07-08 row. No commons/payload/golden change, as scoped; Layer 2 remains deferred.

## Source

Research finding **F8** (`docs/research/ingest-corpus-analysis.md`), **Layer 1 only** — the
server-side owning-plugin rollup derivable from already-collected task-type FQCN prefixes with
*zero new collection*. Source article: Android Studio **Build Analyzer** ("plugins with tasks
impacting build duration" as the primary triage dimension). Builds on plan
[016](implemented/016-task-type-cacheable-capture.md) (`TaskExecution.type` capture + the
`whenReady` dictionary), plan [026](implemented/026-server-rollups-project-cost.md)
(`RollupCalculator` + the two-store byte-for-byte parity discipline), plan
[032](implemented/032-bottlenecks-landing-page.md) (`BottleneckCalculator`, the Bottlenecks
landing page, spec §6), and plan [042](implemented/042-oss-launch-hardening.md)
(`OpenApiContractTest` route/spec drift guard). Layer 2 reuses the plan
[039](implemented/039-addon-foundation.md) "plugin stays dumb, server rules carry the knowledge"
pattern — deferred (see Scope).

## Scope

**In (this slice — server + dashboard, no payload change)**

- `PluginAttribution.owningPlugin(type)` (server-side, next to `RollupCalculator`): a curated
  FQCN-prefix → plugin-label catalog, with an explicit **"(unattributed)"** bucket and a distinct
  **"Gradle core"** bucket.
- `RollupCalculator.pluginCost(rows)` → `PluginCostRollup`; new `BuildStore.pluginCost(...)`
  (in-memory + Postgres, parity-checked) behind a new read route `GET /v1/rollups/plugin-cost`
  (documented in `openapi.yaml`).
- `BottleneckCalculator.compute` gains a folded `topPlugins` ranking → additive
  `topPlugins: List<BottleneckRow>` on `BottlenecksRollup`.
- Dashboard `#/tasks` (`tasksRollupView`/`renderDuration`) gains a **"By plugin"** grouping;
  `bottlenecksView` renders a **"Top plugins by time"** card.

**Out (deferred / explicitly not this slice)**

- **Layer 2** — the applied-plugin-**id** inventory (probe known ids via
  `pluginManager.hasPlugin(id)` or report impl-class FQCNs, narrowing 2) + a server costly-plugin
  catalog with **mode-aware** rules (Firebase Performance instrumentation on debug builds; lingering
  Jetifier). Separate plan: it adds plugin-side collection, a **new nullable payload field + a new
  golden**, and a rules engine — none of which this zero-collection slice needs.
- **Config-phase** costs (Jetifier) are invisible to a *task-time* rollup by construction
  (narrowing 3) — they belong to Layer 2's inventory, not a Layer 1 gap.
- Per-build **report** (`buildhound-report`) by-plugin grouping — deliberately out: it would
  duplicate the attribution catalog into the report's inline JS (Kotlin/JS divergence hazard), and
  fleet-level plugin-regression triage lives on the dashboard, not the single-build artifact.
- No new collection, **no `BuildPayload` schema change, no golden edits**.

## Design

Modules touched: `buildhound-server` (`Rollups.kt`, `Bottlenecks.kt`, `BuildStore.kt`,
`PostgresStores.kt`, `Routes.kt`, `web/dashboard.js`) and `docs/api/openapi.yaml`.

- **`PluginAttribution` (server, not commons).** The catalog stays server-side deliberately —
  F8's own thesis is "plugin stays dumb, server rules carry the knowledge," and `buildhound-commons`
  ships *inside* the published plugin artifact, so a catalog there would contradict the pattern. Its
  only Layer-1 consumer is the server rollup (the dashboard renders server-returned rows; no JS
  mapping). `owningPlugin(type: String?): String` maps prefixes: `com.android.build.` → *Android
  Gradle Plugin*, `org.jetbrains.kotlin.` → *Kotlin Gradle Plugin* (so `KotlinCompile` attributes to
  KGP for free), `com.google.devtools.ksp.` → *KSP*, plus a seed of third-party namespaces
  (Hilt/Dagger, Detekt, ktlint, Spotless, protobuf). Core `org.gradle.` types (`JavaCompile`,
  `Test`, `Copy`) → **"Gradle core"** — a large, honest share kept distinct from **"(unattributed)"**
  (null / `DefaultTask` / build-script-defined FQCNs / unrecognized), per narrowing 1.
- **`RollupCalculator.pluginCost(rows: List<TaskRow>): PluginCostRollup`** folds by
  `PluginAttribution.owningPlugin(it.type)`, summing `durationMs`/count, `sharePct =
  roundTo6(total / grandTotal)`, sorted desc with a plugin-label tiebreak, `take(TOP_N)`. New DTOs
  `PluginCostRow(plugin, totalMs, count, sharePct)` and `PluginCostRollup(plugins, available)`;
  `available` mirrors `TaskDurationRollup.byTypeAvailable` (`rows.any { it.type != null }`).
- **`BuildStore.pluginCost(projectId, days, nowMs)`** — the in-memory store calls
  `RollupCalculator.pluginCost(taskRowsInWindow(...))` (**days window, benchmark included** — exactly
  the `taskDuration` sibling); Postgres fetches the identical windowed `TaskRow`s and defers to the
  same calculator (the plan-026 parity discipline: same multiset → same calculator → bit-identical
  `sharePct`).
- **`topPlugins`** is computed *inside* `BottleneckCalculator.compute`, folding the `currentTasks`
  it already receives (so it inherits the **period window + benchmark-EXCLUSION** the bottlenecks
  store applies), ranked by total ms → `BottleneckRow(key = plugin, currentMs = total, count)`.
  Additive `topPlugins: List<BottleneckRow> = emptyList()` on `BottlenecksRollup`.
- **Route:** `get("/rollups/plugin-cost")` → `call.authenticatedProject(tokens,
  TokenScope::allowsRead)`, `call.daysParam()`, `store.pluginCost(...)` — token + tenant scoped like
  every other rollup. Added to `openapi.yaml` or the plan-042 drift guard fails.
- **Dashboard:** `renderDuration` gets a third **"By plugin"** button; `tasksRollupView` lazily
  fetches `/rollups/plugin-cost` and renders plugin/total/share/runs via `rankedTable`, showing the
  existing plan-016 *"not collected yet"* notice when `available=false`. `bottlenecksView` appends a
  **"Top plugins by time"** card (`rankedTable` over `topPlugins`) after *Slowest work*, omitted when
  empty.

Every new field is a **server response DTO** with a default — the untouched golden payload tests are
themselves the proof of "zero new collection."

## Test strategy

- **Unit `PluginAttributionTest`:** AGP/KGP/KSP + seed third-party attribute; `JavaCompile`/`Test`/
  `Copy` → "Gradle core"; `null`/`DefaultTask`/`com.example.MyTask` → "(unattributed)". A pinned
  golden list so the catalog cannot silently drift.
- **Unit (`RollupCalculatorTest`):** `pluginCost` folds several types into one plugin, `sharePct`
  rounds, the unattributed/core buckets appear, `available` flips with `type` presence, empty window
  → empty + `available=false`.
- **Unit (`BottleneckCalculatorTest`):** `topPlugins` folds `currentTasks`, ranks by total, inherits
  benchmark-exclusion.
- **Store parity (Testcontainers, `RollupStoresIntegrationTest` / `BottleneckStoresIntegrationTest`
  siblings):** in-memory vs Postgres `pluginCost` (incl. `sharePct`) and `topPlugins` agree
  byte-for-byte over a seeded window.
- **Route (`RollupRoutesTest` / `BottleneckRoutesTest`):** `/rollups/plugin-cost` is read-scoped +
  tenant-scoped (401 without token; another tenant's data invisible); bottlenecks response carries
  `topPlugins`.
- **Contract (`OpenApiContractTest`, plan 042):** new path documented; drift guard green.
- **Dashboard (`DashboardScriptTest`):** the "By plugin" button renders rows / the not-collected
  notice; the top-plugins card renders.
- Golden payload tests unchanged (asserts no schema change).

## Risks

- **Heuristic attribution (narrowing 1):** FQCN-prefix rollup is weaker than Build Analyzer's
  registration-based attribution; `DefaultTask`/`Copy`/script-defined types can't be attributed.
  *Mitigation:* an explicit **"(unattributed)"** bucket plus a distinct **"Gradle core"** bucket,
  both surfaced with their `sharePct` so the heuristic's coverage is visible, never hidden.
- **Additive-schema / golden:** no `BuildPayload` change — the new fields are defaulted server
  response DTOs, and appending `topPlugins` to `BottlenecksRollup` is backward-compatible (older
  clients ignore it, like `budgetBreaches`). Contrast: the deferred Layer 2 adds a nullable *payload*
  field + a **new** golden, never edits one.
- **Parity float hazard:** `sharePct` must be `roundTo6`'d and folded from the *identical* `TaskRow`
  multiset in both stores (the plan-026 discipline) or the Testcontainers parity test fails on a
  last-bit division diff.
- **Isolated-projects:** under IP the plan-016 `whenReady` dictionary is intentionally empty → task
  `type` is null → `pluginCost` degrades to `available=false` (a single "(unattributed)" fold),
  reusing the `byTypeAvailable` idiom. It never fails; the dashboard shows the plan-016 notice.
- **Window-inclusion mismatch (named so tests seed correctly):** `/rollups/plugin-cost` uses the
  `taskDuration` **days window, benchmark-included**; `topPlugins` inherits the bottlenecks
  **period window, benchmark-excluded**. The two parity tests seed each accordingly.
- **Multi-tenancy:** the new read route is token + tenant scoped via `authenticatedProject(...,
  allowsRead)`; parity/route tests assert cross-tenant invisibility.
- **Never-fail / CC-safety:** trivially satisfied — the slice is server-only; no plugin or
  config-time code changes. The real hazards (`pluginManager.hasPlugin` probe CC-safety, `BoundedExec`
  bounding, never-fail wrapping) plus narrowings 2 (no public API enumerates applied plugin ids) and
  3 (AGP-8 "category is default" misreading; Jetifier is config-phase) are parked in the deferred
  Layer 2 plan.

## Exit criteria

- `PluginAttribution` maps AGP/KGP/KSP + seed third-party correctly, with distinct "Gradle core" and
  "(unattributed)" buckets, pinned by a golden list.
- `GET /v1/rollups/plugin-cost` returns owning-plugin rows (read-scoped, tenant-scoped), documented
  in `openapi.yaml`; the plan-042 drift guard stays green.
- In-memory and Postgres `pluginCost` + `topPlugins` agree byte-for-byte (parity tests).
- `#/tasks` "By plugin" grouping and the Bottlenecks "Top plugins by time" card render, and both
  degrade to the plan-016 "not collected yet" notice under IP / missing `type`.
- No payload schema change; golden payload tests unchanged.
- `./gradlew build` green.
