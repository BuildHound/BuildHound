-- Normalized per-task rows for module/type/name rollups (plan 026). This is spec §5's planned
-- `tasks` hypertable, landed as a plain table (TimescaleDB conversion deferred like `builds`).
-- Written on ingest in the same transaction as the `builds` row; dedupe stays at the build level
-- (no PK/UNIQUE here — a duplicate build inserts zero task rows). Additive only.
--
-- `user_id` and `started_at` are denormalized from the build onto each task row so
-- buildImpactedUsers and window filtering need no join back to `builds`.
CREATE TABLE task_executions (
    project_id  uuid   NOT NULL REFERENCES projects (id),
    build_id    text   NOT NULL,
    started_at  timestamptz NOT NULL,   -- copied from the build; the time key for windows
    user_id     text,                   -- pseudonymized u_… from environment.userId (spec §3.7)
    path        text   NOT NULL,
    module      text,
    name        text   NOT NULL,        -- last path segment
    type        text,                   -- task FQCN (plan 016); NULL on pre-016 payloads
    outcome     text   NOT NULL,
    cacheable   boolean,
    duration_ms bigint NOT NULL
);

CREATE INDEX task_exec_project_started_idx ON task_executions (project_id, started_at DESC);
CREATE INDEX task_exec_project_module_idx  ON task_executions (project_id, module);
CREATE INDEX task_exec_project_type_idx    ON task_executions (project_id, type);
