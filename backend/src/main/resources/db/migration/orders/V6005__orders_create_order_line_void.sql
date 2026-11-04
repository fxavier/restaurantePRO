-- A void of part or all of a confirmed line (a fact, insert only; written by S14 with an approver, D40).
-- quantity ≤ the line's remaining net quantity is checked under the order's version (r2-decisions §4 orders).
CREATE TABLE orders.order_line_void
(
    id                    UUID                     NOT NULL,
    order_id              UUID                     NOT NULL,
    line_id               UUID                     NOT NULL,
    quantity              NUMERIC(12, 3)           NOT NULL,
    reason                VARCHAR(200)             NOT NULL,
    wasted                BOOLEAN                  NOT NULL,
    operator_employee_id  UUID                     NOT NULL,
    approver_employee_id  UUID                     NOT NULL,
    terminal_id           UUID                     NOT NULL,
    voided_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT order_line_void_pk PRIMARY KEY (id),
    CONSTRAINT order_line_void_order_fk FOREIGN KEY (order_id) REFERENCES orders.order_header (id),
    CONSTRAINT order_line_void_line_fk FOREIGN KEY (line_id) REFERENCES orders.order_line (id),
    CONSTRAINT order_line_void_quantity_positive_ck CHECK (quantity > 0),
    CONSTRAINT order_line_void_reason_not_blank_ck CHECK (btrim(reason) <> '')
);

CREATE INDEX order_line_void_line_ix ON orders.order_line_void (line_id);
