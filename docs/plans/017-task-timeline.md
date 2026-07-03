# Plan 017 — Task timeline view on build detail + HTML artifact

**Status: planned — roadmap phase 2a** · 2026-07-03

## 1. Source

- Roadmap [phase 2a](../build-telemetry-roadmap.md): "the **task timeline** (per-task
  `startMs` already ships in schema v1; lanes computed greedily from start/end overlaps —
  no schema change; also reuse in the HTML artifact)".
- Spec [§3.8](../build-telemetry-spec.md) ("task timeline by worker lane" in the standalone
  artifact) and [§6](../build-telemetry-spec.md) (build detail mirrors the artifact).
- Research: [dashboard-ux-research.md §4.2.1](../research/dashboard-ux-research.md) — the
  worker-lane Gantt is the most legible "where did the time go" view in either incumbent;
  build the component once, share it across surfaces. Reconciliation verdict in
  [research/README.md §5](../research/README.md): `startMs` exists and is populated; plan
  012's deferral note was a false premise; only `worker` is unpopulated.

## 2. Scope

**In:**

- A single shared, dependency-free JS timeline renderer living in `buildhound-report`,
  consumed by both the standalone HTML artifact (inlined — zero network, locked decision
  #4) and the dashboard build-detail page (served as a separate static resource, CSP
  `script-src 'self'` unchanged).
- Greedy lane assignment from `startMs`/`durationMs` overlaps, purely client-side.
  Zero schema change; the unpopulated `worker` field (`BuildPayload.kt:105`) stays
  unpopulated and unused.
- Timeline section on the dashboard detail view and in the report template, plus a
  "max parallel" (lane count) summary chip on both surfaces.
- Architecture-doc update: the §1 dependency rule gains buildhound-report as the shared
  *renderer* channel (server now depends on it); decision-log row in the same PR.
- One-line doc repairs tied to this feature: spec §3.8 clarifies that lanes are computed
  from overlaps (not Gradle worker ids); plan 012's stale "no start offsets" deferral note
  gets a bracketed correction pointing at the reconciliation.

**Out:** zoom / range-brush / group-by timeline controls (spec §6's fuller Develocity
treatment, post-2a) · empty states, work-avoidance ledger, count-summary headers
(plan 018) · task `type`/`cacheable` columns (plan 016; the timeline reads neither) ·
real worker-id capture (needs internal build operations — plan 038) · build comparison
views (plan 022) · payload size caps that bound task-array length (plan 019).

## 3. Design

**The data already exists end to end.** `TaskExecution.startMs` is a required schema-v1
field (`buildhound-commons/src/commonMain/kotlin/dev/buildhound/commons/payload/BuildPayload.kt:99`),
populated on every task from `result.startTime`
(`buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TaskEventCollector.kt:31`).
The server stores the full payload JSON (`buildhound-server/src/main/kotlin/dev/buildhound/server/PostgresStores.kt:62`)
and `GET /v1/builds/{buildId}` returns it verbatim
(`buildhound-server/src/main/kotlin/dev/buildhound/server/Routes.kt:118-125`), so the
dashboard's `detailView` (`buildhound-server/src/main/resources/web/dashboard.js:120-173`)
already receives `tasks[].startMs` today. Because `startMs` has no default, ingest rejects
payloads lacking it — every stored build can render a timeline.

**Shared renderer.** New resource
`buildhound-report/src/main/resources/dev/buildhound/report/timeline.js` defining one
global, `buildhoundTimeline(tasks)`, returning `{svg, lanes}` (or `null` for an empty
array). Self-contained: own duration formatter, own outcome→color map matching the badge
palette both surfaces already share (`report-template.html:25-29`, `web/index.html:23-28`).
Rendering rules: SVG via `createElementNS` with numeric/fixed attributes only
(presentation attributes, never `style=` — the dashboard CSP hash-pins styles,
`DashboardRoutes.kt:29-40`); task paths reach the DOM only as `<title>` `textContent`
hover tooltips (path, outcome, duration, start offset); bars get a minimum width of 1
unit; UP_TO_DATE/SKIPPED/NO_SOURCE bars render dimmed (Develocity's no-op de-emphasis);
a bottom axis draws 4–5 time ticks from 0 to wall duration; non-finite or negative
inputs clamp to 0 (defensive against hand-edited offline payload copies).

**Lane algorithm** (classic greedy interval partitioning, a pure function unit-tested via
node): sort tasks by `startMs` (offset against `min(startMs)`), assign each to the first
lane whose last `endMs` ≤ `startMs`, else open a new lane. Lane count equals maximum
observed concurrency. Lanes are labeled as computed concurrency lanes — deliberately not
claimed to be Gradle worker ids.

**Artifact wiring.** The template gains a `Timeline` section plus a
`/*__BUILDHOUND_TIMELINE_JS__*/` placeholder inside its own `<script>` element;
`ReportAssets.template()` (`buildhound-report/src/main/kotlin/dev/buildhound/report/ReportAssets.kt:14-17`)
splices `timeline.js` in, so `template()` keeps returning the complete self-contained
document and the existing zero-network test automatically covers the new code.
`render()` and the finalizer call site
(`buildhound-gradle-plugin/src/main/kotlin/dev/buildhound/gradle/TelemetryFinalizerAction.kt:128-136`)
are untouched — no plugin logic changes.

**Dashboard wiring.** `buildhound-server` adds `implementation(projects.buildhoundReport)`
(Gradle-free, dependency-free — nothing transitive arrives; mirrors the plugin's existing
dependency, `buildhound-gradle-plugin/build.gradle.kts:46`). `DashboardAssets` loads
`dev/buildhound/report/timeline.js` from the classpath at startup (missing resource fails
startup, not a request — `DashboardRoutes.kt:42-46`), served at `GET /timeline.js` with
the same headers; `web/index.html` adds `<script src="/timeline.js">` before
`dashboard.js`. `detailView` inserts the timeline between the summary chips and the task
table. Both call sites wrap the renderer in `try/catch` (plus a
`typeof buildhoundTimeline === "function"` guard in the dashboard): a renderer defect
degrades to a missing section, never a blank detail page/artifact — the client-side
analogue of the plugin's never-fail rule.

## 4. Implementation steps

1. Create `buildhound-report/src/main/resources/dev/buildhound/report/timeline.js`:
   `buildhoundTimeline(tasks)` with greedy lane assignment, SVG construction, colors,
   dimming, min-width, tooltips, axis ticks, and input clamping as designed above.
2. Extend `report-template.html`: timeline `<section>` (element id `timeline`), the
   `/*__BUILDHOUND_TIMELINE_JS__*/` placeholder script element, a render-IIFE call that
   appends the SVG and a "max parallel" chip inside `try/catch`, hiding the section when
   the renderer returns `null` or throws.
3. Extend `ReportAssets`: add `TIMELINE_PLACEHOLDER` constant; `template()` splices the
   `timeline.js` resource into the raw template and refuses leftover placeholders.
4. Update `ReportAssetsTest`: spliced template contains the timeline function and no
   placeholder residue; `timeline.js` contains no `</script` sequence (it is spliced into
   a script element); existing zero-network and escaping tests keep passing unchanged.
5. Add `buildhound-report` node-harness test (`TimelineScriptTest` + a
   `timeline-smoke.js` resource, modeled on the server's `DashboardScriptTest`): drives
   the pure lane algorithm and SVG output in a real JS engine; skips when node is absent
   (CI runners have it).
6. `buildhound-server/build.gradle.kts`: add `implementation(projects.buildhoundReport)`.
7. `DashboardRoutes.kt`: load `timeline.js` into `DashboardAssets`, serve
   `GET /timeline.js` with `dashboardHeaders()`; `web/index.html`: add the script tag.
8. `web/dashboard.js` `detailView`: guarded timeline section + lane chip.
9. Update `buildhound-server/src/test/resources/web/dashboard-smoke.js` to load
   `timeline.js` into the VM context and assert the detail view renders an SVG timeline;
   add a harness case with the global absent asserting graceful omission.
10. Extend `DashboardRoutesTest`: `/timeline.js` served with the JS content type, CSP and
    caching headers, bytes identical to the report-module resource.
11. Extend `BuildHoundSettingsPluginFunctionalTest`'s artifact test (`:336-346`): the
    rendered artifact contains the timeline section marker (ArtifactTransformReport's
    grep-the-rendered-HTML smoke gate, [comparison-to-spec.md §2.7](../research/comparison-to-spec.md)).
12. Docs, same PR: `docs/architecture.md` §1 dependency rule amended (buildhound-report is
    the shared payload-rendering channel; plugin and server may both depend on it, it
    depends on nothing) + decision-log row; spec §3.8 one-line clarification (lanes
    computed from start/end overlaps); bracketed correction on plan 012's stale
    timeline-deferral note (`docs/plans/implemented/012-dashboard.md:73-75`).

## 5. Test strategy

- **Pure logic (node harness, report module):** sequential tasks → 1 lane; two
  overlapping tasks → 2 lanes; touching intervals (end == next start) share a lane;
  zero-duration tasks get min-width bars; empty array → `null`; hostile input
  (negative duration, non-finite `startMs`, 5 000 tasks) renders without throwing —
  the phase's failure-injection contribution lives here and in the smoke-harness
  error cases.
- **Report unit:** placeholder splice, no-`</script`-in-timeline.js, zero-network scan
  over the spliced template (existing test, now covering timeline code), render
  escaping unchanged.
- **Server:** `DashboardRoutesTest` for the new route/headers; `dashboard-smoke.js`
  detail view with timeline present, with timeline global absent, and with a
  throwing renderer stub (detail page still renders).
- **Plugin functionalTest (TestKit, CC on):** rendered artifact contains the timeline
  section for a real multi-task build — pins the splice end to end under
  configuration cache.
- **Golden files:** none — no schema change; existing contract tests untouched.

## 6. Risks

- **CC / isolated projects:** no new plugin code paths — the finalizer already renders
  the template; risk limited to template size growth. The existing CC-on functional
  tests cover the artifact path; nothing configuration-time is added.
- **Module coupling:** server → buildhound-report is a new dependency edge. Mitigated:
  the module is dependency-free by rule (architecture §1), the edge is
  resources-plus-one-object, and the rule change lands in the decision log. The rejected
  alternative — duplicating the renderer with a sync test — is permanent copy drift.
- **Honesty of "worker lanes":** computed lanes show concurrency, not scheduler
  assignment. Labels and docs say "computed from overlaps"; spec §3.8 is clarified so
  nobody later assumes the `worker` field is populated (it is not — plan 038 territory).
- **XSS:** task paths are attacker-influencable (task names). They reach the SVG only
  via `textContent` on `<title>` nodes; all attributes are numeric or fixed strings;
  both surfaces keep their CSP backstops (artifact meta CSP, dashboard hash-pinned CSP).
- **Rendering scale:** one `<rect>` per task; a 10k-task build draws slowly but does not
  hang, and payload task-array bounds arrive with plan 019. Accepted at pilot scale.
- **Security/privacy:** no new data collected, no new payload fields, no token or auth
  changes; `/timeline.js` is public static content like `/dashboard.js` (contains no data).

## 7. Exit criteria

- A real build of this repo produces an artifact whose timeline shows lanes and bars for
  the build's tasks, offline (no network requests, template zero-network test green).
- On the compose stack, the dashboard build-detail page for an ingested build renders the
  same timeline from stored data, with a lane-count chip; `/timeline.js` serves with CSP
  headers and the smoke harness passes all three timeline cases.
- `./gradlew build` green, including the new node-harness tests on a node-equipped
  runner; no golden files added or changed. Architecture §1 + decision log, spec §3.8,
  and plan 012's correction land in the same PR.
