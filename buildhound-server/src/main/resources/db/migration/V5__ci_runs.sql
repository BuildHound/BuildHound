-- Normalized CI run tree per build (plan 028). One row per (project, build); the connector's
-- enrichment worker upserts it after pulling the provider timeline. The full CiSpan tree rides
-- in `run` (jsonb) — the spec §5 "store the normalized tree as jsonb" choice — so a ci-run read
-- is a single row fetch; `queued_ms`/`started_ms`/`finished_ms` are lifted out for the derived
-- queue-time and Gradle-share views without deserializing. Additive only; existing ingest/query
-- untouched. Tenant-scoped like every table (architecture §5).
--
-- `status`: OK (tree stored) | UNCONFIGURED (no connector/PAT) | PENDING (build not finished within
-- the poll budget) | FAILED (fetch/parse error). PRIMARY KEY makes `saveRun` an idempotent upsert.
--
-- Privacy: `run` may embed agent/worker names (Azure `workerName`) — treated like the dropped
-- plugin `agentName` (plan 005): kept server-side, only ever read back within the owning tenant.
CREATE TABLE ci_runs (
    project_id  uuid   NOT NULL REFERENCES projects (id),
    build_id    text   NOT NULL,
    provider    text,
    run_id      text,
    queued_ms   bigint,
    started_ms  bigint,
    finished_ms bigint,
    status      text   NOT NULL,
    run         jsonb,
    fetched_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, build_id)
);
