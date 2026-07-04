-- Per-artifact APK/AAB/AAR sizes for Android builds (plan 031). This is spec §5's `apk_sizes`
-- table, landed as a plain table (TimescaleDB conversion deferred like `builds`/`task_executions`).
-- Written on ingest in the same transaction as the `builds` row; dedupe stays at the build level
-- (no PK/UNIQUE here — a duplicate build inserts zero artifact rows). Additive only, tenant-scoped.
--
-- `started_at` is denormalized from the build onto each row so trend windowing needs no join back
-- to `builds`; `module`/`variant`/`type` are project-internal Gradle names (no PII). Byte size only.
CREATE TABLE apk_sizes (
    project_id  uuid   NOT NULL REFERENCES projects (id),
    build_id    text   NOT NULL,
    started_at  timestamptz NOT NULL,   -- copied from the build; the time key for trend windows
    module      text,                   -- e.g. ":app"; NULL when the build had no module path
    variant     text   NOT NULL,        -- e.g. "release"
    type        text   NOT NULL,        -- APK | AAB | AAR
    size_bytes  bigint NOT NULL,
    -- Rows are inserted in the same transaction right after the build row, so the composite FK to
    -- the build's (project_id, build_id) unique key holds; it enforces referential integrity.
    FOREIGN KEY (project_id, build_id) REFERENCES builds (project_id, build_id)
);

CREATE INDEX apk_sizes_project_started_idx ON apk_sizes (project_id, started_at DESC);
