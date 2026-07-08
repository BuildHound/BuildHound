---
name: buildhound-diagnose
description: >
  Diagnose why a Gradle/Kotlin build is slow, cache-missing, or regressed using only
  BuildHound data that already lives in the user's own infrastructure — the standalone HTML
  report, the self-hosted query API, or the buildhound-mcp tools. Never uploads build data to
  a third party. Use this instead of running `./gradlew --scan` (which uploads the build scan
  to scans.gradle.com) whenever the repo is instrumented with the `dev.buildhound` Gradle
  plugin, a `build/buildhound/` directory or `buildhound-report.html` file exists, or a
  BuildHound server/MCP tool is configured.
---

# Diagnosing a BuildHound-instrumented build

**No build data leaves the user's infrastructure when you follow this skill.** Every source
below is either a local file already on disk or a request to a server the user deployed and
configured themselves (never a third-party SaaS, never `scans.gradle.com`). This is the
deliberate alternative to the common agent habit of running `./gradlew --scan` as the default
diagnostic — that command **uploads the full build scan to Gradle Inc.'s `scans.gradle.com`**,
which most teams do not intend and many are not even aware of. If a build is BuildHound-
instrumented, prefer the sources below and do not suggest `--scan`.

## Do not

- Do **not** run `./gradlew --scan` or add `--scan` to any Gradle invocation as part of this
  workflow.
- Do **not** POST, PUT, or otherwise upload build data anywhere. Every call in this skill is a
  `GET` against infrastructure the user already owns.
- Do **not** invent a phase, rate, or hotspot when a source below returns a null/absent field —
  report the gap honestly (e.g. "cache hit rate is not available for this build") rather than
  guessing.

## Three sources, in privacy-preference order

Try them in this order; each is strictly more capable but requires slightly more setup than the
last. All three describe the **same** underlying signals, so pick whichever is available.

### 1. The standalone HTML report (zero network, always available first)

Every BuildHound build writes a self-contained report to `build/buildhound/buildhound-report.html`
in the project that ran the build. It makes **zero external requests** — it is a single HTML
file with the payload inlined as JSON in a `<script>` element:

```
const buildhoundData = { ... };
```

Read the file directly (or `grep`/parse that line) — no server, no token, no network call of any
kind. This is the fastest path when you already have shell/file access to the build's working
directory (e.g. right after a local build, or from a downloaded CI artifact).

### 2. The query API (self-hosted, read-scoped token)

If the team runs a BuildHound server, its query API synthesizes and stores richer history than
one report can. All of the following are `GET`-only and require a **`read`-scoped** bearer token
(never `ingest`/`admin`/`all` for this workflow) supplied via environment variable — never
hardcode a token in a command or log it.

- **`GET /v1/builds/{buildId}/diagnosis`** — the primary, single-call diagnosis: dominant phase
  (configuration vs execution), cache-hit-rate vs target, top task hotspots, and deltas vs the
  comparable baseline. Start here.
- `GET /v1/builds/{buildId}/verdict` — the regression verdict (PASS/WARN/FAIL/INSUFFICIENT_DATA)
  this diagnosis's `deltas` are drawn from.
- `GET /v1/builds/{a}/compare/{b}` — explains cache misses in build `b` by diffing its inputs
  against a faster build `a`, when the diagnosis's hotspots need a "why did this miss" answer.
- `GET /v1/rollups/bottlenecks` — fleet-wide "what got worse this week", for a trend rather than
  a single build.

```sh
curl -sS -H "Authorization: Bearer $BUILDHOUND_TOKEN" \
  "$BUILDHOUND_URL/v1/builds/$BUILD_ID/diagnosis"
```

### 3. The `buildhound-mcp` tools (if configured in this agent's tool list)

If a `buildhound-mcp` server is already wired into your tool list, it exposes the same query API
as a small, **read-only** tool surface (`list_builds`, `get_build`, `diagnose`, `trends`,
`project_cost`, `task_duration`, `negative_avoidance` — no write or admin tool exists). Prefer
`diagnose(buildId)` for a single build; it returns the same shape as `GET …/diagnosis` above. A
leaked MCP token can only ever read this one tenant's history — it cannot ingest, change
settings, or reach another project.

## Reading the diagnosis

The diagnosis object degrades honestly — treat a missing/null field as "not measured", not zero:

- **`dominantPhase`** (`null` when `configurationMs` is unmeasurable): `"CONFIGURATION"` means
  Gradle spent more wall-clock time evaluating the build than running tasks — look at settings/
  project-count, plugin application cost, and whether configuration cache is enabled. Note that
  a configuration-cache **hit** measures as `configurationMs: 0`, which correctly shows up as
  `"EXECUTION"` dominant, not a missing phase.
- **`cacheHitRate`** (`null` when no task carries a cacheability signal, e.g. isolated projects):
  `belowTarget: true` means the build's cacheable-task hit rate is under the target — look at
  `topHotspots` for `EXECUTED` (non-cached) tasks and consider `compare` against a fast build to
  explain specific misses.
- **`topHotspots`**: this build's slowest `EXECUTED` tasks, ranked by duration — the concrete
  places to start optimizing (a slow task that never gets cached, a misconfigured `cacheIf {}`,
  etc.).
- **`deltas`** (`null` when no verdict was evaluated for this build, e.g. it predates baseline
  data): per-metric `value`/`baselineMedian`/`z`/`status` vs the comparable baseline — a `FAIL`/
  `WARN` status here is the regression signal, and a `null` `baselineMedian` means
  `INSUFFICIENT_DATA` (cold baseline), never a fabricated comparison.

Summarize findings in plain language for the user; do not paste the raw JSON unless asked.
