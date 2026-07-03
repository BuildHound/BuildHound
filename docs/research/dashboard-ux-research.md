# Dashboard UX Research: Develocity Build Scans and Tuist

Research date: 2026-07-03. Sources: 27 screenshots of Develocity (ge.solutions-team.gradle.com build scans) and Tuist (tuist.dev) dashboards in docs/comparison-images/.

This document merges three groups of Develocity build-scan analyses (18 screens across two scans: a micronaut-starter build and an android-cache-fix-gradle-plugin build) and one group of Tuist analyses (9 screens of the DynaDroid/test-nowinandroid Gradle project) into a single reference for designing the BuildHound dashboard. It closes with concrete recommendations and a reconciliation against `docs/plans/012-dashboard.md` and the dashboard section (§6) of `docs/build-telemetry-spec.md`.

---

## 1. Screen-by-screen inventory

### 1.1 Develocity — the single-build "scan" product

Develocity's unit of analysis is one build. Every screen lives inside the same three-zone shell: a top utility bar carrying the build's identity (status check, project name, task invocation, timestamp, a "Copy Build Scan ID" action, a link back to the Build Scans list, help, and sign-in), a fixed left sidebar of fourteen sections (Summary, Console log, Failures, Deprecations, Timeline, Performance, Tests, Projects, Dependencies, Build dependencies, Plugins, Custom values, Switches, Infrastructure) with a teal-gradient highlight on the active item, and a content pane that is the only thing that changes as the user navigates. A Compare block ("From scans list", "With Build Scan", "See before and after") is pinned to the sidebar on every section.

**Summary.** The landing page is a vertical stack of cards. The header card shows tag chips (CI, branch/investigation name, OS), a plain-language timing sentence ("Started on 1 Mar 2023 at 20:09:55 CET, finished on 1 Mar 2023 at 20:33:46 CET, 23m 52s total build time"), the toolchain line (Gradle 8.0.1, Gradle Enterprise plugin 3.12.3), a composite-build note, and cross-links that pivot to filtered scan lists (CI run build scans, CI workflow build scans, Git commit id build scans) or out to external systems (GitHub Actions build, GitHub source). Below it: a "0 failures" card with reassuring copy ("This build did not contain any failures."), a "0 build deprecations" card, a top-tasks card ("26 tasks executed in 2 projects in 23m 40.314s") listing the slowest tasks by duration, a build-time breakdown card (Initialization & configuration 13.495s vs Execution 23m 38.007s), and a tests card ("41 tests in 10 test classes executed in 1 project in 23m 4s serial time...") that also surfaces "Estimated realized savings" feature statuses (Predictive Test Selection DISABLED, Test Distribution UNAVAILABLE). Every card ends in an "Explore console log / timeline / performance" deep link into the matching sidebar section.

**Console log.** A full-height monospace log viewer inside the product chrome: search icon, line count ("565 lines"), an amber non-blocking warning about truncated long lines with a "Download raw" escape hatch, ANSI-style semantic coloring preserved (green "BUILD SUCCESSFUL in 23m 51s"), and the closing "19 actionable tasks: 19 executed" line. The value is that raw and structured views share the same navigation, so pivoting between them never loses scan context.

**Timeline.** A Gantt-style swimlane of task execution (one dominant teal bar for the 23-minute test task dwarfing everything else) synchronized with a full task table below. Controls: filter and search icons, zoom in/out/fit buttons, a range-brush slider for the visible window, an "Order: Execution" sort dropdown, and a "Group by" segmented control (None | Type | Project). Table columns are Task path, Started after, Duration, and Task type (fully qualified Gradle class names), each with a "?" tooltip; NO-SOURCE rows are dimmed grey. An amber notice explains that resource-usage capture needs plugin 3.18+ — graceful degradation instead of an empty pane.

**Performance.** The densest section, with its own horizontal sub-tab bar (Build | Configuration | Dependency resolution | Task execution | Build cache | Daemon | Network activity) — a two-level navigation pattern (sidebar section plus sub-tabs). The tabs are almost entirely ledger-style: indented label hierarchies on the left, right-aligned high-precision durations on the right.

- *Build*: total build time split into Initialization / Configuration / Execution / End of build, plus GC time and peak heap ("190.4 MiB/1.6 GiB (10.9%)" — used/total plus percentage in one string).
- *Configuration*: serial configuration time broken into script compilation, model configuration, and task-graph calculation; a "Total tasks 106" breakdown by creation phase; and a cost-ranked table of 29 scripts and plugins with a Script/Plugin kind column, self time next to aggregate total ("0.849s (1.596s total)"), application scope, and per-row expand chevrons.
- *Dependency resolution*: a nearly empty page rendering just the one collapsible metric group it has (0.232s split across configuration/execution phases) with no filler content.
- *Task execution*: the work-avoidance breakdown that is the analytical spine of the product — a tree of count + percentage + duration triples: All tasks 26; Tasks avoided 0 (0.0%) split into From cache / Up to date; Tasks executed 19 (73.1%) split into Cacheable 6 (23.1%) 23m 39.733s, Not cacheable 13 (50.0%) 0.540s, Unknown cacheability; then Lifecycle / No source / Skipped rows, fingerprinting cost, and "Avoidance savings 0.000s (0.0%)" split by Up to date / Local build cache / Remote build cache. Category labels are teal links that pivot to the filtered Timeline task list. Zeros are rendered explicitly so layouts stay comparable across builds.
- *Build cache*: cacheability and remote-cache configuration as a hierarchical key/value list — requested-from-cache counts, hit/miss splits with inline percentages ("Miss: 6 (100%)"), stored-to-cache counts, remote cache type/push/URL/auth settings, and operation timings (Miss 6 in 0.484s, Store 6 in 1.343s at 697.1 KiB, Pack/Unpack stats).
- *Daemon*: two label/value rows ("Previous builds run by this daemon: 0", "Other daemons running at time of build: 0") and nothing else — sparse data gets a sparse page, no placeholder illustration.
- *Network activity*: aggregate metrics (5 requests, 0 B downloaded, 1.164s serial time) above an itemized request table ("5 HEAD requests from 1 repository") with method, middle-truncated URL, and right-aligned duration.

**Tests.** Three sub-tabs sharing an identical scaffold: a natural-language bold-number summary sentence, an "Execution" task selector with a dual-thumb range slider, and a table. *Overview* is an expandable tree (task > class > method) with color-coded outcome text (green PASSED, gray SKIPPED) and right-aligned durations. *Slowest tests* is a flat ranked list of the same data — each row stacks test name (as a link), class, and owning task in one multi-line cell. *Test acceleration* is an ROI/upsell view: feature statuses in uppercase gray (DISABLED / UNAVAILABLE) and savings columns (Total savings, TD savings) that render even when empty. Filter state persists across the sub-tabs.

**Projects.** A structural inventory: a heading ("19 projects (1 included build)"), an expandable hierarchy with a "+18" collapsed-children badge on the root, and gray classification pills ("included build" on buildSrc). No metrics — purely orientation.

**Dependencies / Build dependencies.** The same expandable-tree component parameterized by grouping dimension. Dependencies groups project → configuration → dependency coordinates, with a four-facet filter bar (free-text Search plus Resolutions, Projects, and Configurations inputs), a bold summary sentence ("499 dependencies from 5 repositories resolved across 40 configurations in 13 projects" with "5 repositories" hyperlinked), per-configuration resolution times inline on group headers (annotationProcessor 0.648s), requested → resolved version arrows including rich constraint syntax ("log4j-core:{require 2.17.1; reject [2.0, 2.17.1]} → 2.25.3"), and gray pills for resolution type ("platform", "constraint"). Build dependencies reuses the identical tree grouped by build-script file (build.gradle 0.019s, settings.gradle 0.043s) and — being a small dataset — omits the filter bar entirely. Faceted filtering appears only where the dataset earns it.

**Custom values.** A flat two-column key/value list of build-injected metadata ("13 custom values" with a search icon): task-scoped fingerprint hashes, CI run/workflow identifiers, and Git branch/commit/repository — the provenance that powers the summary page's cross-links. Long hashes are ellipsis-truncated.

### 1.2 Tuist — the fleet/analytics product

Tuist's unit of analysis is a project over time. Its shell is a top breadcrumb bar (org / project, with a "Gradle" project-type badge and switcher carets), a fixed left sidebar with collapsible groups (Overview; Builds > Build Runs; Tests > Test Runs / Test Cases / Flaky Tests / Quarantined Tests / Shards; Gradle Cache; Previews; Bundles; Project Settings), and a main column of stacked rounded section cards. Nearly every page instantiates the same template: a KPI stat-card row, a large trend chart, and a searchable/sortable/filterable table, scoped by an "Environment: Any" dropdown and a "Last 30 days" calendar picker in the top right.

**Project Overview.** Three KPI cards — Cache hit rate 68.5% with a green "+100.0% since last month" delta chip, Average build time 62.1s with a *red* +100.0% chip (regression coloring is semantic, not sign-based), and Average test time as a "No data yet" empty card — followed by a full-width cache-hit-rate line chart (0–100% y-axis, 30-day x-axis), an empty-state Tests section (skeleton chart, "Runs: no data yet", a "Get started" docs link, disabled "View more"), and the top of a Builds section with its own scoping controls. Overview is effectively a composition of miniature Builds and Tests pages with "View more" drill-downs.

**Build Runs list.** A card with a toolbar (search input, "Sort by: Ran at" dropdown, Filter dropdown) over a wide, horizontally scrollable table: Project, Status (green-check "Passed" / red "Failed" pills), Branch (git-branch glyph), Commit SHA, Requested tasks, Built by (purple avatar chip "dylandyna"), Duration (clock icon prefix). Rows cover real runs — "test → Failed → 28.4s", "assembleDebug → Passed → 270.9s" — and are the drill-down entry into a single build.

**Tests pages (Test Runs, Test Cases, Flaky Tests, Quarantined Tests, Shards).** All captured in the empty state, which is itself instructive: each page renders its full skeleton — three or four KPI cards (the grid adapts to metric count), a ghost trend chart, and a ghost table — with contextual copy ("No flaky tests", "No sharded runs" rather than a generic "no data") and a "Get started" external documentation link. Each list ships a domain-appropriate default sort ("Sort by: Flaky runs", "Sort by: Last run", "Sort by: Last ran at"). The taxonomy is notable: test health is decomposed into runs, cases, flaky, quarantined/muted/skipped, and shards (with average shard count and shard *balance* as first-class metrics). The Shards page drops the trend chart — a lighter template variant for lower-priority features.

**Gradle Cache.** Remote-cache analytics: KPI cards for average cache hit rate (the card title itself is a dropdown to switch metric), cache downloads (2.1 KB), and cache uploads (586.8 MB); a multi-series percentile line chart with an Average / p99 / p90 / p50 color-coded legend and a Line | Scatter-plot segmented toggle; and a "Recent Builds" section pairing a per-build hit-rate bar chart with a table of the same rows (Project, Hit Rate, Built by, Duration, relative "10m ago" timestamps).

**Builds analytics.** Four KPI cards (Total builds 7, Build success rate 85.7% green, Failed builds 1 red, Avg. build duration 53.3s red) where clicking a card selects it and drives the chart below; a percentile build-duration time series; a "Configuration Insights" card slicing build duration by a selectable dimension ("Type: Gradle version" showing one horizontal bar for Gradle 9.4.0); and a Recent Builds section with side-by-side Successful (6, purple) vs Failed (1, red) counts above per-build duration bars, including one visible ~4-minute outlier.

---

## 2. Common UX patterns across both products

Despite operating at different granularities, the two products converge on a striking number of patterns.

**Stable three-zone shell.** Both use a persistent top identity bar, a fixed left sidebar for primary navigation, and a content pane that is the only mutable region. Users never lose orientation: in Develocity the scan identity (project, tasks, timestamp, status) stays in the header across all fourteen sections; in Tuist the org/project breadcrumb and sidebar stay put across every analytics page.

**Two-level navigation.** The sidebar picks the concern; a horizontal secondary control refines within it. Develocity uses sub-tab bars inside dense sections (Performance's seven tabs, Tests' three). Tuist uses collapsible sidebar groups with nested sub-items (Tests > five sub-pages). Neither product nests deeper than two levels.

**A repeatable page template.** Each product has one dominant page skeleton it parameterizes. Develocity: bold natural-language summary sentence → control cluster (sort, search, expand/filter) → tree or table. Tuist: KPI card row → trend chart → toolbar (search + sort + filter) → table. Both templates degrade gracefully — Develocity's Build dependencies drops the filter bar for small data, Tuist's Shards page drops the trend chart.

**Numbers presented in plain language.** Develocity's signature device is the bold count-summary sentence ("499 dependencies from 5 repositories resolved across 40 configurations in 13 projects") with embedded hyperlinks for drill-down. Tuist's equivalent is the big-number KPI card with a delta chip. Both make aggregates scannable without chart chrome.

**Ledger-style metric layout.** Indented label hierarchies with right-aligned, high-precision values (0.484s, 23m 10.263s) dominate Develocity; Tuist right-aligns durations in tables. Both render zero and empty values explicitly rather than hiding rows, keeping layouts comparable across builds and time ranges.

**Cost-ranked ordering.** Slowest tasks, most expensive scripts/plugins, slowest tests, flakiest tests — every list defaults to descending cost so the biggest offender is the first row, and each list carries a domain-appropriate default sort.

**Drill-down everywhere, one click deep.** Develocity: "Explore …" links from summary cards, chevron expanders on rows, teal-linked category labels that pivot to *filtered* detail views (cacheability categories link into the Timeline task list), and cross-links to scan lists sliced by CI run or commit. Tuist: "View more" buttons on overview cards, clickable table rows, and KPI cards that act as metric switchers driving the chart below.

**Semantic status color and iconography.** Green check/PASSED/success, red for failures, amber for warnings and degraded capability, gray for skipped/no-op/disabled. Tuist adds the important refinement that delta color encodes *goodness*, not sign: +100% cache hit rate is green, +100% build duration is red.

**Honest degraded and empty states.** Develocity replaces missing telemetry with amber upgrade notices ("Resource usage capturing is supported with version 3.18+…") and renders sparse pages sparsely (the two-row Daemon tab). Tuist ships full ghost-skeleton empty states with contextual copy and "Get started" docs links, embedding onboarding in the dashboard itself. Both prefer explanation over blankness.

**Scoping and filter controls attached to the data, not buried in settings.** Develocity: per-section faceted filter inputs, search icons, group-by segmented controls, range-brush sliders, and a shared filter scaffold that persists across sub-tabs. Tuist: Environment dropdown + time-range picker repeated top-right on every page, plus per-table search/sort/filter toolbars.

**Long-string discipline.** Both middle- or end-truncate URLs, hashes, and test names with ellipses while preserving distinguishing prefixes, keeping column layouts stable.

**Contextual help.** Develocity attaches "?" tooltips to nearly every derived metric and column header (Serial execution factor, Avoidance savings, Started after); Tuist puts an info icon on every KPI card. Neither relies on a separate documentation panel for metric definitions.

---

## 3. Where the two products diverge

**Unit of analysis: one build vs the fleet.** Develocity's entire IA is oriented around a single build scan explored through fourteen lenses; cross-build questions are answered by pivoting out to filtered scan lists or the Compare feature. Tuist inverts this: the dashboard is fleet-first (trends, KPIs, percentiles over 30 days) and the individual build is a table row you drill into. BuildHound needs both, and the screenshots essentially hand us the design for each half.

**Depth vs breadth of telemetry.** Develocity exposes extraordinary single-build depth — build-cache pack/unpack timings, per-script configuration cost, per-request network activity, dependency-resolution constraint algebra, a raw console log. Tuist shows far shallower per-build data but broader fleet analytics: percentile series, success rates, dimension slicing by Gradle version, and a rich test-health taxonomy (flaky/quarantined/muted/skipped, shard balance) that Develocity does not lead with.

**Ledgers vs charts.** Develocity is almost chart-free outside the Timeline Gantt: its default presentation is the indented definition list and the dense table. Tuist is chart-forward: every page leads with a time-series visualization, with chart-type toggles and percentile legends. This follows directly from the unit of analysis — a single build has little to plot over time; a fleet has little meaning without it.

**Comparison model.** Develocity makes scan-to-scan comparison a persistent first-class affordance (Compare block on every screen, "See before and after"). Tuist has no explicit compare; comparison is implicit in trend lines and delta chips against last month.

**Density and tone.** Develocity is an expert instrument: monospace precision, fully qualified class names, millisecond durations, upsell expressed as honest UPPERCASE feature-status words (DISABLED / UNAVAILABLE) with pre-built empty savings columns. Tuist is a product dashboard: rounded cards, avatar chips, relative timestamps ("10m ago"), and onboarding links woven into empty states. Develocity assumes an investigating engineer; Tuist assumes a team lead glancing at health.

**Navigation chrome.** Develocity's sidebar is flat (fourteen peer sections per scan); Tuist's is hierarchical (collapsible groups per domain). Develocity relies on sub-tab bars for second-level structure; Tuist relies on sidebar nesting.

---

## 4. Recommendations for the BuildHound dashboard

BuildHound's spec (§6) already envisions both halves: fleet pages (Overview/Bottlenecks, Trends, Tasks explorer, Kotlin, Tests) and a per-build page (Build detail mirroring the HTML artifact). The recommendations below split into what the v0 dashboard (plan 012) should adopt now and what should wait, with design guidance drawn directly from the observed patterns.

### 4.1 MVP (plan-012 scope: builds list, build detail, trends)

1. **Builds list — copy Tuist's Build Runs table.** Columns: status pill (green check Passed / red Failed), branch with a glyph, short commit SHA, requested tasks, mode, duration (right-aligned, clock prefix optional), and a relative "ran at" timestamp. Toolbar: search-free for v0 is fine, but keep the branch/mode/outcome filters visually grouped as a toolbar above the table, defaulting to newest-first. Rows are the drill-down into build detail. Keep the table horizontally scrollable inside its card rather than letting the page scroll.

2. **Build detail — Develocity's Summary structure at reduced depth.** Lead with a natural-language bold-number summary sentence ("142 tasks executed in :app and 11 other modules in 4m 12s") above the summary chips the plan already specifies (outcome, branch, mode, toolchain, config-cache state). Then stack: (a) a duration-sorted task table with outcome column, where UP_TO_DATE / SKIPPED / NO_SOURCE rows are dimmed grey (Develocity's no-op de-emphasis) and FAILED rows read red; (b) a cache/work-avoidance ledger using the count + percentage + duration triple (All tasks → avoided [from cache / up to date] → executed [cacheable / not cacheable]) with zeros rendered explicitly — BuildHound's task-outcome enum maps one-to-one onto this breakdown, and it is the single most valuable screen in Develocity's Performance section. Right-align every duration; keep millisecond precision.

3. **Trends — Tuist's template, hand-rolled.** The plan's bars (builds/day, failures) plus lines (avg duration, hit rate) fit Tuist's chart-over-time pattern. Two cheap additions with outsized value: a small KPI strip above the charts (build count, success rate, avg duration, hit rate for the selected range) using big-number + label cards, and *semantic* delta/coloring rules — failure-related increases red, hit-rate increases green — even before delta chips exist. The plan's "red bar when the day has ≥1 failure" is an acceptable v0 stand-in for Tuist's success/failure split legend.

4. **Empty and degraded states are MVP scope, not polish.** BuildHound's dashboard is useless until a plugin ingests builds, so the first thing every pilot user sees is the empty state. Follow Tuist: contextual copy ("No builds ingested yet for this filter") plus a get-started pointer (the plugin snippet or docs link), never a blank pane. Follow Develocity for partial data: where a feature awaits a schema field (the timeline) or a connector, show an amber one-line notice explaining what would unlock it rather than hiding the section.

5. **Explicit zeros and stable layouts.** Render "From cache 0 (0.0%) 0.000s" rather than omitting the row. This keeps the build-detail ledger visually comparable across builds — the property that makes Develocity's Task execution tab readable at a glance — and it costs nothing.

6. **Plain-language summary sentences as section headers.** Adopt the bold count-sentence device on both list ("312 builds on main in the last 30 days") and detail pages. It is cheap to generate server-side or client-side and is the most distinctive readability pattern in either product.

7. **Truncation and precision discipline.** Middle-truncate long task paths and commit messages; keep full values in `title` attributes; right-align all durations with consistent formatting (ms below 1s, `s` below 1m, `Xm Y.ZZZs` above). Both products treat this as foundational.

### 4.2 Post-MVP (v1 dashboard per spec §6)

1. **Timeline Gantt with synchronized table** once the schema carries task start offsets (the spec's payload already defines `startMs` and `worker`; plan 012 records the deferral). Follow Develocity: swimlane by worker lane, zoom + range brush, an order/group-by control (None | Type | Module), and dimmed no-op rows. The standalone HTML artifact (spec §3.8) already plans "task timeline by worker lane" — build the component once and share it.

2. **Overview/Bottlenecks page as the landing view**, Tuist-style: KPI cards with "since last period" delta chips (semantically colored), a headline trend chart, and "View more" links into Builds/Trends/Tests — the spec's "what regressed in 7d" framing maps exactly onto this template.

3. **Percentile-aware trend charts.** The spec commits to p50/p95 duration trends and to "Tuist's line/scatter toggle pattern"; adopt the Average/p99/p90/p50 color-coded legend and the segmented Line | Scatter toggle as seen on Tuist's cache and builds pages.

4. **Dimension slicing ("Configuration Insights").** A picker that breaks a metric down by Gradle version, JDK, agent, or module directly serves the Tasks explorer and toolchain-drift questions. Start with one dimension and a horizontal bar chart, as Tuist does.

5. **Clickable category pivots.** When the cache ledger says "Not cacheable 13", make it a link to the task table filtered to those tasks — Develocity's teal-label pivot is the highest-leverage drill-down pattern observed and fits hash-encoded filter state.

6. **Comparisons.** Adopt Develocity's persistent compare affordance (pick build A from the list, compare with build B) when the spec's Comparisons page lands in v1/v1.x. Requested→resolved-style arrow notation is a good compact diff device for the comparison view (durations, hit rates, toolchain versions), even though BuildHound does not trace dependency resolution.

7. **Tests taxonomy, incrementally.** Tuist's decomposition (runs → cases → flaky → quarantined, with shard metrics) is the roadmap for BuildHound's Tests and flaky-detection pages (spec §5, v1.x). Start with slowest classes and failures (matching the per-class rollup granularity that is locked), and reuse the ranked "top offenders" list pattern from Develocity's Slowest tests tab — multi-line cells stacking test name / class / task keep the table narrow.

8. **Cross-linking as navigation.** Once the CI connector lands, replicate Develocity's provenance links: from a build detail to "all builds of this pipeline", "all builds of this commit", and outbound to the CI run URL (`ci.buildUrl` is already in the payload). Custom values/tags deserve a flat key/value section on build detail, mirroring Develocity's Custom values screen.

9. **Help tooltips on derived metrics.** `cacheableHitRate`, `avoidedMs`, `criticalPathMs`, and `parallelUtilization` are exactly the kind of derived numbers Develocity annotates with "?" tooltips. Add them when the metrics appear in the UI; a `title` attribute is an acceptable v0 form.

### 4.3 What *not* to copy

- **A console-log viewer**: BuildHound deliberately does not ingest logs; the CI provider owns them. Link out instead.
- **Develocity's fourteen-section sidebar for build detail**: at BuildHound's telemetry depth, a single scrolling detail page with anchored sections (or three to four tabs at most) is more honest than fourteen mostly-thin sections.
- **Dependency-resolution views**: explicitly a v1 non-goal in the spec.
- **Upsell-style DISABLED/UNAVAILABLE feature blocks**: as an OSS tool BuildHound has no paid tiers to advertise; use the amber "what would unlock this" notice pattern instead, which carries the same honesty without the sales framing.

---

## 5. Alignment with plans/012-dashboard.md

The v0 plan is well aligned with the research; most divergences are already recorded as explicit deferrals rather than gaps.

**Aligned.**
- The three views (builds list, build detail, trends) map cleanly onto the two products' core screens: Tuist's Build Runs table, Develocity's Summary/task-table/cache-summary trio, and Tuist's trend template respectively.
- The plan's "summary chips + duration-sorted task table + cache summary" is exactly Develocity's information design at appropriate depth; the same-as-artifact framing matches Develocity's principle of one information design reused across surfaces.
- Hand-rolled SVG charts, no CDN, CSP hardening — orthogonal to UX but compatible with both products' patterns, none of which require heavy chart libraries at v0 scope (Tuist's percentile charts are the only visualization that would strain hand-rolled SVG, and those are post-MVP).
- The trends bars-plus-lines design with branch/mode filters follows the Tuist template; the spec (§6) already names Tuist's line/scatter toggle as the v1 direction, so v0 is a consistent stepping stone.
- Outcome badge classes with an allowlist (the plan's hardening round) line up with the semantic status-pill pattern both products use.

**Recorded deferrals the research endorses — with priority notes.**
- *Timeline deferred*: consistent with the plan's note that schema v1 task entries lack start offsets. However, both the roadmap and the spec's HTML artifact treat the worker-lane timeline as core, and Develocity demonstrates it is the single most legible "where did the time go" view. The additive `startMs` schema field should be the first post-v0 schema change, ahead of the pipeline filter.
- *Pipeline filter deferred*: matches the query-API note; Tuist shows Environment scoping as a dropdown pattern that the pipeline filter can slot into later without layout changes.
- *Filter state in function arguments, not the hash*: acceptable for v0, but the research strengthens the case for hash-encoded state as the first follow-up — both products lean heavily on shareable, deep-linkable filtered views (Develocity's Copy Build Scan ID and filtered scan-list pivots; Tuist's per-page scoping), and category-label pivots (recommendation 4.2.5) require it.
- *Red bar on failure days instead of stacked bars*: a reasonable simplification of Tuist's success/failure split; revisit when the Overview page lands.

**Genuine gaps in the plan relative to the research (small, mostly cheap).**
- **Empty states are not mentioned.** The plan's exit criteria assume ingested builds exist. Given that every pilot user starts at zero, the Tuist-style contextual empty state with a get-started pointer should be added to v0 scope (recommendation 4.1.4). This is the one recommendation that conflicts with the plan by omission rather than by deferral.
- **No KPI strip on trends.** Both products lead with headline numbers before charts; a four-number strip is a low-cost addition inside the existing view (recommendation 4.1.3) and previews the spec's Overview page.
- **No summary-sentence headers.** Not in the plan, trivially additive, and the most characteristic Develocity readability device (recommendation 4.1.6).
- **Dimmed no-op task rows.** The plan specifies a duration-sorted task table but not outcome-based row de-emphasis; adopting Develocity's grey treatment for UP_TO_DATE/SKIPPED/NO_SOURCE rows makes the executed work stand out at no structural cost.
- **Token-paste auth UX** has no analogue in either product (both have real sign-in). The plan's recorded trade-off (pilot audience owns the token; sessionStorage only) stands; just ensure the token-entry state is styled as a deliberate first-run screen rather than an error, borrowing the empty-state tone.

Nothing in the plan contradicts the observed patterns outright; the plan errs on the side of shipping less of the template, and the research mainly argues for pulling three inexpensive patterns forward (empty states, summary sentences, explicit-zero ledgers) while confirming that the expensive ones (timeline, percentiles, comparisons, dimension slicing) belong exactly where the spec already placed them.
