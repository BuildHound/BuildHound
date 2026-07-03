# build-process-watcher (Build Process Watcher)

A GitHub Action by cdsap (Iñaki Villar) that samples JVM-level metrics of Gradle and Kotlin build processes during CI jobs — heap, RSS, GC time, JIT compilation, and class loading — entirely from outside the build, using JDK CLI tools instead of any Gradle API or Java agent.

Research date: 2026-07-03. Source: shallow clone of https://github.com/cdsap/build-process-watcher.

## Overview

Build Process Watcher answers the question "what were the Gradle daemon, Kotlin daemon, and Gradle worker JVMs doing, memory-wise, while my CI job ran?" without asking the build to cooperate. A single YAML line adds the action to a workflow; its main step launches a detached bash sampler, the user's build steps run as normal, and the post step tears the sampler down and publishes results. In Local mode the results are workflow artifacts (SVG charts, CSV, JSON, a pipe-delimited log) plus a Mermaid flowchart in the GitHub job summary. In Remote mode each sample is also streamed to a Go backend on Google Cloud Run, backed by Firestore with a 24-hour TTL, and rendered on a live Plotly dashboard at `https://process-watcher.web.app/runs/{runId}`; runs can optionally be exported to BigQuery when they finish. The README pins usage at `cdsap/build-process-watcher@v0.6.2` and the project carries a GitHub Marketplace badge.

The central finding for BuildHound is that this project uses zero Gradle APIs. There is no plugin, no BuildService, no Tooling API, no build listener, no JMX, and no agent injection anywhere in the repository. Everything is observed externally through `jps`, `jstat`, `jinfo`, and `ps`. That makes it direct prior art for exactly one piece of the BuildHound spec — the §3.6 ProcessProbe — and an instructive contrast for everything else.

## Status & maturity

The project is an active, working beta at personal-project scale. The last commit in the shallow clone is `3b216bf` ("fix: improve Mermaid summary contrast") from 2026-07-02 17:46:07 -0700, one day before this research; full history is unavailable because the clone is shallow.

Test investment is real and broader than typical for a side project. There are 11 Jest test files in `__tests__/` covering report generation, Mermaid output, monitor metrics, frontend assets, and accessibility; Playwright specs in `tests/e2e/` (mobile comparison flow against a static server) and `tests/browser/` (unavailable-run handling); and Go tests throughout the backend, including `backend/integration_test.go`, `backend/main_test.go`, and unit tests for storage, handlers, models, the export queue, and the BigQuery exporter. The CI entry point `.github/workflows/ci-tests.yml` fans out to reusable backend, action-build, frontend-validation, and e2e workflows, and the e2e and local-mode test workflows run the action against itself (`uses: ./`), which is a genuinely good self-hosting smoke test.

The README is accurate to the code, including the Local/Remote behavior matrix and the inputs table. `AGENTS.md` documents maintenance conventions for AI coding agents, including an explicit backward-compatibility policy: compare, replay, and dashboard flows must keep accepting JSON exported by older runs, and missing metrics must be treated as valid legacy data.

Signs of personal scale remain visible: extremely verbose debug logging throughout `monitor_with_backend.sh` (113 `log_script` calls in 854 lines) and the Go handlers (which log every raw ingest payload); stale alternative action entry points; hardcoded development-fallback secrets in the backend; an unauthenticated token-minting endpoint plus a leftover unauthenticated `/test` endpoint; and a Firestore model that rewrites a run's entire document on every ingest. Licensing is also inconsistent: the README declares MIT, `package.json` says ISC, and there is no LICENSE file in the repository — worth resolving before copying any code verbatim. It is a solid CI observability gadget, but the backend is not multi-tenant production infrastructure.

## Architecture

The system has four loosely coupled pieces: an action wrapper, a bash collector, a post-step reporter, and an optional cloud backend with a static frontend.

**Action main step** (`src/index_with_backend.ts`, 262 lines). Resolves inputs, exports run state (RUN_ID, LOG_FILE, backend/frontend URLs, flags) via `core.exportVariable` with backup marker files written to `RUNNER_TEMP` (e.g. `.build-process-watcher-backend-url`), sets step outputs (`run_id`, `backend_url`, `frontend_url`, `remote_monitoring`, `export_to_bigquery`), prints the dashboard URL, and then spawns `monitor_with_backend.sh` with `spawn(..., { detached: true })` plus `child.unref()` so the monitor keeps sampling while the user's subsequent build steps run. The default backend is a hardcoded Cloud Run URL; the default frontend is `https://process-watcher.web.app`.

**Bash collector** (`monitor_with_backend.sh`, 854 lines). Loops every `interval` seconds (default 5). Each iteration runs `jps` and exact-matches JVM main class names against the pattern list `("GradleDaemon" "KotlinCompileDaemon" "GradleWorkerMain")` (line 8). For each match it samples `jstat -gc` (line 751), `jstat -compiler` and `jstat -class` via a header-name-to-column mapping helper (lines 752–753), and RSS via `ps -o rss= -p PID` (line 756). For newly seen PIDs it captures VM flags once with `jinfo -flags` (`get_vm_flags`, lines 239–330). Samples are appended as pipe-delimited lines to a local log file, and in Remote mode each process's line is POSTed individually to `{backend}/ingest` as `{"run_id": ..., "data": "<pipe-delimited line>"}` with a per-run bearer token obtained from an unauthenticated `POST /auth/run/{runId}` (`get_auth_token`, ~line 180), refreshed and retried once on HTTP 401. EXIT/TERM/INT traps (lines 591–593) also invoke the cleanup step with a `CLEANUP_FROM_TRAP=true` marker so trap-driven cleanup skips artifact upload.

**Action post step** (`src/cleanup.ts`, 1,407 lines). Kills the monitor via `monitor.pid` (line 869), marks the run finished through `POST /finish/{runId}` with a direct firebase-admin Firestore fallback if the backend API fails (the fallback needs `GOOGLE_APPLICATION_CREDENTIALS`, so in practice it is a dev-environment path), parses the log, and generates all Local-mode outputs: hand-rolled SVG charts, CSV, a replay-compatible JSON export (shape defined in `src/lib/report.ts`), and a Mermaid flowchart summary (`src/lib/mermaid.ts`) appended to `GITHUB_STEP_SUMMARY`. Artifacts are uploaded with `@actions/artifact`, and a `cleanup.lock` file (line 758) prevents double execution when both the post step and a bash trap fire.

**Go backend + static frontend**. `backend/main.go` (Go 1.23, plain `net/http`, no framework) registers `/healthz`, `/auth/run/` (mint token), `/ingest` (validates the HMAC token, then re-parses the pipe-delimited string server-side via `storage.ParseData`, which accepts 6-, 7-, or 14-column records), `/runs/` (read API polled by the dashboard), `/finish/` (sets `finished`, sets `expire_at` to now+24h for the Firestore TTL policy, triggers BigQuery export), `/cleanup/stale` (admin-secret-gated janitor marking runs with no updates in 5 minutes as finished, `backend/internal/cleanup/cleanup.go`), and a leftover unauthenticated `/test` endpoint. It deploys to Cloud Run via `backend/Dockerfile` and a `workflow_dispatch` workflow. The frontend is framework-free static HTML/CSS/JS on Firebase Hosting; `frontend/public/runs/[runId].html` (2,701 lines) polls `GET /runs/{id}` on a user-configurable refresh timer (default 60 s, persisted in localStorage) and renders Plotly charts, with Plotly 2.35.2 loaded from `cdn.plot.ly` (line 9). `replay.html` and `compare.html` accept uploaded JSON exports, so they need no backend — though they still pull Plotly from the CDN. Shared series-building logic lives in `frontend/public/compare-shared.js` (1,762 lines).

A note on entry points: only `action.yaml` (root) is real — `runs.using: node24`, main `dist/index_with_backend.js`, post `dist/cleanup.js`, which are exactly the two bundles `build.sh` produces with `@vercel/ncc`. The `start/action.yml` (node20) and `composite/action.yml` variants reference `dist/index.js`, which the build never creates, so both are broken; `cleanup/action.yml` (node20) points at the existing `dist/cleanup.js` but is likewise unmaintained.

## Data collected & how

All data comes from JDK attach-adjacent CLI tools plus `ps`; no Gradle, Kotlin, or JVM-internal API is used anywhere.

- **Process discovery:** `jps`, exact match on main class names `GradleDaemon`, `KotlinCompileDaemon`, `GradleWorkerMain` (`monitor_with_backend.sh` lines 8 and 664–700). Liveness is checked with `kill -0` and a `ps -p` fallback (`process_exists`, lines 92–105).
- **Heap:** from `jstat -gc`, used = EU + OU and capacity = EC + OC (awk fields 5–8), i.e. eden plus old generation only — survivor spaces and metaspace are excluded, and "capacity" is committed eden+old, not `-Xmx` (lines ~763–771).
- **GC time:** cumulative YGCT + FGCT (awk fields 14 and 16, lines ~777–790). The concurrent-GC time column (CGCT) that G1/Z report on modern JDKs is not included, so concurrent collection cost is undercounted despite a code comment claiming collector-independence.
- **RSS:** `ps -o rss= -p PID` (line 756) — portable across Linux and macOS runners, no `/proc` parsing.
- **JIT counters:** `jstat -compiler` (Compiled, Failed, Invalid, Time) and **class-loading counters:** `jstat -class` (Loaded, Unloaded, Time), both via `jstat_named_values` (lines 107–141), which parses the jstat header row and maps requested column names to indexes in awk, emitting `N/A` for absent columns instead of failing — a genuinely useful JDK-version-portability trick.
- **VM flags:** `jinfo -flags` once per newly seen PID (`get_vm_flags`, lines 239–330), filtered to flags beginning with `-XX:` (line 302), stored per run in the Firestore `processes` collection and in a local `.process_info` file. Because of the filter, non-`-XX` arguments such as a literal `-Xmx4g` are dropped, though `jinfo` surfaces the effective value as `-XX:MaxHeapSize=...` anyway.
- **Run lifecycle metadata:** run_id, start/end/finished timestamps, finished flag, and the export flag (`backend/internal/models/models.go`).

Each sample row is keyed by elapsed time (HH:MM:SS), PID, and process name; cumulative counters are stored raw and rates (e.g. classes/s) are derived between actual observations in the frontend.

## Outputs & integrations

**Local mode** produces workflow artifacts built in `src/cleanup.ts`: the pipe-delimited `build_process_watcher.log`, hand-rolled dependency-free SVG charts (`memory_usage.svg` with per-process RSS, heap, and an aggregated-RSS line; `gc_time.svg`; and conditionally `jit_compilation.svg` and `class_loading.svg`), a CSV export, and a JSON export whose shape (`{ samples[], process_info{pid: {name, vm_flags}}, finished }`, `src/lib/report.ts`) doubles as the replay/compare payload schema. The job summary gets per-process max/avg/last statistics plus a Mermaid flowchart of at most six representative checkpoints (`MAX_CHECKPOINTS = 6` in `src/lib/mermaid.ts`) showing per-process and aggregated metrics over time — a clever zero-dependency way to get a chart-like visual into `GITHUB_STEP_SUMMARY`.

**Remote mode** adds the live dashboard URL, 24-hour data retention via a Firestore TTL field set at finish time (`backend/internal/storage/storage.go`, ~line 293), and optional BigQuery export on finish: sample rows and process/VM-flag rows stream into `build_process_samples` and `build_process_processes` tables (schemas in `backend/schema/*.sql`), made idempotent with deterministic `insertId`s (`backend/internal/bigqueryexport/export.go`). The export deliberately runs synchronously in the request path because Cloud Run throttles CPU after a response is sent (explicit comment in `backend/internal/exportqueue/queue.go` lines 43–44), with three attempts, exponential backoff, and a Firestore re-read before each insert.

**Offline analysis** is served by `replay.html` (replay a single exported run) and `compare.html` (two-run overlay and side-by-side with a shared timeline), both driven by uploaded JSON exports with no backend dependency.

## Techniques worth borrowing for BuildHound

This repo validates the mechanism proposed for BuildHound's §3.6 ProcessProbe — enumerate JVMs matching the daemon/worker class names, sample `jstat -gc`, capture configured flags — and goes further by demonstrating that continuous time-series sampling at a 5-second cadence is cheap and practical, something the spec defers to v1.x. `scripts/benchmark-jstat-metrics.sh` even measures per-sample cost of each jstat mode against a live PID, which is ready-made evidence for an overhead budget.

Concrete techniques to steal:

1. **The jstat header-mapping trick** (`jstat_named_values`): resolve column names to indexes from the header row at runtime and degrade to `N/A` per column. This is the right way to survive jstat output differences across JDK versions without version-sniffing.
2. **`jinfo -flags` for the configured-vs-used heap delta** the spec wants — but fix the `-XX:`-only filter, and note that the effective max heap appears as `-XX:MaxHeapSize`.
3. **The GC-time caveat**: YGCT + FGCT undercounts concurrent collectors. BuildHound should read the GCT total column or add CGCT, and the spec's `jcmd GC.heap_info` fallback remains wise.
4. **`ps -o rss=` as the RSS source** — portable across Linux and macOS runners, unlike the Linux-only `/proc` approach the spec mentions.
5. **The replay/compare-from-exported-JSON UX** as a model for BuildHound's Comparisons page: a stable JSON export schema that both the live dashboard and fully client-side replay/compare pages consume, with an enforced backward-compatibility policy (AGENTS.md) so old exports keep working.
6. **Chart robustness details**: forward-filled series with a gap cutoff of 2× the median sampling delta so dead processes don't paint flat lines (`src/cleanup.ts` lines 137–166 and 256; mirrored in `compare-shared.js`), and aggregated-RSS tail suppression when only one small process remains (`src/cleanup.ts` lines 401–417).
7. **Main/post action lifecycle discipline**: detached monitor plus PID file, post-step kill and report, bash traps as a second line of defense with a marker to skip duplicate artifact upload, and a lock file to prevent double cleanup. BuildHound's CI assets will face the same start/stop-around-someone-else's-steps problem.

Equally valuable as a catalog of anti-patterns the BuildHound spec already avoids: one HTTP POST per process per sampling interval carrying a pipe-delimited string that the server re-parses (versus one gzip JSON payload with a typed kotlinx-serialization schema); an unauthenticated per-run token mint (versus project-scoped ingest tokens); Firestore whole-document rewrites (versus Postgres/TimescaleDB hypertables); and CDN-loaded Plotly (versus the locked zero-CDN standalone artifact).

Architecturally the two systems are complementary rather than competing: Build Process Watcher watches from outside the build with no Gradle APIs at all, while BuildHound instruments from inside. An outside-the-build sampler in this style could later feed BuildHound's `/v1/metrics` side channel from any CI step without requiring the Gradle plugin.

## Limitations & pitfalls

- **Zero build-semantics visibility.** No tasks, no cache outcomes, no duration attribution — only process-level JVM metrics correlated by wall-clock time. It cannot say which task caused a memory spike; BuildHound's inside-the-build data is needed for that.
- **Approximate heap and GC math.** Heap used/capacity is eden+old only (no survivors, no metaspace); capacity is committed memory, not `-Xmx`; GC time omits CGCT so concurrent collectors (G1, Z) are undercounted.
- **GitHub Actions-only.** Orchestration, artifacts, and summaries are hardwired to GHA (`GITHUB_STEP_SUMMARY`, `@actions/artifact`, `RUNNER_TEMP`), and the tooling requires `jps`/`jstat`/`jinfo` on PATH with attach permission to the build JVMs — availability the README itself hedges on.
- **Chatty ingest protocol.** One POST per process per interval, payload re-parsed server-side from a pipe-delimited string (`storage.ParseData` accepting 6/7/14 columns); request count is O(processes × samples) and slow networks stretch the effective sampling interval because sends are sequential within the loop.
- **Firestore anti-pattern.** All samples for a run live in a single document that is read, appended to, and fully rewritten on every ingest (`StoreSamples` in `storage.go`) — racy under concurrent writers and bounded by Firestore's 1 MiB document limit; retention is fixed at 24 hours.
- **Weak security model.** Anyone who knows or guesses a run_id can mint a valid write token from the public `/auth/run/{id}` endpoint; `JWT_SECRET_KEY` and `ADMIN_SECRET` fall back to hardcoded dev defaults in `backend/internal/auth/auth.go`; tokens are called JWTs but are a custom base64(JSON)+HMAC-SHA256-hex format; a stray unauthenticated `/test` endpoint remains registered.
- **Stale entry points and metadata drift.** `start/action.yml` and `composite/action.yml` reference a `dist/index.js` that the build never produces; `package.json` still calls the package `build-process-monitor` at version 1.0.0 while the README pins v0.6.2; README says MIT while `package.json` says ISC and no LICENSE file exists.
- **Sampling blind spots.** The 5-second default can miss short-lived `GradleWorkerMain` processes entirely, and VM-flag capture drops non-`-XX` arguments.
- **CDN dependency.** All chart pages, including the "offline" replay/compare pages, load Plotly 2.35.2 from `cdn.plot.ly` — directly contrary to BuildHound's locked zero-CDN artifact decision if imitated.

## Notable files

| Path | Why it matters |
| --- | --- |
| `monitor_with_backend.sh` | The entire collector: pattern list (line 8), jstat column mapping (107–141), VM-flag capture (239–330), heap/GC math (~763–790), token auth and per-sample POSTs, trap-based cleanup |
| `src/index_with_backend.ts` | Main step: input handling, env-var + marker-file state handoff, detached monitor spawn |
| `src/cleanup.ts` | Post step: kill, finish (with Firestore fallback), all SVG/CSV/JSON artifacts, Mermaid summary, lock-file reentrancy guard |
| `src/lib/report.ts` | Canonical JSON export shape consumed by replay/compare |
| `src/lib/mermaid.ts` | Six-checkpoint Mermaid flowchart summary trick |
| `action.yaml` | The only working action definition (node24) |
| `backend/main.go` | All routes, including the leftover `/test` endpoint |
| `backend/internal/auth/auth.go` | Custom HMAC token scheme with hardcoded dev-fallback secrets |
| `backend/internal/storage/storage.go` | Doc-per-run append-by-rewrite model, `ParseData`, 24h TTL |
| `backend/internal/exportqueue/queue.go` | Synchronous-in-request-path BigQuery export rationale (Cloud Run CPU throttling), retries |
| `backend/internal/bigqueryexport/export.go` | Idempotent streaming inserts via `stableInsertID` |
| `frontend/public/runs/[runId].html` | 2,701-line live dashboard; Plotly CDN at line 9 |
| `frontend/public/compare-shared.js` | Shared forward-fill/gap/series logic for dashboard, replay, and compare |
| `scripts/benchmark-jstat-metrics.sh` | Per-sample jstat cost measurement — evidence for a probe overhead budget |
| `AGENTS.md` | Agent-maintenance conventions and the legacy-data compatibility policy |
