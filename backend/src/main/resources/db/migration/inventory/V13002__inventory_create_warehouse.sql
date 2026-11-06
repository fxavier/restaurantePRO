-- Warehouses belong to one store (§8.1, D114). negative_policy (D118, P41): ALLOW lets every movement go below zero;
-- REFUSE_MANUAL refuses manual outgoing movements that would. Sales always post.
CREATE TABLE inventory.warehouse
(
    id              UUID                     NOT NULL,
    store_id        UUID                     NOT NULL,
    code            VARCHAR(40)              NOT NULL,
    name            VARCHAR(160)             NOT NULL,
    negative_policy VARCHAR(15)              NOT NULL DEFAULT 'ALLOW',
    active          BOOLEAN                  NOT NULL,
    version         BIGINT                   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT warehouse_pk PRIMARY KEY (id),
    CONSTRAINT warehouse_store_code_uk UNIQUE (store_id, code),
    CONSTRAINT warehouse_negative_policy_ck CHECK (negative_policy IN ('ALLOW', 'REFUSE_MANUAL')),
    CONSTRAINT warehouse_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT warehouse_name_ck CHECK (btrim(name) <> '')
);

-- Where a line consumes (D114): the route of its production destination, else the store's default route
-- (destination_code NULL). The warehouse is of the same store (checked by the service: WAREHOUSE_STORE_MISMATCH).
CREATE TABLE inventory.consumption_route
(
    id               UUID        NOT NULL,
    store_id         UUID        NOT NULL,
    destination_code VARCHAR(40),
    warehouse_id     UUID        NOT NULL,
    version          BIGINT      NOT NULL,
    CONSTRAINT consumption_route_pk PRIMARY KEY (id),
    CONSTRAINT consumption_route_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT consumption_route_destination_ck CHECK (destination_code IS NULL OR btrim(destination_code) <> '')
);
CREATE UNIQUE INDEX consumption_route_store_destination_uk
    ON inventory.consumption_route (store_id, coalesce(destination_code, ''));

-- The minimum of an item in a warehouse (§5 "stock mínimo").
CREATE TABLE inventory.stock_level
(
    warehouse_id  UUID           NOT NULL,
    stock_item_id UUID           NOT NULL,
    min_quantity  NUMERIC(19, 6) NOT NULL,
    version       BIGINT         NOT NULL,
    CONSTRAINT stock_level_pk PRIMARY KEY (warehouse_id, stock_item_id),
    CONSTRAINT stock_level_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT stock_level_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT stock_level_min_ck CHECK (min_quantity >= 0)
);

-- Go-live per store (D122): only lines confirmed at or after active_from are consumed; history is never backfilled.
CREATE TABLE inventory.inventory_activation
(
    store_id                UUID                     NOT NULL,
    active_from             TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_by_account_id UUID,
    activated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT inventory_activation_pk PRIMARY KEY (store_id)
);

CREATE TRIGGER inventory_activation_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.inventory_activation
    FOR EACH ROW
EXECUTE FUNCTION inventory.reject_fact_change();

CREATE TRIGGER inventory_activation_no_truncate
    BEFORE TRUNCATE
    ON inventory.inventory_activation
    FOR EACH STATEMENT
EXECUTE FUNCTION inventory.reject_fact_change();
