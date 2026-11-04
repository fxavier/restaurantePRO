-- G5 review of V3008 (r2-decisions §12):
-- * keys become SHA-256 hex, so a password typed into the username field is never stored readable; the rows are
--   transient counts, so the plain-text ones are simply dropped;
-- * a second kind of row counts failures per client address across usernames (limit 30), next to the
--   username+address pair (limit 10);
-- * block_noticed: the block is audited once, on its first refused request.
DELETE FROM identity.login_throttle;

ALTER TABLE identity.login_throttle
    DROP CONSTRAINT login_throttle_failures_ck,
    DROP CONSTRAINT login_throttle_blocked_at_limit_ck,
    ALTER COLUMN key TYPE VARCHAR(64),
    ADD COLUMN block_noticed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT login_throttle_key_sha256_ck CHECK (key ~ '^[0-9a-f]{64}$'),
    -- LoginThrottling.ADDRESS_MAX_FAILURES is the larger limit; the update that would pass it is refused instead
    ADD CONSTRAINT login_throttle_failures_ck CHECK (failures BETWEEN 1 AND 30),
    ADD CONSTRAINT login_throttle_blocked_at_limit_ck CHECK (blocked_until IS NULL OR failures >= 10),
    ADD CONSTRAINT login_throttle_noticed_when_blocked_ck CHECK (NOT block_noticed OR blocked_until IS NOT NULL);
