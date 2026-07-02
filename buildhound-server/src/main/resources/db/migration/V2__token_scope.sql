-- Spec §5: tokens are scoped — a leaked CI ingest token must not read history.
-- 'all' keeps pre-existing bootstrap tokens working (pilot single-token setup).
ALTER TABLE api_tokens ADD COLUMN scope text NOT NULL DEFAULT 'all';
