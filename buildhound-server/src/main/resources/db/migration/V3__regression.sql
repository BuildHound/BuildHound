-- Regression engine v1 (plan 025). Additive only: new columns on `builds` (backfilled from
-- the stored payload) plus three new tables. No existing column is altered.
-- NOTE (plan 042): project_settings is CREATEd here with baseline/budget/alert columns; plan 042
-- ALTERs this same table with retention columns — it must extend, never re-CREATE.

-- Extracted hot columns for baseline keying + metric correlation (spec §5).
ALTER TABLE builds
    ADD COLUMN ci_provider         text,
    ADD COLUMN ci_run_id           text,
    ADD COLUMN pipeline_name       text,
    ADD COLUMN requested_tasks_sig text;

-- One-shot backfill from the jsonb payload. requested_tasks_sig must match the app's
-- RegressionEngine.requestedTasksSignature exactly: md5 of the sorted task names joined by \n.
UPDATE builds SET
    ci_provider   = payload -> 'ci' ->> 'provider',
    ci_run_id     = payload -> 'ci' ->> 'runId',
    pipeline_name = payload -> 'ci' ->> 'pipelineName',
    requested_tasks_sig = md5(
        coalesce(
            array_to_string(
                -- COLLATE "C" = code-point order, matching Kotlin's List<String>.sorted() exactly, so
                -- a backfilled sig never diverges from the app's under a locale/ICU DB collation.
                -- (Collate the text column, not the ORDER BY ordinal — an ordinal can't be collated.)
                ARRAY(
                    SELECT task FROM jsonb_array_elements_text(payload -> 'requestedTasks') AS task
                    ORDER BY task COLLATE "C"
                ),
                E'\n'
            ),
            ''
        )
    );

-- Baseline window: (project, pipeline, sig, mode) filtered to SUCCESS, newest-first.
CREATE INDEX builds_baseline_idx
    ON builds (project_id, pipeline_name, requested_tasks_sig, mode, started_at DESC);
-- {provider, runId} → build correlation for POST /v1/metrics.
CREATE INDEX builds_ci_run_idx ON builds (project_id, ci_provider, ci_run_id);

-- Custom measures from the metric CLI (spec §5/§7). build_id is null until correlated.
CREATE TABLE custom_metrics (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects (id),
    build_id   text,
    provider   text,
    run_id     text,
    scope      text NOT NULL,
    name       text NOT NULL,
    value      double precision,
    text_value text,
    unit       text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Idempotency: a retried CI step re-POSTing the same measure upserts, never duplicates.
CREATE UNIQUE INDEX custom_metrics_uniq
    ON custom_metrics (project_id, coalesce(build_id, ''), coalesce(provider, ''), coalesce(run_id, ''), scope, name);

-- One persisted verdict per build (spec §5). detail holds the per-metric breakdown.
CREATE TABLE build_verdicts (
    project_id   uuid NOT NULL REFERENCES projects (id),
    build_id     text NOT NULL,
    status       text NOT NULL,
    baseline_key text NOT NULL,
    detail       jsonb NOT NULL,
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, build_id)
);

-- Per-project regression settings (spec §5). Defaults apply when no row exists.
CREATE TABLE project_settings (
    project_id     uuid PRIMARY KEY REFERENCES projects (id),
    baseline_n     integer NOT NULL DEFAULT 20,
    default_branch text NOT NULL DEFAULT 'main',
    warn_z         double precision NOT NULL DEFAULT 3.5,
    fail_z         double precision NOT NULL DEFAULT 5.0,
    budgets        jsonb NOT NULL DEFAULT '{}'::jsonb,
    alert_channels jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
