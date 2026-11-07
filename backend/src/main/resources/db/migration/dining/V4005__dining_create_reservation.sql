-- Reservations (spec §4, §8.3; D126, P47): informative, no capacity or overlap check. customer_id refers to
-- customers.customer without a foreign key (D6). Seating links the occupation opened through the normal table open.

-- target of reservation_occupation_same_store_fk
ALTER TABLE dining.table_occupation
    ADD CONSTRAINT table_occupation_id_store_uk UNIQUE (id, store_id);

CREATE TABLE dining.reservation
(
    id                     UUID                     NOT NULL,
    store_id               UUID                     NOT NULL,
    starts_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_minutes       INTEGER,
    party_size             INTEGER                  NOT NULL,
    contact_name           VARCHAR(120)             NOT NULL,
    contact_phone_digits   VARCHAR(20),
    customer_id            UUID,
    notes                  VARCHAR(500),
    status                 VARCHAR(20)              NOT NULL,
    occupation_id          UUID,
    seated_at              TIMESTAMP WITH TIME ZONE,
    cancelled_at           TIMESTAMP WITH TIME ZONE,
    cancel_reason          VARCHAR(500),
    no_show_at             TIMESTAMP WITH TIME ZONE,
    no_show_by_actor_kind  VARCHAR(20),
    created_by_actor_kind  VARCHAR(20)              NOT NULL,
    created_by_actor_id    UUID,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT                   NOT NULL,
    CONSTRAINT reservation_pk PRIMARY KEY (id),
    -- target of reservation_table_reservation_same_store_fk
    CONSTRAINT reservation_id_store_uk UNIQUE (id, store_id),
    -- the seating occupation is in the reservation's store
    CONSTRAINT reservation_occupation_same_store_fk FOREIGN KEY (occupation_id, store_id)
        REFERENCES dining.table_occupation (id, store_id),
    CONSTRAINT reservation_duration_positive_ck CHECK (duration_minutes IS NULL OR duration_minutes > 0),
    CONSTRAINT reservation_party_size_positive_ck CHECK (party_size > 0),
    CONSTRAINT reservation_contact_name_not_blank_ck CHECK (btrim(contact_name) <> ''),
    CONSTRAINT reservation_contact_phone_digits_ck
        CHECK (contact_phone_digits IS NULL OR contact_phone_digits ~ '^[0-9]{4,20}$'),
    CONSTRAINT reservation_status_ck CHECK (status IN ('BOOKED', 'SEATED', 'CANCELLED', 'NO_SHOW')),
    -- SEATED exactly when it has its occupation and seating time
    CONSTRAINT reservation_seated_ck
        CHECK ((status = 'SEATED') = (occupation_id IS NOT NULL AND seated_at IS NOT NULL)
            AND (occupation_id IS NULL) = (seated_at IS NULL)),
    CONSTRAINT reservation_cancelled_ck
        CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND btrim(coalesce(cancel_reason, '')) <> '')),
    CONSTRAINT reservation_no_show_ck
        CHECK ((status = 'NO_SHOW') = (no_show_at IS NOT NULL AND no_show_by_actor_kind IS NOT NULL))
);

-- the map indicator and the no-show job read a store's BOOKED reservations by start
CREATE INDEX reservation_booked_store_starts_ix ON dining.reservation (store_id, starts_at) WHERE status = 'BOOKED';
-- the calendar lists every state of a store's day
CREATE INDEX reservation_store_starts_ix ON dining.reservation (store_id, starts_at);
-- an occupation seats at most one reservation
CREATE UNIQUE INDEX reservation_occupation_uk ON dining.reservation (occupation_id) WHERE occupation_id IS NOT NULL;

-- the informative tables of a reservation, all of the reservation's store
CREATE TABLE dining.reservation_table
(
    reservation_id UUID NOT NULL,
    table_id       UUID NOT NULL,
    store_id       UUID NOT NULL,
    CONSTRAINT reservation_table_pk PRIMARY KEY (reservation_id, table_id),
    CONSTRAINT reservation_table_reservation_same_store_fk FOREIGN KEY (reservation_id, store_id)
        REFERENCES dining.reservation (id, store_id),
    CONSTRAINT reservation_table_table_same_store_fk FOREIGN KEY (table_id, store_id)
        REFERENCES dining.restaurant_table (id, store_id)
);
CREATE INDEX reservation_table_table_ix ON dining.reservation_table (table_id);
