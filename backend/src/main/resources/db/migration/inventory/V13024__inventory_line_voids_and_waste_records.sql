-- Review of S47. Voids are kept per line, never deleted, so a line consumed in parts (a menu whose components are
-- reprocessed one by one) applies every void of the line to every part, once per original movement.
CREATE TABLE inventory.line_void
(
    void_id          UUID                     NOT NULL,
    consumed_line_id UUID                     NOT NULL,
    store_id         UUID                     NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT line_void_pk PRIMARY KEY (void_id)
);
CREATE INDEX line_void_line_ix ON inventory.line_void (consumed_line_id, created_at);
CREATE INDEX line_void_store_ix ON inventory.line_void (store_id);
INSERT INTO inventory.line_void (void_id, consumed_line_id, store_id, created_at)
SELECT void_id, consumed_line_id, store_id, created_at FROM inventory.pending_void;
DROP TABLE inventory.pending_void;

CREATE TRIGGER line_void_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.line_void
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_fact_change();

-- consumed_line now marks every round line consumption has settled: consumed, or consumed for nothing (untracked,
-- before go-live, nothing of the recipe for the order's mode). Its voids then resolve at once; a void of a line
-- with no consumed_line row is pending.
COMMENT ON TABLE inventory.consumed_line IS
    'Round lines consumption has settled (possibly for nothing); their voids resolve against their SALE_CONSUMPTION';

-- D117 as amended: a wasted void moves no stock (the ingredients really were used). Its share of the sale's cost is
-- reclassified from sale to waste, at the original movement's unit cost, in this append-only record. No foreign key
-- to stock_movement: the ledger stays a table nothing references, so its own guards (append-only, no truncate) speak.
CREATE TABLE inventory.waste_record
(
    id                   UUID                     NOT NULL,
    void_id              UUID                     NOT NULL,
    original_movement_id UUID                     NOT NULL,
    store_id             UUID                     NOT NULL,
    warehouse_id         UUID                     NOT NULL,
    stock_item_id        UUID                     NOT NULL,
    sale_item_id         UUID,
    source_line_id       UUID                     NOT NULL,
    quantity             NUMERIC(19, 6)           NOT NULL,
    unit_cost            NUMERIC(19, 6)           NOT NULL,
    value                NUMERIC(19, 4)           NOT NULL,
    currency             CHAR(3)                  NOT NULL,
    business_date        DATE                     NOT NULL,
    occurred_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    reason               TEXT,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT waste_record_pk PRIMARY KEY (id),
    CONSTRAINT waste_record_source_uk UNIQUE (void_id, original_movement_id),
    CONSTRAINT waste_record_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT waste_record_quantity_ck CHECK (quantity > 0),
    CONSTRAINT waste_record_cost_ck CHECK (unit_cost >= 0 AND value >= 0)
);
CREATE INDEX waste_record_store_date_ix ON inventory.waste_record (store_id, business_date);
CREATE INDEX waste_record_movement_ix ON inventory.waste_record (original_movement_id);

CREATE TRIGGER waste_record_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.waste_record
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_fact_change();
