-- GIN index over payload->'tags' (plan 057): accelerates the tag-cohort feature's `@>` equality
-- containment filter (BuildFilter.tags) and the cohort split's `->>` key lookup. Additive only —
-- no column or table change, no backfill; `tags` has been part of the schema since v1.
CREATE INDEX builds_tags_gin_idx ON builds USING gin ((payload -> 'tags'));
