-- The stock ledger (D113, §5 "Movimentos são o registo de referência"): one fact per movement, signed quantity in
-- the item's base unit, unit cost at posting, signed value. posting_seq is the posting order the moving average and
-- the rebuild replay. One movement per source, item, type and warehouse (§8.2), so a repeated post adds nothing.
CREATE TABLE inventory.stock_movement
(
    id                   UUID                     NOT NULL,
    posting_seq          BIGINT GENERATED ALWAYS AS IDENTITY,
    warehouse_id         UUID                     NOT NULL,
    store_id             UUID                     NOT NULL,
    stock_item_id        UUID                     NOT NULL,
    type                 VARCHAR(25)              NOT NULL,
    quantity             NUMERIC(19, 6)           NOT NULL,
    unit_cost            NUMERIC(19, 6)           NOT NULL,
    value                NUMERIC(19, 4)           NOT NULL,
    currency             CHAR(3)                  NOT NULL,
    source_type          VARCHAR(30)              NOT NULL,
    source_id            UUID                     NOT NULL,
    source_line_id       UUID,
    reverses_movement_id UUID,
    recipe_version_id    UUID,
    policy_version_id    UUID,
    sale_item_id         UUID,
    business_date        DATE                     NOT NULL,
    occurred_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    posted_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_kind           VARCHAR(10)              NOT NULL,
    actor_id             UUID,
    reason               TEXT,
    CONSTRAINT stock_movement_pk PRIMARY KEY (id),
    CONSTRAINT stock_movement_posting_seq_uk UNIQUE (posting_seq),
    CONSTRAINT stock_movement_source_uk UNIQUE (source_type, source_id, stock_item_id, type, warehouse_id),
    CONSTRAINT stock_movement_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT stock_movement_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT stock_movement_type_ck CHECK (type IN ('OPENING', 'RECEIPT', 'SUPPLIER_RETURN', 'SALE_CONSUMPTION',
                                                      'CONSUMPTION_REVERSAL', 'WASTE', 'RETURN_TO_STOCK',
                                                      'PREPARATION_OUT', 'PREPARATION_IN', 'TRANSFER_OUT',
                                                      'TRANSFER_IN', 'TRANSFER_SHORTAGE', 'COUNT_ADJUSTMENT',
                                                      'MANUAL_ADJUSTMENT', 'REVALUATION')),
    -- The sign of the quantity is fixed by the type; a revaluation moves value only.
    CONSTRAINT stock_movement_sign_ck CHECK (CASE
        WHEN type IN ('OPENING', 'RECEIPT', 'CONSUMPTION_REVERSAL', 'RETURN_TO_STOCK', 'PREPARATION_IN',
                      'TRANSFER_IN') THEN quantity > 0
        WHEN type IN ('SUPPLIER_RETURN', 'SALE_CONSUMPTION', 'WASTE', 'PREPARATION_OUT', 'TRANSFER_OUT',
                      'TRANSFER_SHORTAGE') THEN quantity < 0
        WHEN type = 'REVALUATION' THEN quantity = 0
        ELSE quantity <> 0 END),
    CONSTRAINT stock_movement_unit_cost_ck CHECK (unit_cost >= 0),
    CONSTRAINT stock_movement_actor_kind_ck CHECK (actor_kind IN ('ACCOUNT', 'EMPLOYEE', 'SYSTEM')),
    CONSTRAINT stock_movement_actor_ck CHECK ((actor_kind = 'SYSTEM') = (actor_id IS NULL)),
    CONSTRAINT stock_movement_source_type_ck CHECK (btrim(source_type) <> '')
);
CREATE INDEX stock_movement_warehouse_item_ix ON inventory.stock_movement (warehouse_id, stock_item_id, posting_seq);
CREATE INDEX stock_movement_store_date_ix ON inventory.stock_movement (store_id, business_date, type);
CREATE INDEX stock_movement_item_occurred_ix ON inventory.stock_movement (stock_item_id, occurred_at);

CREATE TRIGGER stock_movement_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.stock_movement
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_fact_change();

CREATE TRIGGER stock_movement_no_truncate
    BEFORE TRUNCATE
    ON inventory.stock_movement
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();
