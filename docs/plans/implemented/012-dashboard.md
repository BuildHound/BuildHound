# 012 — Dashboard v0: builds list, build detail, trends

## Source

Roadmap Phase 1: "Dashboard: builds list, build detail (tasks table + timeline +
cache summary), duration & hit-rate trend charts with pipeline/branch/mode filters."
Spec §6. Consumes the plan-010 query API; last Phase-1 chunk.

## Scope

**In:**

- A single static page served by the Ktor server at `/` (embedded resource, same
  zero-dependency posture as the report template: inline CSS/JS, no CDN, hand-rolled
  SVG for charts, `textContent`-only DOM writes, CSP header).
- **Views** (client-side, hash-routed): builds list (filterable by branch/mode/
  outcome, paged, newest first); build detail (summary chips + duration-sorted task
  table + cache summary — same information design as the standalone artifact);
  trends (last 30/90 days: build count + failures as bars, avg duration and hit-rate
  as SVG lines, same filters).
- **Auth model v0**: the page is public (it contains no data); all data calls go to
  the existing Bearer-authenticated query API. The user pastes a *read-scoped* token
  once; it is kept in `sessionStorage` (not localStorage — gone when the tab closes)
  and never appears in URLs. Recorded trade-off: a real session/SSO story is
  post-pilot; the pilot audience is the team that owns the token anyway.
- Ktor: `GET /` + `GET /dashboard.js` from resources with
  `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline';
  script-src 'self'; connect-src 'self'` (script in a separate resource so CSP can
  avoid 'unsafe-inline' for scripts).
- Pipeline filter from the roadmap line is **deferred** with the query API's
  pipeline-column note (plan 010) — branch/mode/outcome filters ship now.

## Test strategy

- `testApplication`: `/` serves HTML with the CSP header and no inline `<script>`;
  `/dashboard.js` served with the right content type; both unauthenticated (they
  carry no data); data endpoints stay 401 without a token (already tested).
- The dashboard JS is syntax-checked with a real JS engine locally (the plan-006
  lesson: string assertions don't catch SyntaxErrors); its fetch/render helpers are
  written defensively against missing optional JSON keys (explicitNulls=false).

## Risks

- XSS: all payload-derived strings rendered via `textContent`; SVG built via
  `createElementNS` with numeric attributes only; CSP as backstop.
- Token handling: sessionStorage only, `Authorization` header only, never logged or
  in URLs; a wrong-scope token surfaces the server's 403 message.
- No new server dependencies.

## Hardening round (review findings, fixed pre-merge)

- **CSP tightened**: `frame-ancestors 'none'` + `X-Frame-Options: DENY` (the page hosts
  token entry — classic clickjacking-on-credential-entry target), `base-uri 'none'`,
  and the inline `<style>` block is **hash-pinned** (`style-src 'sha256-…'`, computed
  at startup from the served bytes) instead of `'unsafe-inline'` — no `unsafe-*`
  source remains, pinned by test. `Cache-Control: no-cache` on both resources so a
  server upgrade can't leave stale JS against a changed API.
- **The node smoke harness is now a repo test** (`DashboardScriptTest` +
  `test/resources/web/dashboard-smoke.js`): drives all three views plus a
  minimal-payload detail and the error path through a DOM/fetch stub in a real JS
  engine. The plan's "syntax-checked locally" was a manual step, i.e. not regression
  coverage (review MEDIUM); it skips when node is absent, and CI runners have node.
- **JS robustness**: `decodeURIComponent` moved inside the router's try (it throws
  synchronously on malformed hashes — before any promise exists to `.catch`); a
  render-generation counter stops a slow stale fetch from appending under a newer
  view; outcome strings go through a `badgeClass` allowlist before becoming CSS
  class names (server allowlists them too — defense in depth for future enum
  values); the token input is cleared after "Use"; the detail mode chip uppercases
  the payload's lowercase serial name to match the list's enum-name casing.

### Recorded deferrals / accepted trade-offs

- **Roadmap's build-detail "timeline" deferred**: task entries carry `durationMs`
  but no start offsets (schema v1), so a timeline needs an additive schema field
  first — same bucket as the pipeline filter above.
  > **Correction (plan 017, 2026-07-03):** this premise was wrong. `TaskExecution.startMs`
  > is a required schema-v1 field and has always been populated; the 2026-07-03
  > reconciliation confirmed it. The timeline needed no schema change — only a greedy lane
  > layout over the existing start/end offsets — and shipped in plan 017. Only the
  > `worker` id stays unpopulated.
- Filter/paging/trend-range state lives in function arguments, not the hash: back
  navigation or refresh resets to defaults. Accepted for v0; hash-encoded state is
  the natural follow-up when the dashboard grows a pipeline filter.
- Paging shows "Older →" whenever a full page returns (one dead click on exact
  multiples of 50), and the server clamps `offset` at 10 000. Accepted at pilot scale.
- The builds/day bar is painted red when the day has ≥1 failure (count in hover
  title) rather than stacking failure bars. Deliberate v0 simplification.

## Exit criteria

Server tests green; a compose-stack user can open `/`, paste the bootstrap token,
and see list/detail/trends over real ingested builds.
