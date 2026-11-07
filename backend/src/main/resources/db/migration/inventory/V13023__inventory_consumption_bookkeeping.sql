-- What consumption (D116, D117) remembers of the rounds it read, so a void can be reversed in proportion and an
-- exception reprocessed without asking orders which round a line came from.
--
-- consumed_line: one row per round line consumption has settled, with its confirmed quantity: consumed (for itself or
-- its menu components), or consumed for nothing (a store not live, before go-live, untracked, nothing of the recipe
-- for the order's mode). A void of a settled line applies at once; of a line not settled yet, it waits.
CREATE TABLE inventory.consumed_line
(
    line_id      UUID                     NOT NULL,
    batch_id     UUID                     NOT NULL,
    store_id     UUID                     NOT NULL,
    quantity     NUMERIC(19, 6)           NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT consumed_line_pk PRIMARY KEY (line_id),
    CONSTRAINT consumed_line_quantity_ck CHECK (quantity > 0)
);

CREATE TRIGGER consumed_line_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.consumed_line
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_fact_change();

-- An exception names the round and the order line it comes from (a component's exception, its menu line), so it
-- can be reprocessed; late_recipe marks one resolved with a recipe version activated after the confirmation.
-- confirmed_at is the confirmation instant of the line that raised it, so a count (S49) can tell which open
-- exceptions fall before its cut-off, however late they were raised.
ALTER TABLE inventory.consumption_exception
    ADD COLUMN batch_id UUID,
    ADD COLUMN line_id UUID,
    ADD COLUMN late_recipe BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL;
CREATE INDEX consumption_exception_open_confirmed_ix ON inventory.consumption_exception (store_id, confirmed_at)
    WHERE resolved_at IS NULL;
ALTER TABLE inventory.consumption_exception
    ADD CONSTRAINT consumption_exception_resolved_ck CHECK ((resolved_at IS NULL) = (resolution IS NULL));
CREATE INDEX consumption_exception_store_ix ON inventory.consumption_exception (store_id, created_at);
CREATE INDEX consumption_exception_open_line_ix ON inventory.consumption_exception (line_id)
    WHERE resolved_at IS NULL;

-- A void's reversals find the consumption of the line it descends from (source_line_id) without a scan.
CREATE INDEX stock_movement_source_line_ix ON inventory.stock_movement (source_line_id, type)
    WHERE source_line_id IS NOT NULL;
CREATE INDEX stock_movement_reverses_ix ON inventory.stock_movement (reverses_movement_id)
    WHERE reverses_movement_id IS NOT NULL;

-- Every void of a round line, kept for good: a line consumed in parts (a menu whose components are reprocessed one by
-- one) applies every void of the line to every part, once per original movement. Consumption and the void listener
-- serialize per line on an advisory lock, so neither misses the other.
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

CREATE TRIGGER line_void_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.line_void
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_fact_change();

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
