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
- 429 responses carry `Retry-After` (Ktor puts `X-RateLimit-*` on successful
  responses). The plugin already classifies 429 as retryable (spools, plan 007) —
  a throttled CI fleet degrades to spool-and-drain instead of losing payloads.
- `/health` and the dashboard's two static resources stay unlimited (no token to
  key on; static bytes).

**Out (recorded):** distributed rate limiting. The limiter state is per-instance
in-memory; with N replicas the effective ceiling is N×limit. Architecture §5's
"stateless horizontally" rule gets a note: rate-limit state is deliberately
instance-local (a shared-store limiter adds a hot write per request; revisit if
the server ever actually scales out — the pilot runs one instance).

## Hardening round (review findings, fixed pre-merge)

- **Rotating-token bypass (both reviews' MEDIUM)**: per-token buckets alone let an
  attacker rotate garbage Bearer tokens — every request minted a fresh bucket, was
  never 429'd, and reached token resolution (a DB read in Postgres mode). Fixed
  with an **outer per-source-host limiter** (`BUILDHOUND_HOST_RPM`, default 600/min,
  0 disables) wrapping both `/v1` subtrees — verified against the Ktor 3.2.2
  bytecode that nested `rateLimit` blocks compose (each collects its ancestors'
  providers). The original "map entry cost is bounded by request rate" reasoning
  was wrong for unique keys (unbounded request rate → ~rps × 60 s live entries +
  parked eviction coroutines); the host layer now bounds that per source.
  **Residual, accepted**: a flood distributed over many source IPs gets one host
  budget each — infra/WAF territory, not application rate limiting. And behind a
  reverse proxy all traffic shares the proxy's host bucket — the pilot exposes the
  port directly; `XForwardedHeaders` must not be installed without revisiting the
  key (it would make it attacker-controlled).
- **Throttle-test flake window accepted (attempted fix reverted)**: Ktor's
  `rateLimiter()` clock IS injectable, but the plugin's key-eviction coroutine
  compares the limiter's `refillAt` against hardwired wall time
  (`io.ktor.util.date.getTimeMillis`, verified in the 3.2.2 bytecode) — a fake
  clock makes every key evict instantly, i.e. it silently disables throttling
  (caught because the tests failed with it), and a real-anchored frozen clock
  still leaves the same 60 s idle-eviction window. Determinism is not achievable
  in 3.2.2; the >60 s-mid-test-stall flake risk is accepted with a comment in the
  test file.
- **Env parsing fails safe**: non-numeric or negative `*_RPM` values fall back to
  the default with a warning — a typo can't silently disable limiting; only an
  explicit `0` does. Effective limits are logged at startup so "off" is visible.
- **Header claim corrected**: Ktor puts `X-RateLimit-*` on successful responses and
  `Retry-After` on the 429 (asserted — it's what the plugin's spool logic needs).
- **Recorded divergence**: the plan said "a `ServerConfig` value"; the
  implementation uses a `RateLimits` parameter (+ the clock) on `buildHoundModule`
  directly — one config type per concern instead of a grab-bag object.
- Compose: rate-limit env vars documented; json-file log rotation added (unlimited
  `/health` + CallLogging was a slow disk-fill vector under GET floods).

## Test strategy

- `testApplication` with a 2/min ingest limit: third POST within the window is 429
  with `Retry-After`; different tokens don't share a bucket; query limiter is
  independent of ingest; limit 0 disables; unauthenticated requests are keyed
  (429 without valid credentials never reaches the store).
- Existing suites run with generous defaults — no test may flake on shared state
  (each `testApplication` builds a fresh module, so limiter maps are per-test).

## Risks

- Ktor's limiter evicts idle keys after ~one refill period, but the key map is
  unbounded and a rotating-token flood mints keys at raw request rate — see the
  hardening round: the per-host layer is what bounds this, per source.
- Per-instance state vs horizontal scaling — recorded above as a deliberate
  decision, not an oversight.

## Exit criteria

Server tests green including the new 429 coverage; a compose-stack user hammering
ingest with one token gets throttled without affecting a second token; plugin
spool path (already tested) handles the 429.
