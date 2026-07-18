# 097 — Dashboard ingest-token generation with 6-hour activation window

## Source

Owner feature request (2026-07-18): "In the dashboard add an option to generate a new
ingest-token … the token must be used within 6 hours after registration. If unused they
need to be removed automatically. There should still be validation on the token using
timestamps if somehow they are not deleted in time." This is the first slice of the
token-CRUD follow-up that plan 042 explicitly deferred ("A follow-up admin plan owns
them", 042 §scope).

## Scope

**In**: mint ingest-scope tokens from the dashboard admin page; 6-hour first-use
(activation) deadline for minted tokens; automatic hard-delete of unactivated expired
tokens; timestamp validation in the auth path as defense-in-depth.

**Out** (explicitly): token listing/revocation UI, minting non-ingest scopes, salt
issuance/rotation, configurable window length, any change to bootstrap
(`BUILDHOUND_BOOTSTRAP_TOKEN`) provisioning.

## Design

- **Migration `V16__token_activation.sql`** (V16 verified free on origin/main; re-check
  after every rebase — two files with one V-number is a boot failure, not a git
  conflict): `ALTER TABLE api_tokens ADD COLUMN activated_at timestamptz` and
  `ADD COLUMN expires_unused_at timestamptz`, plus a partial index on
  `(expires_unused_at) WHERE activated_at IS NULL` for the sweep. Additive only;
  existing rows keep both NULL. **NULL `expires_unused_at` means "no activation
  deadline"** — bootstrap and pre-existing tokens are exempt by construction.
- **Endpoint `POST /v1/admin/tokens`** in `Routes.kt`, via the existing
  `authenticatedProject(TokenScope::allowsAdmin)` + query-rate-limiter pattern (same as
  `/v1/admin/retention`). Mints for the *caller's* project only (tenant scoping via the
  admin token, never a request parameter). Server generates 32 random bytes
  (`SecureRandom`), encodes URL-safe base64, stores only the SHA-256 hash with
  `scope='ingest'` and `expires_unused_at = now() + interval '6 hours'` (DB clock, so
  mint and validation share one clock — no app/DB skew). Response `201`:
  `{ "token": "<plaintext>", "scope": "ingest", "expiresUnusedAt": "<iso>" }` — the only
  place plaintext ever exists; never logged, never re-readable.
- **Auth-path validation (defense-in-depth)**: the token-resolution query gains
  `AND (activated_at IS NOT NULL OR expires_unused_at IS NULL OR expires_unused_at > now())`,
  so an expired unactivated token is rejected even if the sweeper never ran. On
  successful resolution of a token with `activated_at IS NULL`, set it:
  `UPDATE … SET activated_at = now() WHERE id = ? AND activated_at IS NULL` (idempotent
  under concurrent first use).
- **Sweeper**: `deleteExpiredUnactivatedTokens()` on `TokenStore`
  (`DELETE WHERE activated_at IS NULL AND expires_unused_at < now()` — hard delete is
  safe: a never-activated token has authenticated nothing, so no rows reference it).
  Wired next to `startRetentionSweeper()` in `Application.kt` (`main()` only, same
  single-thread scheduled-executor pattern), interval
  `BUILDHOUND_TOKEN_SWEEP_MINUTES` (default 15, `0` disables). Tests call the store
  method directly.
- **Stores**: `TokenStore` gains `mintToken(projectId, hash, scope, ttl)` /
  `markActivated` semantics + the sweep method; `PostgresStores` and the in-memory store
  stay behaviorally identical (in-memory uses an injectable `InstantSource` so tests can
  move time; Postgres tests insert past timestamps via SQL instead).
- **Dashboard** (`web/index.html` + `dashboard.js`, `#/admin` page): a "Generate ingest
  token" section under the existing retention form, reusing the admin-token bar.
  Button → `POST /v1/admin/tokens` → render the plaintext once via `textContent` with a
  copy control and the literal warning that it is shown once and deleted if not used
  within 6 hours. Never persisted to `sessionStorage`/URL/logs. Inline-CSS additions
  only; CSP headers and the style hash pin in `DashboardRoutes.kt` updated if the
  `<style>` block changes. DESIGN-V2 rules: reuse existing token-bar/form/badge
  patterns; no fixture asset references.

## Test strategy

- `testApplication` (in-memory): 401 no token / 403 non-admin scope on the mint
  endpoint; 201 shape; minted plaintext authenticates `POST /v1/builds`; first use sets
  activation exactly once; unactivated token past deadline is rejected (clock moved via
  `InstantSource`); activated token far past deadline still works; bootstrap token
  (NULL deadline) unaffected; sweep deletes only expired-unactivated rows.
- Testcontainers (`PostgresStoresIntegrationTest`): same matrix at the SQL layer —
  resolution predicate, one-time activation UPDATE, sweep DELETE — using rows inserted
  with past timestamps.
- `DashboardScriptTest` smoke harness: new section renders, POST fired with Bearer
  admin token, plaintext rendered via `textContent`, error path (403) shows scope
  message.
- `DashboardRoutesTest`: CSP still hash-pinned / no `unsafe-*` after the style change.

## Risks

- **Flyway V-number collision** on rebase (see above) — re-verify V16 before merge.
- **Bootstrap regression**: env-provisioned tokens must keep working with no deadline —
  covered by an explicit test.
- Plaintext exposure surface: response body + one DOM node; reviews (§3.2) must check no
  log/interceptor captures it.
- No payload-schema change (server DB only) — golden files untouched. No plugin code
  touched — CC not in play.

## Exit criteria

`./gradlew :buildhound-server:test` green including the new matrix; migration applies on
a fresh TimescaleDB via compose; dashboard generates a working ingest token end-to-end;
unactivated tokens vanish after the deadline (sweep) and are rejected even without the
sweep (auth predicate); both §3 reviews (kotlin-gradle + security/privacy) passed with
findings fixed or accepted.
