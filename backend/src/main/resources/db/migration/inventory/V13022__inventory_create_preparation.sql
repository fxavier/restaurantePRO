-- A preparation (D121): a preparation recipe version made in a warehouse for an output quantity (in the output stock
-- item's base unit). DRAFT until confirmed; confirming posts the ingredients' PREPARATION_OUT movements and the
-- output's PREPARATION_IN in one transaction, once (the source key of the movements is the preparation).
CREATE TABLE inventory.preparation
(
    id                     UUID                     NOT NULL,
    store_id               UUID                     NOT NULL,
    warehouse_id           UUID                     NOT NULL,
    recipe_version_id      UUID                     NOT NULL,
    output_stock_item_id   UUID                     NOT NULL,
    output_quantity        NUMERIC(19, 6)           NOT NULL,
    status                 VARCHAR(12)              NOT NULL,
    notes                  TEXT,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by_actor_kind  VARCHAR(10)              NOT NULL,
    created_by_actor_id    UUID,
    confirmed_at           TIMESTAMP WITH TIME ZONE,
    confirmed_by_actor_kind VARCHAR(10),
    confirmed_by_actor_id  UUID,
    cancelled_at           TIMESTAMP WITH TIME ZONE,
    version                BIGINT                   NOT NULL,
    CONSTRAINT preparation_pk PRIMARY KEY (id),
    CONSTRAINT preparation_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT preparation_recipe_version_fk FOREIGN KEY (recipe_version_id) REFERENCES inventory.recipe_version (id),
    CONSTRAINT preparation_output_fk FOREIGN KEY (output_stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT preparation_quantity_ck CHECK (output_quantity > 0),
    CONSTRAINT preparation_status_ck CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT preparation_confirmed_ck CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CONSTRAINT preparation_cancelled_ck CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE INDEX preparation_store_ix ON inventory.preparation (store_id, created_at);

-- A confirmed or cancelled preparation is a fact.
CREATE FUNCTION inventory.guard_preparation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'preparation % is % and immutable', OLD.id, OLD.status USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER preparation_guard
    BEFORE UPDATE OR DELETE
    ON inventory.preparation
    FOR EACH ROW
EXECUTE FUNCTION inventory.guard_preparation();
