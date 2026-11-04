-- D47.2: the Location header of a stored response is replayed with it.
ALTER TABLE shared.idempotency_record
    ADD COLUMN response_location VARCHAR(2048);

-- D47.4: the daily retention job deletes COMPLETED records by completed_at.
CREATE INDEX idempotency_record_retention_ix ON shared.idempotency_record (status, completed_at);
