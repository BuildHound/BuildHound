# 105 — Trends screen: real charting library

## 1. Source

Feature request: *"For the trends screen in the dashboard use a proper charting library to
render the charts in correct and interactive way."*

Binding context: `docs/brand/DESIGN-V2.md` §3 (tokens, status semantics), §5 (measurement grid,
spacing), §8 (interaction/motion), §9 (accessibility) — mandatory for product dashboards per
`CLAUDE.md`. DESIGN-V2 §12.8 requires runtime V2 adoption to happen through an implementation
plan with regression tests; this plan is that vehicle, **chart-scoped only**.

## 2. Scope

### The problem being fixed

The hand-rolled SVG renderers (`dashboard.js:920-1099`, ~90 LOC) are not merely plain — they
render **incorrectly**:

1. **The x-axis is index-based, not time-based** (`:932-933`, `:984-986`). Days with no builds are
   absent from the response, so a 3-week gap and a 1-day gap are drawn the same width. Every
   trend slope on the screen is currently a lie about time.
2. **Null gaps are drawn as connectors** (`:937-947`, `:988-998`). The `value == null` branch
   `return`s, but `path` is already non-empty, so the next point continues the same subpath with
   `L` — a missing cache-hit-rate day renders as a straight line *through* the gap.
3. **`cohortChart` scales each cohort's x independently** (`stepX` recomputed per series inside the
   loop, `:984`). Two cohorts with different active-day counts are stretched to the same width and
   overlaid as if directly comparable. This is a wrong-conclusion bug, not a cosmetic one.
4. **No y-axis, no ticks, no gridlines, no labels** anywhere. A value is only readable by hovering a
   2.5 px dot, and only with a mouse.
5. **Colors contradict DESIGN-V2 status semantics**: cache-hit-rate is green (`:1080`) though cache
   state maps to *Cache-or-information* blue (§3:167); artifact size is violet (`:1157`), the
   *flaky* hue. Several series colors fail the 3:1 contrast floor for essential graphical objects
   (§9:294) — `#22c55e` measures 2.24:1 on light surface.
6. **Interaction is pointer-only.** `<title>` on a dot is not keyboard-reachable, violating §8:285
   and §9:300. The current charts do not satisfy the a11y gate today.

### In scope

- Vendor **uPlot 1.6.32** (MIT) and serve it at `/uplot.js` under the existing CSP.
- Rewrite the Trends screen's four charts on it: average build duration, cache hit rate, builds per
  day (failures highlighted), artifact sizes, plus the tag-cohort multi-series comparison.
- Migrate the one non-trends caller, `benchmarkView` (`dashboard.js:1487-1488`), and delete its
  `{day, durationMs}` shim — `trendChart` is removed, not left orphaned.
- DESIGN-V2 tokens for chart color/typography/grid/spacing, scoped to chart surfaces.
- The a11y layer required by §9:300 — per chart: `<figure>` + `<figcaption>` naming the encoding in
  words, an `sr-only` text summary, and a real `<table>` of plotted values.
- Provenance and attribution for the vendored bytes.

### Explicitly out of scope

- **`timeline.js` and `buildhound-report`.** `timeline.js` is served byte-identical to the report
  module's copy (`DashboardRoutesTest.kt:88-95`) and the report artifact is guarded by
  `ReportAssetsTest`. Its off-token colors (`FROM_CACHE` green, `EXECUTED` amber — both contradict
  §3:167/:171) are real, and need their own plan. The vendored library never enters
  `buildhound-report` in any form.
- **General dashboard V2 adoption** — badge palette, `.muted` at 3.49:1, `.delta-good/bad` conveying
  goodness by hue alone, page max-width, ThemeControl, type scale. Pre-existing; not charting.
- **A qualitative (categorical) series palette in DESIGN-V2** — see §5, Risk 1.
- Response compression and asset caching (`Cache-Control: no-cache` is deliberate).
- No server, schema, or API change. No new `/v1` route, so no `openapi.yaml` edit.

## 3. Design

### 3.1 Library choice — uPlot 1.6.32

MIT · `sha512-KIMVnG68zvu5XXUbC4LQEPnhwOxBuLyW1AHtpm6IKTXImkbLgkMy+jabjLgSLMasNuGGzQm/ep3tOkyTxpiQIw==`
(registry value, independently re-verified against the downloaded tarball) ·
`dist/uPlot.iife.min.js` 51,081 B, sha256 `19c8d4c6ad88929a79f4ae49d6f7161566dfd0ba3d15cc495e974f787eb78f1f` ·
`dist/uPlot.min.css` 1,857 B, sha256 `df630c6a8d6f8eeaff264b50f73ce5b114f646ffd9a0bb74f049b0a00135fa04`.

Selected against uPlot, Chart.js, ECharts, Chartist, Frappe Charts, Observable Plot, billboard.js,
C3, Plotly, ApexCharts and d3 (full + submodules), each judged on **downloaded bytes**, not docs.

Why uPlot wins under this repo's constraints:

| Constraint | uPlot on the real bytes |
|---|---|
| CSP `style-src <hashes>` only — `setAttribute("style", …)` is blocked | **Zero `setAttribute` calls in the entire library.** Geometry is CSSOM (`el.style[name] = …`). This is the check that eliminated Chartist, which emits 14 `style` attributes per chart through an `.attr({style:…})` helper that a naive grep misses |
| No `'unsafe-eval'` | 0 × `eval(` / `new Function(` |
| `default-src 'none'` — no `img-src`, `worker-src`, `blob:` | 0 × `fetch` / `XMLHttpRequest` / `Worker` / `createObjectURL` / `toDataURL` / `import(` |
| Runtime `<style>` injection would be silently blocked | Creates only `canvas`, `div`, `table`, `tbody`, `thead`, `tr`, `th`, `td` — never `style`. No `insertRule` / `cssText` / `adoptedStyleSheets` |
| Stylesheet cannot be a `<link>` (`style-src` has no `'self'`) | 1,857 B of CSS with zero `url()` / `@import` / `@font-face` — pastes into the existing hash-pinned inline `<style>`, which rehashes from served bytes automatically (`DashboardRoutes.kt:36`). **No CSP change, so `DashboardRoutesTest.kt:45-46` stay untouched** |
| Apache-2.0 project | MIT |
| Bundle ships uncompressed (`no-cache`, no `Compression` plugin) | 51 KB — the cheapest real library in the field; the SVG alternatives that are CSP-clean cost 490 KB–1.1 MB |
| Purpose fit | Native time scale with date-aware ticks, native null gaps, drag-to-zoom, cursor/crosshair, legend with series toggle — i.e. bugs 1–4 above are fixed structurally, not patched |

**Recorded disagreement.** The library-research agent's own top recommendation was to vendor nothing
and extend the hand-rolled renderers, on the reasoning that SVG output is required to keep per-point
`<title>` tooltips. That reasoning does not hold: §9:300 requires a *text summary and
keyboard-reachable details*, which a `<title>` on a 2.5 px dot does not provide at all (§8:285).
The a11y gate is satisfied by the adjacent data table (§3.4) regardless of render technology, so
canvas is not a regression here — it is an improvement over the current pointer-only state. The
request is also explicit. Proceeding with uPlot.

Runner-up: **Chartist 1.5.0** (37 KB, real SVG, CSS-themeable) — blocked solely by the `style`
attribute emission above, which 1.5.0 offers no option to disable (`useForeignObject` is gone).
It would win if upstream stopped emitting those two literal `style` strings.

### 3.2 Serving and provenance

Follow the `/timeline.js` precedent exactly (`index.html:95`, `DashboardRoutes.kt:29`/`:135-141`):

- Vendored file at `buildhound-server/src/main/resources/web/uplot.js`. No `build.gradle.kts` change
  (default `processResources` copies the tree), no `Dockerfile` change (`COPY buildhound-server/`
  already covers it).
- `DashboardAssets.uplotJs` + `get("/uplot.js")` in `DashboardRoutes.kt`, same headers as
  `/dashboard.js`. A missing resource fails boot, not a request.
- `<script src="/uplot.js">` in `index.html` **before** `dashboard.js`, exposing the bare `uPlot`
  global — same shape as `buildhoundTimeline`.
- The vendored file carries a prepended header comment: upstream version, source URL, registry
  `dist.integrity`, the sha256 of the untouched bytes below the header, and the **full MIT license
  text** (the upstream minified header carries only a URL). A root `THIRD-PARTY-NOTICES.md` records
  the same — no such file exists in the repo today, and one vendored dependency is the moment to
  start it.
- No `gradle/verification-metadata.xml` entry: this is a vendored file, not a Maven resolution.

### 3.3 Chart rewrite

`dashboard.js:920-1099` is replaced. All chart code stays inside `dashboard.js` (no new module —
the repo serves flat files).

New helpers:

- `timeSeriesChart(spec)` — builds a uPlot time-scale chart. `spec` carries the series
  (label, value accessor, token name, dash pattern), the y formatter, and the figure caption.
  X values are **epoch seconds derived from `point.day`**, so real calendar spacing replaces index
  spacing (bug 1). Series arrays are aligned onto a **shared, gap-filled x domain** — the union of
  days across all series, with `null` for days a series has no value (bugs 1 and 3). uPlot's default
  `spanGaps: false` renders those as true gaps (bug 2). Y axis gets ticks, labels and grid (bug 4).
- `barChart(spec)` — builds the per-day builds/failures chart on uPlot's bars path, failure days
  taking the failure token.
- `chartFallbackTable(spec)` / `chartFigure(spec)` — the a11y layer (§3.4), always rendered.

**Honest nulls are load-bearing.** `TrendPoint.avgHitRate`, `ccHit`, `ccRequested`
(`BuildStore.kt:428-443`) distinguish `null` ("no data") from `0` ("observed, none"). No accessor
may coerce `null → 0`; a missing day must reach uPlot as `null` and render as a gap. Pinned by test
(§4).

**Cohorts** (`/v1/trends/cohorts`) become one `timeSeriesChart` with one series per cohort on the
shared x domain — the fix for bug 3.

**Benchmark** (`:1487-1488`) moves to `timeSeriesChart` with `startedAt` as the x value directly;
the `{day, durationMs}` shim that existed only to satisfy `trendChart`'s tooltip is deleted.

### 3.4 Accessibility (the merge gate, §9:300 / §8:285)

Every chart renders as:

```
<figure class="chart-card">
  <figcaption>…encoding stated in words…</figcaption>
  <div class="chart-plot">          ← uPlot mounts here; aria-hidden
  <p class="sr-only">…summary: range, direction, min/max…</p>
  <details><summary>Show values</summary><table>…</table></details>
</figure>
```

The `<table>` is the keyboard-reachable detail — a real focusable, tabular-numeral table of the
plotted values. Dots are **not** made focusable (2.5 px targets would violate §5:219 / §9:296).

This table is also the **fallback**: when the `uPlot` global is absent or its constructor throws,
the figure renders caption + summary + table and omits the plot. One mechanism serves the a11y
requirement and the degradation path, so the fallback is exercised by real users, not just tests.
This mirrors the existing `timeline.js` contract (`dashboard-smoke.js:661`, `:667`).

Also: `role="img"` + `<title>`/`<desc>` on informative graphics, decorative grid `aria-hidden`,
2 px `--bh-focus` outline at 2 px offset on chart controls (§8:281), a `prefers-reduced-motion`
block, and no meaning carried by hue alone (§3:173).

### 3.5 Tokens

uPlot's CSS is retokenized to DESIGN-V2 and pasted into the hash-pinned inline `<style>` in
`index.html`, alongside the token custom properties for both themes (§3:125-159). Series colors are
read from those custom properties via `getComputedStyle` **inside the chart path only** (guarded by
the `uPlot`-present check), so both themes track automatically and the smoke harness never needs
`getComputedStyle`.

| Surface | Token | Replaces |
|---|---|---|
| Average build duration | `--bh-neutral-solid` | `#3b82f6` (`:1078`) |
| Cache hit rate | `--bh-info-solid` (§3:167 — cache is *information*, not success) | `#22c55e` (`:1080`) |
| Builds/day bar, clean / with failures | `--bh-neutral-solid` / `--bh-failure-solid` | `#3b82f6` / `#ef4444` (`:1092`) |
| Artifact size | `--bh-neutral-solid` (never the flaky violet) | `#a855f7` (`:1157`) |
| Benchmark duration | `--bh-neutral-solid` | `#2563eb` (`:1488`) |
| Grid / axis / tick text | `--bh-grid` / `--bh-control-border` / `--bh-text-muted` | `#8886`, `#888` |
| Cohort series | `--bh-neutral-solid` + per-series dash pattern + direct labels — see Risk 1 | `COHORT_COLORS` (`:954`) |

The 32 px measurement grid (§5:221) goes **inside the chart plot area only**. Durations use the mono
role with tabular numerals (§4:188).

## 4. Test strategy

- **`dashboard-smoke.js` — three passes over the trends view**, closing the hole that a
  guard-and-fallback design would otherwise open (the plan-006 lesson the harness header cites):
  1. **global absent** → figure, caption, summary and value table render; no plot node.
  2. **global throws** → same, and no partial chart node is left behind.
  3. **global present as a recording stub** → assert `dashboard.js` calls uPlot with the right
     shape: series count, x values as epoch seconds matching `points[].day`, **`null` preserved as
     `null`** (never `0`), y values matching `avgDurationMs` / `avgHitRate * 100`, and cohort series
     aligned on one shared x domain. This pass needs no browser API and is where the real bugs live.
  The existing `countTag(app,"svg") >= 5` assertion (`:651`) is replaced by assertions on the figure
  and value table; the three timeline `svg` assertions (`:503`, `:661`, `:667`) are unaffected.
- **`DashboardScriptTest.kt`** — write `uplot.js` into the temp dir and pass it as a 4th argv
  (`:29`); the harness loads it only for pass 3.
- **`DashboardRoutesTest.kt`** — new case for `/uplot.js` mirroring the `/dashboard.js` one (200,
  `text/javascript`, UTF-8, CSP, `no-cache`). The `style-src` hash regex (`:45`) and the
  `no "unsafe"` assertion (`:46`) must stay **unchanged and green** — that is the proof the CSP was
  not widened. The existing style-block/hash parity test (`:98-112`) covers the enlarged inline
  `<style>`.
- **Vendored-bytes regression test** — assert the served `/uplot.js` contains no `setAttribute(`,
  `eval(`, `new Function(`, `document.write`, `innerHTML`, `createElement("style"`, `fetch(`,
  `XMLHttpRequest`, or `import(`. This is what makes a future version bump safe: the property that
  made uPlot selectable is asserted, not assumed.
- **`.github/workflows/ci.yml:288`** — add `/uplot.js` to the image resource smoke loop (the only CI
  reference to web assets; it exists because a bundled-resource regression shipped once).
- **Browser verification is mandatory, not optional.** `./gradlew` cannot see a CSP violation, a
  blocked style, or a NaN layout. Run the server, seed multi-day + tagged builds (no seeder exists —
  one build is one data point, and cohorts need tagged payloads, so a throwaway scratchpad seeding
  script is required), then load `#/trends` and check `read_console_messages` for CSP reports,
  verify both themes, keyboard reach into the value table, and screenshot.

## 5. Risks

1. **DESIGN-V2 defines no qualitative series palette.** It has seven *semantic* solids and forbids
   color-only meaning (§3:173) and copper in status (§3:174). Tag cohorts and per-artifact series
   are not statuses — a green cohort line falsely reads "success", violet reads "flaky". `CLAUDE.md`
   requires reconciling a divergence in the doc *before* coding it, so this plan does **not** invent
   six hexes and label them V2 tokens. Interim: neutral solid + dash patterns + direct labels, which
   needs no doc change and satisfies §3:173 better than color-coding does. A categorical palette is
   a follow-up plan against DESIGN-V2 itself.
2. **Supply chain.** 51 KB of third-party JS enters the served surface for the first time. Mitigated
   by: verified integrity recorded in-repo, the vendored-bytes regression test above, MIT license
   preserved in full, no build-time fetch, and no path into `buildhound-report`. Reviewed under the
   mandatory §3.2 security review.
3. **Canvas is opaque to the smoke harness.** Pass 3's recording stub proves the *call contract*,
   not pixels; only browser verification proves rendering. Stated as an accepted limit, with the
   browser step made mandatory rather than best-effort.
4. **Bundle grows to ~165 KB uncompressed** on a `no-cache` route with no server compression
   configured. Accepted; compression/caching is its own change.
5. **Blast radius beyond trends.** `benchmarkView` is migrated deliberately in this plan rather than
   discovered via a red test.

## 6. Exit criteria

1. Trends renders duration, cache-hit-rate, builds/day, artifact-size and cohort charts on uPlot,
   with a real time x-axis, labelled y-axis with grid, true null gaps, and one shared x domain
   across cohort series.
2. Hover crosshair with values, legend series toggle, and drag-to-zoom work in a browser.
3. Every chart is a `<figure>` with caption, `sr-only` summary and a keyboard-reachable value table;
   charts degrade to caption + summary + table when the global is absent or throws.
4. Chart color, grid, axis and type follow DESIGN-V2 tokens in both themes; cache-hit-rate is
   information-blue, not success-green; every mark clears 3:1 and every label 4.5:1.
5. `./gradlew :buildhound-server:test` green, including the three-pass smoke harness, the
   `/uplot.js` route test and the vendored-bytes regression test — with `DashboardRoutesTest.kt:45`
   and `:46` unchanged (CSP not widened).
6. Browser verification: `#/trends` loads with **zero CSP violations in the console**, both themes
   checked, keyboard reach into the value table confirmed, screenshots attached.
7. `benchmarkView` migrated; `trendChart`/`cohortChart`/`COHORT_COLORS` and the `{day, durationMs}`
   shim removed. `timeline.js` and `buildhound-report` byte-unchanged.
8. Provenance header + `THIRD-PARTY-NOTICES.md` present; `/uplot.js` in the CI asset smoke loop.
9. Code & architecture review (`frontend-reviewer` for JS/HTML, `kotlin-gradle-reviewer` for the
   Kotlin) plus the mandatory §3.2 security & privacy review, both in fresh contexts, findings fixed
   or explicitly accepted.
