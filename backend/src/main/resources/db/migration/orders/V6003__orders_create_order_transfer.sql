-- A transfer of confirmed quantities from one order to another of the same store (a fact; written by S14). Created
-- here so that the net-quantity reads of S13 are final: net = quantity − Σ voids − Σ transferred out.
CREATE TABLE orders.order_transfer
(
    id              UUID                     NOT NULL,
    store_id        UUID                     NOT NULL,
    source_order_id UUID                     NOT NULL,
    target_order_id UUID                     NOT NULL,
    employee_id     UUID                     NOT NULL,
    terminal_id     UUID                     NOT NULL,
    transferred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT order_transfer_pk PRIMARY KEY (id),
    CONSTRAINT order_transfer_source_fk FOREIGN KEY (source_order_id) REFERENCES orders.order_header (id),
    CONSTRAINT order_transfer_target_fk FOREIGN KEY (target_order_id) REFERENCES orders.order_header (id),
    -- target of order_line_origin_transfer_same_order_fk
    CONSTRAINT order_transfer_id_target_uk UNIQUE (id, target_order_id),
    -- target of order_transfer_line_transfer_orders_fk
    CONSTRAINT order_transfer_id_orders_uk UNIQUE (id, source_order_id, target_order_id),
    CONSTRAINT order_transfer_distinct_orders_ck CHECK (source_order_id <> target_order_id)
);

CREATE INDEX order_transfer_source_ix ON orders.order_transfer (source_order_id);
CREATE INDEX order_transfer_target_ix ON orders.order_transfer (target_order_id);
