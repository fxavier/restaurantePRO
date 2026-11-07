-- The balance projection (D113): quantity, value and moving average cost per warehouse and item, updated in the
-- posting transaction under FOR UPDATE, rows locked in ascending (warehouse_id, stock_item_id) order. Not a fact: it
-- is reconcilable (Σ movements = balance, nightly check) and rebuilt by replaying the ledger in posting_seq order.
CREATE TABLE inventory.stock_balance
(
    warehouse_id     UUID                     NOT NULL,
    stock_item_id    UUID                     NOT NULL,
    quantity         NUMERIC(19, 6)           NOT NULL,
    value            NUMERIC(19, 4)           NOT NULL,
    average_cost     NUMERIC(19, 6)           NOT NULL,
    last_posting_seq BIGINT                   NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT stock_balance_pk PRIMARY KEY (warehouse_id, stock_item_id),
    CONSTRAINT stock_balance_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT stock_balance_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT stock_balance_average_cost_ck CHECK (average_cost >= 0)
);
-- Negative balances per warehouse for the status and the gauge (D118).
CREATE INDEX stock_balance_negative_ix ON inventory.stock_balance (warehouse_id) WHERE quantity < 0;
