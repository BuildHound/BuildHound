-- Retention windows (plan 042). Additive: ALTER the existing project_settings table (created in
-- V3__regression.sql, which explicitly reserves this extension) — never a second settings table.
-- Defaults are the spec §5 values (90d raw, ~13mo build-level); a project with no row inherits them.
ALTER TABLE project_settings
    ADD COLUMN retention_raw_days   integer NOT NULL DEFAULT 90,
    ADD COLUMN retention_build_days integer NOT NULL DEFAULT 395;
