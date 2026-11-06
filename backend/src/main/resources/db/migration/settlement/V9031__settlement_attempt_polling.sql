-- D81 (2), (3): automatic polling of payment attempts in doubt. poll_count counts the provider status queries since
-- the attempt became UNKNOWN (a manual reclassification to FAILED needs at least one); next_poll_at is when the
-- InDoubtResolver asks next (backoff 1, 2, 5, 10, 30 min, then every hour); last_polled_at and last_poll_answer
-- show the provider's last word (S31's in-doubt list). An attempt already UNKNOWN before this migration is due now.
ALTER TABLE settlement.payment_attempt
    ADD COLUMN poll_count       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_poll_at     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_polled_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_poll_answer VARCHAR(20),
    ADD CONSTRAINT payment_attempt_poll_count_ck CHECK (poll_count >= 0),
    ADD CONSTRAINT payment_attempt_polled_ck CHECK ((poll_count = 0) = (last_polled_at IS NULL));

UPDATE settlement.payment_attempt SET next_poll_at = updated_at WHERE status = 'UNKNOWN';

-- The resolver's work: UNKNOWN attempts by when they are due.
CREATE INDEX payment_attempt_next_poll_ix ON settlement.payment_attempt (next_poll_at) WHERE status = 'UNKNOWN';
