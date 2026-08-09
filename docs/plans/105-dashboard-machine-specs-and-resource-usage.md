# 105 — Machine specs and build resource usage on the dashboard build detail

## 1. Source

Owner follow-up (2026-08-09): *"Ensure that plan 104 also is added in the server dashboard and not
only on the local HTML report."* Plan 104 shipped the collection and rendered it **only** in the
standalone artifact, listing "Dashboard and `buildhound-mcp` surfaces" as out of scope
(`104:66`). This plan closes the dashboard half.

Anchors: spec §6 (Dashboard → Build detail is specified as a "mirror of HTML artifact"),
§3.2/§3.6 (the fields), §3.7 (residual quasi-identifiers), plan 048 (the precedent: the identical
job for Failure/Warnings, frontend-only).

## 2. Scope

**In**

- `dashboard.js` `detailView` gains a **Machine** section mirroring the report's: hardware chips
  (os·arch, cpus, memory, disk media + free/total, max workers, CI runner chips) and a
  **resource-usage** chip row (daemon CPU utilization over the execution window, window length,
  system CPU load, load average, system memory used/total) with the report's honesty caveat.
- A **CPU** column on the existing Process snapshot table (`processes[].cpuTimeMs`).
- Node smoke-harness coverage; one server round-trip test pinning that the detail response really
  serializes the new blocks.

The pre-104 fields (`os`, `arch`, `cores`, `ramMb`, `workersMax`) are **in scope even though they
predate 104**: rendering them was plan 104's own Tier 0, the report shows them, and a lone disk chip
beside a `"30 % of 8 cores"` figure whose core count appears nowhere is worse than no section.

**Explicitly out of scope**

- **Any server Kotlin change for data delivery.** `GET /v1/builds/{buildId}` already responds the
  whole `BuildPayload` (`Routes.kt:582`); `PayloadScrubber.scrub` and `boundForStorage` both `copy()`
  without naming `environment`/`resourceUsage`/`processes`, and storage is one opaque `payload jsonb`
  column. The fields are already on the wire, merely unread — exactly plan 048's finding. No
  endpoint, projection, schema field, migration, or OpenAPI change (`openapi.yaml:97` documents the
  response as "The build payload", with no schema).
- **Cross-build aggregation** — a machine/runner cohort view or a resource-usage trend needs an
  ingest-time projection into a hot table, unlike this per-build render. Deliberate follow-up, same
  reasoning plan 048 recorded for warnings aggregation.
- **Build-list columns / `BuildSummary`** — a store + projection change, not a render.
- **`buildhound-mcp`.**
- **DESIGN-V2 runtime adoption.** `web/index.html:7-66` is still the original pre-V2 stylesheet (raw
  hex, `system-ui`, zero `--bh-` tokens), as is every existing dashboard section. `DESIGN-V2.md:411`
  and CLAUDE.md both gate runtime V2 adoption on a separate implementation plan with regression
  tests, so this plan renders in the page's existing idiom (`.chips`, `.chips li`, `.muted`,
  `td.num`) rather than half-converting one section of a 113 KB pre-V2 surface. No
  `<link>`/`<img>`/`url()`/`@import`/webfont/relative asset reference is introduced and no CSP
  style-hash test is weakened. Unlike plan 104, the divergence is **also** recorded in
  `docs/brand/DESIGN-V2.md` itself, which is what CLAUDE.md actually asks for — 104 recorded it in
  its plan file only. Counter-precedent this plan deliberately does not repeat: plan 101 (`101:30`)
  promised "New dashboard UI follows DESIGN-V2", shipped pre-V2 markup, and reconciled nowhere.

## 3. Design

Port, do not share. A new `machinePanel(build)` in `dashboard.js` follows the `warningsPanel`
null-or-fragment idiom, is appended in `detailView` between the Work-avoidance ledger and the
Process snapshot, and returns `null` when neither chip row has children.

*Corrected in review:* an earlier draft of this plan said that made the section invisible on a
pre-104 payload. It does not, and should not — the hardware chips read `environment` fields present
since schema v1, so a pre-104 payload with an `environment` block renders the hardware row and no
usage row, which is precisely the Tier-0 rendering this plan set out to deliver. Only a payload with
neither machine-relevant `environment` data nor `resourceUsage` hides the section. A dedicated smoke
fixture now pins that distinction in both directions.

Extracting a shared `machine.js` the way `timeline.js` is shared would
mean refactoring already-merged report code (an IIFE writing into fixed template ids) into a pure
function and rewriting its smoke assertions, for a ~70-line presentation port; the cheaper drift
guard is keeping the two harnesses' assertion **strings byte-identical**, so a divergence fails one
of them. Recorded here so the shared-module option is a deferred decision, not an oversight.

The same applies to the utilization math. Plan 104 §3.1 put `DerivedMetricsCalculator` in commons
"so it is unit-tested once and shared by the report and any later dashboard", and this is that later
dashboard — yet it recomputes the percentage in JS. Not an oversight either: utilization is
deliberately *not* shipped on the payload, so consuming the Kotlin would require the server-side
projection this plan excludes, and the Kotlin has no equivalent of the absolute-duration fallback
the chip needs when window or cores are unknown. The clamp is kept identical to
`coerceIn(0.0, 1.0)` on both sides so the two agree wherever both apply.

Existing helpers only — `el()`, `ms()`, `chipItem()`, and `memMb` (already byte-parity with the
report's `sizeMb`). **No new CSS**: `style-src` is pinned to a hash of the served bytes and plan 103
fails the boot on a stray `<style` open, so the section reuses existing classes. **No `<svg>`**: the
harness asserts the detail page renders zero SVG when the timeline global is absent.

Guards ported verbatim from `report-template.html`, each with its comment:

- `daemonCpuMs != null && daemonCpuMs >= 0` — `null >= 0` is **true** in JS, so a bare range check
  renders an unknown as `0 %`, fabricating the zero plan 104's whole contract forbids.
- The two-sided `Math.min(1, Math.max(0, …))` clamp mirroring
  `DerivedMetricsCalculator.daemonCpuUtilization`'s `coerceIn(0.0, 1.0)`, and the **different**
  fallback label (`"daemon cpu time"`, an absolute duration) when window or cores are unknown.
- The null-prototype `MEDIA_LABELS` map; the independent media/capacity disk gate; the
  both-halves system-memory gate; the `value == null || value === ""` chip skip.

Two deliberate divergences: the CI binding is named `ciAttributes` (`detailView` already binds `ci`
to the `/ci-run` connector response — a different object), and `ci.attributes` values are clamped to
64 chars at render, mirroring `RunnerAttributes.MAX_VALUE_LENGTH`. That cap is plugin-side only —
`PayloadCapper` excludes `ci.attributes` and spec §3.7 puts `ci.*` outside the ingest scrub by
design — so an ingest-scoped token can POST a multi-KB value into an unbounded `.chips li`. Not an
XSS (everything goes through `el()`/`textContent`, the plan-012 no-innerHTML rule), a layout bound.

## 4. Test strategy

- **`dashboard-smoke.js`** (node, executed by `DashboardScriptTest`): canned builds covering the
  rich case (values copied from `golden/build-payload-v1-machine.json` so both surfaces assert the
  same arithmetic), a hostile `runnerClass` string landing as text with zero `<script>` nodes and a
  bounded chip value, an explicit JS `null` `daemonCpuMs` with window+cores present proving **no
  `0 %` chip** (the wire never sends `null` — `explicitNulls = false` — so only a hand-written
  fixture can prove this guard), a negative delta, an overshoot clamping to 100 %, a missing-cores
  fallback label, and the existing missing-optionals build proving the section stays absent. A chip
  parser is needed: the DOM stub's `textContent` returns a node's own text, and label/value are
  separate children. Assertion strings mirror `report-smoke.js` byte-for-byte.
- **`ApplicationTest`**: POST→GET round trip asserting the new blocks survive to the detail
  response, mirroring the plan-044/048 test — the one hop the fetch-stubbing harness cannot cover.
  Honest scope: it runs on `InMemoryBuildStore`, so it pins the **response**, not the jsonb round
  trip.
- Untouched and green: the CSP hash-parity and no-inline-script tests, `ReportAssetsTest`,
  `report-smoke.js`, `OpenApiContractTest`. Both node harnesses `assumeTrue(nodeAvailable())` — a
  green local build may have run zero of these assertions, so CI is the verdict.

## 5. Risks

- **Privacy (§3.2 review).** Nothing new is collected. `machine.diskTotalMb` is named in spec
  §3.7 as a **stronger** residual quasi-identifier than the `cores`/`ramMb` beside it — high-entropy
  and stable per machine — and its prescribed `pseudonymize=strict` bucketing remedy is plugin-side
  and unimplemented, so the raw value renders with no coarsening path. The field is however already
  stored and already returned by `GET /v1/builds/{buildId}` today: this plan changes
  **discoverability**, not collection or API exposure — extending plan 032's "same exposure class as
  data already stored" reasoning, since no document yet covers read-surface exposure of residuals.
- **Exposure boundary.** Reads are scope-gated and tenant-scoped (a foreign build 404s), but the
  page shell is public by design and plan 101 persists `read` tokens in `localStorage` with no
  revocation API (manual SQL). A machine-stable fingerprint now sits behind a long-lived browser
  credential. Accepted, named rather than implied.
- **Drift between the two renderers** — mitigated by byte-identical assertion strings, not by
  structure. If they diverge again, the shared-module refactor becomes the answer.
- **CSP/style-hash boot failure** if CSS is added carelessly — avoided by adding none.

## 6. Exit criteria

1. Build detail renders machine specs, resource usage, the process CPU column and runner chips for
   an ingested plan-104 payload; renders the hardware row alone for a pre-104 payload carrying an
   `environment` block; and hides the section entirely for a payload carrying neither.
2. No server Kotlin change other than the round-trip test; no endpoint/schema/migration/OpenAPI
   change.
3. `./gradlew build` green on CI (node present, so the new harness assertions actually execute);
   CSP and no-inline-script tests unchanged.
4. Spec §6 gains an "As built (plan 105)" sentence; `docs/brand/DESIGN-V2.md` gains the runtime
   adoption-status note covering the report and the dashboard.
5. §3.1 reviews (`frontend-reviewer` for the web assets, `kotlin-gradle-reviewer` for the test) and
   the mandatory §3.2 security & privacy review complete; findings fixed or accepted in the PR.
