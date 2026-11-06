-- Order (spec §8.1: order_header is the physical name of Order; r2-decisions §4 orders, P13, P15).
-- store_id, terminal_id, table_id and the employees refer to other modules by id only (D6).
CREATE SCHEMA IF NOT EXISTS orders;

-- display number of an order on tickets and screens; gaps are fine (a rolled-back opening uses one up)
CREATE SEQUENCE orders.order_number_seq;

CREATE TABLE orders.order_header
(
    id                    UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    terminal_id           UUID                     NOT NULL,
    order_number          BIGINT                   NOT NULL,
    mode                  VARCHAR(10)              NOT NULL,
    table_id              UUID,
    table_code            VARCHAR(20),
    guests                INTEGER,
    lifecycle             VARCHAR(10)              NOT NULL,
    billing_lock          VARCHAR(10)              NOT NULL,
    business_day          DATE                     NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    opened_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    opened_by_employee_id UUID                     NOT NULL,
    closed_at             TIMESTAMP WITH TIME ZONE,
    closed_by_employee_id UUID,
    next_batch_no         INTEGER                  NOT NULL,
    version               BIGINT                   NOT NULL,
    CONSTRAINT order_header_pk PRIMARY KEY (id),
    CONSTRAINT order_header_mode_ck CHECK (mode IN ('DINE_IN', 'COUNTER', 'TAKEAWAY')),
    CONSTRAINT order_header_lifecycle_ck CHECK (lifecycle IN ('OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT order_header_billing_lock_ck CHECK (billing_lock IN ('NONE', 'PAYMENT', 'INVOICE')),
    -- DINE_IN ⇒ table_id, and table_id ⇒ DINE_IN
    CONSTRAINT order_header_table_iff_dine_in_ck CHECK ((mode = 'DINE_IN') = (table_id IS NOT NULL)),
    CONSTRAINT order_header_table_code_needs_table_ck CHECK (table_code IS NULL OR table_id IS NOT NULL),
    CONSTRAINT order_header_guests_positive_ck CHECK (guests IS NULL OR guests > 0),
    -- an order ends (CLOSED or CANCELLED) exactly once, by someone
    CONSTRAINT order_header_ended_ck CHECK ((lifecycle = 'OPEN') = (closed_at IS NULL)),
    CONSTRAINT order_header_ended_by_ck CHECK ((closed_at IS NULL) = (closed_by_employee_id IS NULL)),
    CONSTRAINT order_header_next_batch_no_ck CHECK (next_batch_no >= 1)
);

-- at most one OPEN order per table (backed by dining's open-occupation index): two openings race, one wins
CREATE UNIQUE INDEX order_header_open_table_uk ON orders.order_header (table_id) WHERE lifecycle = 'OPEN';
-- open orders of a store (POS list, settlement's open orders, the day close count)
CREATE INDEX order_header_store_lifecycle_ix ON orders.order_header (store_id, lifecycle);
CREATE INDEX order_header_store_business_day_ix ON orders.order_header (store_id, business_day);
