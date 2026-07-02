-- BuildHound core tables (plan 009). Multi-tenancy from the first real table
-- (architecture §5): every row carries project_id; queries are tenant-filtered.

-- TimescaleDB is used by the rollups chunk; enable when the image provides it,
-- continue silently on plain Postgres (tests, minimal deploys).
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS timescaledb;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'timescaledb extension unavailable, continuing without it';
END
$$;

CREATE TABLE projects (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Tokens are stored hashed (SHA-256 hex) — never the plaintext (spec §8).
CREATE TABLE api_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects (id),
    token_hash text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);

-- One row per build; the full schema-v1 document rides in payload (jsonb), the
-- extracted columns are the hot query keys for lists/trends. Hypertable conversion
-- is deferred to the rollups chunk (unique dedupe constraint vs partition key).
CREATE TABLE builds (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  uuid NOT NULL REFERENCES projects (id),
    build_id    text NOT NULL,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz NOT NULL,
    outcome     text NOT NULL,
    mode        text NOT NULL,
    branch      text,
    duration_ms bigint NOT NULL,
    hit_rate    double precision,
    payload     jsonb NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, build_id)
);

CREATE INDEX builds_project_started_idx ON builds (project_id, started_at DESC);
