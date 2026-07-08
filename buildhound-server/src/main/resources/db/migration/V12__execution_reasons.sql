-- Rerun-cause taxonomy over `executionReasons` (plan 061, research F11). Additive, nullable — no
-- backfill; pre-V12 rows read NULL and degrade to UNCLASSIFIED at read time
-- (RerunCauseRollupCalculator). A fresh insert always writes an array (possibly empty), never NULL.
--
-- Renumbered from the plan's original `V11` reference: plan 057 (`V11__tag_index.sql`) landed on this
-- branch first and took V11, so this is the next free migration number.
ALTER TABLE task_executions ADD COLUMN execution_reasons text[];
