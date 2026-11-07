-- What consumption (D116, D117) remembers of the rounds it read, so a void can be reversed in proportion and an
-- exception reprocessed without asking orders which round a line came from.
--
-- consumed_line: one row per round line that posted SALE_CONSUMPTION movements (for itself or its menu components),
-- with its confirmed quantity. A void reverses consumed × voided ÷ quantity, capped at what is still unreversed.
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

-- A void's reversals find the consumption of the line it descends from (source_line_id) without a scan.
CREATE INDEX stock_movement_source_line_ix ON inventory.stock_movement (source_line_id, type)
    WHERE source_line_id IS NOT NULL;
CREATE INDEX stock_movement_reverses_ix ON inventory.stock_movement (reverses_movement_id)
    WHERE reverses_movement_id IS NOT NULL;

-- A void whose line was not consumed yet (its round's consumption is late or failed and will be retried): the void
-- is remembered and reversed in the transaction that consumes the line. A queue, not a fact: a row is removed once
-- reversed. Consumption and the void listener serialize per line on an advisory lock, so neither misses the other.
CREATE TABLE inventory.pending_void
(
    void_id          UUID                     NOT NULL,
    consumed_line_id UUID                     NOT NULL,
    store_id         UUID                     NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pending_void_pk PRIMARY KEY (void_id)
);
CREATE INDEX pending_void_line_ix ON inventory.pending_void (consumed_line_id);
