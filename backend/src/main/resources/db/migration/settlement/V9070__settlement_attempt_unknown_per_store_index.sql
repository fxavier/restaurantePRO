-- The POS's "pagamentos por confirmar": the UNKNOWN attempts of the session's store, counted on every alerts read,
-- the oldest one first for its order (D180: led by tenant_id). Few rows: the partial index stays small.
CREATE INDEX payment_attempt_store_unknown_ix
    ON settlement.payment_attempt (tenant_id, store_id, created_at) WHERE status = 'UNKNOWN';
