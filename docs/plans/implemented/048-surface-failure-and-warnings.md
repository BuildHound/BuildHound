# 048 — Surface failure detail + warnings on the read surfaces

## 1. Source

Follow-up to plan 047, which *collected* build-failure detail (`failure.message` +
`failure.stackTrace`) and opt-in warnings (`extensions.internalAdapters.{deprecations,
logWarnings, droppedWarnings}`) into the payload but rendered almost none of it: the HTML
report gained only a Failure card, and the server dashboard surfaces neither. This plan
*renders* what 044 already collects. Touches spec §6 (dashboard) and §3.8 (HTML artifact);
adds an architecture decision-log row.

## 2. Scope

**In.** (A) Dashboard build-detail (`dashboard.js` `detailView`) gains a **Failure** section
(exception class + scrubbed message + stacktrace) and a **Warnings** section
(deprecations + log warnings + dropped count). (B) The HTML report (`report-template.html`)
gains the **same Warnings** section (its Failure card already shipped in 044).

**Out — and why it needs no server work.** No new endpoint, schema field, migration, or
server Kotlin logic. `GET /v1/builds/{buildId}` already responds the **whole** `BuildPayload`
(`Routes.kt:362-364`), re-encoded through the same `BuildHoundJson.payload`
(`encodeDefaults=true`) that content-negotiation installs (`Application.kt:191-192`), so
`failure.*` and `extensions.internalAdapters.*` are already on the wire; the report already
embeds the full payload JSON. Both surfaces simply never read those fields today. Explicitly
out: **cross-build warnings aggregation / trends** (a "warnings over time" view or a
dedicated endpoint) — that *would* need an ingest-time projection into a hot table (à la
`test_class_outcomes`) plus a rollup, and is a separable larger feature. Per-build failure
detail is per-build by nature and needs no aggregation.

## 3. Design

Both sections are **render-only**, in the two hand-written frontends. No shared-splice
mechanism (only the timeline renderer is shared that way); the Warnings block is ~15 lines
and is duplicated **byte-for-structure-identical** across the two surfaces so the dashboard
node smoke harness effectively covers the report too (§4).

**Track 1 — dashboard `detailView` (`buildhound-server/.../web/dashboard.js`).**
- **Failure section**, gated on **`build.failure` presence, not `outcome === "FAILED"`**:
  044's extraction is execution-phase only, so a config-phase failure is `FAILED` with **no**
  `failure` object (mirror the report's `if (d.failure)` guard exactly). Renders
  `exceptionClass || "Build failed"` + `": " + message`, and a `<pre>` stacktrace when present
  (the 8 KiB wire-capped, scrubbed copy). Placed high on the page.
- **Warnings section**, gated on a non-empty `build.extensions?.internalAdapters`: a
  "Deprecations (N)" list, a "Log warnings (N)" list, and an "N more dropped past the cap"
  note when `droppedWarnings > 0`. Hidden entirely when the block is absent or both lists are
  empty and dropped is 0 (the default for every build without the opt-in module).
- All strings reach the DOM via the existing `el()` helper (`textContent`, never `innerHTML`
  — `dashboard.js:27`). Minimal CSS for the `<pre>`/section added to the dashboard stylesheet.

**Track 2 — report `render` IIFE (`buildhound-report/.../report-template.html`).**
- The identical Warnings block, reading `d.extensions?.internalAdapters`. Same `el()`/
  `textContent` discipline; no new external reference (the standalone/zero-network invariant,
  locked #4, stays intact — `ReportAssetsTest` enforces it).

## 4. Test strategy

- **Dashboard node smoke (`dashboard-smoke.js`, real DOM render — the only live render
  coverage, since `ReportAssetsTest` is string/contract-only):** add a failed build whose
  canned payload carries `failure` + `extensions.internalAdapters.{deprecations, logWarnings,
  droppedWarnings > 0}`; assert the Failure section (class/message/stacktrace) and Warnings
  section (a deprecation, a log warning, the dropped-count note) all render. Assert the
  existing minimal `FAILED` build (`b2`, no `failure`/`extensions`) renders **neither** and
  does not throw (the presence-gate + config-phase-failure case).
- **Server round-trip (`ApplicationTest`, in-memory):** ingest a payload carrying `failure` +
  `extensions.internalAdapters`, `GET /v1/builds/{id}`, assert both survive the wire. The smoke
  harness stubs `fetch`, so it proves "if the server sends it, we render it" — this test proves the
  server *sends* it, and guards both surfaces if anyone later swaps the detail response for a
  projection DTO.
- **Server jsonb round-trip (`PostgresStoresIntegrationTest`, Testcontainers):** `save`→`findById` of
  the same shape asserts `failure` + the opaque `internalAdapters` extension survive the *real*
  Postgres jsonb encode/decode (the in-memory path is by-reference, so "same `BuildHoundJson`" ≠
  "tested").
- **Report render smoke (`report-smoke.js` + `ReportScriptTest`, real DOM render — new):** runs the
  report's `render()` IIFE against a DOM stub and asserts the Failure + Warnings sections populate.
  `ReportAssetsTest` only checked string-splice invariants, so `render()` shipped untested — and this
  harness immediately caught a **pre-existing latent bug from plan 047**: a literal `</script>` in an
  inline-script comment, which a browser's HTML parser reads as the script's end tag, truncating the
  render (the failure card never rendered in a browser). Fixed by rewording the comment; a new
  `ReportAssetsTest` case pins `<script>`/`</script>` count-parity so no stray closer recurs.
- **Report contracts:** the existing `ReportAssetsTest` zero-network / escaping / placeholder checks
  stay green (the added markup is static template + the `el()` render path).
- No new Kotlin *logic* tests — no server logic changed.

## 5. Risks

- **Privacy (the highest-risk fields, already ruled on in 044).** Failure messages,
  stacktraces, and warning strings are the free-text/PII-risk fields; they are scrubbed at
  collection (044). This plan only *displays* already-scrubbed data. The mandatory §3.2 review
  runs (server + report change) and must confirm the new render paths are `textContent`-only
  with no `innerHTML` reachable.
- **The one inferred link.** "The server serializes these fields on the detail response" was
  inferred from the shared serializer, not observed — pinned by the round-trip test in §4.
- **Drift with the report Failure card.** The dashboard Failure section must match the report
  card's shape so the two surfaces read the same; kept identical by construction.

## 6. Exit criteria

`./gradlew build` green (extended smoke + new round-trip test). A failed build's dashboard
detail shows the exception + scrubbed stacktrace; a build with the opt-in warning module on
shows a warnings panel on **both** the dashboard and the report; a build with neither shows
neither, on both. Spec §6/§3.8 + architecture decision log updated. Three clean-context
reviews (frontend on `dashboard.js`/`report-template.html`, kotlin-gradle on the new test,
mandatory §3.2 security/privacy) pass or findings are addressed.
