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

## Exit criteria

Server tests green; a compose-stack user can open `/`, paste the bootstrap token,
and see list/detail/trends over real ingested builds.
