-- Weighted-average costing (D113, review of S42): every OUT is valued at the current average. A cost an OUT names
-- (a supplier return at its receipt's cost, a transfer shortage at the shipping cost) is kept for reference only.
-- Added as a NULL column: no existing movement is touched.
ALTER TABLE inventory.stock_movement
    ADD COLUMN reference_unit_cost NUMERIC(19, 6);
ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT stock_movement_reference_unit_cost_ck CHECK (reference_unit_cost IS NULL OR reference_unit_cost >= 0);

-- The result of the last nightly balance check per store (D113): GET /status reads it instead of summing the ledger
-- on every request. A projection of the job, rewritten at each run.
CREATE TABLE inventory.balance_check
(
    store_id          UUID                     NOT NULL,
    drifting_balances INTEGER                  NOT NULL,
    checked_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT balance_check_pk PRIMARY KEY (store_id),
    CONSTRAINT balance_check_drifting_ck CHECK (drifting_balances >= 0)
);
