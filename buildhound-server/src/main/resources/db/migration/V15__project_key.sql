-- Dashboard project selector (plan 077). Additive only: one nullable column on `builds`
-- backfilled from the stored payload, plus one hot index. No existing column is altered.

-- The payload's `projectKey` (root project name) — the axis the dashboard selector filters on.
-- Distinct from `project_id` (the tenant): one tenant token may upload several repos.
ALTER TABLE builds ADD COLUMN project_key text;

-- One-shot backfill from the jsonb payload (V3 precedent). A single full-table UPDATE:
-- acceptable at v0.1 scale; a self-hosted upgrade runs this same statement once on migrate.
-- left(…, 256) mirrors the server-side MAX_PROJECT_KEY_CHARS clamp so one pre-existing oversized
-- key from any tenant cannot fail the CREATE INDEX below and brick the whole deployment on
-- upgrade. left() counts codepoints where the server clamp counts UTF-16 units — accepted,
-- cosmetic, both far below the btree tuple limit.
UPDATE builds SET project_key = left(payload ->> 'projectKey', 256);

-- Selector enumeration (GET /v1/project-keys) + per-projectKey filtering scan this index.
CREATE INDEX builds_project_projectkey_started_idx
    ON builds (project_id, project_key, started_at DESC);
