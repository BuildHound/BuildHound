-- Dashboard ingest-token generation (plan 098). Additive only: two nullable columns on
-- `api_tokens`, plus one partial index for the sweep. Existing rows keep both NULL —
-- NULL `expires_unused_at` means "no activation deadline" (bootstrap/pre-existing tokens
-- are exempt by construction, not by a special-cased predicate).

-- Set on first successful resolve of a minted token (auth path, PostgresTokenStore.resolve).
ALTER TABLE api_tokens ADD COLUMN activated_at timestamptz;

-- Minted tokens get `now() + interval '6 hours'` at mint time (DB clock, so mint and
-- validation share one clock — no app/DB skew). NULL for bootstrap/pre-existing tokens.
ALTER TABLE api_tokens ADD COLUMN expires_unused_at timestamptz;

-- Sweep scan: unactivated tokens past their deadline. Partial on activated_at IS NULL keeps
-- the index tiny — once a token is activated it drops out permanently.
CREATE INDEX api_tokens_unactivated_expiry_idx
    ON api_tokens (expires_unused_at) WHERE activated_at IS NULL;
