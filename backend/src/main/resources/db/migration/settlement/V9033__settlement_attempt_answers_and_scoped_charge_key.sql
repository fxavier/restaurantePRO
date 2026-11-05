-- S30 review. (1) D81 (3): answered_count counts the status queries of an UNKNOWN attempt that the provider answered
-- (Succeeded, Failed, Pending, NotFound); a query that timed out, found the provider calls saturated or found no
-- provider is a poll (poll_count, for the backoff) but proves nothing, so a reclassification to FAILED needs an
-- answer. (2) credit_charge.request_key holds the scoped key (operation:actor kind:actor id + the Idempotency-Key, as
-- the idempotency store keys it), longer than the raw key alone.
ALTER TABLE settlement.payment_attempt
    ADD COLUMN answered_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT payment_attempt_answered_ck CHECK (answered_count >= 0 AND answered_count <= poll_count);

ALTER TABLE settlement.credit_charge
    ALTER COLUMN request_key TYPE VARCHAR(500);
