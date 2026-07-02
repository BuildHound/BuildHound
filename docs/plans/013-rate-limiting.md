# 013 — Per-token rate limiting (ingest + query)

## Source

Spec §8: "ingest rate-limited per token". Architecture §5 (multi-tenancy bullet)
promises the same. Recorded as a pre-pilot blocker in plans 009/010 — plan 010
explicitly extends it to the query routes (trends is the first cheap-request/
expensive-aggregation endpoint). Last blocker between the code and the pilot.

## Scope

**In:**

- Ktor's official `RateLimit` plugin (`io.ktor:ktor-server-rate-limit`, same
  catalog-pinned Ktor version — no new version to source) with two named limiters:
  - **ingest** — wraps `POST /v1/builds`; default 60 requests/min per key.
  - **query** — wraps the three read routes; default 120 requests/min per key.
- **Key = SHA-256 of the bearer token** (the same hash used for auth lookups; the
  raw token never sits in the limiter's key map). Requests with no/garbage
  Authorization header share a per-remote-host key — invalid-token floods hit the
  limiter before they hit token resolution.
- Limits configurable via `BUILDHOUND_INGEST_RPM` / `BUILDHOUND_QUERY_RPM`
  (0 disables a limiter — dev/test escape hatch); wired through a `ServerConfig`
  value so `testApplication` tests can set tiny limits without env vars.
- 429 responses carry Ktor's `X-RateLimit-*` headers + `Retry-After`. The plugin
  already classifies 429 as retryable (spools, plan 007) — a throttled CI fleet
  degrades to spool-and-drain instead of losing payloads.
- `/health` and the dashboard's two static resources stay unlimited (no token to
  key on; static bytes).

**Out (recorded):** distributed rate limiting. The limiter state is per-instance
in-memory; with N replicas the effective ceiling is N×limit. Architecture §5's
"stateless horizontally" rule gets a note: rate-limit state is deliberately
instance-local (a shared-store limiter adds a hot write per request; revisit if
the server ever actually scales out — the pilot runs one instance).

## Test strategy

- `testApplication` with a 2/min ingest limit: third POST within the window is 429
  with `Retry-After`; different tokens don't share a bucket; query limiter is
  independent of ingest; limit 0 disables; unauthenticated requests are keyed
  (429 without valid credentials never reaches the store).
- Existing suites run with generous defaults — no test may flake on shared state
  (each `testApplication` builds a fresh module, so limiter maps are per-test).

## Risks

- Ktor's limiter evicts idle keys on a schedule; a token-hash key space is
  attacker-expandable only by sending more distinct tokens — each gets its own
  small bucket, and the map entry cost is bounded by request rate, which is
  exactly what's being limited. Acceptable.
- Per-instance state vs horizontal scaling — recorded above as a deliberate
  decision, not an oversight.

## Exit criteria

Server tests green including the new 429 coverage; a compose-stack user hammering
ingest with one token gets throttled without affecting a second token; plugin
spool path (already tested) handles the 429.
