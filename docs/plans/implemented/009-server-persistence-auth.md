# 009 — Server: Postgres persistence, tenancy + token auth, gzip ingest

## Source

Roadmap Phase 1 (server): "tenancy + tokens, `POST /v1/builds`, Postgres+Timescale
core tables". Spec §5 (ingest, storage), §8 (tokens hashed at rest, rate limiting).
Architecture §5 (storage behind `BuildStore`, multi-tenancy from the first real table,
Flyway + Testcontainers).

## Scope

**In:**

- **Schema (Flyway `V1__core.sql`)**: `projects(id, project_key unique, created_at)`;
  `api_tokens(id, project_id fk, token_hash unique, created_at, revoked_at)`;
  `builds(id, project_id fk, build_id, started_at, finished_at, outcome, mode, branch,
  duration_ms, hit_rate, payload jsonb, created_at)` with `unique(project_id,
  build_id)` (idempotency) and an index on `(project_id, started_at desc)`.
  The TimescaleDB extension is enabled when available (guarded DO block) but hypertable
  conversion is deferred to the rollups chunk — a plain table with a unique
  (project, build) constraint is the correct dedupe shape today, and Timescale requires
  partition columns inside unique indexes. Recorded divergence from the roadmap's
  one-liner.
- **Stores**: `PostgresBuildStore` + `PostgresTokenStore` (plain JDBC + HikariCP — no
  ORM for three queries); `BuildStore` gains the tenant dimension
  (`save(projectId, payload)`, `findById(projectId, buildId)`, `countForProject`);
  in-memory implementations stay for tests/dev.
- **Auth**: `Authorization: Bearer <token>` → SHA-256 hex → `api_tokens` lookup
  (hashed at rest, spec §8) → project. Missing/unknown token → 401. The payload's
  `projectKey` is informational; the token determines the tenant (server-authoritative).
- **gzip ingest**: `Content-Encoding: gzip` handled explicitly on the ingest route
  (bounded: request body capped at 32 MB, decompressed capped at 64 MB — zip-bomb
  guard); plain JSON still accepted.
- **Wiring**: `BUILDHOUND_DB_URL/DB_USER/DB_PASSWORD` env → Hikari pool + Flyway
  migrate on boot + Postgres stores; without DB env the server runs in-memory with a
  prominent warning (keeps `docker run` smoke tests and local dev working).
  **Bootstrap tenancy for the pilot**: `BUILDHOUND_BOOTSTRAP_PROJECT` +
  `BUILDHOUND_BOOTSTRAP_TOKEN` env create the project + token hash on boot when absent
  (no admin API yet — that's post-pilot; only the hash is stored, and the value is
  read from env, never logged).
- In-memory mode gets a static dev token via `BUILDHOUND_DEV_TOKEN` (optional);
  without any token configured, ingest is 401-everything (fail closed).

**Out (next chunks):** rollups + query endpoints (dashboard backend), rate limiting
per token (spec §8 — recorded as a pre-pilot blocker), admin/token-management API,
retention jobs, hypertable conversion.

## Hardening round (review findings, fixed pre-merge)

- Bounded body read (`receiveBounded`): Content-Length pre-check + capped streaming —
  `receive<ByteArray>()` buffered unbounded before the cap (authenticated OOM DoS).
- Persistence errors classified: SQLSTATE 22xxx (data-shaped, e.g. `\u0000` in jsonb)
  → 400 so the plugin drops; other SQL failures → 503 so the plugin spools — a 500
  would have poison-blocked the plugin's spool drain forever.
- Cross-project token reuse fails boot loudly (both stores): `ON CONFLICT DO NOTHING`
  silently resolved the *other* tenant on operator misconfiguration.
- `BUILDHOUND_DEV_TOKEN` is in-memory-only (a stray env var in DB mode must not
  persist a weak credential) and implies project `dev` per the plan.
- Timestamps written as UTC `OffsetDateTime` (no default-zone dependence); DB password
  required (no silent empty default); payload `projectKey` mismatch logs a warning.
- Accepted with notes: unsalted SHA-256 is sound only for high-entropy tokens —
  compose documents `openssl rand -hex 32`; server-generated tokens (and a possible
  pepper) land with the admin API. Retention is unbounded until the retention chunk —
  pilot decision recorded here. Rate limiting stays a pre-pilot blocker.

## Test strategy

- `testApplication` + in-memory stores: 401 without/with unknown token; 202 with
  seeded token; tenant isolation (same buildId, two projects → both stored; dedupe
  within a project); gzip body decodes; oversize request rejected (413); corrupt gzip
  → 400.
- Testcontainers (`@Testcontainers(disabledWithoutDocker = true)` — Docker exists in
  CI, not necessarily in dev sandboxes): Flyway migration applies; save/find/dedupe
  round-trip; token resolution; bootstrap seeding idempotent across restarts.

## Risks

- New dependencies (latest from Maven Central, checked 2026-07-02): postgresql
  42.7.12, flyway 12.10.0 (+ postgresql module), HikariCP 7.1.0, testcontainers
  1.21.4. Server-only — nothing touches the plugin classpath.
- Fail-closed auth: no configured token source must mean no ingest.
- JSONB keeps the full payload queryable; extracted columns are the hot query keys.

## Exit criteria

`./gradlew :buildhound-server:test` green (in-memory suite everywhere, Testcontainers
in CI); compose stack ingests an authed gzip payload end-to-end; unauthenticated
requests rejected.
