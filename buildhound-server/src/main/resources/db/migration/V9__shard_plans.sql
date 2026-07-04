-- Test-sharding addon (plan 040): idempotent LPT shard-plan memo. The first CI job for a
-- (project, reference, total) computes and stores the plan; later jobs read the same one, so
-- inter-job suite-discovery drift can't reshuffle shards mid-run. Tenant-scoped by project_id;
-- `plan` is the jsonb array-of-arrays (shard → class keys).
CREATE TABLE shard_plans (
    project_id uuid        NOT NULL REFERENCES projects (id),
    reference  text        NOT NULL,
    total      int         NOT NULL,
    plan       jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, reference, total)
);
