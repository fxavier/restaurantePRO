-- One line of a transfer: quantity moved from a confirmed source line to a confirmed copy on the target order
-- (origin_transfer_id, no batch), so production and stock never see it twice (§8.2). A fact; written by S14.
CREATE TABLE orders.order_transfer_line
(
    id              UUID           NOT NULL,
    transfer_id     UUID           NOT NULL,
    source_order_id UUID           NOT NULL,
    target_order_id UUID           NOT NULL,
    source_line_id  UUID           NOT NULL,
    target_line_id  UUID           NOT NULL,
    quantity        NUMERIC(12, 3) NOT NULL,
    CONSTRAINT order_transfer_line_pk PRIMARY KEY (id),
    -- the orders are the transfer's, the source line is the source order's and the target line the target order's
    CONSTRAINT order_transfer_line_transfer_orders_fk FOREIGN KEY (transfer_id, source_order_id, target_order_id)
        REFERENCES orders.order_transfer (id, source_order_id, target_order_id),
    CONSTRAINT order_transfer_line_source_same_order_fk FOREIGN KEY (source_line_id, source_order_id)
        REFERENCES orders.order_line (id, order_id),
    CONSTRAINT order_transfer_line_target_same_order_fk FOREIGN KEY (target_line_id, target_order_id)
        REFERENCES orders.order_line (id, order_id),
    CONSTRAINT order_transfer_line_target_uk UNIQUE (target_line_id),
    CONSTRAINT order_transfer_line_quantity_positive_ck CHECK (quantity > 0)
);

CREATE INDEX order_transfer_line_source_ix ON orders.order_transfer_line (source_line_id);
