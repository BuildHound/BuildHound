-- Flaky-test detection (plan 036): a narrow per-class outcome table projected from the payload's
-- `tests` block on ingest, so cross-run divergence is an indexed join, never a jsonb re-scan.
-- module is stored NOT NULL DEFAULT '' (TestUnitKey treats null and "" identically → same join key)
-- so it can key the primary key for idempotency; the read maps '' back to null.
CREATE TABLE test_class_outcomes (
    project_id        uuid        NOT NULL,
    build_id          text        NOT NULL,
    started_at        timestamptz NOT NULL,
    sha               text,
    module            text        NOT NULL DEFAULT '',
    class_fqcn        text        NOT NULL,
    passed            int         NOT NULL,
    failed            int         NOT NULL,
    retry_flaky_cases int         NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, build_id, module, class_fqcn)
);

-- The cross-run signal joins on the same (sha, class) across builds within a project.
CREATE INDEX test_class_outcomes_crossrun
    ON test_class_outcomes (project_id, sha, module, class_fqcn);
