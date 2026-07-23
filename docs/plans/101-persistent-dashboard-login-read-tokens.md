# 101 — Persistent dashboard login via scoped read tokens

**Status: open.**

## Source

Feature request (owner, 2026-07-23): keep a dashboard user logged in after entering a read
token — closing the tab or browser must not log them out. Spec §5 (token scopes); builds on
plan 098 (mint endpoint) and supersedes plan 012's "sessionStorage, tab-scoped only" storage
decision. Prerequisite folded in: today no human-obtainable token has `read` scope — the
bootstrap token is `all` ([Application.kt] `ensureProjectWithToken` default) and the mint
endpoint hardcodes `ingest`, so persisting the entered token would persist an
admin-equivalent credential. Scoped minting therefore lands first, in the same plan.

## Scope

**In:**
- `POST /v1/admin/tokens` accepts optional JSON body `{"scope": "read" | "ingest"}`;
  absent body/field → `ingest` (backward compatible with plan-098 clients); any other value → 400.
- New `GET /v1/whoami`: any valid token (any scope) → `{projectKey, scope}`,
  `Cache-Control: no-store`. Counts as first use (activates a fresh token inside its 6h
  unused window — applies to read tokens equally).
- Dashboard: scope picker (read | ingest) on the admin mint action; token entry calls
  whoami and stores by scope — `read` → `localStorage` (persists across restarts),
  `all` → `sessionStorage` + hint "mint a read token for persistent login",
  `ingest` → rejected with message; invalid → error, nothing stored.
- Token lookup: `localStorage` first, `sessionStorage` fallback; writing one slot clears
  the other. Visible **Forget token** button wipes storage. Any 401 wipes the stored token
  and reshows the token bar (403 keeps current "lacks read scope" handling).
- New dashboard UI follows `docs/brand/DESIGN-V2.md`.

**Out (explicit decisions):**
- Minting `admin`/`all`/`addon`/`metrics` via API — `admin`/`all` stay env-bootstrap-only
  (an API-minted admin token would survive bootstrap rotation as a backdoor); others until needed.
- Token listing/revocation API — stays deferred (plan 042). **Accepted risk:** a leaked
  persisted read token remains valid until manual SQL
  (`UPDATE api_tokens SET revoked_at = now() WHERE …`) or bootstrap rotation.
- Cookie/server sessions (server stays stateless Bearer), client-side TTL (theater — the
  401 wipe is the honest expiry), "remember me" checkbox (minting a read token is the
  opt-in), persistence of the admin-token slot (`buildhound.adminToken` stays
  sessionStorage-only).

## Design

- `buildhound-server/Routes.kt` `adminRoutes`: parse optional bounded body; scope
  allowlist; `MintedTokenResponse` shape unchanged (`scope` echoes the choice).
- `Routes.kt`: `GET /v1/whoami` under read routes' auth helper with an any-scope
  predicate; response type `WhoamiResponse(projectKey, scope)`; `no-store`.
- `web/dashboard.js` + `index.html`: storage policy above; scope picker; Forget button;
  401 handler wipes then reshows bar. No new data rendered except projectKey/scope via
  `textContent`.
- No schema/payload changes → no golden files, no migration (`api_tokens` already has all
  columns needed).

## Test strategy

Server tests (same harness as plan 098's mint tests): mint `read` → token passes
`GET /v1/builds`, 403 on `/v1/admin/*`; default/absent scope → `ingest` (back-compat);
`admin`/`all`/garbage scope → 400; whoami returns correct `{projectKey, scope}` for
read/ingest/all tokens, 401 for invalid, activates an unused minted token. Dashboard JS
has no test harness — manual browser verification (compose stack) recorded in the PR.

## Risks

- **Security:** `localStorage` is XSS-exfiltratable — bounded by read-only scope, strict
  CSP (no inline script/CDN, DashboardRoutes), and the never-persist-`all` policy.
  Whoami adds no new authz surface (requires a valid token; returns only the caller's own
  project/scope).
- **Privacy:** no new data collection; no payload/schema changes.
- **Back-compat:** plan-098 clients and the existing dashboard button send no body → still
  mint `ingest`.
- Revocation gap accepted (see Scope-out) — follow-up is plan 042's deferred
  listing/revocation.

## Exit criteria

Green build; new server tests pass; manual check: read token survives browser restart,
`all` token stays session-only with hint, Forget button and 401 wipe work; §3.1 reviews
(kotlin-gradle-reviewer + frontend-reviewer on dashboard.js) and mandatory §3.2
security & privacy review complete, findings fixed or accepted in the PR.
