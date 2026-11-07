-- Stock transfers between warehouses (D120). Same store: one command ships and receives. Between stores: SHIPPED
-- (the OUT at the source) → RECEIVED (the IN at the destination, with a TRANSFER_SHORTAGE for what did not arrive).
-- In transit = SHIPPED transfers. Unrelated to order transfers (§8.2).
CREATE TABLE inventory.stock_transfer
(
    id                  UUID                     NOT NULL,
    from_warehouse_id   UUID                     NOT NULL,
    to_warehouse_id     UUID                     NOT NULL,
    from_store_id       UUID                     NOT NULL,
    to_store_id         UUID                     NOT NULL,
    status              VARCHAR(10)              NOT NULL,
    notes               TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by_actor_id UUID,
    shipped_at          TIMESTAMP WITH TIME ZONE,
    shipped_by_actor_id UUID,
    received_at         TIMESTAMP WITH TIME ZONE,
    received_by_actor_id UUID,
    cancelled_at        TIMESTAMP WITH TIME ZONE,
    version             BIGINT                   NOT NULL,
    CONSTRAINT stock_transfer_pk PRIMARY KEY (id),
    CONSTRAINT stock_transfer_from_fk FOREIGN KEY (from_warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT stock_transfer_to_fk FOREIGN KEY (to_warehouse_id) REFERENCES inventory.warehouse (id),
    CONSTRAINT stock_transfer_status_ck CHECK (status IN ('DRAFT', 'SHIPPED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT stock_transfer_warehouses_ck CHECK (from_warehouse_id <> to_warehouse_id),
    CONSTRAINT stock_transfer_shipped_ck CHECK ((status IN ('SHIPPED', 'RECEIVED')) = (shipped_at IS NOT NULL)),
    CONSTRAINT stock_transfer_received_ck CHECK ((status = 'RECEIVED') = (received_at IS NOT NULL)),
    CONSTRAINT stock_transfer_cancelled_ck CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE INDEX stock_transfer_from_store_ix ON inventory.stock_transfer (from_store_id, status);
CREATE INDEX stock_transfer_to_store_ix ON inventory.stock_transfer (to_store_id, status);

-- unit_cost is the source average at shipping: the IN and the shortage at the destination use it (D120).
CREATE TABLE inventory.stock_transfer_line
(
    transfer_id       UUID           NOT NULL,
    stock_item_id     UUID           NOT NULL,
    quantity_shipped  NUMERIC(19, 6) NOT NULL,
    quantity_received NUMERIC(19, 6),
    unit_cost         NUMERIC(19, 6),
    CONSTRAINT stock_transfer_line_pk PRIMARY KEY (transfer_id, stock_item_id),
    CONSTRAINT stock_transfer_line_transfer_fk FOREIGN KEY (transfer_id) REFERENCES inventory.stock_transfer (id),
    CONSTRAINT stock_transfer_line_item_fk FOREIGN KEY (stock_item_id) REFERENCES inventory.stock_item (id),
    CONSTRAINT stock_transfer_line_shipped_ck CHECK (quantity_shipped > 0),
    CONSTRAINT stock_transfer_line_received_ck CHECK (
        quantity_received IS NULL OR (quantity_received >= 0 AND quantity_received <= quantity_shipped)),
    CONSTRAINT stock_transfer_line_unit_cost_ck CHECK (unit_cost IS NULL OR unit_cost >= 0)
);
