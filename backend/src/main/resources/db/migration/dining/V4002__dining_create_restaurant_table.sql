-- Physical tables (spec §8.2, CONTEXT.md RestaurantTable). The composite foreign key makes "the area is in
-- the table's own store" a database fact, not only a code check.
CREATE TABLE dining.restaurant_table
(
    id         UUID                     NOT NULL,
    store_id   UUID                     NOT NULL,
    area_id    UUID                     NOT NULL,
    code       VARCHAR(20)              NOT NULL,
    seats      INTEGER                  NOT NULL,
    active     BOOLEAN                  NOT NULL,
    version    BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT restaurant_table_pk PRIMARY KEY (id),
    CONSTRAINT restaurant_table_area_same_store_fk FOREIGN KEY (area_id, store_id)
        REFERENCES dining.room_area (id, store_id),
    -- also the index for "tables of a store" (leading store_id)
    CONSTRAINT restaurant_table_store_code_uk UNIQUE (store_id, code),
    CONSTRAINT restaurant_table_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT restaurant_table_seats_positive_ck CHECK (seats > 0)
);

-- "tables of an area"; also serves the foreign key on area changes
CREATE INDEX restaurant_table_area_ix ON dining.restaurant_table (area_id);
