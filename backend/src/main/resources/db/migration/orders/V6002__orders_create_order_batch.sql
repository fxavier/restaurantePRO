-- A round (spec §8.3 OrderBatch CONFIRMED -> DISPATCHED; r2-decisions §4 orders). Its confirmation is the persisted
-- production work: the OrderBatchConfirmed publication commits with it (D50 §7.1). DISPATCHED = production received it.
CREATE TABLE orders.order_batch
(
    id                       UUID                     NOT NULL,
    order_id                 UUID                     NOT NULL,
    store_id                 UUID                     NOT NULL,
    batch_no                 INTEGER                  NOT NULL,
    status                   VARCHAR(10)              NOT NULL,
    terminal_id              UUID                     NOT NULL,
    confirmed_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_by_employee_id UUID                     NOT NULL,
    release_id               UUID                     NOT NULL,
    catalog_version          BIGINT                   NOT NULL,
    dispatched_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT order_batch_pk PRIMARY KEY (id),
    CONSTRAINT order_batch_order_fk FOREIGN KEY (order_id) REFERENCES orders.order_header (id),
    -- a round is never re-sent: one number per round of an order
    CONSTRAINT order_batch_order_no_uk UNIQUE (order_id, batch_no),
    -- target of order_line_batch_same_order_fk
    CONSTRAINT order_batch_id_order_uk UNIQUE (id, order_id),
    CONSTRAINT order_batch_no_positive_ck CHECK (batch_no >= 1),
    CONSTRAINT order_batch_status_ck CHECK (status IN ('CONFIRMED', 'DISPATCHED')),
    CONSTRAINT order_batch_dispatched_ck CHECK ((status = 'DISPATCHED') = (dispatched_at IS NOT NULL))
);
