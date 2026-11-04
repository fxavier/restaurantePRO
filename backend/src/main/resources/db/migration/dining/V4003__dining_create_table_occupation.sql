-- A table's use by one order (spec §8.1, §8.3 TableOccupation OPEN -> CLOSED; r2-decisions §4 dining).
-- order_id refers to orders.order_header without a foreign key (D6). A closed occupation is history: a move
-- closes it (MOVED) and opens a new one on the target table.

-- target of table_occupation_table_same_store_fk
ALTER TABLE dining.restaurant_table
    ADD CONSTRAINT restaurant_table_id_store_uk UNIQUE (id, store_id);

CREATE TABLE dining.table_occupation
(
    id                    UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    table_id              UUID                     NOT NULL,
    order_id              UUID                     NOT NULL,
    guests                INTEGER                  NOT NULL,
    opened_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    opened_by_employee_id UUID                     NOT NULL,
    closed_at             TIMESTAMP WITH TIME ZONE,
    close_reason          VARCHAR(20),
    subtotal_printed_at   TIMESTAMP WITH TIME ZONE,
    version               BIGINT                   NOT NULL,
    CONSTRAINT table_occupation_pk PRIMARY KEY (id),
    -- the occupation is in its table's store
    CONSTRAINT table_occupation_table_same_store_fk FOREIGN KEY (table_id, store_id)
        REFERENCES dining.restaurant_table (id, store_id),
    CONSTRAINT table_occupation_guests_positive_ck CHECK (guests > 0),
    CONSTRAINT table_occupation_close_reason_ck
        CHECK (close_reason IN ('ORDER_CLOSED', 'ORDER_CANCELLED', 'MOVED')),
    -- closed exactly when it has a reason
    CONSTRAINT table_occupation_closed_with_reason_ck CHECK ((closed_at IS NULL) = (close_reason IS NULL))
);

-- one open occupation per table: two concurrent openings, one wins (the other gets 409 TABLE_OCCUPIED)
CREATE UNIQUE INDEX table_occupation_open_table_uk ON dining.table_occupation (table_id) WHERE closed_at IS NULL;
-- an order sits at one table at a time
CREATE UNIQUE INDEX table_occupation_open_order_uk ON dining.table_occupation (order_id) WHERE closed_at IS NULL;
-- the POS table map reads a store's open occupations
CREATE INDEX table_occupation_open_store_ix ON dining.table_occupation (store_id) WHERE closed_at IS NULL;
