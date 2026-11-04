-- BackOffice login throttling (r2-decisions §12). One row per username and client address ('<username>|<address>'):
-- every password attempt counts in a 15-minute window from the first one; the 10th sets blocked_until 15 minutes
-- ahead, and until then that pair gets 429 without a password check. A success deletes the row. Keyed by the pair,
-- not the username alone, so guessing from one address cannot lock the account out from every other address.
CREATE TABLE identity.login_throttle
(
    key           VARCHAR(120)             NOT NULL,
    failures      INT                      NOT NULL,
    window_start  TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked_until TIMESTAMP WITH TIME ZONE,
    CONSTRAINT login_throttle_pk PRIMARY KEY (key),
    -- LoginThrottling.MAX_FAILURES: the update that would pass it is refused instead
    CONSTRAINT login_throttle_failures_ck CHECK (failures BETWEEN 1 AND 10),
    CONSTRAINT login_throttle_blocked_at_limit_ck CHECK (blocked_until IS NULL OR failures = 10)
);
CREATE INDEX login_throttle_window_start_ix ON identity.login_throttle (window_start);
