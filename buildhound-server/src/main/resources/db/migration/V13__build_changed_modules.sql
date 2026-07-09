-- Change blast-radius attribution (plan 063, research F13): the per-build set of changed Gradle
-- module paths, projected on ingest into a normalized table for the costliest-modules-to-change
-- rollup (GET /v1/rollups/change-blast-radius). Additive; written on ingest in the build's
-- transaction only when the `builds` row was newly inserted (a duplicate build adds no rows), the
-- plan-026 `task_executions` idempotency posture. No PK/UNIQUE — dedupe stays at the build level.
--
-- Migration number: V12 (V12__execution_reasons.sql, plan 061) was the last claimed; this is the
-- next free integer (the plan text's `V{n}` placeholder resolved to V13 at merge).
--
-- `started_at` is denormalized from the build (like task_executions) so the days-window join needs no
-- lookup back to `builds`. `module` is a project-internal Gradle path (":app", ":core:common", ":"
-- for a whole-build root change) — never a filesystem path or a changed-file list (spec §3.7).
CREATE TABLE build_changed_modules (
    project_id  uuid        NOT NULL REFERENCES projects (id),
    build_id    text        NOT NULL,
    started_at  timestamptz NOT NULL,   -- copied from the build; the time key for windows
    module      text        NOT NULL    -- Gradle path only (§3.7), ":" for a root/whole-build change
);

CREATE INDEX build_changed_modules_project_started_idx ON build_changed_modules (project_id, started_at DESC);
CREATE INDEX build_changed_modules_project_module_idx  ON build_changed_modules (project_id, module);
