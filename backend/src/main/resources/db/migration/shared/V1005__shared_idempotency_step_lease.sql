-- D63: executeSteps claims a key with a lease instead of holding the record's row lock, so its steps can run with
-- no transaction (fiscal issue, provider status). lease_owner is a random UUID per call; a live lease
-- (lease_until > now) means "in progress" (409), an expired one may be taken over by compare-and-set.
-- Both are NULL for records written by execute (D47), which never uses a lease.
ALTER TABLE shared.idempotency_record
    ADD COLUMN lease_owner UUID,
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT idempotency_record_lease_ck CHECK ((lease_owner IS NULL) = (lease_until IS NULL));
