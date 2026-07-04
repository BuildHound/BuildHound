-- Addon foundation (plan 039): generic tenant-scoped jsonb key/value storage for the
-- `/v1/addons/<id>/…` namespace. jsonb keeps ingest schema-stable — an addon evolves its own
-- value shape with no per-addon DDL. Every row is keyed by project_id (tenant isolation,
-- architecture §5); the addon_id is validated against a server-side allowlist before any write,
-- so it never names a table or route dynamically.
CREATE TABLE addon_data (
    project_id uuid        NOT NULL REFERENCES projects (id),
    addon_id   text        NOT NULL,
    key        text        NOT NULL,
    value      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, addon_id, key)
);
